package io.kandra.runtime

import com.datastax.oss.driver.api.core.CqlIdentifier
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.context.DriverContext
import com.datastax.oss.driver.api.core.cql.AsyncResultSet
import com.datastax.oss.driver.api.core.cql.BoundStatement
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions
import com.datastax.oss.driver.api.core.cql.ExecutionInfo
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import com.datastax.oss.driver.api.core.cql.ResultSet
import com.datastax.oss.driver.api.core.cql.Row
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import com.datastax.oss.driver.api.core.cql.Statement
import com.datastax.oss.driver.api.core.metadata.Metadata
import com.datastax.oss.driver.api.core.metrics.Metrics
import com.datastax.oss.driver.api.core.type.reflect.GenericType
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Minimal, purpose-built in-memory [CqlSession] for exercising [BatchEngine]'s EVENTUAL-lookup
 * write path (`fireEventual`/`fireEventualSuspend`/`fireEventualStatements`) without a real cluster.
 *
 * Not shared with `kandra-test`'s `FakeKandraSession` — that fake deliberately does not support
 * `PreparedStatement.bind()` (its own doc comment says so), which these tests need in order to
 * produce a real [BoundStatement] for [StatementBuilder.insertLookup] to hand to [BatchEngine].
 * `kandra-runtime` also cannot depend on `kandra-test` (`kandra-test` depends on `kandra-runtime`),
 * so this fake lives here instead.
 *
 * [BoundStatement] is built via a [Proxy] since the interface has many fluent setters
 * (`setIdempotent`, `unset`, `set`, ...) that Kandra's `StatementBuilder` chains together — the
 * proxy simply returns itself for any method whose return type it satisfies, and a harmless
 * default otherwise. Actual bound values are never inspected by these tests.
 */
class FakeEventualCqlSession : CqlSession {

    /** Invoked for every non-prepare call to [execute]. Defaults to an immediate empty success. */
    var onExecute: (Statement<*>) -> ResultSet = { FakeEventualResultSet.empty() }

    /** Invoked for every non-prepare call to [executeAsync]. Defaults to an immediate empty success. */
    var onExecuteAsync: (Statement<*>) -> CompletionStage<AsyncResultSet> =
        { CompletableFuture.completedFuture(FakeEventualAsyncResultSet.empty()) }

    override fun execute(statement: Statement<*>): ResultSet = onExecute(statement)

    override fun executeAsync(statement: Statement<*>): CompletionStage<AsyncResultSet> = onExecuteAsync(statement)

    @Suppress("UNCHECKED_CAST")
    override fun <RequestT : com.datastax.oss.driver.api.core.session.Request, ResultT : Any> execute(
        request: RequestT,
        resultType: GenericType<ResultT>
    ): ResultT? {
        if (request is Statement<*>) execute(request)
        return null
    }

    override fun prepare(query: String): PreparedStatement = FakeEventualPreparedStatement(query)

    override fun prepare(statement: SimpleStatement): PreparedStatement = FakeEventualPreparedStatement(statement.query)

    override fun getName(): String = "FakeEventualCqlSession"
    override fun getMetadata(): Metadata = throw UnsupportedOperationException("FakeEventualCqlSession does not support getMetadata()")
    override fun isSchemaMetadataEnabled(): Boolean = false
    override fun setSchemaMetadataEnabled(newValue: Boolean?): CompletionStage<Metadata> = CompletableFuture.failedFuture(UnsupportedOperationException())
    override fun refreshSchemaAsync(): CompletionStage<Metadata> = CompletableFuture.failedFuture(UnsupportedOperationException())
    override fun checkSchemaAgreementAsync(): CompletionStage<Boolean> = CompletableFuture.completedFuture(true)
    override fun getContext(): DriverContext = throw UnsupportedOperationException("FakeEventualCqlSession does not support getContext()")
    override fun getKeyspace(): Optional<CqlIdentifier> = Optional.empty()
    override fun getMetrics(): Optional<Metrics> = Optional.empty()
    override fun closeFuture(): CompletionStage<Void> = CompletableFuture.completedFuture(null)
    override fun closeAsync(): CompletionStage<Void> = CompletableFuture.completedFuture(null)
    override fun forceCloseAsync(): CompletionStage<Void> = CompletableFuture.completedFuture(null)
    override fun isClosed(): Boolean = false
}

