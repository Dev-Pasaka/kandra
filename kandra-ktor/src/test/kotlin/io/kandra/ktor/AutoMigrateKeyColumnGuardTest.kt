package io.kandra.ktor

import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.KandraAuth
import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.ClusteringKey
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.exception.KandraSchemaException
import io.kandra.test.KandraTestcontainers
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

@ScyllaTable("migrate_guard_test")
private data class MigrateGuardV1(
    @PartitionKey val id: UUID,
    val label: String
)

@ScyllaTable("migrate_guard_test")
private data class MigrateGuardWithPlainColumn(
    @PartitionKey val id: UUID,
    val label: String,
    val note: String
)

@ScyllaTable("migrate_guard_test")
private data class MigrateGuardWithNewClusteringKey(
    @PartitionKey val id: UUID,
    @ClusteringKey val version: Int,
    val label: String
)

@ScyllaTable("migrate_guard_test")
private data class MigrateGuardWithNewPartitionKey(
    @PartitionKey(index = 0) val id: UUID,
    @PartitionKey(index = 1) val shard: Int,
    val label: String
)

/**
 * GH #92 / ISS-079 -- proves `SchemaMode.AUTO_MIGRATE`'s column-diff loop refuses to `ALTER TABLE
 * ADD` a column that the entity declares as a partition/clustering key but the physical table
 * doesn't have as part of its primary key. CQL cannot add a column into an existing primary key --
 * silently emitting a plain `ALTER TABLE ADD` for one would create it as a regular column while
 * Kandra keeps treating it as part of row identity, collapsing distinct logical rows onto the same
 * physical row. A plain (non-key) column addition must still work exactly as before.
 *
 * Run against a real Testcontainers-backed cluster -- this exercises `system_schema.columns`
 * reads and real `ALTER TABLE` execution, which a fake session can't provide.
 */
class AutoMigrateKeyColumnGuardTest {

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
        // "kandra_mg_" (10 chars) + a 32-char dash-stripped UUID = 42 chars, safely under
        // Cassandra's 48-character keyspace name limit (see KandraPluginTest.freshKeyspaceName
        // for the same reasoning -- a longer prefix here previously pushed this over the limit).
        val ks = "kandra_mg_${UUID.randomUUID().toString().replace("-", "")}"
        testKeyspace = ks
        return ks
    }

    private fun columnNames(keyspace: String, tableName: String): Set<String> {
        CqlSession.builder()
            .addContactPoint(KandraTestcontainers.container.contactPoint)
            .withLocalDatacenter(KandraTestcontainers.container.localDatacenter)
            .withKeyspace(keyspace)
            .build().use { session ->
                return session.execute(
                    "SELECT column_name FROM system_schema.columns WHERE keyspace_name = ? AND table_name = ?",
                    keyspace, tableName
                ).all().map { it.getString("column_name")!! }.toSet()
            }
    }

    @OptIn(ExperimentalKandraApi::class)
    private fun installV1(keyspaceName: String, cp: java.net.InetSocketAddress) {
        testApplication {
            application {
                install(Kandra) {
                    contactPoints = "${cp.hostString}:${cp.port}"
                    localDatacenter = KandraTestcontainers.container.localDatacenter
                    keyspace = keyspaceName
                    autoCreateKeyspace = true
                    schemaMode = SchemaMode.AUTO_CREATE
                    register(MigrateGuardV1::class)
                    auth { provider = KandraAuth.static("", "") }
                }
            }
        }
    }

    @OptIn(ExperimentalKandraApi::class)
    @Test
    fun `AUTO_MIGRATE still adds a new plain column exactly as before`() {
        val cp = KandraTestcontainers.container.contactPoint
        val keyspaceName = freshKeyspaceName()
        installV1(keyspaceName, cp)

        SchemaRegistry.clear()
        testApplication {
            application {
                install(Kandra) {
                    contactPoints = "${cp.hostString}:${cp.port}"
                    localDatacenter = KandraTestcontainers.container.localDatacenter
                    keyspace = keyspaceName
                    autoCreateKeyspace = true
                    schemaMode = SchemaMode.AUTO_MIGRATE
                    register(MigrateGuardWithPlainColumn::class)
                    auth { provider = KandraAuth.static("", "") }
                }
            }
        }

        assertTrue(
            "note" in columnNames(keyspaceName, "migrate_guard_test"),
            "a plain (non-key) column must still be added by AUTO_MIGRATE"
        )
    }

    @OptIn(ExperimentalKandraApi::class)
    @Test
    fun `AUTO_MIGRATE refuses to ALTER TABLE ADD a new clustering key column`() {
        val cp = KandraTestcontainers.container.contactPoint
        val keyspaceName = freshKeyspaceName()
        installV1(keyspaceName, cp)

        SchemaRegistry.clear()
        val ex = assertThrows(KandraSchemaException::class.java) {
            testApplication {
                application {
                    install(Kandra) {
                        contactPoints = "${cp.hostString}:${cp.port}"
                        localDatacenter = KandraTestcontainers.container.localDatacenter
                        keyspace = keyspaceName
                        autoCreateKeyspace = true
                        schemaMode = SchemaMode.AUTO_MIGRATE
                        register(MigrateGuardWithNewClusteringKey::class)
                        auth { provider = KandraAuth.static("", "") }
                    }
                }
            }
        }
        assertTrue(ex.message!!.contains("version"), "the exception should name the offending column: ${ex.message}")
        assertTrue(ex.message!!.contains("clustering"), "the exception should say why: ${ex.message}")

        assertFalse(
            "version" in columnNames(keyspaceName, "migrate_guard_test"),
            "the clustering-key column must NOT have been added as a plain column"
        )
    }

    @OptIn(ExperimentalKandraApi::class)
    @Test
    fun `AUTO_MIGRATE refuses to ALTER TABLE ADD a new partition key column`() {
        val cp = KandraTestcontainers.container.contactPoint
        val keyspaceName = freshKeyspaceName()
        installV1(keyspaceName, cp)

        SchemaRegistry.clear()
        val ex = assertThrows(KandraSchemaException::class.java) {
            testApplication {
                application {
                    install(Kandra) {
                        contactPoints = "${cp.hostString}:${cp.port}"
                        localDatacenter = KandraTestcontainers.container.localDatacenter
                        keyspace = keyspaceName
                        autoCreateKeyspace = true
                        schemaMode = SchemaMode.AUTO_MIGRATE
                        register(MigrateGuardWithNewPartitionKey::class)
                        auth { provider = KandraAuth.static("", "") }
                    }
                }
            }
        }
        assertTrue(ex.message!!.contains("shard"), "the exception should name the offending column: ${ex.message}")
        assertTrue(ex.message!!.contains("partition"), "the exception should say why: ${ex.message}")

        assertFalse(
            "shard" in columnNames(keyspaceName, "migrate_guard_test"),
            "the partition-key column must NOT have been added as a plain column"
        )
    }
}
