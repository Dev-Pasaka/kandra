package io.kandra.runtime

import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.Counter
import io.kandra.core.annotations.LookupConsistency
import io.kandra.core.annotations.LookupIndex
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

@ScyllaTable("bsp_widgets")
data class BspWidget(
    @PartitionKey val id: UUID,
    val name: String
)

@ScyllaTable("bsp_accounts")
data class BspAccount(
    @PartitionKey val id: UUID,
    @LookupIndex(tableSuffix = "by_email", consistency = LookupConsistency.BATCH) val email: String
)

@ScyllaTable("bsp_counters")
data class BspCounter(
    @PartitionKey val id: UUID,
    @Counter val hits: Long = 0L
)

/**
 * End-to-end regression coverage for GH #27 / ISS-049: every [BatchEngine] suspend write path must
 * build its statements via [StatementBuilder]'s suspend (`*Suspend`) methods — never the blocking
 * ones — so a prepared-statement cache miss never blocks the calling coroutine dispatcher thread.
 *
 * Driven by [PrepareCallTrackingSession] (see FakeDriverSupport.kt): its blocking `prepare()` throws,
 * so any suspend path that (incorrectly) fell back to it would fail the test with an [AssertionError]
 * instead of silently succeeding. This complements [StatementBuilderSuspendPrepareTest] (which tests
 * `StatementBuilder` in isolation) by proving the actual [BatchEngine] call sites are wired to the
 * suspend builder methods, not just that the builder methods themselves exist.
 */
class BatchEngineSuspendPreparePathTest {

    @AfterEach
    fun tearDown() {
        SchemaRegistry.clear()
    }

