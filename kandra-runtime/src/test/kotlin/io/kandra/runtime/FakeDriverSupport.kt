package io.kandra.runtime

import com.datastax.oss.driver.api.core.ConsistencyLevel
import com.datastax.oss.driver.api.core.CqlIdentifier
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.context.DriverContext
import com.datastax.oss.driver.api.core.cql.AsyncResultSet
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
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Test-only doubles for exercising [StatementBuilder]/[BatchEngine]/[QueryExecutor] without a real
 * cluster, scoped to what ISS-034's reflection-caching tests need: a real `session.prepare(cql)
 * .bind(...)` round trip whose bound values can be inspected, and a [Row] backed by a plain map.
 *
 * `kandra-test`'s `FakeKandraSession` cannot be reused here (`kandra-test` depends on
 * `kandra-runtime`, so a reverse dependency would be circular), and its `PreparedStatement.bind()`
 * unconditionally throws, which makes it unusable for inspecting what [StatementBuilder] actually
 * binds. [BoundStatement] and [Row] are plain interfaces, so [java.lang.reflect.Proxy] stands in
 * for both.
 */

/** Records everything a [BoundStatement] proxy was asked to do, for later assertions. */
internal class RecordedStatement(val cql: String) {
    val values = mutableListOf<Any?>()
    val unsetIndices = mutableSetOf<Int>()
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
            "toString" -> "FakeBoundStatement(${recorded.cql})"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === a.getOrNull(0)
            else -> defaultReturn(method, proxy)
        }
    }

    private fun defaultReturn(method: Method, proxy: Any): Any? {
        val returnType = method.returnType
        return when {
            returnType.isInstance(proxy) -> proxy
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

/** [PreparedStatement] whose [bind] produces a working (proxied), inspectable [BoundStatement]. */
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

/** Always-empty [ResultSet] — `wasApplied()` fixed `true`, no rows. */
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

/** Minimal [CqlSession] double: `prepare` produces an inspectable statement, `execute` records calls. */
internal class FakeCqlSession : CqlSession {
    private val executed = mutableListOf<Statement<*>>()
    fun executedStatements(): List<Statement<*>> = executed.toList()

    override fun execute(statement: Statement<*>): ResultSet {
        executed.add(statement)
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

    override fun executeAsync(statement: Statement<*>): CompletionStage<AsyncResultSet> =
        CompletableFuture.completedFuture(null)

    override fun prepare(query: String): PreparedStatement = FakePreparedStatement(query)
    override fun prepare(statement: SimpleStatement): PreparedStatement = FakePreparedStatement(statement.query)

    override fun getName(): String = "FakeCqlSession"
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

private class RowHandler(private val columns: Map<String, Any?>) : InvocationHandler {
    override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
        val a = args ?: emptyArray()
        return when (method.name) {
            "isNull" -> !columns.containsKey(a[0] as String) || columns[a[0] as String] == null
            "getUuid", "getString", "getInt", "getLong", "getBoolean", "getDouble", "getFloat",
            "getInstant", "getLocalDate", "getBigDecimal", "getByteBuffer", "getObject" ->
                columns[a[0] as String]
            "toString" -> "FakeRow($columns)"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === a.getOrNull(0)
            else -> null
        }
    }
}

/** [Row] backed by a plain `propertyName-agnostic` CQL-column-name -> value map. */
internal fun fakeRow(columns: Map<String, Any?>): Row =
    Proxy.newProxyInstance(Row::class.java.classLoader, arrayOf(Row::class.java), RowHandler(columns)) as Row
