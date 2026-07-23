package io.kandra.runtime

import com.datastax.oss.driver.api.core.ConsistencyLevel
import com.datastax.oss.driver.api.core.CqlIdentifier
import com.datastax.oss.driver.api.core.NoNodeAvailableException
import com.datastax.oss.driver.api.core.context.DriverContext
import com.datastax.oss.driver.api.core.cql.AsyncResultSet
import com.datastax.oss.driver.api.core.cql.BatchStatement
import com.datastax.oss.driver.api.core.cql.BoundStatement
import com.datastax.oss.driver.api.core.cql.BoundStatementBuilder
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
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.Collections
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Test-only support for exercising [BatchEngine] / [StatementBuilder] without a real cluster.
 *
 * `kandra-test`'s `FakeKandraSession` cannot be reused here (it lives in `kandra-test`, which
 * depends on `kandra-runtime` — depending back would be circular), and its own `PreparedStatement
 * .bind()` unconditionally throws (see kandra-test's docs), which makes it useless for exercising
 * [StatementBuilder], which always binds through a real `session.prepare(cql).bind(...)` call.
 *
 * [BoundStatement] is a plain interface (confirmed via `javap` against the driver jar — despite a
 * comment elsewhere in this codebase claiming it's final), so a [java.lang.reflect.Proxy] can stand
 * in for it. The proxy intercepts every call itself rather than delegating to the interface's real
 * default methods (which would require live codec/protocol-version plumbing this test double
 * doesn't have) — it just records what was set at each bound index, which is exactly what
 * [StatementBuilder]'s idempotency/UNSET-vs-NULL tests need to assert on.
 */

/** Records everything a [BoundStatement] proxy was asked to do, for later assertions. */
internal class RecordedStatement(val cql: String) {
    /** Bound value per positional index (raw object, not driver-serialized). */
    val values = mutableListOf<Any?>()

    /** Indices where `unset(idx)` was called (KandraUnset — "leave column alone", no tombstone). */
    val unsetIndices = mutableSetOf<Int>()

    /** Indices where `setBytesUnsafe(idx, null)` was called (explicit null — writes a tombstone). */
    val explicitNullIndices = mutableSetOf<Int>()

    var idempotent: Boolean? = null
    var consistencyLevel: ConsistencyLevel? = null
    var serialConsistencyLevel: ConsistencyLevel? = null

    fun ensureSize(idx: Int) {
        while (values.size <= idx) values.add(null)
    }
}

private class BoundStatementHandler(val recorded: RecordedStatement) : InvocationHandler {
    override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
        val a = args ?: emptyArray()
        return when (method.name) {
            "unset" -> {
                val idx = a[0] as Int
                recorded.ensureSize(idx)
                recorded.unsetIndices += idx
                recorded.explicitNullIndices -= idx
                recorded.values[idx] = null
                proxy
            }
            "set" -> {
                // set(int, ValueT, Class<ValueT>|GenericType<ValueT>|TypeCodec<ValueT>) — all 3-arg overloads.
                val idx = a[0] as Int
                recorded.ensureSize(idx)
                recorded.values[idx] = a.getOrNull(1)
                recorded.unsetIndices -= idx
                recorded.explicitNullIndices -= idx
                proxy
            }
            "setBytesUnsafe" -> {
                val idx = a[0] as Int
                recorded.ensureSize(idx)
                val bytes = a.getOrNull(1) as? ByteBuffer
                recorded.unsetIndices -= idx
                if (bytes == null) {
                    recorded.explicitNullIndices += idx
                    recorded.values[idx] = null
                } else {
                    recorded.explicitNullIndices -= idx
                    recorded.values[idx] = bytes
                }
                proxy
            }
            "setIdempotent" -> {
                recorded.idempotent = a.getOrNull(0) as? Boolean
                proxy
            }
            "isIdempotent" -> recorded.idempotent
            "setConsistencyLevel" -> {
                recorded.consistencyLevel = a.getOrNull(0) as? ConsistencyLevel
                proxy
            }
            "getConsistencyLevel" -> recorded.consistencyLevel
            "setSerialConsistencyLevel" -> {
                recorded.serialConsistencyLevel = a.getOrNull(0) as? ConsistencyLevel
                proxy
            }
            "getSerialConsistencyLevel" -> recorded.serialConsistencyLevel
            "getValues" -> recorded.values.filterIsInstance<ByteBuffer>()
            "toString" -> "FakeBoundStatement(${recorded.cql})"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === a.getOrNull(0)
            else -> defaultReturn(method, proxy)
        }
    }

    private fun defaultReturn(method: Method, proxy: Any): Any? {
        val returnType = method.returnType
        return when {
            returnType.isInstance(proxy) -> proxy // fluent self-returning setter we didn't special-case
            returnType == Boolean::class.javaPrimitiveType -> false
            returnType == Int::class.javaPrimitiveType -> 0
            returnType == Long::class.javaPrimitiveType -> 0L
            else -> null
        }
    }
}

/** Reads the [RecordedStatement] behind a [BoundStatement] built by [FakePreparedStatement]. */
internal fun BoundStatement.recorded(): RecordedStatement =
    (Proxy.getInvocationHandler(this) as BoundStatementHandler).recorded

/** [PreparedStatement] whose [bind] actually produces a working (proxied) [BoundStatement]. */
internal class FakePreparedStatement(private val cql: String) : PreparedStatement {
    override fun bind(vararg values: Any?): BoundStatement {
        val recorded = RecordedStatement(cql)
        values.forEachIndexed { idx, v -> recorded.ensureSize(idx); recorded.values[idx] = v }
        return Proxy.newProxyInstance(
            BoundStatement::class.java.classLoader,
            arrayOf(BoundStatement::class.java),
            BoundStatementHandler(recorded)
        ) as BoundStatement
    }

    override fun getId(): ByteBuffer = ByteBuffer.wrap(cql.toByteArray())
    override fun getQuery(): String = cql
    override fun getVariableDefinitions(): ColumnDefinitions = throw UnsupportedOperationException()
    override fun getPartitionKeyIndices(): List<Int> = emptyList()
    override fun getResultMetadataId(): ByteBuffer? = null
    override fun getResultSetDefinitions(): ColumnDefinitions = throw UnsupportedOperationException()
    override fun setResultMetadata(id: ByteBuffer, definitions: ColumnDefinitions) {}
    override fun boundStatementBuilder(vararg initialValues: Any?): BoundStatementBuilder = throw UnsupportedOperationException()
}

/** Always-empty [ResultSet] — `wasApplied()` fixed `true`, no rows (mirrors kandra-test's FakeResultSet). */
internal class EmptyResultSet : ResultSet {
    override fun iterator(): MutableIterator<Row> = mutableListOf<Row>().iterator()
    override fun isFullyFetched(): Boolean = true
    override fun getAvailableWithoutFetching(): Int = 0
    override fun one(): Row? = null
    override fun all(): List<Row> = emptyList()
    override fun getExecutionInfo(): ExecutionInfo = throw UnsupportedOperationException()
    override fun getExecutionInfos(): List<ExecutionInfo> = emptyList()
    override fun getColumnDefinitions(): ColumnDefinitions = throw UnsupportedOperationException()
    override fun wasApplied(): Boolean = true
}

internal class EmptyAsyncResultSet : AsyncResultSet {
    override fun currentPage(): Iterable<Row> = emptyList()
    override fun remaining(): Int = 0
    override fun hasMorePages(): Boolean = false
    override fun fetchNextPage(): CompletionStage<AsyncResultSet> = CompletableFuture.completedFuture(EmptyAsyncResultSet())
    override fun getExecutionInfo(): ExecutionInfo = throw UnsupportedOperationException()
    override fun getColumnDefinitions(): ColumnDefinitions = throw UnsupportedOperationException()
    override fun wasApplied(): Boolean = true
}

/**
 * Controllable in-memory [CqlSession]: records every [Statement] passed to [execute]/[executeSuspend]
 * (in call order, including retried re-executions of the same statement) and can be told to throw a
 * given exception for the first N calls before succeeding — exactly what's needed to deterministically
 * drive [BatchEngine]'s retry/backoff branches without timing- or network-dependent flakiness.
 */
internal class ControllableFakeSession(
    private val failuresBeforeSuccess: Int = 0,
    private val exceptionFactory: () -> Throwable = { NoNodeAvailableException() }
) : com.datastax.oss.driver.api.core.CqlSession {

    private val executed = Collections.synchronizedList(mutableListOf<Statement<*>>())

    /** Every [Statement] passed to [execute], in call order — including repeated retry attempts. */
    fun executedStatements(): List<Statement<*>> = executed.toList()

    val executeCallCount: Int get() = executed.size

    override fun execute(statement: Statement<*>): ResultSet {
        executed.add(statement)
        if (executed.size <= failuresBeforeSuccess) throw exceptionFactory()
        return EmptyResultSet()
    }

    @Suppress("UNCHECKED_CAST")
    override fun <RequestT : com.datastax.oss.driver.api.core.session.Request, ResultT : Any> execute(
        request: RequestT,
        resultType: GenericType<ResultT>
    ): ResultT? {
        if (request is Statement<*>) execute(request)
        return null
    }

    override fun executeAsync(statement: Statement<*>): CompletionStage<AsyncResultSet> {
        return try {
            execute(statement)
            CompletableFuture.completedFuture(EmptyAsyncResultSet())
        } catch (e: Throwable) {
            CompletableFuture.failedFuture(e)
        }
    }

    override fun prepare(query: String): PreparedStatement = FakePreparedStatement(query)
    override fun prepare(statement: SimpleStatement): PreparedStatement = FakePreparedStatement(statement.query)

    override fun getName(): String = "ControllableFakeSession"
    override fun getMetadata(): Metadata = throw UnsupportedOperationException()
    override fun isSchemaMetadataEnabled(): Boolean = false
    override fun setSchemaMetadataEnabled(newValue: Boolean?): CompletionStage<Metadata> = CompletableFuture.failedFuture(UnsupportedOperationException())
    override fun refreshSchemaAsync(): CompletionStage<Metadata> = CompletableFuture.failedFuture(UnsupportedOperationException())
    override fun checkSchemaAgreementAsync(): CompletionStage<Boolean> = CompletableFuture.completedFuture(true)
    override fun getContext(): DriverContext = throw UnsupportedOperationException()
    override fun getKeyspace(): Optional<CqlIdentifier> = Optional.empty()
    override fun getMetrics(): Optional<Metrics> = Optional.empty()
    override fun closeFuture(): CompletionStage<Void> = CompletableFuture.completedFuture(null)
    override fun closeAsync(): CompletionStage<Void> = CompletableFuture.completedFuture(null)
    override fun forceCloseAsync(): CompletionStage<Void> = CompletableFuture.completedFuture(null)
    override fun isClosed(): Boolean = false
}

/** Pulls out the top-level [BatchStatement]s among everything [ControllableFakeSession] executed. */
internal fun ControllableFakeSession.executedBatches(): List<BatchStatement> =
    executedStatements().filterIsInstance<BatchStatement>()
