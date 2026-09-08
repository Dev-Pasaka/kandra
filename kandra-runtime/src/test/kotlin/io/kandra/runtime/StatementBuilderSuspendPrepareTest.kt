package io.kandra.runtime

import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.Counter
import io.kandra.core.annotations.LookupIndex
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

@ScyllaTable("spp_items")
data class SppItem(
    @PartitionKey val id: UUID,
    val name: String
)

@ScyllaTable("spp_with_lookup")
data class SppWithLookup(
    @PartitionKey val id: UUID,
    @LookupIndex(tableSuffix = "by_email") val email: String
)

@ScyllaTable("spp_counters")
data class SppCounter(
    @PartitionKey val id: UUID,
    @Counter val hits: Long = 0L
)

/**
 * Regression coverage for GH #27 / ISS-049: [StatementBuilder]'s suspend methods must never fall
 * back to the blocking [com.datastax.oss.driver.api.core.CqlSession.prepare] on a prepared-statement
 * cache miss — they must use `prepareAsync` (via [StatementBuilder]'s private `prepareSuspend`)
 * instead, so the first call for a given CQL string never blocks the calling coroutine dispatcher
 * thread for a full driver round-trip.
 *
 * Driven by [PrepareCallTrackingSession] (see FakeDriverSupport.kt), whose blocking `prepare()`
 * throws an [AssertionError] and whose `prepareAsync()` succeeds normally — a suspend method that
 * (incorrectly) called the blocking path would fail loudly here instead of silently passing. Each
 * suspend method is paired with its blocking counterpart exercised against a *fresh* session, both
 * to prove the fake genuinely distinguishes the two paths (the blocking call must actually observe
 * the AssertionError) and to document, side by side, which path each API uses.
 */
class StatementBuilderSuspendPrepareTest {

    @AfterEach
    fun tearDown() {
        SchemaRegistry.clear()
    }

    // ── insertPrimary / insertPrimarySuspend ──────────────────────────────────

    @Test
    fun `insertPrimary (blocking) uses the blocking prepare`() {
        val schema = SchemaRegistry.register(SppItem::class)
        val session = PrepareCallTrackingSession()
        val builder = StatementBuilder(session)

        assertThrowsBlockingPrepare { builder.insertPrimary(schema, SppItem(UUID.randomUUID(), "widget")) }
    }

    @Test
    fun `insertPrimarySuspend never calls blocking prepare on a cache miss`() = runBlocking {
        val schema = SchemaRegistry.register(SppItem::class)
        val session = PrepareCallTrackingSession()
        val builder = StatementBuilder(session)

        builder.insertPrimarySuspend(schema, SppItem(UUID.randomUUID(), "widget"))

        assertEquals(0, session.blockingPrepareCount.get())
        assertEquals(1, session.asyncPrepareCount.get())
    }

    // ── insertPrimaryWithNulls / insertPrimaryWithNullsSuspend ────────────────

    @Test
    fun `insertPrimaryWithNulls (blocking) uses the blocking prepare`() {
        val schema = SchemaRegistry.register(SppItem::class)
        val session = PrepareCallTrackingSession()
        val builder = StatementBuilder(session)

        assertThrowsBlockingPrepare { builder.insertPrimaryWithNulls(schema, SppItem(UUID.randomUUID(), "widget")) }
    }

    @Test
    fun `insertPrimaryWithNullsSuspend never calls blocking prepare on a cache miss`() = runBlocking {
        val schema = SchemaRegistry.register(SppItem::class)
        val session = PrepareCallTrackingSession()
        val builder = StatementBuilder(session)

        builder.insertPrimaryWithNullsSuspend(schema, SppItem(UUID.randomUUID(), "widget"))

        assertEquals(0, session.blockingPrepareCount.get())
        assertEquals(1, session.asyncPrepareCount.get())
    }

    // ── insertLookup / insertLookupSuspend ─────────────────────────────────────

    @Test
    fun `insertLookup (blocking) uses the blocking prepare`() {
        val schema = SchemaRegistry.register(SppWithLookup::class)
        val lookup = schema.lookupTables.single()
        val session = PrepareCallTrackingSession()
        val builder = StatementBuilder(session)

        assertThrowsBlockingPrepare { builder.insertLookup(schema, lookup, SppWithLookup(UUID.randomUUID(), "a@b.com")) }
    }

