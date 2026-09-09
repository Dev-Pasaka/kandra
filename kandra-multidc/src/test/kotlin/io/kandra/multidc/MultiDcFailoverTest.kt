package io.kandra.multidc

import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.KandraAuth
import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.ktor.FailoverPolicy
import io.kandra.ktor.Kandra
import io.kandra.ktor.ReplicationStrategy
import io.kandra.ktor.SchemaMode
import io.kandra.ktor.kandra
import io.kandra.test.KandraMultiDcTestcontainers
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID

@ScyllaTable("multidc_test_items")
data class MultiDcTestItem(
    @PartitionKey val id: UUID,
    val label: String
)

/**
 * Real two-datacenter Cassandra failover/load-balancing tests (GH #84 / ISS-076), against the
 * genuine [KandraMultiDcTestcontainers] topology -- not mocks, not a single-node stand-in.
 *
 * Exercises exactly the config surface `docs/reviews/2026-09-08-pre-multidc-cluster-review.md`
 * flagged as untested ahead of real multi-DC cluster testing: `LoadBalancingConfig.dcAwareFailover`/
 * `allowedRemoteDcs`, `FailoverConfig.onLocalDcUnavailable` (`THROW` vs `RETRY_REMOTE_DC`), and
 * Strict Mode's RF-vs-consistency warning path (ISS-075/GH #83) against a keyspace with genuine
 * multi-DC `NetworkTopologyStrategy` replication -- something a single-node
 * [io.kandra.test.KandraTestcontainers]-backed test structurally cannot exercise, since RF > 1
 * spread across real DCs requires real DCs to spread it across.
 *
 * "Local DC unavailable" is simulated by [KandraMultiDcTestcontainers.pause]ing the `dc1` container
 * via the Docker API, per this issue's own suggested technique. **This is a real caveat, not just an
 * implementation detail:** `pause` (the cgroup freezer) freezes the container's userspace processes
 * but leaves its network namespace and any already-established TCP connections alone -- it simulates
 * an unresponsive/hung node, not a severed network link (no RST or ICMP unreachable is produced the
 * way a real partition would typically produce). See [KandraMultiDcTestcontainers.pause]'s KDoc (GH
 * #108 / ISS-095) for the full explanation. The tests below use bounded retry loops that tolerate
 * either failure mode, so this hasn't been observed to change their outcome -- but a pass here is
 * evidence against "the driver's failover logic works when dc1 hangs," not against "...when the link
 * to dc1 is severed." Pool timeouts are tightened in these tests specifically so the driver's own
 * down-detection doesn't dominate test runtime; production deployments should size these per their
 * own latency/availability tradeoffs, not copy these tightened values.
 *
 * Tagged "manual" and excluded from `:kandra-multidc:test` (see `build.gradle.kts`) for the same
 * reason [KandraMultiDcTestcontainers]'s own doc comment gives: two real nodes gossiping into one
 * cluster from cold, plus real failover-detection wait time on top, is meaningfully slower than the
 * rest of this module's suite. Run explicitly via `./gradlew :kandra-multidc:multiDcTest`.
 */
@Tag("manual")
class MultiDcFailoverTest {

    @AfterEach
    fun cleanup() {
        // Always reset the shared topology, regardless of which DC (if any) a test paused --
        // a paused container left paused would silently break every later test in this JVM run.
        // This only runs if the JVM survives to run it -- see KandraMultiDcTestcontainers.pause's
        // KDoc (GH #108 / ISS-095) for the crash-between-pause-and-cleanup window and why
        // Testcontainers' Ryuk reaper, not this method, is the actual backstop for that case.
        KandraMultiDcTestcontainers.unpauseAll()
        SchemaRegistry.clear()
    }

    /**
     * Baseline: a real write + read against a genuine two-DC `NetworkTopologyStrategy` keyspace,
     * with both DCs healthy -- proves the fixture itself, and the driver's (always-on, per
     * `LoadBalancingConfig.tokenAware`'s doc) token-aware routing, work end-to-end before any test
     * starts pulling a DC down.
     */
    @Test
    fun `token-aware load balancing across a real multi-DC NetworkTopologyStrategy keyspace works end-to-end`() {
        val db = KandraMultiDcTestcontainers.freshNetworkTopologyKeyspace(
            MultiDcTestItem::class,
            localDatacenter = KandraMultiDcTestcontainers.DC1,
            dcReplicationMap = mapOf(KandraMultiDcTestcontainers.DC1 to 1, KandraMultiDcTestcontainers.DC2 to 1)
        )
        try {
            val repo = db.repository<MultiDcTestItem>()
            val item = MultiDcTestItem(UUID.randomUUID(), "cross-dc")
            repo.save(item)
            assertEquals(item, repo.findById(item.id))
        } finally {
            db.close()
        }
    }

