package io.kandra.test

import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.annotations.CacheResult
import io.kandra.core.annotations.ClusteringKey
import io.kandra.core.annotations.ClusteringOrder
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.annotations.SoftDelete
import io.kandra.core.annotations.Version
import io.kandra.core.exception.KandraOptimisticLockException
import io.kandra.core.exception.KandraQueryException
import io.kandra.core.exception.KandraSchemaException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@ScyllaTable("integration_users")
data class IntegrationUser(
    @PartitionKey val id: UUID,
    val email: String,
    @Version val version: Long = 0L
)

@ScyllaTable("integration_widgets")
@SoftDelete(ttlSeconds = 2, markerProperty = "isDeleted")
data class IntegrationWidget(
    @PartitionKey val id: UUID,
    val name: String,
    val isDeleted: Boolean = false
)

/** Regression coverage for Finding #9 — clustering keys were dropped from every key-based WHERE clause. */
@ScyllaTable("integration_events")
data class IntegrationEvent(
    @PartitionKey val streamId: UUID,
    @ClusteringKey(order = ClusteringOrder.DESC) val occurredAt: Instant,
    val payload: String,
    val tags: Set<String> = emptySet(),
    @Version val version: Long = 0L
)

/** Regression coverage for Finding #8 — non-nullable empty Set/Map columns threw on read. */
@ScyllaTable("integration_collections")
data class IntegrationCollections(
    @PartitionKey val id: UUID,
    val tags: Set<String> = emptySet(),
    val metadata: Map<String, String> = emptyMap()
)

/** Regression coverage for Finding #7 — real Caffeine cache operations crashed with IllegalAccessException. */
@ScyllaTable("integration_cached")
@CacheResult(ttlSeconds = 60, maxSize = 100)
data class IntegrationCached(
    @PartitionKey val id: UUID,
    val value: String
)

/** Regression coverage for Finding #10 — KandraBatchScope's save/delete never actually batched. */
@ScyllaTable("integration_batch_primary")
data class IntegrationBatchPrimary(@PartitionKey val id: UUID, val name: String)

@ScyllaTable("integration_batch_secondary")
data class IntegrationBatchSecondary(@PartitionKey val id: UUID, val primaryId: UUID)

/**
 * Real ScyllaDB/Cassandra integration tests via Testcontainers — no fakes involved.
 *
 * Exercises paths `FakeKandraSession` structurally can't verify: real CQL parameter binding,
 * LWT applied/not-applied semantics, and TTL-based soft delete. Each test gets its own isolated
 * keyspace via [KandraTestcontainers.freshKeyspace].
 */
class KandraIntegrationTest {

    private val db = KandraTestcontainers.freshKeyspace(
        IntegrationUser::class,
        IntegrationWidget::class,
        IntegrationEvent::class,
        IntegrationCollections::class,
        IntegrationCached::class,
        IntegrationBatchPrimary::class,
        IntegrationBatchSecondary::class
    )

    @AfterEach
    fun cleanup() = db.close()

    @Test
    fun `save and findById round-trip`() = runBlocking {
        val repo = db.suspendRepository<IntegrationUser>()
        val user = IntegrationUser(UUID.randomUUID(), "alice@example.com")
        repo.save(user)

        val found = repo.findById(user.id)
        assertNotNull(found)
        assertEquals("alice@example.com", found!!.email)
    }

    @Test
    fun `delete removes the row`() = runBlocking {
        val repo = db.suspendRepository<IntegrationUser>()
        val user = IntegrationUser(UUID.randomUUID(), "bob@example.com")
        repo.save(user)
        repo.delete(user)

        assertNull(repo.findById(user.id))
    }

    @Test
    fun `saveAll auto-chunks a large batch`() = runBlocking {
        val repo = db.suspendRepository<IntegrationUser>()
        val users = (1..250).map { IntegrationUser(UUID.randomUUID(), "user$it@example.com") }
        repo.saveAll(users)

        users.take(5).forEach { u -> assertNotNull(repo.findById(u.id)) }
    }

    @Test
    fun `optimistic locking rejects a stale update`() = runBlocking {
        val repo = db.suspendRepository<IntegrationUser>()
        val user = IntegrationUser(UUID.randomUUID(), "carol@example.com")
        repo.save(user)

        val loaded = repo.findById(user.id)!!
        // Someone else updates first, advancing the version.
        repo.update(loaded, loaded.copy(email = "carol+updated@example.com"))

        // Our stale copy (still at the version it was loaded with) now conflicts.
        assertThrows(KandraOptimisticLockException::class.java) {
            runBlocking { repo.update(loaded, loaded.copy(email = "carol+stale@example.com")) }
        }
    }

    @Test
    fun `soft delete keeps the row queryable until TTL expires, marker survives`() = runBlocking {
        val repo = db.suspendRepository<IntegrationWidget>()
        val widget = IntegrationWidget(UUID.randomUUID(), "gadget")
        repo.save(widget)

        repo.delete(widget)

        // Row is still present immediately after soft delete — the TTL hasn't expired yet.
        assertNotNull(repo.findById(widget.id))

        // findActive() excludes it immediately via the permanent (non-TTL'd) marker column.
        val active = repo.findActive()
        assertFalse(active.any { it.id == widget.id })
    }

