package io.kandra.test

import com.datastax.oss.driver.api.core.CqlIdentifier
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.context.DriverContext
import com.datastax.oss.driver.api.core.cql.AsyncResultSet
import com.datastax.oss.driver.api.core.cql.BatchStatement
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import com.datastax.oss.driver.api.core.cql.ResultSet
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import com.datastax.oss.driver.api.core.cql.Statement
import com.datastax.oss.driver.api.core.metadata.Metadata
import com.datastax.oss.driver.api.core.metrics.Metrics
import com.datastax.oss.driver.api.core.type.reflect.GenericType
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory drop-in replacement for [CqlSession] — no ScyllaDB connection required.
 *
 * `prepare(cql).bind(...)` returns a [FakePreparedStatement]/[FakeBoundStatement] pair that
 * records the CQL string and positional bound values without touching a real driver session, so
 * the full `KandraRepository`/`KandraSuspendRepository` surface (`save`, `findById`, `delete`,
 * `update`, batch writes, ...) can run against this fake end-to-end. [execute] captures every
 * [Statement] it's handed (see [capturedStatements]), with [capturedBatches] as a convenience
 * filter for just the [BatchStatement]s (`save`/`delete`/`update`/`saveAll` all route through a
 * `LOGGED` batch). Use via [KandraTestUtils.inMemory] to wire a full repository stack without
 * Testcontainers.
 *
 * Note: this performs no real CQL type encoding/decoding or query semantics — `execute()` always
 * returns an empty, `wasApplied() == true` result set, regardless of the statement. Use this fake
 * to verify structural behaviour (batch composition, save/delete ordering, what was bound to what)
 * rather than actual data round-trips or LWT applied/not-applied semantics; for those, use
 * [KandraTestcontainers] instead.
 */
class FakeKandraSession : CqlSession {

    private val capturedStatementsList = mutableListOf<Statement<*>>()

    /** Returns every [Statement] executed synchronously via [execute], in call order. */
    fun capturedStatements(): List<Statement<*>> = capturedStatementsList.toList()

    /** Returns all [BatchStatement] instances that were executed via [execute]. */
    fun capturedBatches(): List<BatchStatement> = capturedStatementsList.filterIsInstance<BatchStatement>()

    /**
     * Public read view of every statement executed synchronously via [execute], in call order, with its
     * CQL text and positionally bound values. A `BatchStatement` is flattened into its child statements
     * (in batch order); use [capturedBatchViews] to keep batch boundaries.
     *
     * In [CapturedStatement.values], a value that was bound as CQL `null` (e.g. `saveWithNulls`, which
     * writes a tombstone) reads back as Kotlin `null`, while a position that was never bound or was
     * explicitly unset (a plain `save()` of a `null` field, which writes no tombstone) reads back as
     * [FakeUnset] — those are different CQL writes, see [CapturedStatement.isUnset].
     */
    fun capturedStatementViews(): List<CapturedStatement> =
        capturedStatementsList.flatMap { describe(it) }

    /** One entry per captured [BatchStatement], each holding that batch's child statements in order. */
    fun capturedBatchViews(): List<List<CapturedStatement>> =
        capturedBatches().map { describe(it) }

    private fun describe(statement: Statement<*>): List<CapturedStatement> = when (statement) {
        is BatchStatement -> statement.flatMap { describe(it) }
        is FakeBoundStatement -> listOf(CapturedStatement(statement.query, statement.boundValues()))
        is SimpleStatement -> listOf(CapturedStatement(statement.query, statement.positionalValues))
        else -> listOf(CapturedStatement(statement.toString(), emptyList()))
    }

    /** Clears all captured statements/batches. */
    fun reset() = capturedStatementsList.clear()

    /** Returns an empty in-memory table contents list (structural test placeholder). */
    fun tableContents(tableName: String): List<Map<String, Any?>> = emptyList()

    @Suppress("UNCHECKED_CAST")
    override fun <RequestT : com.datastax.oss.driver.api.core.session.Request,
            ResultT : Any> execute(request: RequestT, resultType: GenericType<ResultT>): ResultT? {
        if (request is Statement<*>) execute(request)
        return null
    }

    override fun execute(statement: Statement<*>): ResultSet {
        capturedStatementsList.add(statement)
        return FakeResultSet.empty()
    }

    override fun prepare(query: String): PreparedStatement = FakePreparedStatement(query)

    override fun prepare(statement: SimpleStatement): PreparedStatement =
        FakePreparedStatement(statement.query)

    override fun executeAsync(statement: Statement<*>): CompletionStage<AsyncResultSet> =
        CompletableFuture.completedFuture(FakeAsyncResultSet())

    override fun getName(): String = "FakeKandraSession"

    override fun getMetadata(): Metadata = throw UnsupportedOperationException("FakeKandraSession does not support getMetadata()")

    override fun isSchemaMetadataEnabled(): Boolean = false

    override fun setSchemaMetadataEnabled(newValue: Boolean?): CompletionStage<Metadata> =
        CompletableFuture.failedFuture(UnsupportedOperationException())

    override fun refreshSchemaAsync(): CompletionStage<Metadata> =
        CompletableFuture.failedFuture(UnsupportedOperationException())

    override fun checkSchemaAgreementAsync(): CompletionStage<Boolean> =
        CompletableFuture.completedFuture(true)

    override fun getContext(): DriverContext = throw UnsupportedOperationException("FakeKandraSession does not support getContext()")

    override fun getKeyspace(): Optional<CqlIdentifier> = Optional.empty()

    override fun getMetrics(): Optional<Metrics> = Optional.empty()

    override fun closeFuture(): CompletionStage<Void> = CompletableFuture.completedFuture(null)

    override fun closeAsync(): CompletionStage<Void> = CompletableFuture.completedFuture(null)

    override fun forceCloseAsync(): CompletionStage<Void> = CompletableFuture.completedFuture(null)

    override fun isClosed(): Boolean = false
}

/**
 * A captured statement as seen by a test: the CQL text and the positional values bound to it.
 *
 * Returned by [FakeKandraSession.capturedStatementViews] / [FakeKandraSession.capturedBatchViews]. [values] holds
 * the bound value verbatim at each position (no CQL type encoding happens in the fake); [FakeUnset] marks a position
 * that was never bound or was explicitly unset, as opposed to Kotlin `null`, which is a real CQL `null` bind.
 */
data class CapturedStatement(val cql: String, val values: List<Any?>) {
    /** True if position [index] was never bound or was explicitly unset (no tombstone), as opposed to bound to `null`. */
    fun isUnset(index: Int): Boolean = values.getOrNull(index) === FakeUnset
}
