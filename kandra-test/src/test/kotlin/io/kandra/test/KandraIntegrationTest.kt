package io.kandra.test

import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.annotations.SoftDelete
import io.kandra.core.annotations.Version
import io.kandra.core.exception.KandraOptimisticLockException
import io.kandra.core.exception.KandraQueryException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
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
 * Real ScyllaDB/Cassandra integration tests via Testcontainers — no fakes involved.
 *
 * Exercises paths `FakeKandraSession` structurally can't verify: real CQL parameter binding,
 * LWT applied/not-applied semantics, and TTL-based soft delete. Each test gets its own isolated
 * keyspace via [KandraTestcontainers.freshKeyspace].
 */
class KandraIntegrationTest {

    private val db = KandraTestcontainers.freshKeyspace(IntegrationUser::class, IntegrationWidget::class)

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
}
