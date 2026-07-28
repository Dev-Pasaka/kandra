package io.kandra.test

import com.datastax.oss.driver.api.core.ConsistencyLevel
import com.datastax.oss.driver.api.core.CqlIdentifier
import com.datastax.oss.driver.api.core.ProtocolVersion
import com.datastax.oss.driver.api.core.config.DriverExecutionProfile
import com.datastax.oss.driver.api.core.cql.AsyncResultSet
import com.datastax.oss.driver.api.core.cql.BoundStatement
import com.datastax.oss.driver.api.core.cql.BoundStatementBuilder
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions
import com.datastax.oss.driver.api.core.cql.ExecutionInfo
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import com.datastax.oss.driver.api.core.cql.ResultSet
import com.datastax.oss.driver.api.core.cql.Row
import com.datastax.oss.driver.api.core.cql.Statement
import com.datastax.oss.driver.api.core.metadata.Node
import com.datastax.oss.driver.api.core.metadata.token.Token
import com.datastax.oss.driver.api.core.type.DataType
import com.datastax.oss.driver.api.core.type.codec.registry.CodecRegistry
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

internal class FakeResultSet private constructor(private val rows: List<Row>) : ResultSet {
    companion object {
        fun empty(): FakeResultSet = FakeResultSet(emptyList())
        fun of(rows: List<Row>): FakeResultSet = FakeResultSet(rows)
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

internal class FakeAsyncResultSet : AsyncResultSet {
    override fun currentPage(): Iterable<Row> = emptyList()
    override fun remaining(): Int = 0
    override fun hasMorePages(): Boolean = false
    override fun fetchNextPage(): CompletionStage<AsyncResultSet> =
        CompletableFuture.completedFuture(FakeAsyncResultSet())
    override fun getExecutionInfo(): ExecutionInfo = throw UnsupportedOperationException()
    override fun getColumnDefinitions(): ColumnDefinitions = throw UnsupportedOperationException()
    override fun wasApplied(): Boolean = true
}

/**
 * Sentinel marking a positional variable on a [FakeBoundStatement] that was explicitly left
 * *unset* (via [BoundStatement.unset]) as opposed to bound to an actual CQL `null` (via
 * `setBytesUnsafe(idx, null)`, which [FakeBoundStatement] records as Kotlin `null`). Mirrors the
 * real driver's "unset means don't touch this column" write semantics — see
 * `StatementBuilder.insertPrimary` (uses `unset`) vs. `StatementBuilder.insertPrimaryWithNulls`
 * (binds real `null`, to create a tombstone) for the two call sites this distinction matters for.
 *
 * Returned in place of a value from [FakeBoundStatement.boundValues] wherever that index was
 * never bound, or was explicitly unset.
 */
object FakeUnset {
    override fun toString(): String = "FakeUnset"
}

/**
 * Minimal, structural stand-in for the DataStax driver's [BoundStatement].
 *
 * Implements just enough of the (large) `BoundStatement`/`Statement` interface surface to let
 * `io.kandra.runtime.StatementBuilder` build and bind statements against a [FakeKandraSession]
 * without a real driver `CqlSession` underneath. It performs **no** real CQL type encoding,
 * validation, or protocol-level byte serialization — values passed to `set`/`setBytesUnsafe`/
 * `unset` are stored verbatim (or as [FakeUnset]) at their positional index, retrievable via
 * [boundValues] so tests can assert on exactly what a repository call bound.
 *
 * Driver features this class has no use for (routing keys/tokens, per-name column lookups,
 * execution profiles, custom payloads, tracing, ...) are implemented only to satisfy the
 * interface contract: each is stored and returned faithfully via a plain field, but never
 * interpreted or validated. This is intentionally not a full CQL type system — see the module's
 * "structural test" framing (batch composition, save/delete ordering, what-was-called-with-what),
 * not semantic query execution.
 */
internal class FakeBoundStatement(
    private val preparedStatementRef: PreparedStatement,
    val query: String
) : BoundStatement {

    private val values = mutableListOf<Any?>()

    /** Positional bound values in argument order. An index never bound reads back as [FakeUnset]. */
    fun boundValues(): List<Any?> = values.toList()

    /** Seeds positional values from a `prepared.bind(v0, v1, ...)` call — only ever called once, immediately after construction. */
    internal fun seedInitialValues(initialValues: Array<out Any?>) {
        values.clear()
        values.addAll(initialValues)
    }

    private fun ensureCapacity(index: Int) {
        while (values.size <= index) values.add(FakeUnset)
    }

    // ── Bindable: value storage (the only behavior this fake actually needs) ─────────────────

    override fun getPreparedStatement(): PreparedStatement = preparedStatementRef

    /** Not backed by real protocol bytes — use [boundValues] to inspect what was bound. */
    override fun getValues(): List<ByteBuffer> = List(values.size) { ByteBuffer.allocate(0) }

    override fun getBytesUnsafe(i: Int): ByteBuffer? = throw UnsupportedOperationException(
        "FakeBoundStatement does not simulate real byte encoding — use boundValues() to inspect bound values."
    )

    override fun setBytesUnsafe(i: Int, v: ByteBuffer?): BoundStatement {
        ensureCapacity(i)
        values[i] = null // explicit null bind (e.g. saveWithNulls' tombstone writes) — distinct from FakeUnset
        return this
    }

    override fun <ValueT : Any> set(i: Int, v: ValueT?, targetClass: Class<ValueT>): BoundStatement {
        ensureCapacity(i)
        values[i] = v
        return this
    }

    override fun unset(i: Int): BoundStatement {
        ensureCapacity(i)
        values[i] = FakeUnset
        return this
    }

    override fun isSet(i: Int): Boolean {
        ensureCapacity(i)
        return values[i] !== FakeUnset
    }

    override fun size(): Int = values.size
    override fun getType(i: Int): DataType = throw UnsupportedOperationException("FakeBoundStatement has no real column type metadata.")
    override fun firstIndexOf(id: CqlIdentifier): Int = throw UnsupportedOperationException("FakeBoundStatement only supports positional binding.")
    override fun firstIndexOf(name: String): Int = throw UnsupportedOperationException("FakeBoundStatement only supports positional binding.")
    override fun codecRegistry(): CodecRegistry = throw UnsupportedOperationException("FakeBoundStatement does not use a real CodecRegistry.")
    override fun protocolVersion(): ProtocolVersion = throw UnsupportedOperationException("FakeBoundStatement does not target a real protocol version.")

    // ── Statement<BoundStatement> / Request plumbing — stored, never interpreted ─────────────

    private var executionProfileNameValue: String? = null
    private var executionProfileValue: DriverExecutionProfile? = null
    private var routingKeyspaceValue: CqlIdentifier? = null
    private var nodeValue: Node? = null
    private var routingKeyValue: ByteBuffer? = null
    private var routingTokenValue: Token? = null
    private var customPayloadValue: Map<String, ByteBuffer> = emptyMap()
    private var idempotentValue: Boolean? = null
    private var tracingValue = false
    private var queryTimestampValue = Statement.NO_DEFAULT_TIMESTAMP
    private var timeoutValue: Duration? = null
    private var pagingStateValue: ByteBuffer? = null
    private var pageSizeValue = 0
    private var consistencyLevelValue: ConsistencyLevel? = null
    private var serialConsistencyLevelValue: ConsistencyLevel? = null

    override fun getExecutionProfileName(): String? = executionProfileNameValue
    override fun setExecutionProfileName(name: String?): BoundStatement { executionProfileNameValue = name; return this }
    override fun getExecutionProfile(): DriverExecutionProfile? = executionProfileValue
    override fun setExecutionProfile(profile: DriverExecutionProfile?): BoundStatement { executionProfileValue = profile; return this }
    override fun getRoutingKeyspace(): CqlIdentifier? = routingKeyspaceValue
    override fun setRoutingKeyspace(keyspace: CqlIdentifier?): BoundStatement { routingKeyspaceValue = keyspace; return this }
    override fun getNode(): Node? = nodeValue
    override fun setNode(node: Node?): BoundStatement { nodeValue = node; return this }
    override fun getRoutingKey(): ByteBuffer? = routingKeyValue
    override fun setRoutingKey(key: ByteBuffer?): BoundStatement { routingKeyValue = key; return this }
    override fun getRoutingToken(): Token? = routingTokenValue
    override fun setRoutingToken(token: Token?): BoundStatement { routingTokenValue = token; return this }
    override fun getCustomPayload(): Map<String, ByteBuffer> = customPayloadValue
    override fun setCustomPayload(payload: Map<String, ByteBuffer>): BoundStatement { customPayloadValue = payload; return this }
    override fun isIdempotent(): Boolean? = idempotentValue
    override fun setIdempotent(idempotent: Boolean?): BoundStatement { idempotentValue = idempotent; return this }
    override fun isTracing(): Boolean = tracingValue
    override fun setTracing(tracing: Boolean): BoundStatement { tracingValue = tracing; return this }
    override fun getQueryTimestamp(): Long = queryTimestampValue
    override fun setQueryTimestamp(timestamp: Long): BoundStatement { queryTimestampValue = timestamp; return this }
    override fun getTimeout(): Duration? = timeoutValue
    override fun setTimeout(timeout: Duration?): BoundStatement { timeoutValue = timeout; return this }
    override fun getPagingState(): ByteBuffer? = pagingStateValue
    override fun setPagingState(pagingState: ByteBuffer?): BoundStatement { pagingStateValue = pagingState; return this }
    override fun getPageSize(): Int = pageSizeValue
    override fun setPageSize(pageSize: Int): BoundStatement { pageSizeValue = pageSize; return this }
    override fun getConsistencyLevel(): ConsistencyLevel? = consistencyLevelValue
    override fun setConsistencyLevel(consistencyLevel: ConsistencyLevel?): BoundStatement { consistencyLevelValue = consistencyLevel; return this }
    override fun getSerialConsistencyLevel(): ConsistencyLevel? = serialConsistencyLevelValue
    override fun setSerialConsistencyLevel(serialConsistencyLevel: ConsistencyLevel?): BoundStatement { serialConsistencyLevelValue = serialConsistencyLevel; return this }
}

internal class FakePreparedStatement(private val query: String) : PreparedStatement {
    override fun bind(vararg values: Any?): BoundStatement =
        FakeBoundStatement(this, query).also { if (values.isNotEmpty()) it.seedInitialValues(values) }

    override fun getId(): ByteBuffer = ByteBuffer.wrap(query.toByteArray())
    override fun getResultMetadataId(): ByteBuffer? = null
    override fun getQuery(): String = query
    override fun getVariableDefinitions(): ColumnDefinitions = throw UnsupportedOperationException()
    override fun getPartitionKeyIndices(): List<Int> = emptyList()
    override fun getResultSetDefinitions(): ColumnDefinitions = throw UnsupportedOperationException()
    override fun setResultMetadata(id: ByteBuffer, definitions: ColumnDefinitions) {}
    override fun boundStatementBuilder(vararg initialValues: Any?): BoundStatementBuilder =
        throw UnsupportedOperationException()
}
