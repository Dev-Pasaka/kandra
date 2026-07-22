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
 * Regression coverage for ISS-028 — cache invalidation on save/update/etc. used a partition-key-only
 * cache key (`partitionKeyOf`) that never matched `findById`'s real cache key (full key: partition +
 * clustering) for any clustering-keyed cached entity, so invalidation always silently missed.
 */
@ScyllaTable("integration_cached_clustered")
@CacheResult(ttlSeconds = 60, maxSize = 100)
data class IntegrationCachedClustered(
    @PartitionKey val id: UUID,
    @ClusteringKey(order = ClusteringOrder.DESC) val createdAt: Instant,
    val value: String
)

/**
 * Regression coverage for ISS-029 — `@LookupIndex` resolution (`find`/`findAll`/`findPage`/`exists`/
 * `deleteBy` via a lookup predicate) only ever reconstructed the primary table's partition key from the
 * lookup row, never its clustering key, so it broke entirely for any entity combining both a
 * `@LookupIndex` and a clustering key once `selectById` started requiring the full key (ISS-025).
 */
@ScyllaTable("integration_lookup_clustered")
data class IntegrationLookupClustered(
    @PartitionKey val ownerId: UUID,
    @ClusteringKey(order = ClusteringOrder.DESC) val createdAt: Instant,
    @io.kandra.core.annotations.LookupIndex(tableSuffix = "by_slug") val slug: String,
    val content: String
)

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
        IntegrationBatchSecondary::class,
        IntegrationCachedClustered::class,
        IntegrationLookupClustered::class
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

    // ── ISS-028 regression: cache invalidation used a partition-key-only key, never matching
    // findById's real (full-key) cache key for a clustering-keyed cached entity ──────────────────

    @Test
    fun `update invalidates the cache for a clustering-keyed cached entity`() = runBlocking {
        val repo = db.suspendRepository<IntegrationCachedClustered>()
        val entity = IntegrationCachedClustered(UUID.randomUUID(), Instant.now(), "original")
        repo.save(entity)

        // Use the round-tripped entity (from findById) for every subsequent key reference, not the raw
        // pre-save Instant.now() -- CQL TIMESTAMP columns only store millisecond precision, so the raw
        // sub-millisecond-precision Instant and the round-tripped one are never .equals(). A real caller
        // always operates on the round-tripped value from here on, so this test does too.
        val first = repo.findById(entity.id, entity.createdAt) // populates the cache under the full key
        assertEquals("original", first?.value)

        repo.update(first!!, first.copy(value = "updated"))

        val second = repo.findById(first.id, first.createdAt) // must be a fresh read, not a stale cache hit
        assertEquals("updated", second?.value)
    }

    @Test
    fun `updateForce and delete also invalidate the cache for a clustering-keyed cached entity`() = runBlocking {
        val repo = db.suspendRepository<IntegrationCachedClustered>()
        val entity = IntegrationCachedClustered(UUID.randomUUID(), Instant.now(), "v1")
        repo.save(entity)
        repo.findById(entity.id, entity.createdAt) // populate cache

        repo.updateForce(entity.copy(value = "v2"))
        assertEquals("v2", repo.findById(entity.id, entity.createdAt)?.value)

        repo.findById(entity.id, entity.createdAt) // repopulate cache
        repo.delete(entity.copy(value = "v2"))
        assertNull(repo.findById(entity.id, entity.createdAt))
    }

    // ── ISS-029 regression: @LookupIndex resolution broke entirely for clustering-keyed entities ──

    @Test
    fun `find via LookupIndex works on an entity that also has a clustering key`() = runBlocking {
        val repo = db.suspendRepository<IntegrationLookupClustered>()
        val ownerId = UUID.randomUUID()
        val older = IntegrationLookupClustered(ownerId, Instant.now().minusSeconds(60), "older-slug", "older content")
        val newer = IntegrationLookupClustered(ownerId, Instant.now(), "newer-slug", "newer content")
        repo.save(older)
        repo.save(newer)

        val foundByOlderSlug = repo.find { +IntegrationLookupClusteredTable.slug.eq("older-slug") }
        assertNotNull(foundByOlderSlug)
        assertEquals("older content", foundByOlderSlug!!.content)
        // Compare via epoch millis, not raw Instant equality -- CQL TIMESTAMP columns only store
        // millisecond precision, so the pre-save Instant.now() (sub-millisecond) never .equals() the
        // round-tripped value read back through the lookup, even though it's the same logical row.
        assertEquals(older.createdAt.toEpochMilli(), foundByOlderSlug.createdAt.toEpochMilli())

        val foundByNewerSlug = repo.find { +IntegrationLookupClusteredTable.slug.eq("newer-slug") }
        assertNotNull(foundByNewerSlug)
        assertEquals("newer content", foundByNewerSlug!!.content)
    }

    @Test
    fun `findPage via LookupIndex resolves exactly the one matching row on a clustering-keyed entity`() = runBlocking {
        val repo = db.suspendRepository<IntegrationLookupClustered>()
        val ownerId = UUID.randomUUID()
        val a = IntegrationLookupClustered(ownerId, Instant.now().minusSeconds(120), "page-slug-a", "content-a")
        val b = IntegrationLookupClustered(ownerId, Instant.now().minusSeconds(60), "page-slug-b", "content-b")
        repo.save(a)
        repo.save(b)

        val page = repo.findPage(20, null) { +IntegrationLookupClusteredTable.slug.eq("page-slug-b") }
        assertEquals(1, page.items.size)
        assertEquals("content-b", page.items.first().content)
        assertFalse(page.hasMore)
    }

    @Test
    fun `deleteBy via LookupIndex deletes only the matching row on a clustering-keyed entity`() = runBlocking {
        val repo = db.suspendRepository<IntegrationLookupClustered>()
        val ownerId = UUID.randomUUID()
        val keep = IntegrationLookupClustered(ownerId, Instant.now().minusSeconds(60), "keep-slug", "keep")
        val remove = IntegrationLookupClustered(ownerId, Instant.now(), "remove-slug", "remove")
        repo.save(keep)
        repo.save(remove)

        repo.deleteBy { +IntegrationLookupClusteredTable.slug.eq("remove-slug") }

        assertNotNull(repo.findById(ownerId, keep.createdAt))
        assertNull(repo.findById(ownerId, remove.createdAt))
    }
}
