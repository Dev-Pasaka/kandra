package io.kandra.runtime

import com.datastax.oss.driver.api.core.cql.BatchStatement
import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.LookupConsistency
import io.kandra.core.annotations.LookupIndex
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.exception.KandraQueryException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

@ScyllaTable("be_widgets")
data class BeWidget(
    @PartitionKey val id: UUID,
    val name: String
)

@ScyllaTable("be_accounts")
data class BeAccount(
    @PartitionKey val id: UUID,
    @LookupIndex(tableSuffix = "by_email", consistency = LookupConsistency.BATCH) val email: String,
    @LookupIndex(tableSuffix = "by_handle", consistency = LookupConsistency.EVENTUAL) val handle: String
)

/**
 * Focused unit tests for [BatchEngine], previously only exercised indirectly through
 * `kandra-test`'s Testcontainers-based `KandraIntegrationTest`. Uses [ControllableFakeSession]
 * (see FakeDriverSupport.kt) rather than `kandra-test`'s `FakeKandraSession` — that one always
 * succeeds (`wasApplied()` hardcoded `true`, no way to inject a failure), which makes it useless
 * for driving the retry/backoff branches below.
 *
 * Deliberately NOT covered here (see docs/issues/ISS-020): genuine LWT `[applied]` semantics for
 * `saveIfNotExists`/versioned `update` — those need a real cluster and are covered by
 * `kandra-test`'s `KandraIntegrationTest`.
 */
class BatchEngineTest {

    @AfterEach
    fun tearDown() {
        SchemaRegistry.clear()
    }

    /** Unconfined + no real dispatch means `scope.launch { ... }` for EVENTUAL writes runs
     *  synchronously (to completion, since nothing in that block suspends) before the launching
     *  call returns — makes EVENTUAL-write assertions deterministic instead of racy. */
    private fun unconfinedScope() = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    // ── Retry / backoff (executeWithRetry) ───────────────────────────────────

    @Test
    fun `retries a retryable failure and succeeds within maxAttempts`() {
        val schema = SchemaRegistry.register(BeWidget::class)
        val session = ControllableFakeSession(failuresBeforeSuccess = 2)
        val engine = BatchEngine(
            session, StatementBuilder(session), unconfinedScope(),
            retryConfig = RetryConfig().apply { backoffMillis = 1; maxBackoffMillis = 2 }
        )

        engine.save(schema, BeWidget(UUID.randomUUID(), "widget-1"))

        assertEquals(3, session.executeCallCount) // 2 failed attempts + 1 success, same statement re-executed
    }

    @Test
    fun `gives up after maxAttempts and wraps the last error`() {
        val schema = SchemaRegistry.register(BeWidget::class)
        val session = ControllableFakeSession(failuresBeforeSuccess = Int.MAX_VALUE)
        val engine = BatchEngine(
            session, StatementBuilder(session), unconfinedScope(),
            retryConfig = RetryConfig().apply { maxAttempts = 3; backoffMillis = 1; maxBackoffMillis = 2 }
        )

        val ex = assertThrows(KandraQueryException::class.java) {
            engine.save(schema, BeWidget(UUID.randomUUID(), "widget-1"))
        }

        assertTrue(ex.message!!.contains("failed after 3 attempts"), "unexpected message: ${ex.message}")
        assertEquals(3, session.executeCallCount)
    }

    @Test
    fun `does not retry a non-retryable exception`() {
        val schema = SchemaRegistry.register(BeWidget::class)
        val session = ControllableFakeSession(failuresBeforeSuccess = 1, exceptionFactory = { IllegalStateException("boom") })
        val engine = BatchEngine(session, StatementBuilder(session), unconfinedScope())

        assertThrows(IllegalStateException::class.java) {
            engine.save(schema, BeWidget(UUID.randomUUID(), "widget-1"))
        }

        assertEquals(1, session.executeCallCount) // rethrown immediately, no retry loop entered
    }

    @Test
    fun `rejects new queries once shutting down, without touching the session`() {
        val schema = SchemaRegistry.register(BeWidget::class)
        val session = ControllableFakeSession()
        val engine = BatchEngine(session, StatementBuilder(session), unconfinedScope())
        engine.isShuttingDown.set(true)

        assertThrows(KandraQueryException::class.java) {
            engine.save(schema, BeWidget(UUID.randomUUID(), "widget-1"))
        }

        assertEquals(0, session.executeCallCount)
    }

    // ── BATCH vs EVENTUAL lookup-consistency split ───────────────────────────

