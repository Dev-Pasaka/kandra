package io.kandra.runtime

import com.datastax.oss.driver.api.core.NoNodeAvailableException
import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.exception.KandraQueryException
import io.kandra.runtime.repository.KandraRepository
import io.kandra.runtime.repository.KandraSuspendRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@ScyllaTable("cc_widgets")
data class CcWidget(
    @PartitionKey val id: UUID,
    val name: String
)

/**
 * Covers GH-29 items 1 & 2: `append`/`remove`/`put`/`increment`/`decrement` and `deleteById`'s
 * "not found" branch used to call `session.execute`/`session.executeSuspend` directly from
 * `KandraRepository`/`KandraSuspendRepository`, bypassing [BatchEngine.checkNotShuttingDown],
 * `inFlightCount` tracking, and retry-on-transient-error entirely (unlike every other write, which
 * routes through `executeWithRetry`/`executeWithRetrySuspend`).
 *
 * The fix adds `append`/`remove`/`put`/`increment`/`decrement`/`deleteById` (+ `*Suspend`
 * counterparts) directly on [BatchEngine], and routes the five repository methods (and
 * `deleteById`'s not-found branch) through them instead of touching `session` directly. Tested here
 * against [BatchEngine] itself (mirroring [BatchEngineTest]'s conventions) plus a couple of
 * repository-level tests proving the call sites are actually wired up.
 */
class BatchEngineCollectionCounterTest {

    @AfterEach
    fun tearDown() {
        SchemaRegistry.clear()
    }

    private fun unconfinedScope() = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    // ── shutdown gate ──────────────────────────────────────────────────────

    @Test
    fun `append is rejected once shutting down, without touching the session`() {
        val schema = SchemaRegistry.register(CcWidget::class)
        val session = ControllableFakeSession()
        val engine = BatchEngine(session, StatementBuilder(session), unconfinedScope())
        engine.isShuttingDown.set(true)

        assertThrows(KandraQueryException::class.java) {
            engine.append(schema, listOf(UUID.randomUUID()), "name", listOf("x"))
        }
        assertEquals(0, session.executeCallCount)
    }

    @Test
    fun `appendSuspend is rejected once shutting down, without touching the session`() {
        val schema = SchemaRegistry.register(CcWidget::class)
        val session = ControllableFakeSession()
        val engine = BatchEngine(session, StatementBuilder(session), unconfinedScope())
        engine.isShuttingDown.set(true)

        assertThrows(KandraQueryException::class.java) {
            runBlocking { engine.appendSuspend(schema, listOf(UUID.randomUUID()), "name", listOf("x")) }
        }
        assertEquals(0, session.executeCallCount)
    }

    @Test
    fun `remove is rejected once shutting down, without touching the session`() {
        val schema = SchemaRegistry.register(CcWidget::class)
        val session = ControllableFakeSession()
        val engine = BatchEngine(session, StatementBuilder(session), unconfinedScope())
        engine.isShuttingDown.set(true)

        assertThrows(KandraQueryException::class.java) {
            engine.remove(schema, listOf(UUID.randomUUID()), "name", listOf("x"))
        }
        assertEquals(0, session.executeCallCount)
    }

    @Test
    fun `put is rejected once shutting down, without touching the session`() {
        val schema = SchemaRegistry.register(CcWidget::class)
        val session = ControllableFakeSession()
        val engine = BatchEngine(session, StatementBuilder(session), unconfinedScope())
        engine.isShuttingDown.set(true)

        assertThrows(KandraQueryException::class.java) {
            engine.put(schema, listOf(UUID.randomUUID()), "name", mapOf("k" to "v"))
        }
        assertEquals(0, session.executeCallCount)
    }

    @Test
    fun `increment is rejected once shutting down, without touching the session`() {
        val schema = SchemaRegistry.register(CcWidget::class)
        val session = ControllableFakeSession()
        val engine = BatchEngine(session, StatementBuilder(session), unconfinedScope())
        engine.isShuttingDown.set(true)

        assertThrows(KandraQueryException::class.java) {
            engine.increment(schema, "name", mapOf("id" to UUID.randomUUID()), 1L)
        }
        assertEquals(0, session.executeCallCount)
    }

