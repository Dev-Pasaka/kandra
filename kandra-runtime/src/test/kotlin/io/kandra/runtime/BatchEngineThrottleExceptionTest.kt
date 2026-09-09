package io.kandra.runtime

import com.datastax.oss.driver.api.core.RequestThrottlingException
import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.annotations.Version
import io.kandra.core.exception.KandraThrottledException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

@ScyllaTable("bte_widgets")
data class BteWidget(@PartitionKey val id: UUID, val name: String)

@ScyllaTable("bte_leases")
data class BteLease(@PartitionKey val id: UUID, val holder: String, @Version val version: Long = 1L)

/**
 * GH #103 / ISS-090: a backpressure throttle rejection (`RequestThrottlingException`, thrown by the
 * driver when `throttle.enabled = true` and `maxQueueSize` is exceeded) previously wasn't in
 * `RetryConfig.retryOn` and wasn't wrapped into a `Kandra*Exception` -- it leaked as a raw,
 * undocumented DataStax driver type to any caller only catching Kandra's documented exception
 * hierarchy. It's now caught explicitly (ahead of the generic retryOn check, so it's never retried
 * regardless of configuration) and wrapped into `KandraThrottledException` at every
 * `executeWithRetry`/`executeWithRetrySuspend`/`executeOnce`/`executeOnceSuspend` call site.
 */
class BatchEngineThrottleExceptionTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterEach
    fun tearDown() {
        scope.cancel()
        SchemaRegistry.clear()
    }

    private fun newEngine(session: ScriptedCqlSession): BatchEngine =
        BatchEngine(session, StatementBuilder(session), scope)

    private fun throttled(message: String = "queue full"): RequestThrottlingException =
        RequestThrottlingException(message)

    @Test
    fun `RequestThrottlingException is not in the default retryOn set`() {
        assertFalse(RetryConfig().retryOn.any { it == RequestThrottlingException::class })
    }

    // ── executeWithRetry (blocking) ────────────────────────────────────────

    @Test
    fun `save() wraps a throttle rejection into KandraThrottledException instead of leaking it`() {
        val schema = SchemaRegistry.register(BteWidget::class)
        val error = throttled()
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Throw(error)))
        val engine = newEngine(session)

        val thrown = assertThrows(KandraThrottledException::class.java) {
            engine.save(schema, BteWidget(UUID.randomUUID(), "a"))
        }

        assertSame(error, thrown.cause, "the raw driver exception must be preserved as the cause")
        assertEquals(1, session.executeCount.get(), "a throttle rejection must never be retried")
    }

    @Test
    fun `saveSuspend() wraps a throttle rejection into KandraThrottledException instead of leaking it`() = runBlocking {
        val schema = SchemaRegistry.register(BteWidget::class)
        val error = throttled()
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Throw(error)))
        val engine = newEngine(session)

        val thrown = assertThrows(KandraThrottledException::class.java) {
            runBlocking { engine.saveSuspend(schema, BteWidget(UUID.randomUUID(), "a")) }
        }

        assertSame(error, thrown.cause)
        assertEquals(1, session.executeCount.get(), "a throttle rejection must never be retried")
    }

    // ── executeOnce (versioned update LWT) ─────────────────────────────────

    @Test
    fun `versioned update() wraps a throttle rejection into KandraThrottledException`() {
        val schema = SchemaRegistry.register(BteLease::class)
        val error = throttled()
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Throw(error)))
        val engine = newEngine(session)
        val old = BteLease(UUID.randomUUID(), "a", version = 1L)

        val thrown = assertThrows(KandraThrottledException::class.java) {
            engine.update(schema, old, old.copy(holder = "b"))
        }

        assertSame(error, thrown.cause)
        assertEquals(1, session.executeCount.get())
    }

    @Test
    fun `versioned updateSuspend() wraps a throttle rejection into KandraThrottledException`() = runBlocking {
        val schema = SchemaRegistry.register(BteLease::class)
        val error = throttled()
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Throw(error)))
        val engine = newEngine(session)
        val old = BteLease(UUID.randomUUID(), "a", version = 1L)

        val thrown = assertThrows(KandraThrottledException::class.java) {
            runBlocking { engine.updateSuspend(schema, old, old.copy(holder = "b")) }
        }

        assertSame(error, thrown.cause)
        assertEquals(1, session.executeCount.get())
    }
}
