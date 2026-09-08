package io.kandra.runtime

import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.LookupIndex
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.runtime.dsl.KandraColumnRef
import io.kandra.runtime.dsl.QueryContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

@ScyllaTable("qep_items")
data class QepItem(
    @PartitionKey val id: UUID,
    val name: String? = null
)

@ScyllaTable("qep_accounts")
data class QepAccount(
    @PartitionKey val id: UUID,
    @LookupIndex(tableSuffix = "by_email") val email: String
)

/**
 * End-to-end regression coverage for GH #27 / ISS-049: [QueryExecutor]'s suspend read paths
 * (`findByIdSuspend`, `resolveRowsSuspend`'s lookup and `IN` branches, `findPageSuspend`'s lookup
 * branch) must build their `SELECT` statements via [StatementBuilder]'s suspend methods, never the
 * blocking ones, on a prepared-statement cache miss.
 *
 * Driven by [PrepareCallTrackingSession] (see FakeDriverSupport.kt) — its blocking `prepare()`
 * throws, so a suspend path that (incorrectly) called it fails loudly here.
 * `rawSuspend`/`rawQuerySuspend`/`raw`/`rawQuery` are deliberately not covered here — they're owned
 * by a different fix (GH #32) and already used `session.prepareSuspend` directly, unaffected by
 * this change.
 */
class QueryExecutorSuspendPreparePathTest {

    @AfterEach
    fun tearDown() {
        SchemaRegistry.clear()
    }

    @Test
    fun `findByIdSuspend never calls blocking prepare on a cache miss`() = runBlocking {
        val schema = SchemaRegistry.register(QepItem::class)
        val id = UUID.randomUUID()
        val session = PrepareCallTrackingSession().apply {
            rowsToReturn = listOf(fakeRow(mapOf("id" to id)))
        }
        val executor = QueryExecutor(session, schema, StatementBuilder(session))

        val found = executor.findByIdSuspend(QepItem::class, id)

        assertEquals(id, found?.id)
        assertEquals(0, session.blockingPrepareCount.get())
        assertEquals(1, session.asyncPrepareCount.get())
    }

    @Test
    fun `findAllSuspend via a LookupIndex predicate never calls blocking prepare`() = runBlocking {
        val schema = SchemaRegistry.register(QepAccount::class)
        val id = UUID.randomUUID()
        val session = PrepareCallTrackingSession().apply {
            rowsToReturn = listOf(fakeRow(mapOf("id" to id, "email" to "a@b.com")))
        }
        val executor = QueryExecutor(session, schema, StatementBuilder(session))
        val block: QueryContext.() -> Unit = { KandraColumnRef<String>("email") eq "a@b.com" }

        val results = executor.findAllSuspend(QepAccount::class, block)

        assertEquals(1, results.size)
        assertEquals(0, session.blockingPrepareCount.get())
        // selectByLookupSuspend (lookup table) + selectByIdSuspend (primary table) -- two distinct CQL strings.
        assertEquals(2, session.asyncPrepareCount.get())
    }

    @Test
    fun `findAllSuspend via an IN predicate on the partition key never calls blocking prepare`() = runBlocking {
        val schema = SchemaRegistry.register(QepItem::class)
        val id = UUID.randomUUID()
        val session = PrepareCallTrackingSession().apply {
            rowsToReturn = listOf(fakeRow(mapOf("id" to id)))
        }
        val executor = QueryExecutor(session, schema, StatementBuilder(session))
        val block: QueryContext.() -> Unit = { KandraColumnRef<UUID>("id") isIn listOf(id) }

        val results = executor.findAllSuspend(QepItem::class, block)

        assertEquals(1, results.size)
        assertEquals(0, session.blockingPrepareCount.get())
        assertEquals(1, session.asyncPrepareCount.get())
    }

    @Test
    fun `existsSuspend via a LookupIndex predicate never calls blocking prepare`() = runBlocking {
        val schema = SchemaRegistry.register(QepAccount::class)
        val id = UUID.randomUUID()
        val session = PrepareCallTrackingSession().apply {
            rowsToReturn = listOf(fakeRow(mapOf("id" to id, "email" to "a@b.com")))
        }
        val executor = QueryExecutor(session, schema, StatementBuilder(session))
        val block: QueryContext.() -> Unit = { KandraColumnRef<String>("email") eq "a@b.com" }

        val exists = executor.existsSuspend(block)

        assertTrue(exists)
        assertEquals(0, session.blockingPrepareCount.get())
        assertEquals(2, session.asyncPrepareCount.get())
    }

    @Test
    fun `findPageSuspend via a LookupIndex predicate never calls blocking prepare`() = runBlocking {
        val schema = SchemaRegistry.register(QepAccount::class)
        val id = UUID.randomUUID()
        val session = PrepareCallTrackingSession().apply {
            rowsToReturn = listOf(fakeRow(mapOf("id" to id, "email" to "a@b.com")))
        }
        val executor = QueryExecutor(session, schema, StatementBuilder(session))
        val block: QueryContext.() -> Unit = { KandraColumnRef<String>("email") eq "a@b.com" }

        val page = executor.findPageSuspend(QepAccount::class, pageSize = 10, pageToken = null, block = block)

        assertEquals(1, page.items.size)
        assertEquals(0, session.blockingPrepareCount.get())
        // selectByLookupSuspend (lookup table) + session.prepareSuspend for the resolved primary-key SELECT.
        assertEquals(2, session.asyncPrepareCount.get())
    }
}
