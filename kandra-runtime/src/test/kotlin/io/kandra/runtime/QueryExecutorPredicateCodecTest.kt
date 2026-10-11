package io.kandra.runtime

import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.annotations.SecondaryIndex
import io.kandra.runtime.dsl.KandraColumnRef
import io.kandra.runtime.dsl.QueryContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

private enum class PcStatus { ACTIVE, SUSPENDED }

@ScyllaTable("pc_users")
private data class PcUser(
    @PartitionKey val id: UUID,
    @SecondaryIndex val status: PcStatus
)

/**
 * Regression coverage for GH #144: [QueryExecutor.buildWhere] bound predicate values raw, with no
 * [io.kandra.runtime.codec.KandraCodec.encode] step -- unlike key-column binding elsewhere in this
 * class and in [StatementBuilder], which already goes through the codec. Any predicate on a column
 * type the driver has no built-in codec for (an enum, confirmed here; any type registered via
 * [io.kandra.runtime.codec.KandraCodec.registerEncoder] would fail the same way) threw
 * `CodecNotFoundException` at bind time against a real cluster -- confirmed live against ScyllaDB
 * Cloud during the v3.0 test round. `status` here is a plain (non-key, non-indexed) column so the
 * predicate takes [QueryExecutor]'s direct-CQL branch, exactly the path `buildWhere` builds.
 *
 * Also covers GH #146: an `IN` predicate on `status` (the @SecondaryIndex column) exercises
 * [QueryExecutor.resolveRows]/[QueryExecutor.resolveRowsSuspend]'s IN-on-partition-key-vs-IN-on-
 * secondary-index routing -- see the tests below for the full explanation.
 */
class QueryExecutorPredicateCodecTest {

    @AfterEach
    fun tearDown() {
        SchemaRegistry.clear()
    }