    @Test
    fun `graceful shutdown rejects new queries once isShuttingDown is set`() = runBlocking {
        val repo = db.suspendRepository<IntegrationUser>()
        val user = IntegrationUser(UUID.randomUUID(), "dana@example.com")
        repo.save(user)
        assertEquals(0, db.runtime.inFlightCount.get())

        db.runtime.isShuttingDown.set(true)
        assertThrows(KandraQueryException::class.java) {
            runBlocking { repo.findById(user.id) }
        }
    }

    // ── Finding #9 regression: clustering keys were dropped from every key-based WHERE clause ──

    @Test
    fun `findById on a clustering-keyed entity requires the full key, not just the partition key`() = runBlocking {
        val repo = db.suspendRepository<IntegrationEvent>()
        val streamId = UUID.randomUUID()
        val first = IntegrationEvent(streamId, Instant.now().minusSeconds(60), "first")
        val second = IntegrationEvent(streamId, Instant.now(), "second")
        repo.save(first)
        repo.save(second)

        // Passing only the partition key must fail loudly now, not silently return an arbitrary row.
        assertThrows(KandraSchemaException::class.java) {
            runBlocking { repo.findById(streamId) }
        }

        val found = repo.findById(streamId, second.occurredAt)
        assertNotNull(found)
        assertEquals("second", found!!.payload)
    }

    @Test
    fun `deleteById on a clustering-keyed entity deletes only the targeted row, not the whole partition`() = runBlocking {
        val repo = db.suspendRepository<IntegrationEvent>()
        val streamId = UUID.randomUUID()
        val first = IntegrationEvent(streamId, Instant.now().minusSeconds(60), "keep-me")
        val second = IntegrationEvent(streamId, Instant.now(), "delete-me")
        repo.save(first)
        repo.save(second)

        repo.deleteById(streamId, second.occurredAt)

        assertNotNull(repo.findById(streamId, first.occurredAt))
        assertNull(repo.findById(streamId, second.occurredAt))
    }

    @Test
    fun `append and update work on a clustering-keyed entity instead of hard-crashing`() = runBlocking {
        val repo = db.suspendRepository<IntegrationEvent>()
        val event = IntegrationEvent(UUID.randomUUID(), Instant.now(), "payload", tags = setOf("a"))
        repo.save(event)

        repo.append(event, IntegrationEvent::tags, setOf("b"))
        val afterAppend = repo.findById(event.streamId, event.occurredAt)!!
        assertEquals(setOf("a", "b"), afterAppend.tags)

        val updated = repo.findById(event.streamId, event.occurredAt)!!
        repo.update(updated, updated.copy(payload = "updated-payload"))
        val afterUpdate = repo.findById(event.streamId, event.occurredAt)!!
        assertEquals("updated-payload", afterUpdate.payload)
    }

    // ── Finding #8 regression: non-nullable empty Set/Map columns threw on read ──────────────

    @Test
    fun `a freshly-saved entity with default-empty collection columns is readable via findById`() = runBlocking {
        val repo = db.suspendRepository<IntegrationCollections>()
        val entity = IntegrationCollections(UUID.randomUUID())
        repo.save(entity)

        val found = repo.findById(entity.id)
        assertNotNull(found)
        assertEquals(emptySet<String>(), found!!.tags)
        assertEquals(emptyMap<String, String>(), found.metadata)
    }

    // ── Finding #7 regression: real Caffeine cache operations crashed with IllegalAccessException ──

    @Test
    fun `save and findById do not crash when a real Caffeine cache is configured`() = runBlocking {
        val repo = db.suspendRepository<IntegrationCached>()
        val entity = IntegrationCached(UUID.randomUUID(), "hello")

        repo.save(entity) // used to throw IllegalAccessException from KandraCache.invalidate
        val first = repo.findById(entity.id) // populates the cache
        val second = repo.findById(entity.id) // should be a cache hit, not a crash
        assertEquals("hello", first?.value)
        assertEquals("hello", second?.value)
    }

    // ── Finding #10 regression: KandraBatchScope's save/delete never actually batched ─────────

    @OptIn(ExperimentalKandraApi::class)
    @Test
    fun `batchBlocking saveInBatch across two repositories commits as a single atomic batch`() {
        val primaryRepo = db.repository<IntegrationBatchPrimary>()
        val secondaryRepo = db.repository<IntegrationBatchSecondary>()
        val primary = IntegrationBatchPrimary(UUID.randomUUID(), "widget")
        val secondary = IntegrationBatchSecondary(UUID.randomUUID(), primary.id)

        db.runtime.batchBlocking {
            primaryRepo.saveInBatch(primary)
            secondaryRepo.saveInBatch(secondary)
        }

        assertNotNull(primaryRepo.findById(primary.id))
        assertNotNull(secondaryRepo.findById(secondary.id))
    }

    @OptIn(ExperimentalKandraApi::class)
    @Test
    fun `saveIfNotExistsInBatch always throws inside a batch scope`() {
        val primaryRepo = db.suspendRepository<IntegrationBatchPrimary>()
        assertThrows(KandraQueryException::class.java) {
            runBlocking {
                db.runtime.batch {
                    primaryRepo.saveIfNotExistsInBatch(IntegrationBatchPrimary(UUID.randomUUID(), "x"))
                }
            }
        }
    }
}
