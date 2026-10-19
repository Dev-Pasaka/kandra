package io.kandra.core

import io.kandra.core.annotations.LookupIndex
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.annotations.SecondaryIndex
import io.kandra.core.exception.KandraSchemaException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@ScyllaTable(name = "ks_invoices", keyspace = "billing")
data class KsInvoice(
    @PartitionKey val id: String,
    @LookupIndex(tableSuffix = "by_email") val email: String,
    @SecondaryIndex val status: String
)

@ScyllaTable(name = "ks_default")
data class KsDefault(
    @PartitionKey val id: String,
    @LookupIndex(tableSuffix = "by_email") val email: String,
    @SecondaryIndex val status: String
)

@ScyllaTable(name = "ks_blank", keyspace = "   ")
data class KsBlank(@PartitionKey val id: String)

@ScyllaTable(name = "ks_bad", keyspace = "bad-keyspace; DROP")
data class KsBad(@PartitionKey val id: String)

class SchemaRegistryKeyspaceTest {
    @AfterEach
    fun cleanup() = SchemaRegistry.clear()

    @Test
    fun `annotation keyspace is parsed into the schema`() {
        val s = SchemaRegistry.register(KsInvoice::class)
        assertEquals("billing", s.keyspace)
        assertEquals("billing.ks_invoices", s.qualifiedTableName)
    }

    @Test
    fun `default and blank keyspace resolve to null`() {
        val d = SchemaRegistry.register(KsDefault::class)
        assertNull(d.keyspace)
        assertEquals("ks_default", d.qualifiedTableName)
        val b = SchemaRegistry.register(KsBlank::class)
        assertNull(b.keyspace)
        assertEquals("ks_blank", b.qualifiedTableName)
    }

    @Test
    fun `invalid keyspace identifier throws actionable KandraSchemaException`() {
        val ex = assertThrows<KandraSchemaException> { SchemaRegistry.register(KsBad::class) }
        assertTrue(ex.message!!.contains("KsBad") && ex.message!!.contains("keyspace"))
    }

    @Test
    fun `lookup tables inherit the entity keyspace`() {
        val s = SchemaRegistry.register(KsInvoice::class)
        val l = s.lookupTables.single()
        assertEquals("billing", l.keyspace)
        assertEquals("billing.ks_invoices_by_email", l.qualifiedTableName)
        assertNull(SchemaRegistry.register(KsDefault::class).lookupTables.single().keyspace)
    }

    @Test
    fun `DDL is qualified when keyspace is set`() {
        val s = SchemaRegistry.register(KsInvoice::class)
        assertTrue(DdlGenerator.primaryTable(s).startsWith("CREATE TABLE IF NOT EXISTS billing.ks_invoices ("))
        assertTrue(
            DdlGenerator.lookupTable(s.lookupTables.single())
                .startsWith("CREATE TABLE IF NOT EXISTS billing.ks_invoices_by_email (")
        )
        assertEquals(
            "CREATE INDEX IF NOT EXISTS billing.ks_invoices_status_idx ON billing.ks_invoices (status);",
            DdlGenerator.secondaryIndex(s, s.secondaryIndexes.single())
        )
        assertEquals(
            "ALTER TABLE billing.ks_invoices ADD status TEXT;",
            DdlGenerator.alterTableAddColumn(s, s.secondaryIndexes.single())
        )
    }

    @Test
    fun `DDL is unchanged when keyspace is null`() {
        val s = SchemaRegistry.register(KsDefault::class)
        assertTrue(DdlGenerator.primaryTable(s).startsWith("CREATE TABLE IF NOT EXISTS ks_default ("))
        assertTrue(
            DdlGenerator.lookupTable(s.lookupTables.single())
                .startsWith("CREATE TABLE IF NOT EXISTS ks_default_by_email (")
        )
        assertEquals(
            "CREATE INDEX IF NOT EXISTS ks_default_status_idx ON ks_default (status);",
            DdlGenerator.secondaryIndex(s, s.secondaryIndexes.single())
        )
        assertEquals(
            "ALTER TABLE ks_default ADD status TEXT;",
            DdlGenerator.alterTableAddColumn(s, s.secondaryIndexes.single())
        )
    }
}
