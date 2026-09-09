package io.kandra.runtime

import com.datastax.oss.driver.api.core.DefaultConsistencyLevel
import io.kandra.core.KandraConsistency
import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.LookupIndex
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.annotations.SecondaryIndex
import io.kandra.core.annotations.SoftDelete
import io.kandra.runtime.dsl.KandraColumnRef
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

@ScyllaTable("qerc_items")
data class QercItem(@PartitionKey val id: UUID, val group: String)

@ScyllaTable("qerc_soft_delete_items")
@SoftDelete(markerProperty = "deleted")
data class QercSoftDeleteItem(
    @PartitionKey val id: UUID,
    @SecondaryIndex val deleted: Boolean = false
)

@ScyllaTable("qerc_lookup_items")
data class QercLookupItem(
    @PartitionKey val id: UUID,
    @LookupIndex(tableSuffix = "by_email") val email: String
)

/**
 * Regression coverage for GH #55 (ISS-054) and GH #67 (ISS-066).
 *
 * GH #55: `findAll`/`find`/`exists`/`findPage`'s direct-CQL, IN, and lookup-index-primary-select
 * branches previously executed with no consistency level at all (not even the driver's implicit
 * default was set explicitly), and had no per-call override parameter. These tests assert on the
 * actual consistency level [BatchEngineConsistencyPropagationTest]-style — via
 * [ScriptedCqlSession.lastBoundConsistencyLevel], which [fakeBoundStatement] populates from whatever
 * `.setConsistencyLevel(...)` call the code under test actually makes.
 *
 * GH #67: unpaged reads (`findAll`/`find`/`exists`'s IN and direct-CQL branches) had no row cap —
 * `resolveRows`/`resolveRowsSuspend` called `.all()`/`executeSuspendAll` unconditionally. These tests
 * assert the configured cap ([QueryExecutor]'s `maxUnpagedResultRows`) is actually enforced.
 */
class QueryExecutorConsistencyAndRowCapTest {

    @AfterEach
    fun tearDown() {
        SchemaRegistry.clear()
    }

    private fun row(id: UUID, group: String = "g1") = fakeRow(mapOf("id" to id, "group" to group))

    // ── GH #55: read consistency ─────────────────────────────────────────────

