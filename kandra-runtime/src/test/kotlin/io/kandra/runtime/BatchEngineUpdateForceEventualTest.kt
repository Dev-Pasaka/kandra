package io.kandra.runtime

import com.datastax.oss.driver.api.core.NoNodeAvailableException
import com.datastax.oss.driver.api.core.cql.BatchStatement
import com.datastax.oss.driver.api.core.cql.Statement
import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.KandraEventListener
import io.kandra.core.SchemaRegistry
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@ScyllaTable("ufe_accounts")
data class UfeAccount(
    @PartitionKey val id: UUID,
    @LookupIndex(tableSuffix = "by_email", consistency = LookupConsistency.BATCH) val email: String,
    @LookupIndex(tableSuffix = "by_handle", consistency = LookupConsistency.EVENTUAL) val handle: String
)

/**
 * Covers GH-29 item 4: [BatchEngine.updateForce]'s EVENTUAL lookup writes used to fire via a raw
 * `scope.launch { session.execute(it) }` / `session.executeSuspend(it)` — unlike `save`/`update`
 * (fixed under GH-14/ISS-033), this bypassed `executeWithRetry`/`executeWithRetrySuspend` entirely
 * (no retry, no inFlightCount tracking, no shutdown gate) and only logged failures — never forwarding
 * them to [KandraEventListener.onEventualWriteFailed] like every other eventual write path.
 *
 * While fixing this, the identical bypass was found in `updateSuspend`'s (and the shared
 * `updateLookupsSuspend`'s) EVENTUAL branch — it also called `session.executeSuspend` directly
 * instead of going through `executeWithRetrySuspend`, even though the *blocking* `update()`/
 * `updateLookups()` already routed through `fireEventualStatements` correctly. Both are fixed the
 * same way here: via a new `fireEventualStatementsSuspend` helper mirroring the existing
 * (blocking) `fireEventualStatements`.
 */
@OptIn(ExperimentalKandraApi::class)
class BatchEngineUpdateForceEventualTest {

    @AfterEach
    fun tearDown() {
        SchemaRegistry.clear()
    }

    private class RecordingEventListener : KandraEventListener {
        val failures = CopyOnWriteArrayList<Triple<String, Any, Throwable>>()
        override fun onEventualWriteFailed(tableName: String, entity: Any, error: Throwable) {
            failures.add(Triple(tableName, entity, error))
        }
    }

    private val scopes = mutableListOf<CoroutineScope>()

    @AfterEach
    fun cleanupScopes() {
        scopes.forEach { it.cancel() }
        scopes.clear()
    }

