package io.kandra.test

import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.annotations.CacheResult
import io.kandra.core.annotations.ClusteringKey
import io.kandra.core.annotations.ClusteringOrder
import io.kandra.core.annotations.GeneratedUuid
import io.kandra.core.annotations.LookupIndex
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.annotations.SecondaryIndex
import io.kandra.core.annotations.SoftDelete
import io.kandra.core.annotations.Version
import io.kandra.core.exception.KandraOptimisticLockException
import io.kandra.core.exception.KandraQueryException
import io.kandra.core.exception.KandraSchemaException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
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

/**
 * Regression coverage for ISS-036 — `findActive()`'s marker column has a `@SecondaryIndex`, so the
 * query is answered by the index and no `ALLOW FILTERING` (nor `allowFullScan = true`) is required.
 */
@ScyllaTable("integration_indexed_widgets")
@SoftDelete(ttlSeconds = 2, markerProperty = "isDeleted")
data class IntegrationIndexedWidget(
    @PartitionKey val id: UUID,
    val name: String,
    @SecondaryIndex val isDeleted: Boolean = false
)

/**
 * Regression coverage for ISS-030 — `BatchEngine`'s soft-delete unconditionally deleted lookup-table
 * rows, contradicting the documented "soft delete does not remove lookup rows" behavior. A soft-deleted
 * row still "exists" until its TTL expires, so it must remain resolvable via its `@LookupIndex` too.
 */
