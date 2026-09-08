package io.kandra.runtime.repository

import com.datastax.oss.driver.api.core.CqlIdentifier
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel
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
import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.KandraConsistency
import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.runtime.BatchEngine
import io.kandra.runtime.ConsistencyConfig
import io.kandra.runtime.FakePreparedStatement
import io.kandra.runtime.StatementBuilder
import io.kandra.runtime.codec.KandraCodec
import io.kandra.runtime.fakeRow
import io.kandra.runtime.recorded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

@ScyllaTable("rc_widgets")
data class RcWidget(
    @PartitionKey val id: UUID,
    val name: String
)

/**
 * ISS-048 regression: [KandraRepository]/[KandraSuspendRepository] used to build their own
 * all-defaults `StatementBuilder`/`QueryExecutor` in a field initializer instead of reading the
 * ones already configured on the [BatchEngine] they receive — silently discarding whatever
 * `consistency`/`codec` the plugin (or a test) configured, on every read.
 *
 * Both tests below construct a [BatchEngine] with a deliberately non-default `StatementBuilder`/
 * `codec` and assert the repository's *read* path actually used it. Against the pre-fix code
 * (`private val statementBuilder = StatementBuilder(session)` in `KandraRepository`), both fail:
 * the first would observe `LOCAL_ONE` (the hardcoded [ConsistencyConfig] default) instead of the
 * configured `QUORUM`, and the second would decode with `KandraCodec.default` instead of the
 * registered custom decoder.
 */
class KandraRepositoryConfigTest {

    @AfterEach
    fun tearDown() {
        SchemaRegistry.clear()
    }

    private fun unconfinedScope() = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    /** Records every statement executed against it and always answers with a single, fixed [Row]. */
    private class RowReturningFakeSession(private val row: Row) : CqlSession {
        private val executed = mutableListOf<Statement<*>>()
        fun executedStatements(): List<Statement<*>> = executed.toList()

        override fun execute(statement: Statement<*>): ResultSet {
            executed.add(statement)
            return OneRowResultSet(row)
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
            executed.add(statement)
            return CompletableFuture.completedFuture(OneRowAsyncResultSet(row))
        }

        override fun prepare(query: String): PreparedStatement = FakePreparedStatement(query)
        override fun prepare(statement: SimpleStatement): PreparedStatement = FakePreparedStatement(statement.query)

        override fun getName(): String = "RowReturningFakeSession"
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

    private class OneRowResultSet(private val row: Row) : ResultSet {
        override fun iterator(): MutableIterator<Row> = mutableListOf(row).iterator()
        override fun isFullyFetched(): Boolean = true
        override fun getAvailableWithoutFetching(): Int = 1
        override fun one(): Row? = row
        override fun all(): List<Row> = listOf(row)
        override fun getExecutionInfo(): ExecutionInfo = throw UnsupportedOperationException()
        override fun getExecutionInfos(): List<ExecutionInfo> = emptyList()
        override fun getColumnDefinitions(): ColumnDefinitions = throw UnsupportedOperationException()
        override fun wasApplied(): Boolean = true
    }

    /** [AsyncResultSet] counterpart of [OneRowResultSet], for the suspend call sites (`executeSuspend` → `AsyncPagingIterable.one()`). */
    private class OneRowAsyncResultSet(private val row: Row) : AsyncResultSet {
        override fun currentPage(): Iterable<Row> = listOf(row)
        override fun remaining(): Int = 0
        override fun hasMorePages(): Boolean = false
        override fun fetchNextPage(): CompletionStage<AsyncResultSet> = CompletableFuture.completedFuture(this)
        override fun getExecutionInfo(): ExecutionInfo = throw UnsupportedOperationException()
        override fun getColumnDefinitions(): ColumnDefinitions = throw UnsupportedOperationException()
        override fun wasApplied(): Boolean = true
    }

    @Test
    fun `findById resolves consistency from the BatchEngine's configured StatementBuilder, not a fresh default one`() {
        val id = UUID.randomUUID()
        val session = RowReturningFakeSession(fakeRow(mapOf("id" to id, "name" to "widget-1")))
        val schema = SchemaRegistry.register(RcWidget::class)

        val configuredBuilder = StatementBuilder(
            session,
            consistencyConfig = ConsistencyConfig().apply { defaultRead = KandraConsistency.QUORUM }
        )
        val batchEngine = BatchEngine(session, configuredBuilder, unconfinedScope())
        val repo = KandraRepository(session, schema, RcWidget::class, batchEngine)

        repo.findById(id)

        val bound = session.executedStatements().single() as BoundStatement
        assertEquals(DefaultConsistencyLevel.QUORUM, bound.recorded().consistencyLevel)
    }

    @Test
    @OptIn(ExperimentalKandraApi::class)
    fun `findById decodes using the BatchEngine's configured codec, not KandraCodec_default`() {
        val id = UUID.randomUUID()
        val session = RowReturningFakeSession(fakeRow(mapOf("id" to id, "name" to "real-row-value")))
        val schema = SchemaRegistry.register(RcWidget::class)

        val configuredCodec = KandraCodec().apply {
            registerDecoder(String::class) { _, _ -> "from-custom-codec" }
        }
        val batchEngine = BatchEngine(session, StatementBuilder(session), unconfinedScope(), codec = configuredCodec)
        val repo = KandraRepository(session, schema, RcWidget::class, batchEngine)

        val found = repo.findById(id)

        assertEquals("from-custom-codec", found?.name)
    }

    // ── KandraSuspendRepository — same bug, same fix, suspend call sites ───────────────────────

    @Test
    fun `suspend findById resolves consistency from the BatchEngine's configured StatementBuilder, not a fresh default one`() = runBlocking {
        val id = UUID.randomUUID()
        val session = RowReturningFakeSession(fakeRow(mapOf("id" to id, "name" to "widget-1")))
        val schema = SchemaRegistry.register(RcWidget::class)

        val configuredBuilder = StatementBuilder(
            session,
            consistencyConfig = ConsistencyConfig().apply { defaultRead = KandraConsistency.QUORUM }
        )
        val batchEngine = BatchEngine(session, configuredBuilder, unconfinedScope())
        val repo = KandraSuspendRepository(session, schema, RcWidget::class, batchEngine)

        repo.findById(id)

        val bound = session.executedStatements().single() as BoundStatement
        assertEquals(DefaultConsistencyLevel.QUORUM, bound.recorded().consistencyLevel)
    }

    @Test
    @OptIn(ExperimentalKandraApi::class)
    fun `suspend findById decodes using the BatchEngine's configured codec, not KandraCodec_default`() = runBlocking {
        val id = UUID.randomUUID()
        val session = RowReturningFakeSession(fakeRow(mapOf("id" to id, "name" to "real-row-value")))
        val schema = SchemaRegistry.register(RcWidget::class)

        val configuredCodec = KandraCodec().apply {
            registerDecoder(String::class) { _, _ -> "from-custom-codec" }
        }
        val batchEngine = BatchEngine(session, StatementBuilder(session), unconfinedScope(), codec = configuredCodec)
        val repo = KandraSuspendRepository(session, schema, RcWidget::class, batchEngine)

        val found = repo.findById(id)

        assertEquals("from-custom-codec", found?.name)
    }
}