    @OptIn(ExperimentalKandraApi::class)
    @Test
    fun `FailoverPolicy THROW (default) - a local-DC outage surfaces an error instead of silently using the remote DC`() {
        val keyspace = "kandra_multidc_${UUID.randomUUID().toString().replace("-", "")}"
        val dc1 = KandraMultiDcTestcontainers.contactPoint(KandraMultiDcTestcontainers.DC1)

        testApplication {
            application {
                install(Kandra) {
                    contactPoints = "${dc1.hostString}:${dc1.port}"
                    localDatacenter = KandraMultiDcTestcontainers.DC1
                    this.keyspace = keyspace
                    autoCreateKeyspace = true
                    replicationStrategy = ReplicationStrategy.NetworkTopologyStrategy(
                        mapOf(KandraMultiDcTestcontainers.DC1 to 1, KandraMultiDcTestcontainers.DC2 to 1)
                    )
                    schemaMode = SchemaMode.AUTO_CREATE
                    register(MultiDcTestItem::class)
                    auth { provider = KandraAuth.static("", "") }
                    // Deliberately left at defaults: dcAwareFailover = false, onLocalDcUnavailable = THROW.
                    pool {
                        requestTimeoutMillis = 3000
                        connectionTimeoutMillis = 3000
                        heartbeatIntervalSeconds = 1
                    }
                }

                // Prove the keyspace/table is usable before pulling dc1 down.
                val repo = kandra.repository<MultiDcTestItem>()
                repo.save(MultiDcTestItem(UUID.randomUUID(), "before-outage"))

                KandraMultiDcTestcontainers.pause(KandraMultiDcTestcontainers.DC1)
                try {
                    var threw = false
                    // Bounded retry against the detection-timing race (the driver needs a moment to
                    // notice dc1 stopped responding) -- what's under test is "this never silently
                    // succeeds against a dead local DC with no failover configured," not the exact
                    // number of milliseconds detection takes.
                    for (attempt in 1..10) {
                        try {
                            repo.save(MultiDcTestItem(UUID.randomUUID(), "during-outage-$attempt"))
                        } catch (e: Exception) {
                            threw = true
                            break
                        }
                        Thread.sleep(500)
                    }
                    assertTrue(threw, "a write during a local-DC outage with no failover configured must eventually fail, never silently succeed")
                } finally {
                    KandraMultiDcTestcontainers.unpause(KandraMultiDcTestcontainers.DC1)
                }
            }
        }
    }