    @Test
    fun `save batches BATCH-consistency lookups but fires EVENTUAL ones separately`() {
        val schema = SchemaRegistry.register(BeAccount::class)
        val session = ControllableFakeSession()
        val engine = BatchEngine(session, StatementBuilder(session), unconfinedScope())

        engine.save(schema, BeAccount(UUID.randomUUID(), "a@example.com", "handle1"))

        val batches = session.executedBatches()
        assertEquals(1, batches.size, "expected exactly one LOGGED BATCH (primary + BATCH lookup)")
        assertEquals(2, batches.single().size(), "expected primary insert + the BATCH-consistency email lookup only")

        // The EVENTUAL (handle) lookup insert must NOT be inside the LOGGED BATCH — it fires
        // separately, after the batch commits.
        val nonBatchStatements = session.executedStatements().filterNot { it is BatchStatement }
        assertEquals(1, nonBatchStatements.size, "expected exactly one non-batched EVENTUAL lookup insert")
    }

    @Test
    fun `saveAll separates BATCH lookups (in-batch) from EVENTUAL lookups (fired after commit)`() {
        val schema = SchemaRegistry.register(BeAccount::class)
        val session = ControllableFakeSession()
        val engine = BatchEngine(session, StatementBuilder(session), unconfinedScope())

        val entities = listOf(
            BeAccount(UUID.randomUUID(), "a@example.com", "handle-a"),
            BeAccount(UUID.randomUUID(), "b@example.com", "handle-b")
        )
        engine.saveAll(schema, entities)

        val batches = session.executedBatches()
        assertEquals(1, batches.size)
        // 2 primary inserts + 2 BATCH-consistency (email) lookup inserts = 4
        assertEquals(4, batches.single().size())

        // 2 EVENTUAL (handle) lookup inserts fire outside the batch
        val nonBatchStatements = session.executedStatements().filterNot { it is BatchStatement }
        assertEquals(2, nonBatchStatements.size)
    }

    // ── Batch auto-chunking around batchMaxChunkSize ─────────────────────────

    @Test
    fun `saveAll splits into multiple LOGGED BATCH statements once over batchMaxChunkSize`() {
        val schema = SchemaRegistry.register(BeWidget::class)
        val session = ControllableFakeSession()
        val engine = BatchEngine(session, StatementBuilder(session), unconfinedScope())
        engine.configureBatchLimits(warnThresholdKb = 999_999, maxChunkSize = 10, autoChunk = true)

        val entities = (1..25).map { BeWidget(UUID.randomUUID(), "widget-$it") }
        engine.saveAll(schema, entities)

        val batches = session.executedBatches()
        assertEquals(3, batches.size, "25 statements chunked at 10 should yield 3 LOGGED BATCH executions (10+10+5)")
        assertEquals(25, batches.sumOf { it.size() })
        assertTrue(batches.all { it.size() <= 10 }, "no chunk should exceed batchMaxChunkSize")
    }

    @Test
    fun `saveAll does not chunk when batchAutoChunk is disabled, even over the limit`() {
        val schema = SchemaRegistry.register(BeWidget::class)
        val session = ControllableFakeSession()
        val engine = BatchEngine(session, StatementBuilder(session), unconfinedScope())
        engine.configureBatchLimits(warnThresholdKb = 999_999, maxChunkSize = 10, autoChunk = false)

        val entities = (1..25).map { BeWidget(UUID.randomUUID(), "widget-$it") }
        engine.saveAll(schema, entities)

        val batches = session.executedBatches()
        assertEquals(1, batches.size, "chunking disabled — a single LOGGED BATCH regardless of size")
        assertEquals(25, batches.single().size())
    }

    @Test
    fun `saveAll stays a single batch exactly at batchMaxChunkSize`() {
        val schema = SchemaRegistry.register(BeWidget::class)
        val session = ControllableFakeSession()
        val engine = BatchEngine(session, StatementBuilder(session), unconfinedScope())
        engine.configureBatchLimits(warnThresholdKb = 999_999, maxChunkSize = 10, autoChunk = true)

        val entities = (1..10).map { BeWidget(UUID.randomUUID(), "widget-$it") }
        engine.saveAll(schema, entities)

        val batches = session.executedBatches()
        assertEquals(1, batches.size, "count == batchMaxChunkSize should NOT trigger chunking (only exceeding it does)")
        assertEquals(10, batches.single().size())
    }

    @Test
    fun `saveAll no-ops on an empty entity list`() {
        val schema = SchemaRegistry.register(BeWidget::class)
        val session = ControllableFakeSession()
        val engine = BatchEngine(session, StatementBuilder(session), unconfinedScope())

        engine.saveAll(schema, emptyList())

        assertEquals(0, session.executeCallCount)
    }
}
