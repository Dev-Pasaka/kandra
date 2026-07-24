package io.kandra.ktor

import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.test.KandraTestcontainers
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.util.UUID

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
        val ks = "kandra_ktor_test_${UUID.randomUUID().toString().replace("-", "")}"
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
                }
            }
        }
    }

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
}