    @Test
    fun `findAll direct-CQL branch resolves to the configured default read consistency`() {
        val schema = SchemaRegistry.register(QercItem::class)
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Rows(listOf(row(UUID.randomUUID())))))
        val config = ConsistencyConfig().apply { defaultRead = KandraConsistency.LOCAL_QUORUM }
        val executor = QueryExecutor(session, schema, StatementBuilder(session, consistencyConfig = config))

        executor.findAll(QercItem::class) { KandraColumnRef<String>("group") eq "g1" }

        assertEquals(DefaultConsistencyLevel.LOCAL_QUORUM, session.lastBoundConsistencyLevel)
    }

    @Test
    fun `findAll honors a per-call consistency override over the configured default`() {
        val schema = SchemaRegistry.register(QercItem::class)
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Rows(listOf(row(UUID.randomUUID())))))
        val config = ConsistencyConfig().apply { defaultRead = KandraConsistency.LOCAL_QUORUM }
        val executor = QueryExecutor(session, schema, StatementBuilder(session, consistencyConfig = config))

        executor.findAll(QercItem::class, consistency = KandraConsistency.ALL) {
            KandraColumnRef<String>("group") eq "g1"
        }

        assertEquals(DefaultConsistencyLevel.ALL, session.lastBoundConsistencyLevel)
    }

    @Test
    fun `findAllSuspend direct-CQL branch resolves to the configured default read consistency`() = runBlocking {
        val schema = SchemaRegistry.register(QercItem::class)
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Rows(listOf(row(UUID.randomUUID())))))
        val config = ConsistencyConfig().apply { defaultRead = KandraConsistency.LOCAL_QUORUM }
        val executor = QueryExecutor(session, schema, StatementBuilder(session, consistencyConfig = config))

        executor.findAllSuspend(QercItem::class) { KandraColumnRef<String>("group") eq "g1" }

        assertEquals(DefaultConsistencyLevel.LOCAL_QUORUM, session.lastBoundConsistencyLevel)
    }

    @Test
    fun `exists direct-CQL branch resolves to the configured default read consistency`() {
        val schema = SchemaRegistry.register(QercItem::class)
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Rows(listOf(row(UUID.randomUUID())))))
        val config = ConsistencyConfig().apply { defaultRead = KandraConsistency.LOCAL_QUORUM }
        val executor = QueryExecutor(session, schema, StatementBuilder(session, consistencyConfig = config))

        executor.exists { KandraColumnRef<String>("group") eq "g1" }

        assertEquals(DefaultConsistencyLevel.LOCAL_QUORUM, session.lastBoundConsistencyLevel)
    }

    @Test
    fun `findPage primary-table select resolves to the configured default read consistency`() {
        val schema = SchemaRegistry.register(QercItem::class)
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Rows(listOf(row(UUID.randomUUID())))))
        val config = ConsistencyConfig().apply { defaultRead = KandraConsistency.LOCAL_QUORUM }
        val executor = QueryExecutor(session, schema, StatementBuilder(session, consistencyConfig = config))

        executor.findPage(QercItem::class, pageSize = 10, pageToken = null) {
            KandraColumnRef<String>("group") eq "g1"
        }

        assertEquals(DefaultConsistencyLevel.LOCAL_QUORUM, session.lastBoundConsistencyLevel)
    }

    // ── GH #67: row cap ──────────────────────────────────────────────────────

    @Test
    fun `findAll truncates to maxUnpagedResultRows when the query returns more`() {
        val schema = SchemaRegistry.register(QercItem::class)
        val manyRows = (1..10).map { row(UUID.randomUUID()) }
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Rows(manyRows)))
        val executor = QueryExecutor(session, schema, StatementBuilder(session), maxUnpagedResultRows = 3)

        val results = executor.findAll(QercItem::class) { KandraColumnRef<String>("group") eq "g1" }

        assertEquals(3, results.size)
    }

    @Test
    fun `findAll returns everything when under the cap`() {
        val schema = SchemaRegistry.register(QercItem::class)
        val fewRows = (1..2).map { row(UUID.randomUUID()) }
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Rows(fewRows)))
        val executor = QueryExecutor(session, schema, StatementBuilder(session), maxUnpagedResultRows = 3)

        val results = executor.findAll(QercItem::class) { KandraColumnRef<String>("group") eq "g1" }

        assertEquals(2, results.size)
    }

    @Test
    fun `findAllSuspend truncates to maxUnpagedResultRows when the query returns more`() = runBlocking {
        val schema = SchemaRegistry.register(QercItem::class)
        val manyRows = (1..10).map { row(UUID.randomUUID()) }
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Rows(manyRows)))
        val executor = QueryExecutor(session, schema, StatementBuilder(session), maxUnpagedResultRows = 3)

        val results = executor.findAllSuspend(QercItem::class) { KandraColumnRef<String>("group") eq "g1" }

        assertEquals(3, results.size)
    }

    // ── GH #94: findActive() row cap ──────────────────────────────────────────

    private fun softDeleteRow(id: UUID, deleted: Boolean = false) = fakeRow(mapOf("id" to id, "deleted" to deleted))

    @Test
    fun `findActive truncates to maxUnpagedResultRows when the query returns more`() {
        val schema = SchemaRegistry.register(QercSoftDeleteItem::class)
        val manyRows = (1..10).map { softDeleteRow(UUID.randomUUID()) }
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Rows(manyRows)))
        val executor = QueryExecutor(session, schema, StatementBuilder(session), maxUnpagedResultRows = 3)

        val results = executor.findActive(QercSoftDeleteItem::class)

        assertEquals(3, results.size)
    }

    @Test
    fun `findActiveSuspend truncates to maxUnpagedResultRows when the query returns more`() = runBlocking {
        val schema = SchemaRegistry.register(QercSoftDeleteItem::class)
        val manyRows = (1..10).map { softDeleteRow(UUID.randomUUID()) }
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Rows(manyRows)))
        val executor = QueryExecutor(session, schema, StatementBuilder(session), maxUnpagedResultRows = 3)

        val results = executor.findActiveSuspend(QercSoftDeleteItem::class)

        assertEquals(3, results.size)
    }

    @Test
    fun `findActive returns everything when under the cap`() {
        val schema = SchemaRegistry.register(QercSoftDeleteItem::class)
        val fewRows = (1..2).map { softDeleteRow(UUID.randomUUID()) }
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Rows(fewRows)))
        val executor = QueryExecutor(session, schema, StatementBuilder(session), maxUnpagedResultRows = 3)

        val results = executor.findActive(QercSoftDeleteItem::class)

        assertEquals(2, results.size)
    }

    // ── GH #96: lookup-index read hop consistency ─────────────────────────────

    @Test
    fun `findAll lookup-index branch resolves the configured default read consistency for the lookup hop`() {
        val schema = SchemaRegistry.register(QercLookupItem::class)
        // Lookup miss -- resolveRows returns before the primary-table hop, isolating the assertion
        // to the lookup-table SELECT's own consistency level.
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Rows(emptyList())))
        val config = ConsistencyConfig().apply { defaultRead = KandraConsistency.LOCAL_QUORUM }
        val executor = QueryExecutor(session, schema, StatementBuilder(session, consistencyConfig = config))

        val results = executor.findAll(QercLookupItem::class) { KandraColumnRef<String>("email") eq "a@b.com" }

        assertEquals(emptyList<QercLookupItem>(), results)
        assertEquals(DefaultConsistencyLevel.LOCAL_QUORUM, session.lastBoundConsistencyLevel)
    }

    @Test
    fun `findAllSuspend lookup-index branch resolves the configured default read consistency for the lookup hop`() = runBlocking {
        val schema = SchemaRegistry.register(QercLookupItem::class)
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Rows(emptyList())))
        val config = ConsistencyConfig().apply { defaultRead = KandraConsistency.LOCAL_QUORUM }
        val executor = QueryExecutor(session, schema, StatementBuilder(session, consistencyConfig = config))

        val results = executor.findAllSuspend(QercLookupItem::class) { KandraColumnRef<String>("email") eq "a@b.com" }

        assertEquals(emptyList<QercLookupItem>(), results)
        assertEquals(DefaultConsistencyLevel.LOCAL_QUORUM, session.lastBoundConsistencyLevel)
    }

    @Test
    fun `findAll lookup-index branch honors a per-call consistency override for the lookup hop`() {
        val schema = SchemaRegistry.register(QercLookupItem::class)
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Rows(emptyList())))
        val config = ConsistencyConfig().apply { defaultRead = KandraConsistency.LOCAL_QUORUM }
        val executor = QueryExecutor(session, schema, StatementBuilder(session, consistencyConfig = config))

        executor.findAll(QercLookupItem::class, consistency = KandraConsistency.ALL) {
            KandraColumnRef<String>("email") eq "a@b.com"
        }

        assertEquals(DefaultConsistencyLevel.ALL, session.lastBoundConsistencyLevel)
    }
}
