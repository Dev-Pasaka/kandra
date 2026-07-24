package io.kandra.runtime

import com.datastax.oss.driver.api.core.NoNodeAvailableException
import com.datastax.oss.driver.api.core.cql.BatchableStatement
import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.KandraEventListener
import io.kandra.core.annotations.LookupConsistency
import io.kandra.core.exception.KandraQueryException
import io.kandra.core.schema.ColumnSchema
import io.kandra.core.schema.LookupTableSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.typeOf

/**
 * Covers GH-14: `BatchEngine.fireEventual`/`fireEventualSuspend`/`fireEventualStatements` used to
 * call `session.execute`/`session.executeSuspend` directly, bypassing `executeWithRetry`'s
 * retry-on-transient-error, `inFlightCount` bookkeeping, and shutdown gate. These tests exercise
 * the fixed behavior directly (via reflection into the private `fireEventual*` methods, since they
 * have no public entry point of their own) against a purpose-built fake [CqlSession].
 */
class BatchEngineEventualWriteTest {

    data class Widget(val id: String, val email: String)

    private val idCol = ColumnSchema(propertyName = "id", cqlName = "id", type = typeOf<String>())
    private val emailCol = ColumnSchema(propertyName = "email", cqlName = "email", type = typeOf<String>())
    private val lookupSchema = LookupTableSchema(
        tableName = "widget_by_email",
        indexColumn = emailCol,
        partitionKeyColumns = listOf(idCol),
        consistency = LookupConsistency.EVENTUAL
    )
    private val widget = Widget(id = "w-1", email = "a@b.com")

    private val scopes = mutableListOf<CoroutineScope>()

    @AfterEach
    fun cleanup() {
        scopes.forEach { it.cancel() }
        scopes.clear()
    }

