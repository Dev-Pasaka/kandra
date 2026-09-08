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
import com.datastax.oss.driver.api.core.metadata.Node
import com.datastax.oss.driver.api.core.metrics.Metrics
import com.datastax.oss.driver.api.core.type.reflect.GenericType
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicInteger

/**
 * What a [ScriptedCqlSession] does the next time `execute`/`executeAsync` is invoked.
 */
sealed class ExecuteOutcome {
    /** Simulate a transient driver exception (e.g. [com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException]). */
    data class Throw(val error: Throwable) : ExecuteOutcome()

    /** Simulate a normal LWT-style response with the driver's synthetic `[applied]` column set to [applied]. */
    data class Applied(val applied: Boolean) : ExecuteOutcome()

    /** Simulate a read returning an arbitrary row list — for `findAll`/`exists`-style read-path tests. */
    data class Rows(val rows: List<Row>) : ExecuteOutcome()
}

/**
 * A minimal, hand-scripted [CqlSession] test double for [BatchEngine] unit tests.
 *
 * Each call to `execute`/`executeAsync` pops the next [ExecuteOutcome] off [outcomes] (queue semantics);
 * once the queue is empty, every subsequent call returns `Applied(true)`. [executeCount] lets tests
 * assert exactly how many times the driver was actually invoked (e.g. "never retried").
 *
 * Only the members actually exercised by [BatchEngine] are meaningfully implemented — everything else
 * throws [UnsupportedOperationException] or returns an inert default, mirroring the existing
 * `kandra-test` `FakeKandraSession` pattern (kept separate here to avoid a kandra-runtime -> kandra-test
 * circular dependency).
 */
class ScriptedCqlSession(outcomes: List<ExecuteOutcome> = emptyList()) : CqlSession {

    private val queue = ArrayDeque(outcomes)
    val executeCount = AtomicInteger(0)

    /** The [Statement] passed to the most recent `execute`/`executeAsync` call. */
    var lastStatement: Statement<*>? = null
        private set

    /**
     * The consistency level of the most recently `.bind()`-built [BoundStatement], as recorded by
     * [fakeBoundStatement]'s `setConsistencyLevel` interception (real [BoundStatement]s can't be
     * instantiated outside driver internals, so this proxy is the only way to observe what
     * [BatchEngine]/[StatementBuilder] actually set on one). `null` until a bound statement calls
     * `.setConsistencyLevel(...)`.
     */
    var lastBoundConsistencyLevel: ConsistencyLevel? = null

    /** The CQL text of the most recent `prepare`/`prepareAsync` call. */
    var lastPreparedCql: String? = null
        private set

