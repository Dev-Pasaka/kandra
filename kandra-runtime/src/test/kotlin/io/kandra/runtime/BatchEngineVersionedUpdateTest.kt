package io.kandra.runtime

import com.datastax.oss.driver.api.core.DefaultConsistencyLevel
import com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException
import com.datastax.oss.driver.api.core.servererrors.WriteType
import io.kandra.core.InternalKandraApi
import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.annotations.Version
import io.kandra.core.exception.KandraOptimisticLockException
import io.kandra.core.exception.KandraQueryException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Reproduces GH-15: `@Version` LWT updates must not be blindly retried on transient errors,
 * because a retry cannot distinguish "my own prior attempt already applied server-side" from
 * "someone else changed the row" — see [BatchEngine.executeOnce] / [BatchEngine.executeOnceSuspend].
 */
@OptIn(InternalKandraApi::class)
class BatchEngineVersionedUpdateTest {

    @ScyllaTable("balances")
    data class Balance(
        @PartitionKey val accountId: UUID,
        val amountCents: Long,
        @Version val version: Long = 1L
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterEach
    fun tearDown() {
        scope.cancel()
        SchemaRegistry.clear()
    }

    private fun newEngine(session: ScriptedCqlSession): BatchEngine =
        BatchEngine(session, StatementBuilder(session), scope)

    private fun transientError(): WriteTimeoutException =
        WriteTimeoutException(fakeNode(), DefaultConsistencyLevel.LOCAL_QUORUM, 1, 0, WriteType.SIMPLE)

    // ── Blocking ──────────────────────────────────────────────────────────────

    @Test
    fun `blocking update surfaces a transient exception instead of retrying and throwing a false optimistic lock`() {
        val schema = SchemaRegistry.register(Balance::class)
        // Scripted exactly like the GH-15 scenario: the server actually applied the write and advanced
        // the version, but the client only observed a timeout. A blind retry of this exact statement
        // would then see [applied]=false (the version no longer matches) and wrongly conclude someone
        // else modified the row. The second scripted outcome only gets consumed if a retry happens.
        val session = ScriptedCqlSession(
            listOf(ExecuteOutcome.Throw(transientError()), ExecuteOutcome.Applied(false))
        )
        val engine = newEngine(session)

        val old = Balance(UUID.randomUUID(), 100L, version = 1L)
        val new = old.copy(amountCents = 200L)

        // Must surface the original WriteTimeoutException — not a KandraOptimisticLockException
        // manufactured by retrying into the second scripted outcome.
        assertThrows(WriteTimeoutException::class.java) {
            engine.update(schema, old, new)
        }
        assertEquals(1, session.executeCount.get(), "the LWT statement must be executed exactly once, never retried")
        assertEquals(0, engine.inFlightCount.get(), "inFlightCount must be decremented even when the call throws")
    }

    @Test
    fun `blocking update throws KandraOptimisticLockException on a genuine applied=false conflict`() {
        val schema = SchemaRegistry.register(Balance::class)
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Applied(false)))
        val engine = newEngine(session)

        val old = Balance(UUID.randomUUID(), 100L, version = 1L)
        val new = old.copy(amountCents = 200L)

        assertThrows(KandraOptimisticLockException::class.java) {
            engine.update(schema, old, new)
        }
        assertEquals(1, session.executeCount.get(), "a real conflict must not be retried either")
        assertEquals(0, engine.inFlightCount.get())
    }

    @Test
    fun `blocking update succeeds on a real applied=true result`() {
        val schema = SchemaRegistry.register(Balance::class)
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Applied(true)))
        val engine = newEngine(session)

        val old = Balance(UUID.randomUUID(), 100L, version = 1L)
        val new = old.copy(amountCents = 200L)

        engine.update(schema, old, new)
        assertEquals(1, session.executeCount.get())
    }

    @Test
    fun `blocking update still honors shutdown gating for the versioned path`() {
        val schema = SchemaRegistry.register(Balance::class)
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Applied(true)))
        val engine = newEngine(session)
        engine.isShuttingDown.set(true)

        val old = Balance(UUID.randomUUID(), 100L, version = 1L)
        val new = old.copy(amountCents = 200L)

        assertThrows(KandraQueryException::class.java) {
            engine.update(schema, old, new)
        }
        assertEquals(0, session.executeCount.get(), "shutdown must reject before the driver is ever called")
    }

    // ── Suspend ───────────────────────────────────────────────────────────────

    @Test
    fun `suspend update surfaces a transient exception instead of retrying and throwing a false optimistic lock`() = runBlocking {
        val schema = SchemaRegistry.register(Balance::class)
        val session = ScriptedCqlSession(
            listOf(ExecuteOutcome.Throw(transientError()), ExecuteOutcome.Applied(false))
        )
        val engine = newEngine(session)

        val old = Balance(UUID.randomUUID(), 100L, version = 1L)
        val new = old.copy(amountCents = 200L)

        assertThrows(WriteTimeoutException::class.java) {
            runBlocking { engine.updateSuspend(schema, old, new) }
        }
        assertEquals(1, session.executeCount.get(), "the LWT statement must be executed exactly once, never retried")
        assertEquals(0, engine.inFlightCount.get())
    }

    @Test
    fun `suspend update throws KandraOptimisticLockException on a genuine applied=false conflict`() = runBlocking {
        val schema = SchemaRegistry.register(Balance::class)
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Applied(false)))
        val engine = newEngine(session)

        val old = Balance(UUID.randomUUID(), 100L, version = 1L)
        val new = old.copy(amountCents = 200L)

        assertThrows(KandraOptimisticLockException::class.java) {
            runBlocking { engine.updateSuspend(schema, old, new) }
        }
        assertEquals(1, session.executeCount.get())
        assertEquals(0, engine.inFlightCount.get())
    }

    @Test
    fun `suspend update still honors shutdown gating for the versioned path`() = runBlocking {
        val schema = SchemaRegistry.register(Balance::class)
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Applied(true)))
        val engine = newEngine(session)
        engine.isShuttingDown.set(true)

        val old = Balance(UUID.randomUUID(), 100L, version = 1L)
        val new = old.copy(amountCents = 200L)

        assertThrows(KandraQueryException::class.java) {
            runBlocking { engine.updateSuspend(schema, old, new) }
        }
        assertFalse(session.executeCount.get() > 0, "shutdown must reject before the driver is ever called")
    }
}