    private fun newScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes.add(it) }

    private fun fastRetryConfig(): RetryConfig = RetryConfig().apply {
        maxAttempts = 3
        backoffMillis = 5
        maxBackoffMillis = 20
    }

    private fun awaitTrue(timeoutMs: Long = 2000, poll: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!poll() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    // ── (d) updateForce's EVENTUAL write failures now reach the event listener ──

    @Test
    fun `updateForce forwards a failed EVENTUAL lookup write to the event listener`() {
        val schema = SchemaRegistry.register(UfeAccount::class)
        val session = FakeEventualCqlSession()
        session.onExecute = { stmt: Statement<*> ->
            // The primary + BATCH-lookup LOGGED BATCH always succeeds; only the standalone
            // EVENTUAL (handle) lookup insert fails.
            if (stmt !is BatchStatement) throw IllegalStateException("simulated eventual write failure")
            FakeEventualResultSet.empty()
        }
        val listener = RecordingEventListener()
        val engine = BatchEngine(session, StatementBuilder(session), newScope(), eventListener = listener)

        engine.updateForce(schema, UfeAccount(UUID.randomUUID(), "a@example.com", "handle1"))

        awaitTrue { listener.failures.isNotEmpty() }
        assertEquals(1, listener.failures.size)
        val (tableName, _, error) = listener.failures.first()
        assertEquals("(updateForce)", tableName)
        assertTrue(error is IllegalStateException, "unexpected error type: $error")
    }

    @Test
    fun `updateForceSuspend forwards a failed EVENTUAL lookup write to the event listener`() = runBlocking {
        val schema = SchemaRegistry.register(UfeAccount::class)
        val session = FakeEventualCqlSession()
        session.onExecuteAsync = { stmt: Statement<*> ->
            if (stmt !is BatchStatement) {
                CompletableFuture.failedFuture(IllegalStateException("simulated eventual write failure"))
            } else {
                CompletableFuture.completedFuture(FakeEventualAsyncResultSet.empty())
            }
        }
        val listener = RecordingEventListener()
        val engine = BatchEngine(session, StatementBuilder(session), newScope(), eventListener = listener)

        engine.updateForceSuspend(schema, UfeAccount(UUID.randomUUID(), "a@example.com", "handle1"))

        awaitTrue { listener.failures.isNotEmpty() }
        assertEquals(1, listener.failures.size)
        val (tableName, _, error) = listener.failures.first()
        assertEquals("(updateForce)", tableName)
        assertTrue(error is IllegalStateException, "unexpected error type: $error")
    }

    // ── retry-on-transient-error now applies to updateForce's EVENTUAL write ────

    @Test
    fun `updateForce retries a transient EVENTUAL lookup write failure and succeeds`() {
        val schema = SchemaRegistry.register(UfeAccount::class)
        val session = FakeEventualCqlSession()
        val attempts = AtomicInteger(0)
        val succeeded = CountDownLatch(1)
        session.onExecute = { stmt: Statement<*> ->
            if (stmt !is BatchStatement) {
                if (attempts.incrementAndGet() == 1) throw NoNodeAvailableException()
                succeeded.countDown()
            }
            FakeEventualResultSet.empty()
        }
        val listener = RecordingEventListener()
        val engine = BatchEngine(session, StatementBuilder(session), newScope(), eventListener = listener, retryConfig = fastRetryConfig())

        engine.updateForce(schema, UfeAccount(UUID.randomUUID(), "a@example.com", "handle1"))

        assertTrue(succeeded.await(2, TimeUnit.SECONDS), "eventual write should have retried and succeeded")
        assertEquals(2, attempts.get(), "expected exactly one retry (2 attempts total)")
        assertTrue(listener.failures.isEmpty(), "no failure should be reported once the retry succeeds")
    }

    @Test
    fun `updateForceSuspend retries a transient EVENTUAL lookup write failure and succeeds`() = runBlocking {
        val schema = SchemaRegistry.register(UfeAccount::class)
        val session = FakeEventualCqlSession()
        val attempts = AtomicInteger(0)
        val succeeded = CountDownLatch(1)
        session.onExecuteAsync = { stmt: Statement<*> ->
            if (stmt !is BatchStatement) {
                if (attempts.incrementAndGet() == 1) {
                    CompletableFuture.failedFuture(NoNodeAvailableException())
                } else {
                    succeeded.countDown()
                    CompletableFuture.completedFuture(FakeEventualAsyncResultSet.empty())
                }
            } else {
                CompletableFuture.completedFuture(FakeEventualAsyncResultSet.empty())
            }
        }
        val listener = RecordingEventListener()
        val engine = BatchEngine(session, StatementBuilder(session), newScope(), eventListener = listener, retryConfig = fastRetryConfig())

        engine.updateForceSuspend(schema, UfeAccount(UUID.randomUUID(), "a@example.com", "handle1"))

        assertTrue(succeeded.await(2, TimeUnit.SECONDS), "eventual write should have retried and succeeded")
        assertEquals(2, attempts.get(), "expected exactly one retry (2 attempts total)")
        assertTrue(listener.failures.isEmpty(), "no failure should be reported once the retry succeeds")
    }

    // Note: updateForce's/updateSuspend's EVENTUAL writes now route through the same
    // fireEventualStatements/fireEventualStatementsSuspend helpers save/update already use, so their
    // shutdown-gate behavior is already covered by BatchEngineEventualWriteTest's
    // "fireEventualStatements (update path) is rejected once isShuttingDown is set" tests -- not
    // repeated here (testing it via updateForce directly is confounded by updateForce's own primary
    // batch being gated first, before the EVENTUAL branch is even reached).

    // ── updateSuspend's own EVENTUAL branch (found alongside item 4) ────────────

    @Test
    fun `updateSuspend forwards a failed EVENTUAL lookup write to the event listener`() = runBlocking {
        val schema = SchemaRegistry.register(UfeAccount::class)
        val session = FakeEventualCqlSession()
        session.onExecuteAsync = { stmt: Statement<*> ->
            if (stmt !is BatchStatement) {
                CompletableFuture.failedFuture(IllegalStateException("simulated eventual write failure"))
            } else {
                CompletableFuture.completedFuture(FakeEventualAsyncResultSet.empty())
            }
        }
        val listener = RecordingEventListener()
        val engine = BatchEngine(session, StatementBuilder(session), newScope(), eventListener = listener)

        val old = UfeAccount(UUID.randomUUID(), "a@example.com", "handle1")
        val new = old.copy() // identical -> exactly one EVENTUAL (re-insert) statement, no delete

        engine.updateSuspend(schema, old, new)

        awaitTrue { listener.failures.isNotEmpty() }
        assertEquals(1, listener.failures.size)
        val (tableName, _, error) = listener.failures.first()
        assertEquals("(update)", tableName)
        assertTrue(error is IllegalStateException, "unexpected error type: $error")
    }

    @Test
    fun `updateSuspend retries a transient EVENTUAL lookup write failure and succeeds`() = runBlocking {
        val schema = SchemaRegistry.register(UfeAccount::class)
        val session = FakeEventualCqlSession()
        val attempts = AtomicInteger(0)
        val succeeded = CountDownLatch(1)
        session.onExecuteAsync = { stmt: Statement<*> ->
            if (stmt !is BatchStatement) {
                if (attempts.incrementAndGet() == 1) {
                    CompletableFuture.failedFuture(NoNodeAvailableException())
                } else {
                    succeeded.countDown()
                    CompletableFuture.completedFuture(FakeEventualAsyncResultSet.empty())
                }
            } else {
                CompletableFuture.completedFuture(FakeEventualAsyncResultSet.empty())
            }
        }
        val listener = RecordingEventListener()
        val engine = BatchEngine(session, StatementBuilder(session), newScope(), eventListener = listener, retryConfig = fastRetryConfig())

        val old = UfeAccount(UUID.randomUUID(), "a@example.com", "handle1")
        val new = old.copy()
        engine.updateSuspend(schema, old, new)

        assertTrue(succeeded.await(2, TimeUnit.SECONDS), "eventual write should have retried and succeeded")
        assertEquals(2, attempts.get(), "expected exactly one retry (2 attempts total)")
        assertTrue(listener.failures.isEmpty(), "no failure should be reported once the retry succeeds")
    }
}