    private fun nextOutcome(): ExecuteOutcome {
        executeCount.incrementAndGet()
        return if (queue.isNotEmpty()) queue.removeFirst() else ExecuteOutcome.Applied(true)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <RequestT : com.datastax.oss.driver.api.core.session.Request, ResultT : Any> execute(
        request: RequestT,
        resultType: GenericType<ResultT>
    ): ResultT? {
        if (request is Statement<*>) execute(request)
        return null
    }

    override fun execute(statement: Statement<*>): ResultSet {
        lastStatement = statement
        return when (val outcome = nextOutcome()) {
            is ExecuteOutcome.Throw -> throw outcome.error
            is ExecuteOutcome.Applied -> FakeAppliedResultSet(outcome.applied)
            is ExecuteOutcome.Rows -> FakeRowsResultSet(outcome.rows)
        }
    }

    override fun executeAsync(statement: Statement<*>): CompletionStage<AsyncResultSet> {
        lastStatement = statement
        return when (val outcome = nextOutcome()) {
            is ExecuteOutcome.Throw -> {
                val future = CompletableFuture<AsyncResultSet>()
                future.completeExceptionally(outcome.error)
                future
            }
            is ExecuteOutcome.Applied -> CompletableFuture.completedFuture(FakeAppliedAsyncResultSet(outcome.applied))
            is ExecuteOutcome.Rows -> CompletableFuture.completedFuture(FakeRowsAsyncResultSet(outcome.rows))
        }
    }

    override fun prepare(query: String): PreparedStatement {
        lastPreparedCql = query
        return FakeBindablePreparedStatement(query) { lastBoundConsistencyLevel = it }
    }

    override fun prepare(statement: SimpleStatement): PreparedStatement = prepare(statement.query)

    // AsyncCqlSession's default prepareAsync(String) routes through the generic execute(request, resultType)
    // overload above (which only understands Statement requests) and NPEs on the result — override directly
    // so io.kandra.runtime.driver.prepareSuspend (used by the versioned-update suspend path) works.
    override fun prepareAsync(query: String): CompletionStage<PreparedStatement> {
        lastPreparedCql = query
        return CompletableFuture.completedFuture(FakeBindablePreparedStatement(query) { lastBoundConsistencyLevel = it })
    }

    override fun prepareAsync(statement: SimpleStatement): CompletionStage<PreparedStatement> = prepareAsync(statement.query)

    override fun getName(): String = "ScriptedCqlSession"

    override fun getMetadata(): Metadata = throw UnsupportedOperationException("ScriptedCqlSession does not support getMetadata()")

    override fun isSchemaMetadataEnabled(): Boolean = false

    override fun setSchemaMetadataEnabled(newValue: Boolean?): CompletionStage<Metadata> =
        CompletableFuture.failedFuture(UnsupportedOperationException())

    override fun refreshSchemaAsync(): CompletionStage<Metadata> =
        CompletableFuture.failedFuture(UnsupportedOperationException())

    override fun checkSchemaAgreementAsync(): CompletionStage<Boolean> = CompletableFuture.completedFuture(true)

    override fun getContext(): DriverContext = throw UnsupportedOperationException("ScriptedCqlSession does not support getContext()")

    override fun getKeyspace(): Optional<CqlIdentifier> = Optional.empty()

    override fun getMetrics(): Optional<Metrics> = Optional.empty()

    override fun closeFuture(): CompletionStage<Void> = CompletableFuture.completedFuture(null)

    override fun closeAsync(): CompletionStage<Void> = CompletableFuture.completedFuture(null)

    override fun forceCloseAsync(): CompletionStage<Void> = CompletableFuture.completedFuture(null)

    override fun isClosed(): Boolean = false
}

/** [PreparedStatement] whose [bind] returns a dynamic-proxy [BoundStatement] (see [fakeBoundStatement]). */
private class FakeBindablePreparedStatement(
    private val query: String,
    private val onSetConsistencyLevel: (ConsistencyLevel) -> Unit = {}
) : PreparedStatement {
    override fun bind(vararg values: Any?): BoundStatement = fakeBoundStatement(onSetConsistencyLevel)
    override fun getId(): ByteBuffer = ByteBuffer.wrap(query.toByteArray())
    override fun getResultMetadataId(): ByteBuffer? = null
    override fun getQuery(): String = query
    override fun getVariableDefinitions(): ColumnDefinitions = throw UnsupportedOperationException()
    override fun getPartitionKeyIndices(): List<Int> = emptyList()
    override fun getResultSetDefinitions(): ColumnDefinitions = throw UnsupportedOperationException()
    override fun setResultMetadata(id: ByteBuffer, definitions: ColumnDefinitions) {}
    override fun boundStatementBuilder(vararg initialValues: Any?): BoundStatementBuilder = throw UnsupportedOperationException()
}

/**
 * [BoundStatement] is a wide interface (Bindable/GettableByIndex/SettableByIndex/Statement) with no
 * production-usable final implementation reachable outside the driver internals. Rather than hand-write
 * dozens of unused methods, a [Proxy] answers every "fluent setter" call (return type == BoundStatement)
 * by returning itself so call chains like `.setSerialConsistencyLevel(...)` keep working, and answers
 * anything else with a type-appropriate inert default. Nothing in [BatchEngine] ever reads values back
 * off the bound statement it built — it only builds and passes it to `session.execute(...)`, which our
 * [ScriptedCqlSession] ignores in favor of the scripted [ExecuteOutcome] queue.
 */
private fun fakeBoundStatement(onSetConsistencyLevel: (ConsistencyLevel) -> Unit = {}): BoundStatement {
    // Fluent setters (setSerialConsistencyLevel, setIdempotent, setTracing, ...) are declared to return
    // a generic `SelfT extends Statement<SelfT>` — erased to plain Object at runtime, so `method.returnType`
    // can't be compared against BoundStatement::class directly. Matched by name instead (every setter-style
    // method on Statement/Bindable/BoundStatement starts with "set", plus a few no-arg fluent methods).
    val selfReturningNames = setOf("enableTracing", "disableTracing", "copy")
    val handler = InvocationHandler { proxy, method, args ->
        when {
            method.name == "toString" -> "FakeBoundStatement"
            method.name == "hashCode" -> System.identityHashCode(proxy)
            method.name == "equals" -> false
            method.name == "setConsistencyLevel" -> {
                (args?.getOrNull(0) as? ConsistencyLevel)?.let(onSetConsistencyLevel)
                proxy
            }
            method.name.startsWith("set") || method.name in selfReturningNames -> proxy
            method.returnType == Boolean::class.javaPrimitiveType -> false
            method.returnType == Int::class.javaPrimitiveType -> 0
            method.returnType == Long::class.javaPrimitiveType -> 0L
            method.returnType == Optional::class.java -> Optional.empty<Any>()
            else -> null
        }
    }
    return Proxy.newProxyInstance(
        BoundStatement::class.java.classLoader,
        arrayOf(BoundStatement::class.java),
        handler
    ) as BoundStatement
}

/**
 * Inert [Node] proxy — several driver exception constructors (e.g. [com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException])
 * require a non-null coordinator [Node], but nothing under test inspects it.
 */
fun fakeNode(): Node {
    val handler = InvocationHandler { proxy, method, _ ->
        when {
            method.name == "toString" -> "FakeNode"
            method.name == "hashCode" -> System.identityHashCode(proxy)
            method.name == "equals" -> false
            method.returnType == Boolean::class.javaPrimitiveType -> false
            method.returnType == Int::class.javaPrimitiveType -> 0
            method.returnType == Optional::class.java -> Optional.empty<Any>()
            else -> null
        }
    }
    return Proxy.newProxyInstance(Node::class.java.classLoader, arrayOf(Node::class.java), handler) as Node
}

/** [Row] proxy that answers every `getBoolean(...)` call with [applied] regardless of column/index argument. */
private fun fakeAppliedRow(applied: Boolean): Row {
    val handler = InvocationHandler { proxy, method, _ ->
        when {
            method.name == "getBoolean" -> applied
            method.name == "toString" -> "FakeRow(applied=$applied)"
            method.name == "hashCode" -> System.identityHashCode(proxy)
            method.name == "equals" -> false
            method.returnType == Boolean::class.javaPrimitiveType -> false
            method.returnType == Int::class.javaPrimitiveType -> 0
            else -> null
        }
    }
    return Proxy.newProxyInstance(Row::class.java.classLoader, arrayOf(Row::class.java), handler) as Row
}

private class FakeAppliedResultSet(private val applied: Boolean) : ResultSet {
    private val row = fakeAppliedRow(applied)
    override fun iterator(): MutableIterator<Row> = mutableListOf(row).iterator()
    override fun isFullyFetched(): Boolean = true
    override fun getAvailableWithoutFetching(): Int = 1
    override fun one(): Row? = row
    override fun all(): List<Row> = listOf(row)
    override fun getExecutionInfo(): ExecutionInfo = throw UnsupportedOperationException()
    override fun getExecutionInfos(): List<ExecutionInfo> = emptyList()
    override fun getColumnDefinitions(): ColumnDefinitions = throw UnsupportedOperationException()
    override fun wasApplied(): Boolean = applied
}

private class FakeAppliedAsyncResultSet(private val applied: Boolean) : AsyncResultSet {
    private val row = fakeAppliedRow(applied)
    override fun currentPage(): Iterable<Row> = listOf(row)
    override fun remaining(): Int = 0
    override fun hasMorePages(): Boolean = false
    override fun fetchNextPage(): CompletionStage<AsyncResultSet> = CompletableFuture.completedFuture(FakeAppliedAsyncResultSet(applied))
    override fun getExecutionInfo(): ExecutionInfo = throw UnsupportedOperationException()
    override fun getColumnDefinitions(): ColumnDefinitions = throw UnsupportedOperationException()
    override fun wasApplied(): Boolean = applied
}

/** Single-page [ResultSet] backed by an arbitrary row list — see [ExecuteOutcome.Rows]. */
private class FakeRowsResultSet(private val rows: List<Row>) : ResultSet {
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

/** Single-page [AsyncResultSet] backed by an arbitrary row list — see [ExecuteOutcome.Rows]. */
private class FakeRowsAsyncResultSet(private val rows: List<Row>) : AsyncResultSet {
    override fun currentPage(): Iterable<Row> = rows
    override fun remaining(): Int = 0
    override fun hasMorePages(): Boolean = false
    override fun fetchNextPage(): CompletionStage<AsyncResultSet> = CompletableFuture.completedFuture(FakeRowsAsyncResultSet(emptyList()))
    override fun getExecutionInfo(): ExecutionInfo = throw UnsupportedOperationException()
    override fun getColumnDefinitions(): ColumnDefinitions = throw UnsupportedOperationException()
    override fun wasApplied(): Boolean = true
}
