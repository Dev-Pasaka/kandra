package io.kandra.ktor

import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.KandraAuth
import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.test.KandraTestcontainers
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
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
}
