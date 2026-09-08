package io.kandra.ktor

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.auth.ProgrammaticPlainTextAuthProvider
import com.datastax.oss.driver.api.core.config.DefaultDriverOption
import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.KandraAuth
import io.kandra.core.KandraAuthProvider
import io.kandra.core.KandraCredentials
import io.kandra.core.KandraEventListener
import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.exception.KandraSchemaException
import io.kandra.test.KandraTestcontainers
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

@ScyllaTable("test_items")
data class TestItem(
    @PartitionKey val id: UUID,
    val label: String
)

/**
 * Integration tests for the Kandra Ktor plugin, against a real Cassandra container
 * (via [KandraTestcontainers] — no fakes, no hardcoded `localhost:9042`).
 */
class KandraPluginTest {

    private var testKeyspace: String? = null

    @AfterEach
    fun cleanup() {
        SchemaRegistry.clear()
        testKeyspace?.let { ks ->
            CqlSession.builder()
                .addContactPoint(KandraTestcontainers.container.contactPoint)
                .withLocalDatacenter(KandraTestcontainers.container.localDatacenter)
                .build().use { it.execute("DROP KEYSPACE IF EXISTS $ks") }
        }
        testKeyspace = null
    }

    private fun freshKeyspaceName(): String {
        // "kandra_ktor_" (12 chars) + a 32-char dash-stripped UUID = 44 chars, safely under
        // Cassandra's 48-character keyspace name limit. The previous "kandra_ktor_test_" prefix
        // (17 chars) pushed this to 49 chars and made every install() throw InvalidQueryException.
        val ks = "kandra_ktor_${UUID.randomUUID().toString().replace("-", "")}"
        testKeyspace = ks
        return ks
    }

    @Test
    fun `SchemaRegistry registers entity class`() {
        val schema = SchemaRegistry.register(TestItem::class)
        assertNotNull(schema)
        assert(schema.tableName == "test_items")
        assert(schema.partitionKeys.first().cqlName == "id")
    }

    @OptIn(ExperimentalKandraApi::class)
    @Test
    fun `plugin installs without error`() {
        val cp = KandraTestcontainers.container.contactPoint
        testApplication {
            application {
                install(Kandra) {
                    contactPoints = "${cp.hostString}:${cp.port}"
                    localDatacenter = KandraTestcontainers.container.localDatacenter
                    keyspace = freshKeyspaceName()
                    autoCreateKeyspace = true
                    schemaMode = SchemaMode.AUTO_CREATE
                    register(TestItem::class)
                    // The Cassandra Testcontainers image runs with AllowAllAuthenticator (no auth
                    // required) — use a blank-credentials provider instead of the default
                    // KandraAuth.fromEnv(), which throws if SCYLLA_USERNAME/SCYLLA_PASSWORD aren't
                    // set in the environment (they never are in CI or local dev for this test).
                    auth { provider = KandraAuth.static("", "") }
                }
            }
        }
    }

    @OptIn(ExperimentalKandraApi::class)
    @Test
    fun `kandraSession is accessible after install`() {
        val cp = KandraTestcontainers.container.contactPoint
        testApplication {
            application {
                install(Kandra) {
                    contactPoints = "${cp.hostString}:${cp.port}"
                    localDatacenter = KandraTestcontainers.container.localDatacenter
                    keyspace = freshKeyspaceName()
                    autoCreateKeyspace = true
                    schemaMode = SchemaMode.NONE
                    register(TestItem::class)
                    auth { provider = KandraAuth.static("", "") }
                }
                val session = kandraSession
                assertNotNull(session)
            }
        }
    }

