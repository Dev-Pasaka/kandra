package io.kandra.runtime

import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.LookupIndex
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

@ScyllaTable("rc_items")
data class RcItem(
    @PartitionKey val id: UUID,
    val label: String,
    val note: String? = null
)

@ScyllaTable("rc_with_lookup")
data class RcWithLookup(
    @PartitionKey val id: UUID,
    @LookupIndex(tableSuffix = "by_email") val email: String
)

/**
 * Proves [StatementBuilder] reads entity field values through the cached
 * `TableSchema.reflection.propertiesByName` map (populated once at [SchemaRegistry.register] time)
 * instead of re-resolving `entity::class.memberProperties` on every call, and that doing so produces
 * byte-for-byte identical bound statements to the old per-call-reflection behavior. See ISS-034 /
 * GitHub #13.
 */
class StatementBuilderReflectionCacheTest {

    @AfterEach
    fun tearDown() = SchemaRegistry.clear()

    private fun columnIndex(schema: io.kandra.core.schema.TableSchema, propertyName: String): Int =
        (schema.partitionKeys + schema.clusteringKeys + schema.columns + schema.lookupTables.map { it.indexColumn })
            .distinctBy { it.cqlName }
            .indexOfFirst { it.propertyName == propertyName }

    @Test
    fun `insertPrimary binds every column via the cached property map`() {
        val schema = SchemaRegistry.register(RcItem::class)
        val builder = StatementBuilder(FakeCqlSession())
        val id = UUID.randomUUID()

        val stmt = builder.insertPrimary(schema, RcItem(id, "widget", "a note"))
        val recorded = stmt.recorded()

        assertEquals(id, recorded.values[columnIndex(schema, "id")])
        assertEquals("widget", recorded.values[columnIndex(schema, "label")])
        assertEquals("a note", recorded.values[columnIndex(schema, "note")])
    }

    @Test
    fun `insertPrimary unsets a null nullable column read from the cached property map`() {
        val schema = SchemaRegistry.register(RcItem::class)
        val builder = StatementBuilder(FakeCqlSession())
        val idx = columnIndex(schema, "note")

        val stmt = builder.insertPrimary(schema, RcItem(UUID.randomUUID(), "widget", note = null))
        val recorded = stmt.recorded()

        assertTrue(idx in recorded.unsetIndices)
        assertFalse(idx in recorded.explicitNullIndices)
    }

    @Test
    fun `insertPrimaryWithNulls binds explicit null read from the cached property map`() {
        val schema = SchemaRegistry.register(RcItem::class)
        val builder = StatementBuilder(FakeCqlSession())
        val idx = columnIndex(schema, "note")

        val stmt = builder.insertPrimaryWithNulls(schema, RcItem(UUID.randomUUID(), "widget", note = null))
        val recorded = stmt.recorded()

        assertTrue(idx in recorded.explicitNullIndices)
        assertFalse(idx in recorded.unsetIndices)
    }

    @Test
    fun `insertLookup reads the entity's lookup column value via the cached property map`() {
        val schema = SchemaRegistry.register(RcWithLookup::class)
        val builder = StatementBuilder(FakeCqlSession())
        val lookup = schema.lookupTables.single()
        val id = UUID.randomUUID()

        val stmt = builder.insertLookup(schema, lookup, RcWithLookup(id, "a@b.com"))
        val recorded = stmt.recorded()

        // insertLookup's column order is [indexColumn] + partitionKeyColumns + clusteringKeyColumns
        assertEquals("a@b.com", recorded.values[0])
        assertEquals(id, recorded.values[1])
    }

    @Test
    fun `repeated insertPrimary calls for the same entity type reuse the same cached property map`() {
        val schema = SchemaRegistry.register(RcItem::class)
        val builder = StatementBuilder(FakeCqlSession())

        // Two independent entities of the same registered type — both statement builds must read
        // through schema.reflection.propertiesByName, which is the exact same Map instance both times.
        val propsBeforeFirstCall = schema.reflection.propertiesByName
        builder.insertPrimary(schema, RcItem(UUID.randomUUID(), "one"))
        builder.insertPrimary(schema, RcItem(UUID.randomUUID(), "two"))
        val propsAfterCalls = schema.reflection.propertiesByName

        assertTrue(propsBeforeFirstCall === propsAfterCalls, "the cached property map must not be rebuilt by insertPrimary")
    }
}
