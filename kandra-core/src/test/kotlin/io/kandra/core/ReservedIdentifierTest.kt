package io.kandra.core

import io.kandra.core.annotations.ClusteringKey
import io.kandra.core.annotations.LookupIndex
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.annotations.SecondaryIndex
import io.kandra.core.exception.KandraSchemaException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

@ScyllaTable(name = "invites", keyspace = "my_ks")
data class ReservedInviteRow(@PartitionKey val token: String, val ownerId: UUID)

@ScyllaTable("reserved_everything")
data class ReservedEverythingRow(
    @PartitionKey val token: String,
    @ClusteringKey val order: Int,
    @LookupIndex(tableSuffix = "by_from") val from: String,
    @SecondaryIndex val default: String,
    val timestamp: Long,
)

@ScyllaTable("order")
data class ReservedTableNameRow(@PartitionKey val id: UUID)

@ScyllaTable(name = "rows", keyspace = "select")
data class ReservedKeyspaceRow(@PartitionKey val id: UUID)

/** GH #188: reserved words as column names are quoted in DDL; reserved table/keyspace names are rejected. */
class ReservedIdentifierTest {

    @AfterEach
    fun cleanup() = SchemaRegistry.clear()

    @Test
    fun `quoteIfNeeded quotes reserved words in lowercase and leaves everything else alone`() {
        assertEquals("\"token\"", CqlNaming.quoteIfNeeded("token"))
        assertEquals("\"token\"", CqlNaming.quoteIfNeeded("Token"))
        assertEquals("\"default\"", CqlNaming.quoteIfNeeded("default"))
        assertEquals("owner_id", CqlNaming.quoteIfNeeded("owner_id"))
        // Unreserved keywords that parse bare stay bare, so existing DDL is unchanged.
        listOf("timestamp", "type", "key", "ttl", "date", "count", "users", "status", "value")
            .forEach { assertEquals(it, CqlNaming.quoteIfNeeded(it)) }
    }

    @Test
    fun `the reporter's entity generates valid CREATE TABLE DDL`() {
        val schema = SchemaRegistry.register(ReservedInviteRow::class)
        assertEquals(
            "CREATE TABLE IF NOT EXISTS my_ks.invites (\n    \"token\" TEXT,\n    owner_id UUID,\n    PRIMARY KEY (\"token\")\n);",
            DdlGenerator.primaryTable(schema)
        )
        assertEquals("token", schema.partitionKeys.single().cqlName, "cqlName itself stays raw")
    }

    @Test
    fun `every DDL position quotes a reserved column`() {
        val schema = SchemaRegistry.register(ReservedEverythingRow::class)
        val ddl = DdlGenerator.allStatements(schema)
        val primary = ddl.first()
        assertTrue(primary.contains("PRIMARY KEY (\"token\", \"order\")"), primary)
        assertTrue(primary.contains("CLUSTERING ORDER BY (\"order\" ASC)"), primary)
        assertTrue(primary.contains("\"from\" TEXT"), primary)
        assertTrue(primary.contains("\"default\" TEXT"), primary)
        assertTrue(primary.contains("    timestamp BIGINT"), primary)
        val lookup = ddl.single { it.contains("reserved_everything_by_from") }
        assertTrue(lookup.contains("PRIMARY KEY (\"from\")"), lookup)
        val index = ddl.single { it.startsWith("CREATE INDEX") }
        assertEquals(
            "CREATE INDEX IF NOT EXISTS reserved_everything_default_idx ON reserved_everything (\"default\");",
            index
        )
        assertTrue(DdlGenerator.alterTableAddColumn(schema, schema.columns.first { it.cqlName == "default" }).contains("ADD \"default\" TEXT"))
    }

    @Test
    fun `a reserved table name is rejected with a clear message`() {
        val e = assertThrows<KandraSchemaException> { SchemaRegistry.register(ReservedTableNameRow::class) }
        assertTrue(e.message!!.contains("reserved CQL keyword"), e.message)
    }

    @Test
    fun `a reserved keyspace name is rejected with a clear message`() {
        val e = assertThrows<KandraSchemaException> { SchemaRegistry.register(ReservedKeyspaceRow::class) }
        assertTrue(e.message!!.contains("reserved CQL keyword"), e.message)
    }
}
