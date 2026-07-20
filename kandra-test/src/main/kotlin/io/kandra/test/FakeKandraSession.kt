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
 * Captures [BatchStatement] executions so tests can assert batch behaviour.
 * Use via [KandraTestUtils.inMemory] to wire a full repository stack without Testcontainers.
 *
 * Note: Because the DataStax driver's `BoundStatement` is final, the fake session
 * does not simulate actual query routing. Use it to verify structural behaviour
 * (batch composition, save/delete ordering) rather than data round-trips.
 */
class FakeKandraSession : CqlSession {

    private val capturedBatchStatements = mutableListOf<BatchStatement>()

    /** Returns all [BatchStatement] instances that were executed via [execute]. */
    fun capturedBatches(): List<BatchStatement> = capturedBatchStatements.toList()

    /** Clears all captured batches. */
    fun reset() = capturedBatchStatements.clear()

    /** Returns an empty in-memory table contents list (structural test placeholder). */
    fun tableContents(tableName: String): List<Map<String, Any?>> = emptyList()

    @Suppress("UNCHECKED_CAST")
    override fun <RequestT : com.datastax.oss.driver.api.core.session.Request,
            ResultT : Any> execute(request: RequestT, resultType: GenericType<ResultT>): ResultT? {
        if (request is Statement<*>) execute(request)
        return null
    }

    override fun execute(statement: Statement<*>): ResultSet {
        if (statement is BatchStatement) capturedBatchStatements.add(statement)
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
