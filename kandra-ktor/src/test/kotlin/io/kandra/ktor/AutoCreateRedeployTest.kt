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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

@ScyllaTable("redeploy_entity_a")
private data class RedeployEntityA(
    @PartitionKey val id: UUID,
    val label: String
)

@ScyllaTable("redeploy_entity_b")
private data class RedeployEntityB(
    @PartitionKey val id: UUID,
    val amount: Long
)

/**
 * GH #90 / ISS-077 -- end-to-end proof, against a real cluster, that AUTO_CREATE's DDL bootstrap
 * claim does not permanently lock out later deploys. Before the fix, the claim was keyed by a
 * fixed constant: the first successful install marked it DONE forever, so a later install that
 * registered a brand-new entity (the normal "I added a table" deploy) silently skipped its
 * `CREATE TABLE` and started up as if nothing was wrong -- the new table simply never existed.
 */
class AutoCreateRedeployTest {

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
        val ks = "kandra_redeploy_${UUID.randomUUID().toString().replace("-", "")}"
        testKeyspace = ks
        return ks
    }

    private fun tableExists(keyspace: String, tableName: String): Boolean {
        CqlSession.builder()
            .addContactPoint(KandraTestcontainers.container.contactPoint)
            .withLocalDatacenter(KandraTestcontainers.container.localDatacenter)
            .withKeyspace(keyspace)
            .build().use { session ->
                val row = session.execute(
                    "SELECT table_name FROM system_schema.tables WHERE keyspace_name = ? AND table_name = ?",
                    keyspace, tableName
                ).one()
                return row != null
            }
    }

    @OptIn(ExperimentalKandraApi::class)
    @Test
    fun `a later deploy that registers a new entity still gets its table created, even though an earlier deploy already completed the DDL bootstrap claim`() {
        val cp = KandraTestcontainers.container.contactPoint
        val keyspaceName = freshKeyspaceName()

        // Deploy 1: only RedeployEntityA is registered. This wins the DDL bootstrap claim and
        // marks it DONE.
        testApplication {
            application {
                install(Kandra) {
                    contactPoints = "${cp.hostString}:${cp.port}"
                    localDatacenter = KandraTestcontainers.container.localDatacenter
                    keyspace = keyspaceName
                    autoCreateKeyspace = true
                    schemaMode = SchemaMode.AUTO_CREATE
                    register(RedeployEntityA::class)
                    auth { provider = KandraAuth.static("", "") }
                }
            }
        }
        assertTrue(tableExists(keyspaceName, "redeploy_entity_a"), "entity A's table should exist after deploy 1")
        assertFalse(tableExists(keyspaceName, "redeploy_entity_b"), "entity B is not registered yet in deploy 1")

        // Deploy 2: same keyspace, but a new entity (RedeployEntityB) was added to the app and is
        // now also registered -- the normal shape of "I added a table" in a real deployment.
        SchemaRegistry.clear()
        testApplication {
            application {
                install(Kandra) {
                    contactPoints = "${cp.hostString}:${cp.port}"
                    localDatacenter = KandraTestcontainers.container.localDatacenter
                    keyspace = keyspaceName
                    autoCreateKeyspace = true
                    schemaMode = SchemaMode.AUTO_CREATE
                    register(RedeployEntityA::class, RedeployEntityB::class)
                    auth { provider = KandraAuth.static("", "") }
                }
            }
        }

        assertTrue(
            tableExists(keyspaceName, "redeploy_entity_b"),
            "entity B's table must be created on deploy 2, even though deploy 1's DDL bootstrap claim already completed"
        )
    }
}