    /**
     * GH #5 — consistency Strict Mode. Proves the wiring against a real cluster: setting
     * `consistency { strictMode = true }` alongside `loadBalancing { allowedRemoteDcs = listOf(...) }`
     * (a fake remote DC name is enough — this only derives a topology signal from config, it never
     * queries actual cluster topology) doesn't break normal query execution. `defaultRead` defaults to
     * `LOCAL_ONE`, so this also exercises the WARN path (visible in test output, not asserted here —
     * see `ConsistencyStrictModeTest` in `kandra-runtime` for behavior assertions on the WARN itself).
     */
    @Test
    fun `strictMode and allowedRemoteDcs can be set together without breaking real query execution`() {
        val cp = KandraTestcontainers.container.contactPoint
        testApplication {
            application {
                install(Kandra) {
                    contactPoints = "${cp.hostString}:${cp.port}"
                    localDatacenter = KandraTestcontainers.container.localDatacenter
                    keyspace = freshKeyspaceName()
                    autoCreateKeyspace = true
                    schemaMode = SchemaMode.AUTO_CREATE
                    register(TestItem::class)
                    auth { provider = KandraAuth.static("", "") }
                    consistency {
                        strictMode = true
                    }
                    loadBalancing {
                        allowedRemoteDcs = listOf("fake-remote-dc")
                    }
                }

                val repo = kandra.suspendRepository<TestItem>()
                val item = TestItem(UUID.randomUUID(), "strict-mode-item")
                runBlocking {
                    repo.save(item) // write resolves to defaultWrite = LOCAL_QUORUM -- no strict-mode WARN
                    val found = repo.findById(item.id) // read resolves to defaultRead = LOCAL_ONE -- WARN fires, query still succeeds
                    assertNotNull(found)
                    assert(found!!.label == "strict-mode-item")
                }
            }
        }
    }

    /**
     * GH #34 — graceful shutdown drain used to busy-wait with a raw `Thread.sleep(50)` loop. It's
     * now `delay`-based polling under `withTimeoutOrNull`, run via `runBlocking` on the plugin's own
     * `pluginScope` context rather than an ad-hoc `GlobalScope`. This test fires `ApplicationStopping`
     * directly via `environment.monitor.raise(...)` (the exact mechanism Ktor itself uses to invoke
     * the subscribed handler — see io.ktor.events.Events.raise, which calls handlers synchronously
     * and in order) so the timing assertions below measure only the drain hook itself, decoupled from
     * container/session teardown cost.
     *
     * Proves the loop still exits as soon as `inFlightCount` reaches zero rather than waiting out the
     * full `drainTimeoutMs`.
     */
    @OptIn(ExperimentalKandraApi::class)
    @Test
    fun `graceful shutdown drain exits promptly once in-flight queries finish`() {
        val cp = KandraTestcontainers.container.contactPoint
        testApplication {
            application {
                install(Kandra) {
                    contactPoints = "${cp.hostString}:${cp.port}"
                    localDatacenter = KandraTestcontainers.container.localDatacenter
                    keyspace = freshKeyspaceName()
                    autoCreateKeyspace = true
                    schemaMode = SchemaMode.NONE
                    auth { provider = KandraAuth.static("", "") }
                    shutdown {
                        graceful = true
                        drainTimeoutMs = 5_000
                    }
                }

                val runtime = kandra
                runtime.inFlightCount.incrementAndGet()
                // Simulate one in-flight query finishing shortly after shutdown is signalled.
                Thread {
                    Thread.sleep(150)
                    runtime.inFlightCount.decrementAndGet()
                }.apply { isDaemon = true; start() }

                val elapsedMs = measureTimeMillis {
                    environment.monitor.raise(ApplicationStopping, this@application)
                }

                assert(runtime.inFlightCount.get() == 0) { "in-flight count should have drained to zero" }
                // Well under the 5s drainTimeoutMs -- proves early-exit, not a full-timeout wait.
                // Bounded generously above the ~150ms simulated finish for CI/scheduler jitter.
                assert(elapsedMs < 3_000) {
                    "drain should exit well before drainTimeoutMs once queries finish, took ${elapsedMs}ms"
                }
            }
        }
    }