    @Test
    fun `insertLookupSuspend never calls blocking prepare on a cache miss`() = runBlocking {
        val schema = SchemaRegistry.register(SppWithLookup::class)
        val lookup = schema.lookupTables.single()
        val session = PrepareCallTrackingSession()
        val builder = StatementBuilder(session)

        builder.insertLookupSuspend(schema, lookup, SppWithLookup(UUID.randomUUID(), "a@b.com"))

        assertEquals(0, session.blockingPrepareCount.get())
        assertEquals(1, session.asyncPrepareCount.get())
    }

    // ── deleteLookup / deleteLookupSuspend ──────────────────────────────────────

    @Test
    fun `deleteLookup (blocking) uses the blocking prepare`() {
        val schema = SchemaRegistry.register(SppWithLookup::class)
        val lookup = schema.lookupTables.single()
        val session = PrepareCallTrackingSession()
        val builder = StatementBuilder(session)

        assertThrowsBlockingPrepare { builder.deleteLookup(lookup, "a@b.com") }
    }

    @Test
    fun `deleteLookupSuspend never calls blocking prepare on a cache miss`() = runBlocking {
        val schema = SchemaRegistry.register(SppWithLookup::class)
        val lookup = schema.lookupTables.single()
        val session = PrepareCallTrackingSession()
        val builder = StatementBuilder(session)

        builder.deleteLookupSuspend(lookup, "a@b.com")

        assertEquals(0, session.blockingPrepareCount.get())
        assertEquals(1, session.asyncPrepareCount.get())
    }

    // ── selectById / selectByIdSuspend ─────────────────────────────────────────

    @Test
    fun `selectById (blocking) uses the blocking prepare`() {
        val schema = SchemaRegistry.register(SppItem::class)
        val session = PrepareCallTrackingSession()
        val builder = StatementBuilder(session)

        assertThrowsBlockingPrepare { builder.selectById(schema, UUID.randomUUID()) }
    }

    @Test
    fun `selectByIdSuspend never calls blocking prepare on a cache miss`() = runBlocking {
        val schema = SchemaRegistry.register(SppItem::class)
        val session = PrepareCallTrackingSession()
        val builder = StatementBuilder(session)

        builder.selectByIdSuspend(schema, UUID.randomUUID())

        assertEquals(0, session.blockingPrepareCount.get())
        assertEquals(1, session.asyncPrepareCount.get())
    }

    // ── selectByLookup / selectByLookupSuspend ─────────────────────────────────

    @Test
    fun `selectByLookup (blocking) uses the blocking prepare`() {
        val schema = SchemaRegistry.register(SppWithLookup::class)
        val lookup = schema.lookupTables.single()
        val session = PrepareCallTrackingSession()
        val builder = StatementBuilder(session)

        assertThrowsBlockingPrepare { builder.selectByLookup(lookup, "a@b.com") }
    }

    @Test
    fun `selectByLookupSuspend never calls blocking prepare on a cache miss`() = runBlocking {
        val schema = SchemaRegistry.register(SppWithLookup::class)
        val lookup = schema.lookupTables.single()
        val session = PrepareCallTrackingSession()
        val builder = StatementBuilder(session)

        builder.selectByLookupSuspend(lookup, "a@b.com")

        assertEquals(0, session.blockingPrepareCount.get())
        assertEquals(1, session.asyncPrepareCount.get())
    }

    // ── selectByPartitionKeyIn / selectByPartitionKeyInSuspend ─────────────────

    @Test
    fun `selectByPartitionKeyIn (blocking) uses the blocking prepare`() {
        val schema = SchemaRegistry.register(SppItem::class)
        val session = PrepareCallTrackingSession()
        val builder = StatementBuilder(session)

        assertThrowsBlockingPrepare { builder.selectByPartitionKeyIn(schema, listOf(UUID.randomUUID())) }
    }

    @Test
    fun `selectByPartitionKeyInSuspend never calls blocking prepare on a cache miss`() = runBlocking {
        val schema = SchemaRegistry.register(SppItem::class)
        val session = PrepareCallTrackingSession()
        val builder = StatementBuilder(session)

        builder.selectByPartitionKeyInSuspend(schema, listOf(UUID.randomUUID()))

        assertEquals(0, session.blockingPrepareCount.get())
        assertEquals(1, session.asyncPrepareCount.get())
    }

    // ── deleteById / deleteByIdSuspend ──────────────────────────────────────────

    @Test
    fun `deleteById (blocking) uses the blocking prepare`() {
        val schema = SchemaRegistry.register(SppItem::class)
        val session = PrepareCallTrackingSession()
        val builder = StatementBuilder(session)

        assertThrowsBlockingPrepare { builder.deleteById(schema, UUID.randomUUID()) }
    }

