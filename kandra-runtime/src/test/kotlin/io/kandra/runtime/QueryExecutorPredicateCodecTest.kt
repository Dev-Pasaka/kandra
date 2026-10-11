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