    /**
     * GH #34 companion test: when in-flight queries never finish, the drain must still be capped at
     * `drainTimeoutMs` (via `withTimeoutOrNull`) rather than hanging indefinitely, and must log/force
     * through with the count still non-zero.
     */
    @OptIn(ExperimentalKandraApi::class)
    @Test
    fun `graceful shutdown forces close after drainTimeoutMs when in-flight queries never finish`() {
        val cp = KandraTestcontainers.container.contactPoint
        testApplication {
            application {
                install(Kandra) {
                    contactPoints = "${cp.hostString}:${cp.port}"
                    localDatacenter = KandraTestcontainers.container.localDatacenter
                    keyspace = freshKeyspaceName()
                    autoCreateKeyspace = true
                    schemaMode = SchemaMode.NONE
                    auth { provider = KandraAuth.static("", "") }
                    shutdown {
                        graceful = true
                        drainTimeoutMs = 300
                    }
                }

                val runtime = kandra
                runtime.inFlightCount.incrementAndGet() // never decremented -- simulates a stuck query

                val elapsedMs = measureTimeMillis {
                    environment.monitor.raise(ApplicationStopping, this@application)
                }

                assert(runtime.inFlightCount.get() > 0) { "in-flight count should still be non-zero -- forced close path" }
                // Bounded near drainTimeoutMs (300ms) -- proves withTimeoutOrNull actually caps the
                // wait rather than hanging forever. Generous upper bound for CI/scheduler jitter.
                assert(elapsedMs in 300..3_000) {
                    "drain should be capped near drainTimeoutMs (300ms), took ${elapsedMs}ms"
                }

                // Reset so the natural end-of-test teardown (which also raises ApplicationStopping)
                // doesn't re-enter a "still draining" state.
                runtime.inFlightCount.set(0)
            }
        }
    }

    /**
     * GH #36 item 4 — unit-level proof of [HealthCheckCache]'s debounce logic in isolation, with a
     * counting fake probe instead of a real cluster query. No Testcontainers needed: this is purely
     * about the cache's own TTL arithmetic, decoupled from `runtime.isHealthy()`.
     */
    @Test
    fun `HealthCheckCache does not re-probe within the TTL window, and does after it elapses`() {
        val probeCalls = AtomicInteger(0)
        val cache = HealthCheckCache(ttlMillis = 300)

        runBlocking {
            repeat(5) {
                val healthy = cache.check { probeCalls.incrementAndGet(); true }
                assertTrue(healthy)
            }
            assertEquals(1, probeCalls.get(), "5 rapid calls inside the TTL window should probe only once")
            assertEquals(1, cache.probeCount.get())

            delay(350) // past the 300ms TTL
            assertTrue(cache.check { probeCalls.incrementAndGet(); true })
            assertEquals(2, probeCalls.get(), "a call after the TTL elapsed should trigger a fresh probe")
            assertEquals(2, cache.probeCount.get())
        }
    }

    /** `ttlMillis = 0` is documented as "disable caching" — every call must probe. */
    @Test
    fun `HealthCheckCache with ttlMillis 0 probes on every call`() {
        val probeCalls = AtomicInteger(0)
        val cache = HealthCheckCache(ttlMillis = 0)

        runBlocking {
            repeat(3) { cache.check { probeCalls.incrementAndGet(); true } }
        }
        assertEquals(3, probeCalls.get())
    }

