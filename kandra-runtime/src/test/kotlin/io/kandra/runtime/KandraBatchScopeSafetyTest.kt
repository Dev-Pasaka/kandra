package io.kandra.runtime

import com.datastax.oss.driver.api.core.NoNodeAvailableException
import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.exception.KandraQueryException
import io.kandra.runtime.codec.KandraCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

@ScyllaTable("bs_widgets")
data class BsWidget(
    @PartitionKey val id: UUID,
    val name: String
)

/**
 * Covers GH-29 item 3: [KandraBatchScope.execute] used to commit the caller-collected `LOGGED
 * BATCH` via a raw, blocking `session.execute(batch)` call -- with no shutdown gate and no retry --
 * even when invoked from [KandraRuntime.batch], which is declared `suspend` per its public
 * signature. The fix splits `execute()`/adds `executeSuspend()`, both now routed through
 * [BatchEngine.executeBatchScope]/[BatchEngine.executeBatchScopeSuspend] (i.e.
 * `executeWithRetry`/`executeWithRetrySuspend`), so a caller-controlled batch gets the same safety
 * net as every other write, and the suspend variant no longer blocks the calling coroutine's thread
 * on the final commit.
 */
@OptIn(ExperimentalKandraApi::class)
class KandraBatchScopeSafetyTest {

    @AfterEach
    fun tearDown() {
        SchemaRegistry.clear()
    }

    private fun unconfinedScope() = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private fun newRuntime(session: ControllableFakeSession, retryConfig: RetryConfig = RetryConfig()): KandraRuntime {
        val engine = BatchEngine(session, StatementBuilder(session), unconfinedScope(), retryConfig = retryConfig)
        return KandraRuntime(session, engine, KandraCodec.default)
    }

    // ── (c) existing callers of KandraRuntime.batch { } keep working ─────────

    @Test
    fun `batch (suspend) still commits collected statements as a single LOGGED BATCH`() = runBlocking {
        SchemaRegistry.register(BsWidget::class)
        val session = ControllableFakeSession()
        val runtime = newRuntime(session)
        val repo = runtime.suspendRepository<BsWidget>()

        runtime.batch {
            repo.saveInBatch(BsWidget(UUID.randomUUID(), "a"))
            repo.saveInBatch(BsWidget(UUID.randomUUID(), "b"))
        }

        val batches = session.executedBatches()
        assertEquals(1, batches.size, "expected exactly one LOGGED BATCH")
        assertEquals(2, batches.single().size(), "expected both saveInBatch statements in the same batch")
    }

    @Test
    fun `batchBlocking still commits collected statements as a single LOGGED BATCH`() {
        SchemaRegistry.register(BsWidget::class)
        val session = ControllableFakeSession()
        val runtime = newRuntime(session)
        val repo = runtime.repository<BsWidget>()

        runtime.batchBlocking {
            repo.saveInBatch(BsWidget(UUID.randomUUID(), "a"))
            repo.saveInBatch(BsWidget(UUID.randomUUID(), "b"))
        }

        val batches = session.executedBatches()
        assertEquals(1, batches.size, "expected exactly one LOGGED BATCH")
        assertEquals(2, batches.single().size(), "expected both saveInBatch statements in the same batch")
    }

    // ── retry-on-transient-error now applies to the final commit ─────────────

    @Test
    fun `batch (suspend) never retries a transient failure on the final commit -- the batch is not idempotent`() {
        SchemaRegistry.register(BsWidget::class)
        val session = ControllableFakeSession(failuresBeforeSuccess = Int.MAX_VALUE)
        val runtime = newRuntime(session, RetryConfig().apply { backoffMillis = 1; maxBackoffMillis = 2 })
        val repo = runtime.suspendRepository<BsWidget>()

        // A caller-controlled batch { } commit wraps a plain (non-idempotent) INSERT and is never
        // explicitly marked idempotent itself -- ISS-055 / GH #56 -- so it must fail on the first
        // retryable-class exception rather than blindly retrying.
        assertThrows(NoNodeAvailableException::class.java) {
            runBlocking { runtime.batch { repo.saveInBatch(BsWidget(UUID.randomUUID(), "a")) } }
        }

        assertEquals(1, session.executeCallCount)
    }

    @Test
    fun `batchBlocking never retries a transient failure on the final commit -- the batch is not idempotent`() {
        SchemaRegistry.register(BsWidget::class)
        val session = ControllableFakeSession(failuresBeforeSuccess = Int.MAX_VALUE)
        val runtime = newRuntime(session, RetryConfig().apply { backoffMillis = 1; maxBackoffMillis = 2 })
        val repo = runtime.repository<BsWidget>()

        assertThrows(NoNodeAvailableException::class.java) {
            runtime.batchBlocking { repo.saveInBatch(BsWidget(UUID.randomUUID(), "a")) }
        }

        assertEquals(1, session.executeCallCount)
    }

    // ── shutdown gate now applies to the final commit, not just at batch{} entry ──

    @Test
    fun `batch (suspend) commit is rejected if shutdown is signalled mid-block, before the final commit`() {
        SchemaRegistry.register(BsWidget::class)
        val session = ControllableFakeSession()
        val engine = BatchEngine(session, StatementBuilder(session), unconfinedScope())
        val runtime = KandraRuntime(session, engine, KandraCodec.default)
        val repo = runtime.suspendRepository<BsWidget>()

        val ex = assertThrows(KandraQueryException::class.java) {
            runBlocking {
                runtime.batch {
                    repo.saveInBatch(BsWidget(UUID.randomUUID(), "a"))
                    // Shutdown is signalled AFTER checkNotShuttingDown() already passed at batch{}'s
                    // entry, but BEFORE the collected statements are committed -- this is exactly the
                    // gap the old raw session.execute(batch) call never checked.
                    engine.isShuttingDown.set(true)
                }
            }
        }
        assertTrue(ex.message?.contains("shutting down") == true, "unexpected message: ${ex.message}")
        assertEquals(0, session.executeCallCount, "the LOGGED BATCH must never reach the session once shutdown was signalled before commit")
    }