    private fun newScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes.add(it) }

    @OptIn(ExperimentalKandraApi::class)
    private class RecordingEventListener : KandraEventListener {
        val failures = CopyOnWriteArrayList<Triple<String, Any, Throwable>>()
        override fun onEventualWriteFailed(tableName: String, entity: Any, error: Throwable) {
            failures.add(Triple(tableName, entity, error))
        }
    }

    private fun fastRetryConfig(): RetryConfig = RetryConfig().apply {
        maxAttempts = 3
        backoffMillis = 5
        maxBackoffMillis = 20
    }

    private fun newEngine(
        session: FakeEventualCqlSession,
        listener: RecordingEventListener,
        retryConfig: RetryConfig = fastRetryConfig()
    ): BatchEngine = BatchEngine(
        session = session,
        statementBuilder = StatementBuilder(session),
        scope = newScope(),
        eventListener = listener,
        retryConfig = retryConfig
    )

    private fun invokeFireEventual(engine: BatchEngine, lookups: List<LookupTableSchema>, entity: Any) {
        val m = BatchEngine::class.java.getDeclaredMethod("fireEventual", List::class.java, Any::class.java)
        m.isAccessible = true
        m.invoke(engine, lookups, entity)
    }

    private fun invokeFireEventualSuspend(engine: BatchEngine, lookups: List<LookupTableSchema>, entity: Any) {
        val m = BatchEngine::class.java.getDeclaredMethod("fireEventualSuspend", List::class.java, Any::class.java)
        m.isAccessible = true
        m.invoke(engine, lookups, entity)
    }

    private fun invokeFireEventualStatements(engine: BatchEngine, stmts: List<BatchableStatement<*>>, entity: Any, context: String, tableName: String) {
        val m = BatchEngine::class.java.getDeclaredMethod(
            "fireEventualStatements", List::class.java, Any::class.java, String::class.java, String::class.java
        )
        m.isAccessible = true
        m.invoke(engine, stmts, entity, context, tableName)
    }

    private fun awaitTrue(timeoutMs: Long = 2000, poll: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!poll() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    // ── (a) retry on transient error ──────────────────────────────────────────

    @Test
    fun `fireEventual retries a transient failure once and eventually succeeds`() {
        val session = FakeEventualCqlSession()
        val attempts = AtomicInteger(0)
        val succeeded = CountDownLatch(1)
        session.onExecute = {
            if (attempts.incrementAndGet() == 1) throw NoNodeAvailableException()
            succeeded.countDown()
            FakeEventualResultSet.empty()
        }
        val listener = RecordingEventListener()
        val engine = newEngine(session, listener)

        invokeFireEventual(engine, listOf(lookupSchema), widget)

        assertTrue(succeeded.await(2, TimeUnit.SECONDS), "eventual write should have retried and succeeded")
        assertEquals(2, attempts.get(), "expected exactly one retry (2 attempts total)")
        assertTrue(listener.failures.isEmpty(), "no failure should be reported once the retry succeeds")
    }

    @Test
    fun `fireEventualSuspend retries a transient failure once and eventually succeeds`() {
        val session = FakeEventualCqlSession()
        val attempts = AtomicInteger(0)
        val succeeded = CountDownLatch(1)
        session.onExecuteAsync = {
            if (attempts.incrementAndGet() == 1) {
                CompletableFuture.failedFuture(NoNodeAvailableException())
            } else {
                succeeded.countDown()
                CompletableFuture.completedFuture(FakeEventualAsyncResultSet.empty())
            }
        }
        val listener = RecordingEventListener()
        val engine = newEngine(session, listener)

        invokeFireEventualSuspend(engine, listOf(lookupSchema), widget)

        assertTrue(succeeded.await(2, TimeUnit.SECONDS), "eventual write should have retried and succeeded")
        assertEquals(2, attempts.get(), "expected exactly one retry (2 attempts total)")
        assertTrue(listener.failures.isEmpty(), "no failure should be reported once the retry succeeds")
    }

    @Test
    fun `fireEventualStatements (update path) retries a transient failure once and eventually succeeds`() {
        val session = FakeEventualCqlSession()
        val attempts = AtomicInteger(0)
        val succeeded = CountDownLatch(1)
        session.onExecute = {
            if (attempts.incrementAndGet() == 1) throw NoNodeAvailableException()
            succeeded.countDown()
            FakeEventualResultSet.empty()
        }
        val listener = RecordingEventListener()
        val engine = newEngine(session, listener)
        val stmt = StatementBuilder(session).insertLookup(lookupSchema, widget)

        invokeFireEventualStatements(engine, listOf(stmt), widget, "(update)", "widget_by_email")

        assertTrue(succeeded.await(2, TimeUnit.SECONDS), "eventual write should have retried and succeeded")
        assertEquals(2, attempts.get(), "expected exactly one retry (2 attempts total)")
        assertTrue(listener.failures.isEmpty(), "no failure should be reported once the retry succeeds")
    }

    // ── (b) inFlightCount tracking ────────────────────────────────────────────

    @Test
    fun `fireEventual increments inFlightCount while executing and decrements after`() {
        val session = FakeEventualCqlSession()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        session.onExecute = {
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
            FakeEventualResultSet.empty()
        }
        val listener = RecordingEventListener()
        val engine = newEngine(session, listener)

        assertEquals(0, engine.inFlightCount.get())
        invokeFireEventual(engine, listOf(lookupSchema), widget)

        assertTrue(entered.await(2, TimeUnit.SECONDS), "write should have started")
        assertEquals(1, engine.inFlightCount.get(), "inFlightCount should be incremented while the eventual write is in flight")

        release.countDown()

        awaitTrue { engine.inFlightCount.get() == 0 }
        assertEquals(0, engine.inFlightCount.get(), "inFlightCount should be decremented once the eventual write completes")
    }

    @Test
    fun `fireEventualSuspend increments inFlightCount while executing and decrements after`() {
        val session = FakeEventualCqlSession()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        session.onExecuteAsync = {
            val future = CompletableFuture<com.datastax.oss.driver.api.core.cql.AsyncResultSet>()
            executor.submit {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                future.complete(FakeEventualAsyncResultSet.empty())
            }
            future
        }
        val listener = RecordingEventListener()
        val engine = newEngine(session, listener)

        assertEquals(0, engine.inFlightCount.get())
        invokeFireEventualSuspend(engine, listOf(lookupSchema), widget)

        assertTrue(entered.await(2, TimeUnit.SECONDS), "write should have started")
        assertEquals(1, engine.inFlightCount.get(), "inFlightCount should be incremented while the eventual write is in flight")

        release.countDown()

        awaitTrue { engine.inFlightCount.get() == 0 }
        assertEquals(0, engine.inFlightCount.get(), "inFlightCount should be decremented once the eventual write completes")
        executor.shutdown()
    }

    // ── (c) rejected once isShuttingDown is set ───────────────────────────────

    @Test
    fun `fireEventual is rejected once isShuttingDown is set, same as a synchronous query`() {
        val session = FakeEventualCqlSession()
        var executeWasCalled = false
        session.onExecute = { executeWasCalled = true; FakeEventualResultSet.empty() }
        val listener = RecordingEventListener()
        val engine = newEngine(session, listener)
        engine.isShuttingDown.set(true)

        invokeFireEventual(engine, listOf(lookupSchema), widget)

        awaitTrue { listener.failures.isNotEmpty() }
        assertEquals(1, listener.failures.size)
        val (_, _, error) = listener.failures.first()
        assertTrue(error is KandraQueryException, "expected the same KandraQueryException a synchronous query would throw")
        assertTrue(error.message?.contains("shutting down") == true, "unexpected message: ${error.message}")
        assertFalse(executeWasCalled, "the driver should never be invoked once shutdown has been signalled")
    }

    @Test
    fun `fireEventualSuspend is rejected once isShuttingDown is set, same as a synchronous query`() {
        val session = FakeEventualCqlSession()
        var executeWasCalled = false
        session.onExecuteAsync = { executeWasCalled = true; CompletableFuture.completedFuture(FakeEventualAsyncResultSet.empty()) }
        val listener = RecordingEventListener()
        val engine = newEngine(session, listener)
        engine.isShuttingDown.set(true)

        invokeFireEventualSuspend(engine, listOf(lookupSchema), widget)

        awaitTrue { listener.failures.isNotEmpty() }
        assertEquals(1, listener.failures.size)
        val (_, _, error) = listener.failures.first()
        assertTrue(error is KandraQueryException, "expected the same KandraQueryException a synchronous query would throw")
        assertTrue(error.message?.contains("shutting down") == true, "unexpected message: ${error.message}")
        assertFalse(executeWasCalled, "the driver should never be invoked once shutdown has been signalled")
    }

    @Test
    fun `fireEventualStatements (update path) is rejected once isShuttingDown is set`() {
        val session = FakeEventualCqlSession()
        var executeWasCalled = false
        session.onExecute = { executeWasCalled = true; FakeEventualResultSet.empty() }
        val listener = RecordingEventListener()
        val engine = newEngine(session, listener)
        val stmt = StatementBuilder(session).insertLookup(lookupSchema, widget)
        engine.isShuttingDown.set(true)

        invokeFireEventualStatements(engine, listOf(stmt), widget, "(update)", "widget_by_email")

        awaitTrue { listener.failures.isNotEmpty() }
        assertEquals(1, listener.failures.size)
        val (_, _, error) = listener.failures.first()
        assertTrue(error is KandraQueryException, "expected the same KandraQueryException a synchronous query would throw")
        assertFalse(executeWasCalled, "the driver should never be invoked once shutdown has been signalled")
    }
}
