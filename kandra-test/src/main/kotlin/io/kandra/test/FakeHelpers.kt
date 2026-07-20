package io.kandra.test

import com.datastax.oss.driver.api.core.cql.AsyncResultSet
import com.datastax.oss.driver.api.core.cql.BoundStatementBuilder
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions
import com.datastax.oss.driver.api.core.cql.ExecutionInfo
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import com.datastax.oss.driver.api.core.cql.ResultSet
import com.datastax.oss.driver.api.core.cql.Row
import java.nio.ByteBuffer
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

internal class FakePreparedStatement(private val query: String) : PreparedStatement {
    override fun bind(vararg values: Any?): com.datastax.oss.driver.api.core.cql.BoundStatement =
        throw UnsupportedOperationException("FakePreparedStatement.bind() not supported in FakeKandraSession.")

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