    @OptIn(ExperimentalKandraApi::class)
    @Test
    fun `FailoverPolicy RETRY_REMOTE_DC - a local-DC outage transparently fails over to the allowed remote DC`() {
        val keyspace = "kandra_multidc_${UUID.randomUUID().toString().replace("-", "")}"
        val dc1 = KandraMultiDcTestcontainers.contactPoint(KandraMultiDcTestcontainers.DC1)

        testApplication {
            application {
                install(Kandra) {
                    contactPoints = "${dc1.hostString}:${dc1.port}"
                    localDatacenter = KandraMultiDcTestcontainers.DC1
                    this.keyspace = keyspace
                    autoCreateKeyspace = true
                    replicationStrategy = ReplicationStrategy.NetworkTopologyStrategy(
                        mapOf(KandraMultiDcTestcontainers.DC1 to 1, KandraMultiDcTestcontainers.DC2 to 1)
                    )
                    schemaMode = SchemaMode.AUTO_CREATE
                    register(MultiDcTestItem::class)
                    auth { provider = KandraAuth.static("", "") }
                    pool {
                        requestTimeoutMillis = 3000
                        connectionTimeoutMillis = 3000
                        heartbeatIntervalSeconds = 1
                    }
                    loadBalancing {
                        dcAwareFailover = true
                        allowedRemoteDcs = listOf(KandraMultiDcTestcontainers.DC2)
                    }
                    failover {
                        onLocalDcUnavailable = FailoverPolicy.RETRY_REMOTE_DC
                    }
                }

                val repo = kandra.repository<MultiDcTestItem>()
                repo.save(MultiDcTestItem(UUID.randomUUID(), "before-outage"))

                KandraMultiDcTestcontainers.pause(KandraMultiDcTestcontainers.DC1)
                try {
                    val item = MultiDcTestItem(UUID.randomUUID(), "during-outage-via-dc2")
                    var savedOk = false
                    var lastError: Exception? = null
                    // Same detection-timing tolerance as the THROW test above -- what's under test
                    // is "this configuration eventually, transparently succeeds via dc2," not
                    // whether the very first attempt (which may still race dc1 being marked DOWN)
                    // happens to land on it.
                    for (attempt in 1..15) {
                        try {
                            repo.save(item)
                            savedOk = true
                            break
                        } catch (e: Exception) {
                            lastError = e
                            Thread.sleep(500)
                        }
                    }
                    assertTrue(savedOk, "a write during a local-DC outage with RETRY_REMOTE_DC configured should eventually succeed via dc2, but every attempt failed: $lastError")

                    val found = repo.findById(item.id)
                    assertNotNull(found, "the value written via dc2 failover must be readable back")
                    assertEquals(item, found)
                } finally {
                    KandraMultiDcTestcontainers.unpause(KandraMultiDcTestcontainers.DC1)
                }
            }
        }
    }

    /**
     * ISS-075/GH #83's Strict Mode is WARN-only by design (see `ConsistencyConfig.strictMode`'s own
     * doc) -- there is no thrown exception or return value to assert on directly. What this proves
     * instead is the thing a single-node Testcontainers test structurally cannot: the warning's
     * *precondition* (`multiDcTopology`, auto-derived from `loadBalancing.allowedRemoteDcs`) and the
     * consistency-resolution code path it guards both run correctly end-to-end against a keyspace
     * with genuine multi-DC `NetworkTopologyStrategy` replication, not just a single-node stand-in
     * declaring a higher RF than it can actually serve (the caveat `KandraMultiDcTestcontainers`
     * and `KandraTestcontainers` both document on their `dcReplicationMap`/`replicationFactor`
     * parameters).
     */
    @OptIn(ExperimentalKandraApi::class)
    @Test
    fun `Strict Mode's LOCAL_ONE-in-multi-DC warning path runs cleanly against a genuine multi-DC keyspace`() {
        val keyspace = "kandra_multidc_${UUID.randomUUID().toString().replace("-", "")}"
        val dc1 = KandraMultiDcTestcontainers.contactPoint(KandraMultiDcTestcontainers.DC1)

        testApplication {
            application {
                install(Kandra) {
                    contactPoints = "${dc1.hostString}:${dc1.port}"
                    localDatacenter = KandraMultiDcTestcontainers.DC1
                    this.keyspace = keyspace
                    autoCreateKeyspace = true
                    replicationStrategy = ReplicationStrategy.NetworkTopologyStrategy(
                        mapOf(KandraMultiDcTestcontainers.DC1 to 1, KandraMultiDcTestcontainers.DC2 to 1)
                    )
                    schemaMode = SchemaMode.AUTO_CREATE
                    register(MultiDcTestItem::class)
                    auth { provider = KandraAuth.static("", "") }
                    // multiDcTopology is auto-derived from this being non-empty (Kandra.kt) --
                    // the precondition for Strict Mode's warning to even be considered.
                    loadBalancing { allowedRemoteDcs = listOf(KandraMultiDcTestcontainers.DC2) }
                    consistency {
                        strictMode = true
                        defaultRead = io.kandra.core.KandraConsistency.LOCAL_ONE // the level Strict Mode warns about
                    }
                }

                val repo = kandra.repository<MultiDcTestItem>()
                val item = MultiDcTestItem(UUID.randomUUID(), "strict-mode-smoke")
                repo.save(item)
                // The real assertion: strictMode=true never changes behavior (WARN-only), so a
                // LOCAL_ONE read against a genuine multi-DC keyspace must still succeed normally.
                assertEquals(item, repo.findById(item.id))
            }
        }
    }
}