    @Test
    fun `decrement is rejected once shutting down, without touching the session`() {
        val schema = SchemaRegistry.register(CcWidget::class)
        val session = ControllableFakeSession()
        val engine = BatchEngine(session, StatementBuilder(session), unconfinedScope())
        engine.isShuttingDown.set(true)

        assertThrows(KandraQueryException::class.java) {
            engine.decrement(schema, "name", mapOf("id" to UUID.randomUUID()), 1L)
        }
        assertEquals(0, session.executeCallCount)
    }

    @Test
    fun `incrementSuspend and decrementSuspend are rejected once shutting down, without touching the session`() {
        val schema = SchemaRegistry.register(CcWidget::class)
        val session = ControllableFakeSession()
        val engine = BatchEngine(session, StatementBuilder(session), unconfinedScope())
        engine.isShuttingDown.set(true)

        assertThrows(KandraQueryException::class.java) {
            runBlocking { engine.incrementSuspend(schema, "name", mapOf("id" to UUID.randomUUID()), 1L) }
        }
        assertThrows(KandraQueryException::class.java) {
            runBlocking { engine.decrementSuspend(schema, "name", mapOf("id" to UUID.randomUUID()), 1L) }
        }
        assertEquals(0, session.executeCallCount)
    }

    @Test
    fun `deleteById is rejected once shutting down, without touching the session`() {
        val schema = SchemaRegistry.register(CcWidget::class)
        val session = ControllableFakeSession()
        val engine = BatchEngine(session, StatementBuilder(session), unconfinedScope())
        engine.isShuttingDown.set(true)

        assertThrows(KandraQueryException::class.java) {
            engine.deleteById(schema, UUID.randomUUID())
        }
        assertEquals(0, session.executeCallCount)
    }

    @Test
    fun `deleteByIdSuspend is rejected once shutting down, without touching the session`() {
        val schema = SchemaRegistry.register(CcWidget::class)
        val session = ControllableFakeSession()
        val engine = BatchEngine(session, StatementBuilder(session), unconfinedScope())
        engine.isShuttingDown.set(true)

        assertThrows(KandraQueryException::class.java) {
            runBlocking { engine.deleteByIdSuspend(schema, UUID.randomUUID()) }
        }
        assertEquals(0, session.executeCallCount)
    }

    // ── retry-on-transient-error ───────────────────────────────────────────

    @Test
    fun `increment retries a retryable failure and succeeds within maxAttempts`() {
        val schema = SchemaRegistry.register(CcWidget::class)
        val session = ControllableFakeSession(failuresBeforeSuccess = 2)
        val engine = BatchEngine(
            session, StatementBuilder(session), unconfinedScope(),
            retryConfig = RetryConfig().apply { backoffMillis = 1; maxBackoffMillis = 2 }
        )

        engine.increment(schema, "name", mapOf("id" to UUID.randomUUID()), 1L)

        assertEquals(3, session.executeCallCount)
    }

    @Test
    fun `append retries a retryable failure and succeeds within maxAttempts`() {
        val schema = SchemaRegistry.register(CcWidget::class)
        val session = ControllableFakeSession(failuresBeforeSuccess = 2)
        val engine = BatchEngine(
            session, StatementBuilder(session), unconfinedScope(),
            retryConfig = RetryConfig().apply { backoffMillis = 1; maxBackoffMillis = 2 }
        )

        engine.append(schema, listOf(UUID.randomUUID()), "name", listOf("x"))

        assertEquals(3, session.executeCallCount)
    }

    @Test
    fun `deleteById retries a retryable failure and succeeds within maxAttempts`() {
        val schema = SchemaRegistry.register(CcWidget::class)
        val session = ControllableFakeSession(failuresBeforeSuccess = 2)
        val engine = BatchEngine(
            session, StatementBuilder(session), unconfinedScope(),
            retryConfig = RetryConfig().apply { backoffMillis = 1; maxBackoffMillis = 2 }
        )

        engine.deleteById(schema, UUID.randomUUID())

        assertEquals(3, session.executeCallCount)
    }