    @Test
    fun `batchBlocking commit is rejected if shutdown is signalled mid-block, before the final commit`() {
        SchemaRegistry.register(BsWidget::class)
        val session = ControllableFakeSession()
        val engine = BatchEngine(session, StatementBuilder(session), unconfinedScope())
        val runtime = KandraRuntime(session, engine, KandraCodec.default)
        val repo = runtime.repository<BsWidget>()

        val ex = assertThrows(KandraQueryException::class.java) {
            runtime.batchBlocking {
                repo.saveInBatch(BsWidget(UUID.randomUUID(), "a"))
                engine.isShuttingDown.set(true)
            }
        }
        assertTrue(ex.message?.contains("shutting down") == true, "unexpected message: ${ex.message}")
        assertEquals(0, session.executeCallCount, "the LOGGED BATCH must never reach the session once shutdown was signalled before commit")
    }

    @Test
    fun `batch with no statements collected is a no-op and never touches the session`() = runBlocking {
        SchemaRegistry.register(BsWidget::class)
        val session = ControllableFakeSession()
        val runtime = newRuntime(session)

        runtime.batch { }

        assertEquals(0, session.executeCallCount)
    }

    // ── ISS-059 / GH #60: suspend batch { } collection must never block the dispatcher ───────

    /**
     * [KandraBatchScope.saveInBatch]/`deleteInBatch` on [io.kandra.runtime.repository.KandraSuspendRepository]
     * used to collect statements via [BatchEngine.collectSave] (the blocking-prepare path) even when called
     * from inside [KandraRuntime.batch]'s suspend block -- only the final commit was suspend-safe. This uses
     * [PrepareCallTrackingSession], whose blocking `prepare()` throws an [AssertionError], to prove that on
     * a cold cache (first call for this CQL string, guaranteed here since each test gets a fresh session and
     * schema), the suspend `batch { }` path collects statements purely through `prepareAsync`
     * ([StatementBuilder.insertPrimarySuspend] / [BatchEngine.collectSaveSuspend]) -- never falling back to
     * the blocking `prepare()` that would otherwise starve the calling coroutine's dispatcher thread.
     */
    @Test
    fun `suspend batch collects saveInBatch statements without ever calling blocking prepare`() = runBlocking {
        SchemaRegistry.register(BsWidget::class)
        val session = PrepareCallTrackingSession()
        val engine = BatchEngine(session, StatementBuilder(session), unconfinedScope())
        val runtime = KandraRuntime(session, engine, KandraCodec.default)
        val repo = runtime.suspendRepository<BsWidget>()

        // Does not throw -- if collection fell back to the blocking prepare(), PrepareCallTrackingSession
        // would raise an AssertionError before this line completes.
        runtime.batch {
            repo.saveInBatch(BsWidget(UUID.randomUUID(), "a"))
            repo.saveInBatch(BsWidget(UUID.randomUUID(), "b"))
        }

        assertEquals(0, session.blockingPrepareCount.get(), "suspend batch { } must never call the blocking CqlSession.prepare()")
        assertTrue(session.asyncPrepareCount.get() > 0, "expected at least one prepareAsync call for the collected INSERTs")
    }

    /**
     * Same proof for `deleteInBatch` on the suspend repository -- [BatchEngine.collectDeleteSuspend]
     * must use [StatementBuilder.deleteByIdSuspend], never the blocking [StatementBuilder.deleteById].
     */
    @Test
    fun `suspend batch collects deleteInBatch statements without ever calling blocking prepare`() = runBlocking {
        SchemaRegistry.register(BsWidget::class)
        val session = PrepareCallTrackingSession()
        val engine = BatchEngine(session, StatementBuilder(session), unconfinedScope())
        val runtime = KandraRuntime(session, engine, KandraCodec.default)
        val repo = runtime.suspendRepository<BsWidget>()

        runtime.batch {
            repo.deleteInBatch(BsWidget(UUID.randomUUID(), "a"))
        }

        assertEquals(0, session.blockingPrepareCount.get(), "suspend batch { } must never call the blocking CqlSession.prepare()")
        assertTrue(session.asyncPrepareCount.get() > 0, "expected at least one prepareAsync call for the collected DELETE")
    }

    /**
     * Control case: `batchBlocking { }` is explicitly the blocking entry point (see class doc on
     * [KandraBatchScope]) -- it's expected to keep using the blocking prepare path via
     * [BatchEngine.collectSave], not [BatchEngine.collectSaveSuspend]. `PrepareCallTrackingSession`'s
     * blocking `prepare()` always throws, so this only asserts the failure happens where expected
     * (during collection, backed by a real [AssertionError] from the fake, not a driver call).
     */
    @Test
    fun `batchBlocking still uses the blocking prepare path (control case)`() {
        SchemaRegistry.register(BsWidget::class)
        val session = PrepareCallTrackingSession()
        val engine = BatchEngine(session, StatementBuilder(session), unconfinedScope())
        val runtime = KandraRuntime(session, engine, KandraCodec.default)
        val repo = runtime.repository<BsWidget>()

        val ex = assertThrows(AssertionError::class.java) {
            runtime.batchBlocking {
                repo.saveInBatch(BsWidget(UUID.randomUUID(), "a"))
            }
        }
        assertTrue(ex.message?.contains("Blocking CqlSession.prepare") == true, "unexpected message: ${ex.message}")
    }
}
