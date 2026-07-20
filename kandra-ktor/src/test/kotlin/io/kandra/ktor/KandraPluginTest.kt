package io.kandra.ktor

import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.kandra.ktor.SchemaMode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.util.UUID

@ScyllaTable("test_items")
data class TestItem(
    @PartitionKey val id: UUID,
    val label: String
)

/**
 * Integration tests for the Kandra Ktor plugin.
 *
 * Full tests (plugin installs, DDL runs, session accessible) require a live ScyllaDB/Cassandra
 * instance. Run with `-Pkandra.integration=true` or via Testcontainers in CI.
 * The disabled tests document the expected integration behaviour.
 */
class KandraPluginTest {

    @AfterEach
    fun cleanup() = SchemaRegistry.clear()

    @Test
    fun `SchemaRegistry registers entity class`() {
        val schema = SchemaRegistry.register(TestItem::class)
        assertNotNull(schema)
        assert(schema.tableName == "test_items")
        assert(schema.partitionKeys.first().cqlName == "id")
    }

    @Test
    @Disabled("Requires live ScyllaDB — run with Testcontainers in CI")
    fun `plugin installs without error`() {
        testApplication {
            application {
                install(Kandra) {
                    contactPoints = "localhost:9042"
                    keyspace = "kandra_test"
                    schemaMode = SchemaMode.AUTO_CREATE
                    register(TestItem::class)
                }
            }
        }
    }

    @Test
    @Disabled("Requires live ScyllaDB — run with Testcontainers in CI")
    fun `kandraSession is accessible after install`() {
        testApplication {
            application {
                install(Kandra) {
                    contactPoints = "localhost:9042"
                    keyspace = "kandra_test"
                    schemaMode = SchemaMode.NONE
                    register(TestItem::class)
                }
                val session = kandraSession
                assertNotNull(session)
            }
        }
    }
}
