package io.kandra.runtime

import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.Counter
import io.kandra.core.annotations.LookupIndex
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.exception.KandraSchemaException
import kotlinx.coroutines.runBlocking
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

@ScyllaTable("sb_counters")
data class SbCounter(
    @PartitionKey val id: UUID,
    @Counter val hits: Long = 0L
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

        val stmt = builder.insertLookup(schema, lookup, WithLookup(UUID.randomUUID(), "a@b.com"))

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

    // ── counterUpdate: Long.MIN_VALUE overflow guard (GH #36 item 3 / ISS-049) ────────────────────
    // Math.abs(Long.MIN_VALUE) overflows back to Long.MIN_VALUE itself (two's-complement has no
    // positive representation for it) -- silently binding a negative delta while the CQL literally
    // says "+ ?" (or vice versa for a "- ?"). counterUpdate must reject this explicitly instead of
    // relying on Math.abs's silent wraparound.

    @Test
    fun `counterUpdate throws KandraSchemaException for delta = Long_MIN_VALUE instead of silently overflowing`() {
        val schema = SchemaRegistry.register(SbCounter::class)
        val builder = StatementBuilder(ControllableFakeSession())

        assertThrows(KandraSchemaException::class.java) {
            builder.counterUpdate(schema, "hits", mapOf("id" to UUID.randomUUID()), Long.MIN_VALUE)
        }
    }

    @Test
    fun `counterUpdateSuspend throws KandraSchemaException for delta = Long_MIN_VALUE instead of silently overflowing`() {
        val schema = SchemaRegistry.register(SbCounter::class)
        val builder = StatementBuilder(ControllableFakeSession())

        assertThrows(KandraSchemaException::class.java) {
            runBlocking { builder.counterUpdateSuspend(schema, "hits", mapOf("id" to UUID.randomUUID()), Long.MIN_VALUE) }
        }
    }

    @Test
    fun `counterUpdate accepts Long_MIN_VALUE plus one (the actual boundary) without throwing`() {
        val schema = SchemaRegistry.register(SbCounter::class)
        val builder = StatementBuilder(ControllableFakeSession())

        // Sanity check that the guard is exact -- it must not over-reject values Math.abs handles fine.
        val stmt = builder.counterUpdate(schema, "hits", mapOf("id" to UUID.randomUUID()), Long.MIN_VALUE + 1)
        assertEquals(Long.MAX_VALUE, stmt.recorded().values[0])
    }

    @Test
    fun `counterUpdate does not overflow back to a negative value for a normal negative delta`() {
        val schema = SchemaRegistry.register(SbCounter::class)
        val builder = StatementBuilder(ControllableFakeSession())

        val stmt = builder.counterUpdate(schema, "hits", mapOf("id" to UUID.randomUUID()), -5L)
        assertEquals(5L, stmt.recorded().values[0], "counterUpdate binds the absolute value; the sign lives in the CQL operator")
    }
}