    /**
     * GH #36 item 4 — end-to-end proof through the real plugin and route, against the real
     * Testcontainers cluster: rapid repeated hits to `/kandra/health` within
     * `healthCheckCacheTtlMs` must not re-query the cluster, and a hit after the TTL elapses must.
     * `HealthCheckCache.probeCount` (read off the attribute the plugin stores it under) is the
     * ground truth for "did an actual cluster query run", rather than inferring it from timing.
     */
    @OptIn(ExperimentalKandraApi::class)
    @Test
    fun `kandra health debounces repeated hits within the cache TTL and re-probes after it elapses`() {
        val cp = KandraTestcontainers.container.contactPoint
        lateinit var cache: HealthCheckCache
        testApplication {
            application {
                install(Kandra) {
                    contactPoints = "${cp.hostString}:${cp.port}"
                    localDatacenter = KandraTestcontainers.container.localDatacenter
                    keyspace = freshKeyspaceName()
                    autoCreateKeyspace = true
                    schemaMode = SchemaMode.NONE
                    auth { provider = KandraAuth.static("", "") }
                    healthCheck = true
                    healthCheckCacheTtlMs = 400
                }
                cache = attributes[KandraHealthCheckCacheKey]
            }

            repeat(5) {
                val response = client.get("/kandra/health")
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("""{"status":"UP"}""", response.bodyAsText())
            }
            assertEquals(1, cache.probeCount.get(), "5 rapid hits within the TTL should trigger only one real cluster query")

            delay(500) // past healthCheckCacheTtlMs = 400
            val response = client.get("/kandra/health")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(2, cache.probeCount.get(), "a hit after the TTL elapsed should trigger a fresh cluster query")
        }
    }

    /**
     * GH #65 — `config.keyspace` was previously only blank-checked before being spliced into CQL as
     * both an unquoted identifier and a string literal. Install must now fail fast, before any
     * connection is attempted (`contactPoints` below is never actually reachable/used).
     */
    @Test
    fun `install throws when keyspace is not a valid CQL identifier`() {
        val ex = assertThrows(KandraSchemaException::class.java) {
            testApplication {
                application {
                    install(Kandra) {
                        contactPoints = "localhost:19999"
                        localDatacenter = "dc1"
                        keyspace = "bad keyspace; DROP KEYSPACE other"
                    }
                }
            }
        }
        assertTrue(ex.message!!.contains("not a valid CQL identifier"))
    }

    /**
     * GH #65 companion: `ReplicationStrategy.NetworkTopologyStrategy`'s `dcReplicationMap` keys are
     * spliced into the `CREATE KEYSPACE ... replication = {...}` literal unvalidated. Must also fail
     * fast at install time, before any bootstrap connection is attempted.
     */
    @Test
    fun `install throws when a NetworkTopologyStrategy DC name is not a valid CQL identifier`() {
        val ex = assertThrows(KandraSchemaException::class.java) {
            testApplication {
                application {
                    install(Kandra) {
                        contactPoints = "localhost:19999"
                        localDatacenter = "dc1"
                        keyspace = "coinx"
                        autoCreateKeyspace = true
                        replicationStrategy = ReplicationStrategy.NetworkTopologyStrategy(
                            mapOf("us-east' } ; --" to 3)
                        )
                    }
                }
            }
        }
        assertTrue(ex.message!!.contains("not a valid CQL identifier"))
    }

    /**
     * GH #65 — a valid keyspace name must still install and run queries normally, proving the new
     * identifier validation doesn't reject legitimate configuration.
     */
    @OptIn(ExperimentalKandraApi::class)
    @Test
    fun `install succeeds and queries work with a valid keyspace name`() {
        val cp = KandraTestcontainers.container.contactPoint
        testApplication {
            application {
                install(Kandra) {
                    contactPoints = "${cp.hostString}:${cp.port}"
                    localDatacenter = KandraTestcontainers.container.localDatacenter
                    keyspace = freshKeyspaceName()
                    autoCreateKeyspace = true
                    schemaMode = SchemaMode.AUTO_CREATE
                    register(TestItem::class)
                    auth { provider = KandraAuth.static("", "") }
                }

                val repo = kandra.suspendRepository<TestItem>()
                val item = TestItem(UUID.randomUUID(), "valid-keyspace-item")
                runBlocking {
                    repo.save(item)
                    assertNotNull(repo.findById(item.id))
                }
            }
        }
    }

