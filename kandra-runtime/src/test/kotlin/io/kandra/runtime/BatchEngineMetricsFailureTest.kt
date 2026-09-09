package io.kandra.runtime

import io.kandra.core.KandraMetrics
import io.kandra.core.SchemaRegistry
import io.kandra.core.exception.KandraQueryException
import io.kandra.runtime.codec.KandraCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Records every [record]/[recordFailure] call for assertions — see ISS-074 / GH #82. */
private class RecordingMetrics : KandraMetrics {
    data class Success(val tableName: String, val operation: String, val durationMs: Long, val attempts: Int)
    data class Failure(val tableName: String, val operation: String, val durationMs: Long, val attempts: Int, val exceptionType: String)

    val successes = CopyOnWriteArrayList<Success>()
    val failures = CopyOnWriteArrayList<Failure>()

    override fun record(tableName: String, operation: String, durationMs: Long) {
        // Should never be reached directly by BatchEngine now that it always calls the 4-arg overload,
        // but implement it anyway since it's the interface's sole abstract member.
        successes.add(Success(tableName, operation, durationMs, attempts = -1))
    }

    override fun record(tableName: String, operation: String, durationMs: Long, attempts: Int) {
        successes.add(Success(tableName, operation, durationMs, attempts))
    }

    override fun recordFailure(tableName: String, operation: String, durationMs: Long, attempts: Int, exceptionType: String) {
        failures.add(Failure(tableName, operation, durationMs, attempts, exceptionType))
    }
}

/**
 * Coverage for ISS-074 / GH #82 — [KandraMetrics.recordFailure] used to not exist at all, so retry
 * exhaustion, an immediate non-retryable exception, and a shutdown-rejection all recorded *nothing*,
 * leaving every failure invisible to metrics/alerting even though the success path recorded on every
 * single successful call.
 */
class BatchEngineMetricsFailureTest {

    @AfterEach
    fun tearDown() {
        SchemaRegistry.clear()
    }

    private fun unconfinedScope() = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private fun newEngine(
        session: ControllableFakeSession,
        retryConfig: RetryConfig = RetryConfig().apply { backoffMillis = 1; maxBackoffMillis = 2 }
    ): BatchEngine =
        BatchEngine(session, StatementBuilder(session), unconfinedScope(), retryConfig = retryConfig)

    @Test
    fun `recordFailure fires on retry exhaustion with the exhausted attempt count and exception type`() {
        SchemaRegistry.register(BsWidget::class)
        // deleteById's statement IS idempotent, and NoNodeAvailableException IS in the default
        // retryOn set -- so this must retry up to maxAttempts, then give up.
        val session = ControllableFakeSession(failuresBeforeSuccess = Int.MAX_VALUE)
        val engine = newEngine(session, RetryConfig().apply { maxAttempts = 3; backoffMillis = 1; maxBackoffMillis = 2 })
        val metrics = RecordingMetrics()
        engine.setMetrics(metrics)
        val schema = SchemaRegistry.get(BsWidget::class)

        assertThrows(KandraQueryException::class.java) {
            engine.deleteById(schema, UUID.randomUUID())
        }

        assertEquals(0, metrics.successes.size, "must not record success on a call that ultimately failed")
        assertEquals(1, metrics.failures.size)
        val failure = metrics.failures.single()
        assertEquals(schema.tableName, failure.tableName)
        assertEquals("deleteById", failure.operation)
        assertEquals(3, failure.attempts, "expected the failure to report all 3 exhausted attempts")
        assertTrue(
            failure.exceptionType.contains("NoNodeAvailableException"),
            "expected exceptionType to name the underlying exception, got: ${failure.exceptionType}"
        )
    }

