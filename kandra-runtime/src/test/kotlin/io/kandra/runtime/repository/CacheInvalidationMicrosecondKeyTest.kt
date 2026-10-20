package io.kandra.runtime.repository

import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.CacheResult
import io.kandra.core.annotations.ClusteringKey
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
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@ScyllaTable("cache_events")
@CacheResult(ttlSeconds = 60, maxSize = 1000)
data class CacheEvent(
    @PartitionKey val ownerId: UUID,
    @ClusteringKey val createdAt: Instant,
    val title: String
)

/**
 * GH #178: a caller that holds a microsecond-precision `Instant` (e.g. `Instant.now()` echoed back by
 * an earlier API response) must not defeat cache invalidation -- the stored row only has millisecond
 * precision, so writes invalidate under the millisecond key.
 */
class CacheInvalidationMicrosecondKeyTest {

    @AfterEach
    fun tearDown() {
        SchemaRegistry.clear()
    }

    private fun unconfinedScope() = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private val owner = UUID.randomUUID()
    private val micros: Instant = Instant.parse("2026-10-20T11:07:27.733837Z")
    private val millis: Instant = micros.truncatedTo(ChronoUnit.MILLIS)

    private fun row(title: String) = fakeRow(mapOf("owner_id" to owner, "created_at" to millis, "title" to title))

    // Executes: findById (Rows) -> write (Applied) -> findById (Rows)
    private fun session() = ScriptedCqlSession(
        listOf(ExecuteOutcome.Rows(listOf(row("old"))), ExecuteOutcome.Applied(true), ExecuteOutcome.Rows(listOf(row("new"))))
    )

    @Test
    fun `blocking save invalidates an entry cached under a microsecond key`() {
        val schema = SchemaRegistry.register(CacheEvent::class)
        val session = session()
        val repo = KandraRepository(session, schema, CacheEvent::class, BatchEngine(session, StatementBuilder(session), unconfinedScope()))

        assertEquals("old", repo.findById(owner, micros)?.title)
        assertEquals("old", repo.findById(owner, micros)?.title)
        assertEquals(1, session.executeCount.get(), "second read is served from the cache")

        repo.save(CacheEvent(owner, millis, "new"))   // entity as read back from the DB: millisecond precision

        assertEquals("new", repo.findById(owner, micros)?.title, "stale cached entry must have been invalidated")
    }

    @Test
    fun `suspend save invalidates an entry cached under a microsecond key`() = runBlocking {
        val schema = SchemaRegistry.register(CacheEvent::class)
        val session = session()
        val repo = KandraSuspendRepository(session, schema, CacheEvent::class, BatchEngine(session, StatementBuilder(session), unconfinedScope()))

        assertEquals("old", repo.findById(owner, micros)?.title)
        repo.save(CacheEvent(owner, millis, "new"))

        assertEquals("new", repo.findById(owner, micros)?.title, "stale cached entry must have been invalidated")
    }

    @Test
    fun `deleteById with a microsecond key invalidates the millisecond-keyed entry`() {
        val schema = SchemaRegistry.register(CacheEvent::class)
        val session = ScriptedCqlSession(
            // findById, then deleteById's own lookup + delete batch, then the final findById
            listOf(
                ExecuteOutcome.Rows(listOf(row("old"))),
                ExecuteOutcome.Rows(listOf(row("old"))),
                ExecuteOutcome.Applied(true),
                ExecuteOutcome.Rows(listOf(row("again"))),
            )
        )
        val repo = KandraRepository(session, schema, CacheEvent::class, BatchEngine(session, StatementBuilder(session), unconfinedScope()))

        assertEquals("old", repo.findById(owner, millis)?.title)
        repo.deleteById(owner, micros)
        assertEquals("again", repo.findById(owner, millis)?.title)
    }
}