private class FakeEventualPreparedStatement(private val query: String) : PreparedStatement {
    override fun bind(vararg values: Any?): BoundStatement = newFluentBoundStatementProxy()

    override fun getId(): ByteBuffer = ByteBuffer.wrap(query.toByteArray())
    override fun getResultMetadataId(): ByteBuffer? = null
    override fun getQuery(): String = query
    override fun getVariableDefinitions(): ColumnDefinitions = throw UnsupportedOperationException()
    override fun getPartitionKeyIndices(): List<Int> = emptyList()
    override fun getResultSetDefinitions(): ColumnDefinitions = throw UnsupportedOperationException()
    override fun setResultMetadata(id: ByteBuffer, definitions: ColumnDefinitions) {}
    override fun boundStatementBuilder(vararg initialValues: Any?): com.datastax.oss.driver.api.core.cql.BoundStatementBuilder =
        throw UnsupportedOperationException()
}

/**
 * A dynamic-proxy [BoundStatement] that fluently returns itself from any method whose return type
 * it satisfies (every `set*`/`unset` chain method returns `BoundStatement` at the interface level)
 * and a harmless default (null / false / 0) otherwise. Good enough for [StatementBuilder] to build
 * a statement object to hand to [CqlSession.execute]/[CqlSession.executeAsync] — this fake never
 * inspects bound values, only which [Statement] instance was executed and how many times.
 */
private fun newFluentBoundStatementProxy(): BoundStatement {
    lateinit var proxy: BoundStatement
    val handler = java.lang.reflect.InvocationHandler { p, method, args ->
        when (method.name) {
            "equals" -> p === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(p)
            "toString" -> "FakeBoundStatement"
            else -> {
                val returnType = method.returnType
                when {
                    returnType.isInstance(p) -> p
                    returnType == Boolean::class.javaPrimitiveType -> false
                    returnType == Int::class.javaPrimitiveType -> 0
                    returnType == Long::class.javaPrimitiveType -> 0L
                    else -> null
                }
            }
        }
    }
    proxy = Proxy.newProxyInstance(
        BoundStatement::class.java.classLoader,
        arrayOf(BoundStatement::class.java),
        handler
    ) as BoundStatement
    return proxy
}

class FakeEventualResultSet private constructor(private val rows: List<Row>) : ResultSet {
    companion object {
        fun empty(): FakeEventualResultSet = FakeEventualResultSet(emptyList())
    }

    override fun iterator(): MutableIterator<Row> = rows.toMutableList().iterator()
    override fun isFullyFetched(): Boolean = true
    override fun getAvailableWithoutFetching(): Int = rows.size
    override fun one(): Row? = rows.firstOrNull()
    override fun all(): List<Row> = rows
    override fun getExecutionInfo(): ExecutionInfo = throw UnsupportedOperationException()
    override fun getExecutionInfos(): List<ExecutionInfo> = emptyList()
    override fun getColumnDefinitions(): ColumnDefinitions = throw UnsupportedOperationException()
    override fun wasApplied(): Boolean = true
}

class FakeEventualAsyncResultSet private constructor() : AsyncResultSet {
    companion object {
        fun empty(): FakeEventualAsyncResultSet = FakeEventualAsyncResultSet()
    }

    override fun currentPage(): Iterable<Row> = emptyList()
    override fun remaining(): Int = 0
    override fun hasMorePages(): Boolean = false
    override fun fetchNextPage(): CompletionStage<AsyncResultSet> = CompletableFuture.completedFuture(empty())
    override fun getExecutionInfo(): ExecutionInfo = throw UnsupportedOperationException()
    override fun getColumnDefinitions(): ColumnDefinitions = throw UnsupportedOperationException()
    override fun wasApplied(): Boolean = true
}