    @Test
    fun `recordFailure fires immediately on a non-retryable exception type, without retrying`() {
        SchemaRegistry.register(BsWidget::class)
        // deleteById's statement is idempotent, but IllegalStateException is not in the default
        // retryOn set -- so this must fail on the very first attempt, no retry.
        val session = ControllableFakeSession(failuresBeforeSuccess = Int.MAX_VALUE, exceptionFactory = { IllegalStateException("boom") })
        val engine = newEngine(session)
        val metrics = RecordingMetrics()
        engine.setMetrics(metrics)
        val schema = SchemaRegistry.get(BsWidget::class)

        assertThrows(IllegalStateException::class.java) {
            engine.deleteById(schema, UUID.randomUUID())
        }

        assertEquals(0, metrics.successes.size)
        assertEquals(1, metrics.failures.size)
        val failure = metrics.failures.single()
        assertEquals(schema.tableName, failure.tableName)
        assertEquals("deleteById", failure.operation)
        assertEquals(1, failure.attempts, "expected exactly 1 attempt for an immediate non-retryable failure")
        assertTrue(
            failure.exceptionType.contains("IllegalStateException"),
            "expected exceptionType to name the underlying exception, got: ${failure.exceptionType}"
        )
        assertEquals(1, session.executeCallCount, "must not have retried")
    }

    @Test
    fun `recordFailure fires immediately on a non-idempotent statement, without retrying`() {
        SchemaRegistry.register(BsWidget::class)
        // save() builds a LOGGED BATCH around a plain (non-idempotent) INSERT -- even though
        // NoNodeAvailableException IS in the default retryOn set, the statement's non-idempotency
        // must still short-circuit any retry.
        val session = ControllableFakeSession(failuresBeforeSuccess = Int.MAX_VALUE)
        val engine = newEngine(session)
        val metrics = RecordingMetrics()
        engine.setMetrics(metrics)
        val schema = SchemaRegistry.get(BsWidget::class)

        assertThrows(com.datastax.oss.driver.api.core.NoNodeAvailableException::class.java) {
            engine.save(schema, BsWidget(UUID.randomUUID(), "a"))
        }

        assertEquals(0, metrics.successes.size)
        assertEquals(1, metrics.failures.size)
        val failure = metrics.failures.single()
        assertEquals(1, failure.attempts, "expected exactly 1 attempt -- a non-idempotent statement is never retried")
        assertTrue(failure.exceptionType.contains("NoNodeAvailableException"), "got: ${failure.exceptionType}")
    }

    @Test
    fun `recordFailure fires on a shutdown-rejection with zero attempts and zero duration`() {
        SchemaRegistry.register(BsWidget::class)
        val session = ControllableFakeSession()
        val engine = newEngine(session)
        engine.isShuttingDown.set(true)
        val metrics = RecordingMetrics()
        engine.setMetrics(metrics)
        val schema = SchemaRegistry.get(BsWidget::class)

        val ex = assertThrows(KandraQueryException::class.java) {
            engine.deleteById(schema, UUID.randomUUID())
        }
        assertTrue(ex.message?.contains("shutting down") == true, "unexpected message: ${ex.message}")

        assertEquals(0, metrics.successes.size)
        assertEquals(1, metrics.failures.size)
        val failure = metrics.failures.single()
        assertEquals(schema.tableName, failure.tableName)
        assertEquals("deleteById", failure.operation)
        assertEquals(0, failure.attempts, "a shutdown-rejection never makes any attempt")
        assertEquals(0L, failure.durationMs, "a shutdown-rejection fails before any timing starts")
        assertEquals(0, session.executeCallCount, "the query must never reach the session once shutdown was signalled")
    }

    @Test
    fun `record still fires with the successful attempt count when a query succeeds after retries`() {
        SchemaRegistry.register(BsWidget::class)
        // Fails twice (NoNodeAvailableException, retryable + deleteById is idempotent), then succeeds
        // on the 3rd attempt.
        val session = ControllableFakeSession(failuresBeforeSuccess = 2)
        val engine = newEngine(session)
        val metrics = RecordingMetrics()
        engine.setMetrics(metrics)
        val schema = SchemaRegistry.get(BsWidget::class)

        engine.deleteById(schema, UUID.randomUUID())

        assertEquals(0, metrics.failures.size, "must not record a failure once the query ultimately succeeded")
        assertEquals(1, metrics.successes.size)
        assertEquals(3, metrics.successes.single().attempts, "expected the success to report all 3 attempts it took")
    }
}