    @Test
    fun `findAll eq predicate on an enum column binds the encoded name, not the raw enum instance`() {
        val schema = SchemaRegistry.register(PcUser::class)
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Rows(emptyList())))
        val executor = QueryExecutor(session, schema, StatementBuilder(session))
        val block: QueryContext.() -> Unit = { KandraColumnRef<PcStatus>("status") eq PcStatus.ACTIVE }

        val results = executor.findAll(PcUser::class, block = block)

        assertEquals(emptyList<PcUser>(), results)
        assertEquals(listOf("ACTIVE"), session.lastBoundValues, "Expected the enum's .name to be bound, not the raw PcStatus instance")
    }

    @Test
    fun `findAllSuspend eq predicate on an enum column binds the encoded name, not the raw enum instance`() = runBlocking {
        val schema = SchemaRegistry.register(PcUser::class)
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Rows(emptyList())))
        val executor = QueryExecutor(session, schema, StatementBuilder(session))
        val block: QueryContext.() -> Unit = { KandraColumnRef<PcStatus>("status") eq PcStatus.ACTIVE }

        val results = executor.findAllSuspend(PcUser::class, block = block)

        assertEquals(emptyList<PcUser>(), results)
        assertEquals(listOf("ACTIVE"), session.lastBoundValues, "Expected the enum's .name to be bound, not the raw PcStatus instance")
    }

    @Test
    fun `IN predicate on an enum column encodes every value in the list`() {
        val schema = SchemaRegistry.register(PcUser::class)
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Rows(emptyList())))
        val executor = QueryExecutor(session, schema, StatementBuilder(session))
        val block: QueryContext.() -> Unit = { KandraColumnRef<PcStatus>("status") isIn listOf(PcStatus.ACTIVE, PcStatus.SUSPENDED) }

        executor.findAll(PcUser::class, block = block)

        assertEquals(listOf("ACTIVE", "SUSPENDED"), session.lastBoundValues)
    }

    @Test
    fun `IN predicate on a @SecondaryIndex column queries that column, not the partition key`() {
        // Regression coverage for GH #146: resolveRows() validated whether the IN predicate's
        // column was the partition key or a @SecondaryIndex column, but then took the
        // selectByPartitionKeyIn() shortcut unconditionally either way. That path hardcodes
        // schema.partitionKeys.first() for both the generated CQL column name and the value
        // encoding -- so `status isIn [...]` silently generated `WHERE id IN ?` with the status
        // values encoded as UUIDs, instead of `WHERE status IN (?, ...)`. Assert the generated
        // CQL actually names the secondary-index column, not the partition key.
        val schema = SchemaRegistry.register(PcUser::class)
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Rows(emptyList())))
        val executor = QueryExecutor(session, schema, StatementBuilder(session))
        val block: QueryContext.() -> Unit = { KandraColumnRef<PcStatus>("status") isIn listOf(PcStatus.ACTIVE, PcStatus.SUSPENDED) }

        executor.findAll(PcUser::class, block = block)

        val cql = session.lastPreparedCql ?: error("Expected a prepared statement")
        assertTrue(cql.contains("status"), "Expected the generated CQL to filter on 'status', got: $cql")
        assertTrue(!cql.contains(Regex("""\bid\b""")), "Expected the generated CQL to NOT filter on the partition key 'id', got: $cql")
        assertEquals(listOf("ACTIVE", "SUSPENDED"), session.lastBoundValues)
    }

    @Test
    fun `findAllSuspend IN predicate on a @SecondaryIndex column queries that column, not the partition key`() = runBlocking {
        // Suspend-path twin of the above -- resolveRowsSuspend() had the identical bug.
        val schema = SchemaRegistry.register(PcUser::class)
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Rows(emptyList())))
        val executor = QueryExecutor(session, schema, StatementBuilder(session))
        val block: QueryContext.() -> Unit = { KandraColumnRef<PcStatus>("status") isIn listOf(PcStatus.ACTIVE, PcStatus.SUSPENDED) }

        executor.findAllSuspend(PcUser::class, block = block)

        val cql = session.lastPreparedCql ?: error("Expected a prepared statement")
        assertTrue(cql.contains("status"), "Expected the generated CQL to filter on 'status', got: $cql")
        assertTrue(!cql.contains(Regex("""\bid\b""")), "Expected the generated CQL to NOT filter on the partition key 'id', got: $cql")
        assertEquals(listOf("ACTIVE", "SUSPENDED"), session.lastBoundValues)
    }

    @Test
    fun `IN predicate on the partition key still takes the selectByPartitionKeyIn shortcut`() {
        // Make sure GH #146's fix didn't also break the genuine partition-key case it's supposed
        // to leave alone.
        val schema = SchemaRegistry.register(PcUser::class)
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Rows(emptyList())))
        val executor = QueryExecutor(session, schema, StatementBuilder(session))
        val ids = listOf(UUID.randomUUID(), UUID.randomUUID())
        val block: QueryContext.() -> Unit = { KandraColumnRef<UUID>("id") isIn ids }

        executor.findAll(PcUser::class, block = block)

        val cql = session.lastPreparedCql ?: error("Expected a prepared statement")
        assertTrue(cql.contains("id"), "Expected the generated CQL to filter on 'id', got: $cql")
        assertTrue(!cql.contains("status"), "Expected the generated CQL to NOT filter on 'status', got: $cql")
    }

    @Test
    fun `eq predicate on an unresolvable column name falls back to the raw value instead of throwing`() {
        // A hand-built KandraColumnRef against a name the schema doesn't recognize -- proves a
        // resolution miss degrades gracefully (raw value passed through) rather than failing the
        // whole query, matching this fix's documented fallback behavior.
        val schema = SchemaRegistry.register(PcUser::class)
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Rows(emptyList())))
        val executor = QueryExecutor(session, schema, StatementBuilder(session))
        val block: QueryContext.() -> Unit = { KandraColumnRef<String>("not_a_real_column") eq "whatever" }

        executor.findAll(PcUser::class, block = block)

        assertEquals(listOf("whatever"), session.lastBoundValues)
    }
}