    @Test
    fun `deleteByIdSuspend retries a retryable failure and succeeds within maxAttempts`() = runBlocking {
        val schema = SchemaRegistry.register(CcWidget::class)
        val session = ControllableFakeSession(failuresBeforeSuccess = 2)
        val engine = BatchEngine(
            session, StatementBuilder(session), unconfinedScope(),
            retryConfig = RetryConfig().apply { backoffMillis = 1; maxBackoffMillis = 2 }
        )

        engine.deleteByIdSuspend(schema, UUID.randomUUID())

        assertEquals(3, session.executeCallCount)
    }

    // ── inFlightCount tracking ─────────────────────────────────────────────

    @Test
    fun `increment increments inFlightCount while executing and decrements after`() {
        val schema = SchemaRegistry.register(CcWidget::class)
        val session = FakeEventualCqlSession()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        session.onExecute = {
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
            FakeEventualResultSet.empty()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = BatchEngine(session, StatementBuilder(session), scope)

        assertEquals(0, engine.inFlightCount.get())
        val thread = Thread { engine.increment(schema, "name", mapOf("id" to UUID.randomUUID()), 1L) }
        thread.start()

        assertTrue(entered.await(2, TimeUnit.SECONDS), "write should have started")
        assertEquals(1, engine.inFlightCount.get(), "inFlightCount should be incremented while the write is in flight")

        release.countDown()
        thread.join(2000)
        assertEquals(0, engine.inFlightCount.get(), "inFlightCount should be decremented once the write completes")
        scope.cancel()
    }

    @Test
    fun `deleteById increments inFlightCount while executing and decrements after`() {
        val schema = SchemaRegistry.register(CcWidget::class)
        val session = FakeEventualCqlSession()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        session.onExecute = {
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
            FakeEventualResultSet.empty()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = BatchEngine(session, StatementBuilder(session), scope)

        assertEquals(0, engine.inFlightCount.get())
        val thread = Thread { engine.deleteById(schema, UUID.randomUUID()) }
        thread.start()

        assertTrue(entered.await(2, TimeUnit.SECONDS), "write should have started")
        assertEquals(1, engine.inFlightCount.get(), "inFlightCount should be incremented while the write is in flight")

        release.countDown()
        thread.join(2000)
        assertEquals(0, engine.inFlightCount.get(), "inFlightCount should be decremented once the write completes")
        scope.cancel()
    }

    @Test
    fun `does not retry a non-retryable exception on increment`() {
        val schema = SchemaRegistry.register(CcWidget::class)
        val session = ControllableFakeSession(failuresBeforeSuccess = 1, exceptionFactory = { IllegalStateException("boom") })
        val engine = BatchEngine(session, StatementBuilder(session), unconfinedScope())

        assertThrows(IllegalStateException::class.java) {
            engine.increment(schema, "name", mapOf("id" to UUID.randomUUID()), 1L)
        }

        assertEquals(1, session.executeCallCount)
    }

    // ── repository-level wiring: deleteById's "not found" branch ─────────

    @Test
    fun `KandraRepository deleteById's not-found branch is rejected once shutting down`() {
        val schema = SchemaRegistry.register(CcWidget::class)
        val session = ControllableFakeSession()
        val engine = BatchEngine(session, StatementBuilder(session), unconfinedScope())
        val repo = KandraRepository(session, schema, CcWidget::class, engine)
        // ControllableFakeSession.execute always returns an empty ResultSet -> findById's SELECT
        // resolves to "not found", driving deleteById into the not-found branch below.
        engine.isShuttingDown.set(true)

        assertThrows(KandraQueryException::class.java) {
            repo.deleteById(UUID.randomUUID())
        }
        // Only the SELECT (findById lookup) reached the session -- the not-found DELETE never did.
        assertEquals(1, session.executeCallCount)
    }

    @Test
    fun `KandraSuspendRepository deleteById's not-found branch is rejected once shutting down`() = runBlocking {
        val schema = SchemaRegistry.register(CcWidget::class)
        val session = ControllableFakeSession()
        val engine = BatchEngine(session, StatementBuilder(session), unconfinedScope())
        val repo = KandraSuspendRepository(session, schema, CcWidget::class, engine)
        engine.isShuttingDown.set(true)

        assertThrows(KandraQueryException::class.java) {
            runBlocking { repo.deleteById(UUID.randomUUID()) }
        }
        assertEquals(1, session.executeCallCount)
    }
}