@ScyllaTable("integration_soft_deleted_lookup")
@SoftDelete(ttlSeconds = 60, markerProperty = "isDeleted")
data class IntegrationSoftDeletedLookup(
    @PartitionKey val id: UUID,
    @LookupIndex(tableSuffix = "by_slug") val slug: String,
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

/**
 * Covers `@GeneratedUuid`: an `Instant`-derived clustering key silently overwrites same-millisecond
 * rows on save (Cassandra writes are upserts on the full key) — `@GeneratedUuid` avoids that by
 * populating the clustering key with a UUIDv7 instead, which sorts chronologically like the `Instant`
 * would have, without the collision risk. The placeholder value below is always overwritten by Kandra
 * on save; it exists only so the data class constructor doesn't require callers to supply one.
 */
@ScyllaTable("integration_generated_uuid_events")
data class IntegrationGeneratedUuidEvent(
    @PartitionKey val streamId: UUID,
    @GeneratedUuid @ClusteringKey val eventId: UUID = UUID(0, 0),
    val payload: String
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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KandraIntegrationTest {

    /**
     * One keyspace + DDL registration for every test in this class, not one per `@Test` method.
     * `@TestInstance(PER_CLASS)` makes JUnit construct this class exactly once, so this expensive
     * `freshKeyspace()` call (a keyspace CREATE plus CREATE TABLE for every registered entity/lookup
     * table) runs once instead of once per test method. With 10 entities now registered, running it
     * per-method (the JUnit default) meant ~17 keyspace-and-DDL setups per suite run — enough CQL churn
     * against a single shared Testcontainers container to trip `DriverTimeoutException` under CI's more
     * constrained resources. Tests share this keyspace; use random UUIDs / unique literal values per
     * test (already the existing convention here) to avoid cross-test collisions, since there's no
     * per-test isolation anymore.
     */
    private val db = KandraTestcontainers.freshKeyspace(
        IntegrationUser::class,
        IntegrationWidget::class,
        IntegrationIndexedWidget::class,
        IntegrationEvent::class,
        IntegrationCollections::class,
        IntegrationCached::class,
        IntegrationBatchPrimary::class,
        IntegrationBatchSecondary::class,
        IntegrationCachedClustered::class,
        IntegrationLookupClustered::class,
        IntegrationSoftDeletedLookup::class,
        IntegrationGeneratedUuidEvent::class
    )

    @AfterAll
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
        // The marker column has no @SecondaryIndex, so this requires an explicit opt-in — ISS-036.
        val active = repo.findActive(allowFullScan = true)
        assertFalse(active.any { it.id == widget.id })
    }

    // ── ISS-036: findActive()'s ALLOW FILTERING is now an explicit opt-in ──────────────────

    @Test
    fun `findActive without a secondary index throws unless allowFullScan is true`() = runBlocking {
        val repo = db.suspendRepository<IntegrationWidget>()
        val widget = IntegrationWidget(UUID.randomUUID(), "widget-no-index")
        repo.save(widget)

        // No @SecondaryIndex on `isDeleted` and no allowFullScan=true — must throw at call time,
        // not silently emit ALLOW FILTERING with just a WARN log.
        assertThrows(KandraQueryException::class.java) {
            runBlocking { repo.findActive() }
        }
        Unit
    }

    @Test
    fun `findActive with allowFullScan=true succeeds and still emits ALLOW FILTERING`() = runBlocking {
        val repo = db.suspendRepository<IntegrationWidget>()
        val widget = IntegrationWidget(UUID.randomUUID(), "widget-full-scan")
        repo.save(widget)

        val active = repo.findActive(allowFullScan = true)
        assertTrue(active.any { it.id == widget.id })
    }

    @Test
    fun `findActive on an entity with a secondary index succeeds without allowFullScan`() = runBlocking {
        val repo = db.suspendRepository<IntegrationIndexedWidget>()
        val widget = IntegrationIndexedWidget(UUID.randomUUID(), "widget-indexed")
        repo.save(widget)

        // Marker column has @SecondaryIndex — no ALLOW FILTERING needed, no opt-in required.
        val active = repo.findActive()
        assertTrue(active.any { it.id == widget.id })

        repo.delete(widget)
        val activeAfterDelete = repo.findActive()
        assertFalse(activeAfterDelete.any { it.id == widget.id })
    }

    @Test
    fun `graceful shutdown rejects new queries once isShuttingDown is set`() = runBlocking {
        // Uses its own isolated keyspace/runtime, not the shared `db` -- this test permanently flips
        // isShuttingDown on the runtime it uses, with no way to reset it, which would otherwise poison
        // every other test sharing `db` under the class's PER_CLASS instance lifecycle (JUnit doesn't
        // guarantee method execution order, so this could fail arbitrary other tests depending on when
        // it happens to run).
        val shutdownDb = KandraTestcontainers.freshKeyspace(IntegrationUser::class)
        try {
            val repo = shutdownDb.suspendRepository<IntegrationUser>()
            val user = IntegrationUser(UUID.randomUUID(), "dana@example.com")
            repo.save(user)
            assertEquals(0, shutdownDb.runtime.inFlightCount.get())

            shutdownDb.runtime.isShuttingDown.set(true)
            assertThrows(KandraQueryException::class.java) {
                runBlocking { repo.findById(user.id) }
            }
        } finally {
            shutdownDb.close()
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

    // ── @GeneratedUuid: avoids same-millisecond clustering-key collisions on upsert ─────────

    @Test
    fun `GeneratedUuid populates the clustering key on save, ignoring the placeholder value`() = runBlocking {
        val repo = db.suspendRepository<IntegrationGeneratedUuidEvent>()
        val streamId = UUID.randomUUID()
        repo.save(IntegrationGeneratedUuidEvent(streamId, payload = "only-row"))

        val rows = repo.findAll { IntegrationGeneratedUuidEventTable.streamId eq streamId }
        assertEquals(1, rows.size)
        assertEquals("only-row", rows.first().payload)
        assertNotEquals(UUID(0, 0), rows.first().eventId)
        assertEquals(7, rows.first().eventId.version())
    }

    @Test
    fun `GeneratedUuid clustering keys sort chronologically even when written in the same millisecond`() = runBlocking {
        val repo = db.suspendRepository<IntegrationGeneratedUuidEvent>()
        val streamId = UUID.randomUUID()

        // Both saves target the same partition back-to-back — an Instant-derived clustering key
        // would risk landing in the same millisecond and one row silently overwriting the other.
        repo.save(IntegrationGeneratedUuidEvent(streamId, payload = "first"))
        repo.save(IntegrationGeneratedUuidEvent(streamId, payload = "second"))

        val rows = repo.findAll { IntegrationGeneratedUuidEventTable.streamId eq streamId }
        assertEquals(2, rows.size)
        assertEquals(listOf("first", "second"), rows.map { it.payload })
        assertTrue(rows[0].eventId < rows[1].eventId)
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

        val foundByOlderSlug = repo.find { IntegrationLookupClusteredTable.slug eq "older-slug" }
        assertNotNull(foundByOlderSlug)
        assertEquals("older content", foundByOlderSlug!!.content)
        // Compare via epoch millis, not raw Instant equality -- CQL TIMESTAMP columns only store
        // millisecond precision, so the pre-save Instant.now() (sub-millisecond) never .equals() the
        // round-tripped value read back through the lookup, even though it's the same logical row.
        assertEquals(older.createdAt.toEpochMilli(), foundByOlderSlug.createdAt.toEpochMilli())

        val foundByNewerSlug = repo.find { IntegrationLookupClusteredTable.slug eq "newer-slug" }
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

        val page = repo.findPage(20, null) { IntegrationLookupClusteredTable.slug eq "page-slug-b" }
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

        repo.deleteBy { IntegrationLookupClusteredTable.slug eq "remove-slug" }

        assertNotNull(repo.findById(ownerId, keep.createdAt))
        assertNull(repo.findById(ownerId, remove.createdAt))
    }

    // ── ISS-030 regression: soft-delete unconditionally deleted lookup-table rows ──────────────

    @Test
    fun `soft-deleting a row does not remove its LookupIndex row`() = runBlocking {
        val repo = db.suspendRepository<IntegrationSoftDeletedLookup>()
        val entity = IntegrationSoftDeletedLookup(UUID.randomUUID(), "keep-findable")
        repo.save(entity)

        repo.delete(entity) // soft delete -- ttlSeconds = 60, well past this test's runtime

        // The lookup row must still resolve the (soft-deleted) entity, not disappear immediately.
        val foundViaLookup = repo.find { IntegrationSoftDeletedLookupTable.slug eq "keep-findable" }
        assertNotNull(foundViaLookup)
        assertTrue(foundViaLookup!!.isDeleted)
    }
}