    /**
     * GH #58 — `loadBalancing.dcAwareFailover` + `allowedRemoteDcs` + `failover { onLocalDcUnavailable
     * = RETRY_REMOTE_DC }` were previously validated at startup but never wired into the driver at
     * all: `CqlSessionBuilder.buildDriverConfig` never set the driver's own DC-failover options.
     * This proves the wiring actually reaches the live driver's execution profile -- read directly
     * off `session.context.config`, not re-derived from the plugin config -- and that installing with
     * these options set doesn't break normal query execution against the (single-DC) test cluster.
     */
    @OptIn(ExperimentalKandraApi::class)
    @Test
    fun `dcAwareFailover with RETRY_REMOTE_DC wires the driver's native DC-failover options`() {
        val cp = KandraTestcontainers.container.contactPoint
        testApplication {
            application {
                install(Kandra) {
                    contactPoints = "${cp.hostString}:${cp.port}"
                    localDatacenter = KandraTestcontainers.container.localDatacenter
                    keyspace = freshKeyspaceName()
                    autoCreateKeyspace = true
                    schemaMode = SchemaMode.AUTO_CREATE
                    register(TestItem::class)
                    auth { provider = KandraAuth.static("", "") }
                    loadBalancing {
                        dcAwareFailover = true
                        allowedRemoteDcs = listOf("fake-remote-dc")
                        maxRemoteNodesPerRemoteDc = 2
                    }
                    failover {
                        onLocalDcUnavailable = FailoverPolicy.RETRY_REMOTE_DC
                    }
                }

                val profile = kandraSession.context.config.defaultProfile
                assertEquals(
                    2,
                    profile.getInt(DefaultDriverOption.LOAD_BALANCING_DC_FAILOVER_MAX_NODES_PER_REMOTE_DC)
                )
                assertTrue(
                    profile.getBoolean(DefaultDriverOption.LOAD_BALANCING_DC_FAILOVER_ALLOW_FOR_LOCAL_CONSISTENCY_LEVELS)
                )

                val repo = kandra.suspendRepository<TestItem>()
                val item = TestItem(UUID.randomUUID(), "dc-failover-item")
                runBlocking {
                    repo.save(item)
                    assertNotNull(repo.findById(item.id))
                }
            }
        }
    }

    /**
     * GH #58 companion: `dcAwareFailover = true` alone (without `onLocalDcUnavailable =
     * RETRY_REMOTE_DC`) must leave the driver's DC-failover options at their inert defaults --
     * matches the documented "both knobs must be set together" contract.
     */
    @OptIn(ExperimentalKandraApi::class)
    @Test
    fun `dcAwareFailover without RETRY_REMOTE_DC leaves the driver's DC-failover options inert`() {
        val cp = KandraTestcontainers.container.contactPoint
        testApplication {
            application {
                install(Kandra) {
                    contactPoints = "${cp.hostString}:${cp.port}"
                    localDatacenter = KandraTestcontainers.container.localDatacenter
                    keyspace = freshKeyspaceName()
                    autoCreateKeyspace = true
                    schemaMode = SchemaMode.NONE
                    auth { provider = KandraAuth.static("", "") }
                    loadBalancing {
                        dcAwareFailover = true
                        allowedRemoteDcs = listOf("fake-remote-dc")
                    }
                    // failover.onLocalDcUnavailable left at its default (THROW)
                }

                val profile = kandraSession.context.config.defaultProfile
                assertEquals(
                    0,
                    profile.getInt(DefaultDriverOption.LOAD_BALANCING_DC_FAILOVER_MAX_NODES_PER_REMOTE_DC)
                )
            }
        }
    }

