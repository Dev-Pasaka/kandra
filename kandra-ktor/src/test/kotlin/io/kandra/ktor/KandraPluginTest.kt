package io.kandra.ktor

import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.KandraAuth
import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.test.KandraTestcontainers
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.util.UUID
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
}