    private fun unconfinedScope() = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    @Test
    fun `saveSuspend (with a BATCH lookup) never calls blocking prepare`() = runBlocking {
        val schema = SchemaRegistry.register(BspAccount::class)
        val session = PrepareCallTrackingSession()
        val scope = unconfinedScope()
        val engine = BatchEngine(session, StatementBuilder(session), scope)
        try {
            engine.saveSuspend(schema, BspAccount(UUID.randomUUID(), "a@b.com"))

            assertEquals(0, session.blockingPrepareCount.get())
            assertTrue(session.asyncPrepareCount.get() >= 2, "expected async prepares for both the primary INSERT and the BATCH lookup INSERT")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `saveIfNotExistsSuspend never calls blocking prepare`() = runBlocking {
        val schema = SchemaRegistry.register(BspWidget::class)
        val session = PrepareCallTrackingSession()
        val scope = unconfinedScope()
        val engine = BatchEngine(session, StatementBuilder(session), scope)
        try {
            engine.saveIfNotExistsSuspend(schema, BspWidget(UUID.randomUUID(), "widget"))

            assertEquals(0, session.blockingPrepareCount.get())
            assertTrue(session.asyncPrepareCount.get() >= 1)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `saveWithNullsSuspend never calls blocking prepare`() = runBlocking {
        val schema = SchemaRegistry.register(BspWidget::class)
        val session = PrepareCallTrackingSession()
        val scope = unconfinedScope()
        val engine = BatchEngine(session, StatementBuilder(session), scope)
        try {
            engine.saveWithNullsSuspend(schema, BspWidget(UUID.randomUUID(), "widget"))

            assertEquals(0, session.blockingPrepareCount.get())
            assertTrue(session.asyncPrepareCount.get() >= 1)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `deleteSuspend (with a lookup) never calls blocking prepare`() = runBlocking {
        val schema = SchemaRegistry.register(BspAccount::class)
        val session = PrepareCallTrackingSession()
        val scope = unconfinedScope()
        val engine = BatchEngine(session, StatementBuilder(session), scope)
        try {
            engine.deleteSuspend(schema, BspAccount(UUID.randomUUID(), "a@b.com"))

            assertEquals(0, session.blockingPrepareCount.get())
            assertTrue(session.asyncPrepareCount.get() >= 2, "expected async prepares for both the primary DELETE and the lookup DELETE")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `deleteByIdSuspend never calls blocking prepare`() = runBlocking {
        val schema = SchemaRegistry.register(BspWidget::class)
        val session = PrepareCallTrackingSession()
        val scope = unconfinedScope()
        val engine = BatchEngine(session, StatementBuilder(session), scope)
        try {
            engine.deleteByIdSuspend(schema, UUID.randomUUID())

            assertEquals(0, session.blockingPrepareCount.get())
            assertEquals(1, session.asyncPrepareCount.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `saveAllSuspend never calls blocking prepare`() = runBlocking {
        val schema = SchemaRegistry.register(BspAccount::class)
        val session = PrepareCallTrackingSession()
        val scope = unconfinedScope()
        val engine = BatchEngine(session, StatementBuilder(session), scope)
        try {
            val entities = listOf(
                BspAccount(UUID.randomUUID(), "a@b.com"),
                BspAccount(UUID.randomUUID(), "c@d.com")
            )
            engine.saveAllSuspend(schema, entities)

            assertEquals(0, session.blockingPrepareCount.get())
            // 2 entities x (1 primary INSERT + 1 BATCH lookup INSERT), but CQL is identical across
            // entities so the cache collapses this to exactly 2 distinct async prepares.
            assertEquals(2, session.asyncPrepareCount.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `updateSuspend without a Version column (lookup value changes) never calls blocking prepare`() = runBlocking {
        val schema = SchemaRegistry.register(BspAccount::class)
        val session = PrepareCallTrackingSession()
        val scope = unconfinedScope()
        val engine = BatchEngine(session, StatementBuilder(session), scope)
        try {
            val id = UUID.randomUUID()
            val old = BspAccount(id, "old@b.com")
            val new = BspAccount(id, "new@b.com")

            engine.updateSuspend(schema, old, new)

            assertEquals(0, session.blockingPrepareCount.get())
            // insertPrimary + deleteLookup(old) + insertLookup(new), all distinct CQL.
            assertTrue(session.asyncPrepareCount.get() >= 3)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `updateForceSuspend never calls blocking prepare`() = runBlocking {
        val schema = SchemaRegistry.register(BspAccount::class)
        val session = PrepareCallTrackingSession()
        val scope = unconfinedScope()
        val engine = BatchEngine(session, StatementBuilder(session), scope)
        try {
            engine.updateForceSuspend(schema, BspAccount(UUID.randomUUID(), "a@b.com"))

            assertEquals(0, session.blockingPrepareCount.get())
            assertTrue(session.asyncPrepareCount.get() >= 1)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `appendSuspend, removeSuspend and putSuspend never call blocking prepare`() = runBlocking {
        val schema = SchemaRegistry.register(BspWidget::class)
        val session = PrepareCallTrackingSession()
        val scope = unconfinedScope()
        val engine = BatchEngine(session, StatementBuilder(session), scope)
        try {
            val keys = listOf(UUID.randomUUID())
            engine.appendSuspend(schema, keys, "name", listOf("x"))
            engine.removeSuspend(schema, keys, "name", listOf("x"))
            engine.putSuspend(schema, keys, "name", mapOf("k" to "v"))

            assertEquals(0, session.blockingPrepareCount.get())
            assertTrue(session.asyncPrepareCount.get() >= 2, "append/put share CQL; remove is distinct")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `incrementSuspend and decrementSuspend never call blocking prepare`() = runBlocking {
        val schema = SchemaRegistry.register(BspCounter::class)
        val session = PrepareCallTrackingSession()
        val scope = unconfinedScope()
        val engine = BatchEngine(session, StatementBuilder(session), scope)
        try {
            val keys = mapOf("id" to UUID.randomUUID())
            engine.incrementSuspend(schema, "hits", keys, 1L)
            engine.decrementSuspend(schema, "hits", keys, 1L)

            assertEquals(0, session.blockingPrepareCount.get())
            assertEquals(2, session.asyncPrepareCount.get(), "increment (+) and decrement (-) produce distinct CQL")
        } finally {
            scope.cancel()
        }
    }
}