    /**
     * GH #61 — the credential-rotation loop previously called `getCredentials()` and discarded the
     * result: `onCredentialRefreshed()` fired and success was logged, but nothing pushed the new
     * value into the live session's auth. This proves the refreshed username genuinely reaches the
     * driver's live `ProgrammaticPlainTextAuthProvider` (read via reflection -- the driver exposes no
     * public getter for the current value) and that the success event fires only once that's true.
     */
    @OptIn(ExperimentalKandraApi::class)
    @Test
    fun `credential rotation pushes refreshed credentials into the live session's auth provider`() {
        val cp = KandraTestcontainers.container.contactPoint
        val callCount = AtomicInteger(0)
        val rotatingProvider = KandraAuthProvider {
            if (callCount.incrementAndGet() == 1) {
                KandraCredentials("initial-user", "initial-pass")
            } else {
                KandraCredentials("rotated-user", "rotated-pass")
            }
        }
        val refreshedCount = AtomicInteger(0)
        val listener = object : KandraEventListener {
            override fun onEventualWriteFailed(tableName: String, entity: Any, error: Throwable) {}
            override fun onCredentialRefreshed() {
                refreshedCount.incrementAndGet()
            }
        }

        testApplication {
            application {
                install(Kandra) {
                    contactPoints = "${cp.hostString}:${cp.port}"
                    localDatacenter = KandraTestcontainers.container.localDatacenter
                    keyspace = freshKeyspaceName()
                    autoCreateKeyspace = true
                    schemaMode = SchemaMode.NONE
                    auth {
                        provider = rotatingProvider
                        refreshIntervalSeconds = 1
                    }
                    eventListener = listener
                }

                val session = kandraSession
                runBlocking { delay(2_500) } // allow at least one 1s refresh tick to fire

                assertTrue(refreshedCount.get() >= 1, "onCredentialRefreshed should have fired")

                val liveAuthProvider = session.context.authProvider.orElse(null)
                assertNotNull(liveAuthProvider, "session should have a live auth provider (initial credentials were non-blank)")
                assertTrue(liveAuthProvider is ProgrammaticPlainTextAuthProvider)

                val usernameField = ProgrammaticPlainTextAuthProvider::class.java.getDeclaredField("username")
                usernameField.isAccessible = true
                val liveUsername = String(usernameField.get(liveAuthProvider) as CharArray)
                assertEquals("rotated-user", liveUsername, "the live session's auth provider should carry the refreshed username")
            }
        }
    }

    /**
     * GH #61 companion: when the session was opened without an active auth provider (initial
     * credentials were blank, e.g. against `AllowAllAuthenticator`), a subsequent credential refresh
     * has nothing to push the new value into. It must not fire a misleading success event.
     */
    @OptIn(ExperimentalKandraApi::class)
    @Test
    fun `credential rotation does not fire onCredentialRefreshed when the session has no live auth provider`() {
        val cp = KandraTestcontainers.container.contactPoint
        val refreshedCount = AtomicInteger(0)
        val listener = object : KandraEventListener {
            override fun onEventualWriteFailed(tableName: String, entity: Any, error: Throwable) {}
            override fun onCredentialRefreshed() {
                refreshedCount.incrementAndGet()
            }
        }

        testApplication {
            application {
                install(Kandra) {
                    contactPoints = "${cp.hostString}:${cp.port}"
                    localDatacenter = KandraTestcontainers.container.localDatacenter
                    keyspace = freshKeyspaceName()
                    autoCreateKeyspace = true
                    schemaMode = SchemaMode.NONE
                    auth {
                        provider = KandraAuth.static("", "") // blank -- no live auth provider is built
                        refreshIntervalSeconds = 1
                    }
                    eventListener = listener
                }

                runBlocking { delay(2_500) }

                assertFalse(refreshedCount.get() >= 1, "onCredentialRefreshed should not fire with no live auth provider to update")
                assertFalse(kandraSession.context.authProvider.isPresent, "session should have no live auth provider at all")
            }
        }
    }
}