    @Test
    fun `deleteByIdSuspend never calls blocking prepare on a cache miss`() = runBlocking {
        val schema = SchemaRegistry.register(SppItem::class)
        val session = PrepareCallTrackingSession()
        val builder = StatementBuilder(session)

        builder.deleteByIdSuspend(schema, UUID.randomUUID())

        assertEquals(0, session.blockingPrepareCount.get())
        assertEquals(1, session.asyncPrepareCount.get())
    }

    // ── appendToCollection / appendToCollectionSuspend ──────────────────────────

    @Test
    fun `appendToCollection (blocking) uses the blocking prepare`() {
        val schema = SchemaRegistry.register(SppItem::class)
        val session = PrepareCallTrackingSession()
        val builder = StatementBuilder(session)

        assertThrowsBlockingPrepare { builder.appendToCollection(schema, listOf(UUID.randomUUID()), "name", listOf("x")) }
    }

    @Test
    fun `appendToCollectionSuspend never calls blocking prepare on a cache miss`() = runBlocking {
        val schema = SchemaRegistry.register(SppItem::class)
        val session = PrepareCallTrackingSession()
        val builder = StatementBuilder(session)

        builder.appendToCollectionSuspend(schema, listOf(UUID.randomUUID()), "name", listOf("x"))

        assertEquals(0, session.blockingPrepareCount.get())
        assertEquals(1, session.asyncPrepareCount.get())
    }

    // ── removeFromCollection / removeFromCollectionSuspend ──────────────────────

    @Test
    fun `removeFromCollection (blocking) uses the blocking prepare`() {
        val schema = SchemaRegistry.register(SppItem::class)
        val session = PrepareCallTrackingSession()
        val builder = StatementBuilder(session)

        assertThrowsBlockingPrepare { builder.removeFromCollection(schema, listOf(UUID.randomUUID()), "name", listOf("x")) }
    }

    @Test
    fun `removeFromCollectionSuspend never calls blocking prepare on a cache miss`() = runBlocking {
        val schema = SchemaRegistry.register(SppItem::class)
        val session = PrepareCallTrackingSession()
        val builder = StatementBuilder(session)

        builder.removeFromCollectionSuspend(schema, listOf(UUID.randomUUID()), "name", listOf("x"))

        assertEquals(0, session.blockingPrepareCount.get())
        assertEquals(1, session.asyncPrepareCount.get())
    }

    // ── counterUpdate / counterUpdateSuspend ────────────────────────────────────

    @Test
    fun `counterUpdate (blocking) uses the blocking prepare`() {
        val schema = SchemaRegistry.register(SppCounter::class)
        val session = PrepareCallTrackingSession()
        val builder = StatementBuilder(session)

        assertThrowsBlockingPrepare { builder.counterUpdate(schema, "hits", mapOf("id" to UUID.randomUUID()), 1L) }
    }

    @Test
    fun `counterUpdateSuspend never calls blocking prepare on a cache miss`() = runBlocking {
        val schema = SchemaRegistry.register(SppCounter::class)
        val session = PrepareCallTrackingSession()
        val builder = StatementBuilder(session)

        builder.counterUpdateSuspend(schema, "hits", mapOf("id" to UUID.randomUUID()), 1L)

        assertEquals(0, session.blockingPrepareCount.get())
        assertEquals(1, session.asyncPrepareCount.get())
    }

    // ── Cache is shared between the blocking and suspend prepare paths ─────────

    @Test
    fun `a second selectByIdSuspend call for the same CQL is a cache hit, not a second async prepare`() = runBlocking {
        val schema = SchemaRegistry.register(SppItem::class)
        val session = PrepareCallTrackingSession()
        val builder = StatementBuilder(session)

        // Both calls bind different id values but produce the identical CQL template
        // ("SELECT * FROM spp_items WHERE id = ?") -- StatementBuilder's cache is keyed by CQL
        // string, not by bound values, so the second call must be a pure cache read.
        builder.selectByIdSuspend(schema, UUID.randomUUID())
        assertEquals(1, session.asyncPrepareCount.get())

        builder.selectByIdSuspend(schema, UUID.randomUUID())
        assertEquals(1, session.asyncPrepareCount.get(), "second call for the same CQL string must be a cache hit")
        assertEquals(0, session.blockingPrepareCount.get())
    }

    /** Asserts [block] triggered [PrepareCallTrackingSession]'s blocking `prepare()` trap. */
    private fun assertThrowsBlockingPrepare(block: () -> Unit) {
        val thrown = assertThrows(AssertionError::class.java, block)
        assertTrue(
            thrown.message?.contains("Blocking CqlSession.prepare") == true,
            "expected the blocking-prepare trap to fire, got: ${thrown.message}"
        )
    }
}
