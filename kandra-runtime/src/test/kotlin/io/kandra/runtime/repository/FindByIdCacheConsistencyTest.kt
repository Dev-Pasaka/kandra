package io.kandra.runtime.repository

import io.kandra.core.KandraConsistency
import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.CacheResult
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.runtime.BatchEngine
import io.kandra.runtime.ExecuteOutcome
import io.kandra.runtime.ScriptedCqlSession
import io.kandra.runtime.StatementBuilder
import io.kandra.runtime.fakeRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

@ScyllaTable("cache_widgets")
@CacheResult(ttlSeconds = 60, maxSize = 1000)
data class CacheWidget(
    @PartitionKey val id: UUID,
    val name: String
)

/**
 * GH #95: a `findById(consistency = ...)` caller-supplied override must reach Scylla even when
 * the entity has `@CacheResult` and the id is already cached -- a cache hit previously ignored
 * `consistency` entirely, since only the miss path threaded it through to [io.kandra.runtime.QueryExecutor].
 */
class FindByIdCacheConsistencyTest {

    @AfterEach
    fun tearDown() {
        SchemaRegistry.clear()
    }

    private fun unconfinedScope() = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    @Test
    fun `blocking findById with a consistency override bypasses the cache and hits the DB`() {
        val schema = SchemaRegistry.register(CacheWidget::class)
        val id = UUID.randomUUID()
        val row = fakeRow(mapOf("id" to id, "name" to "n1"))
        // Two DB round trips expected: the initial cache-populating read, then the
        // consistency-override read that must bypass the now-populated cache.
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Rows(listOf(row)), ExecuteOutcome.Rows(listOf(row))))
        val engine = BatchEngine(session, StatementBuilder(session), unconfinedScope())
        val repo = KandraRepository(session, schema, CacheWidget::class, engine)

        repo.findById(id)
        assertEquals(1, session.executeCount.get(), "first call should populate the cache from the DB")

        repo.findById(id)
        assertEquals(1, session.executeCount.get(), "second call should be served from the cache")

        repo.findById(id, consistency = KandraConsistency.LOCAL_QUORUM)
        assertEquals(2, session.executeCount.get(), "consistency override must bypass the cache and hit the DB")

        repo.findById(id)
        assertEquals(2, session.executeCount.get(), "the override read must not have clobbered the cached value")
    }

    @Test
    fun `suspend findById with a consistency override bypasses the cache and hits the DB`() = runBlocking {
        val schema = SchemaRegistry.register(CacheWidget::class)
        val id = UUID.randomUUID()
        val row = fakeRow(mapOf("id" to id, "name" to "n1"))
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Rows(listOf(row)), ExecuteOutcome.Rows(listOf(row))))
        val engine = BatchEngine(session, StatementBuilder(session), unconfinedScope())
        val repo = KandraSuspendRepository(session, schema, CacheWidget::class, engine)

        repo.findById(id)
        assertEquals(1, session.executeCount.get(), "first call should populate the cache from the DB")

        repo.findById(id)
        assertEquals(1, session.executeCount.get(), "second call should be served from the cache")

        repo.findById(id, consistency = KandraConsistency.LOCAL_QUORUM)
        assertEquals(2, session.executeCount.get(), "consistency override must bypass the cache and hit the DB")

        repo.findById(id)
        assertEquals(2, session.executeCount.get(), "the override read must not have clobbered the cached value")
    }
}
