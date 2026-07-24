package io.kandra.runtime

import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.LookupIndex
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.exception.KandraSchemaException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

@ScyllaTable("sb_items")
data class SbItem(
    @PartitionKey val id: UUID,
    val label: String,
    val note: String? = null
)

@ScyllaTable("sb_nullable_key")
data class NullableKeyEntity(
    @PartitionKey val id: UUID?,
    val label: String
)

@ScyllaTable("sb_with_lookup")
data class WithLookup(
    @PartitionKey val id: UUID,
    @LookupIndex(tableSuffix = "by_email") val email: String
)

/**
 * Focused unit tests for [StatementBuilder]'s idempotency flags and UNSET-vs-NULL binding —
 * previously only exercised indirectly through `kandra-test`'s Testcontainers-based integration
 * test. Uses [ControllableFakeSession]/[FakePreparedStatement] (see FakeDriverSupport.kt) so the
 * returned [com.datastax.oss.driver.api.core.cql.BoundStatement] can actually be inspected —
 * `kandra-test`'s `FakeKandraSession` can't do this (`PreparedStatement.bind()` unconditionally
 * throws there).
 */
class StatementBuilderTest {

    @AfterEach
    fun tearDown() {
        SchemaRegistry.clear()
    }

    /** Index of a column within the exact column ordering [StatementBuilder] itself builds:
     *  partition keys, then clustering keys, then regular columns, then lookup index columns. */
    private fun columnIndex(schema: io.kandra.core.schema.TableSchema, propertyName: String): Int =
        (schema.partitionKeys + schema.clusteringKeys + schema.columns + schema.lookupTables.map { it.indexColumn })
            .distinctBy { it.cqlName }
            .indexOfFirst { it.propertyName == propertyName }

    // ── Idempotency ───────────────────────────────────────────────────────────

    @Test
    fun `insertPrimary is not idempotent by default (plain INSERT)`() {
        val schema = SchemaRegistry.register(SbItem::class)
        val builder = StatementBuilder(ControllableFakeSession())

        val stmt = builder.insertPrimary(schema, SbItem(UUID.randomUUID(), "widget", "a note"))

        assertEquals(false, stmt.recorded().idempotent, "plain INSERT must not be idempotent (retry risk)")
    }

    @Test
    fun `insertPrimary with ifNotExists is idempotent (LWT is safe to retry)`() {
        val schema = SchemaRegistry.register(SbItem::class)
        val builder = StatementBuilder(ControllableFakeSession())

        val stmt = builder.insertPrimary(schema, SbItem(UUID.randomUUID(), "widget"), ifNotExists = true)

        assertEquals(true, stmt.recorded().idempotent)
    }

    @Test
    fun `insertLookup is never idempotent`() {
        val schema = SchemaRegistry.register(WithLookup::class)
        val builder = StatementBuilder(ControllableFakeSession())
        val lookup = schema.lookupTables.single()

        val stmt = builder.insertLookup(lookup, WithLookup(UUID.randomUUID(), "a@b.com"))

        assertEquals(false, stmt.recorded().idempotent)
    }

    @Test
    fun `deleteLookup and deleteById are idempotent`() {
        val schema = SchemaRegistry.register(WithLookup::class)
        val builder = StatementBuilder(ControllableFakeSession())
        val lookup = schema.lookupTables.single()

        assertEquals(true, builder.deleteLookup(lookup, "a@b.com").recorded().idempotent)
        assertEquals(true, builder.deleteById(schema, UUID.randomUUID()).recorded().idempotent)
    }

    @Test
    fun `selectById is idempotent`() {
        val schema = SchemaRegistry.register(SbItem::class)
        val builder = StatementBuilder(ControllableFakeSession())

        val stmt = builder.selectById(schema, UUID.randomUUID())

        assertEquals(true, stmt.recorded().idempotent)
    }

    // ── UNSET (no tombstone) vs explicit NULL (tombstone) ────────────────────

    @Test
    fun `insertPrimary calls unset (not null) for a null nullable column`() {
        val schema = SchemaRegistry.register(SbItem::class)
        val builder = StatementBuilder(ControllableFakeSession())
        val idx = columnIndex(schema, "note")

        val stmt = builder.insertPrimary(schema, SbItem(UUID.randomUUID(), "widget", note = null))
        val recorded = stmt.recorded()

        assertTrue(idx in recorded.unsetIndices, "expected 'note' to be UNSET, leaving any existing value alone")
        assertFalse(idx in recorded.explicitNullIndices, "save() must never tombstone a null field")
    }

    @Test
    fun `insertPrimaryWithNulls binds actual null (tombstone) instead of unset`() {
        val schema = SchemaRegistry.register(SbItem::class)
        val builder = StatementBuilder(ControllableFakeSession())
        val idx = columnIndex(schema, "note")

        val stmt = builder.insertPrimaryWithNulls(schema, SbItem(UUID.randomUUID(), "widget", note = null))
        val recorded = stmt.recorded()

        assertTrue(idx in recorded.explicitNullIndices, "saveWithNulls() must bind a real null to create a tombstone")
        assertFalse(idx in recorded.unsetIndices)
    }

    @Test
    fun `insertPrimary binds a non-null value normally (neither unset nor explicit null)`() {
        val schema = SchemaRegistry.register(SbItem::class)
        val builder = StatementBuilder(ControllableFakeSession())
        val idx = columnIndex(schema, "note")

        val stmt = builder.insertPrimary(schema, SbItem(UUID.randomUUID(), "widget", note = "hello"))
        val recorded = stmt.recorded()

        assertFalse(idx in recorded.unsetIndices)
        assertFalse(idx in recorded.explicitNullIndices)
        assertEquals("hello", recorded.values[idx])
    }

    @Test
    fun `insertPrimary throws when a partition key value is null (keys can never be UNSET)`() {
        val schema = SchemaRegistry.register(NullableKeyEntity::class)
        val builder = StatementBuilder(ControllableFakeSession())

        assertThrows(KandraSchemaException::class.java) {
            builder.insertPrimary(schema, NullableKeyEntity(id = null, label = "x"))
        }
    }
}
