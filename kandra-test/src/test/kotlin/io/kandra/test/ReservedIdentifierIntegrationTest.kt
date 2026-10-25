package io.kandra.test

import io.kandra.core.CqlNaming
import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.annotations.ClusteringKey
import io.kandra.core.annotations.Counter
import io.kandra.core.annotations.LookupIndex
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.annotations.SecondaryIndex
import io.kandra.core.annotations.SoftDelete
import io.kandra.core.annotations.Version
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

/** GH #188: the reporter's exact entity. */
@ScyllaTable(name = "invites")
data class InviteRow(@PartitionKey val token: String, val ownerId: UUID)

/** GH #188: reserved words in every column role Kandra generates CQL for. */
@ScyllaTable("reserved_rows")
data class ReservedRow(
    @PartitionKey val token: String,
    @ClusteringKey val order: Int,
    @LookupIndex(tableSuffix = "by_from") val from: String,
    @SecondaryIndex val default: String,
    val set: Set<String>? = null,
    val select: String? = null,
    @Version val update: Long = 0L,
)

@ScyllaTable("reserved_counters")
data class ReservedCounterRow(@PartitionKey val token: String, @Counter val limit: Long = 0L)

@ScyllaTable("reserved_soft")
@SoftDelete(ttlSeconds = 60, markerProperty = "delete")
data class ReservedSoftRow(@PartitionKey val token: String, val note: String? = null, @SecondaryIndex val delete: Boolean = false)

/**
 * Regression coverage for GH #188 against a real database: a column named after a reserved CQL word
 * (`token`, `order`, `from`, ...) made `CREATE TABLE` fail with `no viable alternative at input`.
 * [KandraTestcontainers.freshKeyspace] runs the generated DDL, so before the fix this class failed to
 * construct; the tests then drive every statement kind through those columns.
 */
@OptIn(ExperimentalKandraApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReservedIdentifierIntegrationTest {

    private val db = KandraTestcontainers.freshKeyspace(
        InviteRow::class, ReservedRow::class, ReservedCounterRow::class, ReservedSoftRow::class
    )

    @AfterAll
    fun cleanup() = db.close()

    @Test
    fun `the reporter's invite entity round-trips`() = runBlocking {
        val repo = db.suspendRepository<InviteRow>()
        val invite = InviteRow("tok-${UUID.randomUUID()}", UUID.randomUUID())
        repo.save(invite)
        assertEquals(invite, repo.findById(invite.token))
        assertEquals(invite, repo.find { InviteRowTable.token eq invite.token })
        repo.deleteById(invite.token)
        assertNull(repo.findById(invite.token))
    }

    @Test
    fun `save, lookup, index, range, collections, versioned update, page and delete with reserved columns`() = runBlocking {
        val repo = db.suspendRepository<ReservedRow>()
        val token = "t-${UUID.randomUUID()}"
        val from = "f-${UUID.randomUUID()}"
        val first = repo.saveAndGet(ReservedRow(token, 1, from, "d-${UUID.randomUUID()}", setOf("a"), "s"))
        repo.save(ReservedRow(token, 2, "f-${UUID.randomUUID()}", "d2", null, null))

        assertEquals(first, repo.findById(token, 1))
        assertEquals(first, repo.find { ReservedRowTable.from eq from })                  // lookup table
        assertEquals(first, repo.find { ReservedRowTable.default eq first.default })       // secondary index
        assertEquals(listOf(2), repo.findAll { ReservedRowTable.token eq token; ReservedRowTable.order gt 1 }.map { it.order })
        assertEquals(2, repo.findPage(10, null) { ReservedRowTable.token eq token }.items.size)
        assertTrue(repo.exists { ReservedRowTable.token eq token })

        repo.append(first, ReservedRow::set, listOf("b"))
        assertEquals(setOf("a", "b"), repo.findById(token, 1)?.set)
        repo.remove(first, ReservedRow::set, listOf("a"))
        assertEquals(setOf("b"), repo.findById(token, 1)?.set)

        val current = repo.findById(token, 1)!!
        val updated = repo.updateAndGet(current, current.copy(select = "changed"))
        assertEquals("changed", repo.findById(token, 1)?.select)
        assertEquals(current.update + 1, updated.update)

        db.runtime.batch { repo.deleteInBatch(updated) }
        assertNull(repo.findById(token, 1))
        assertNull(repo.find { ReservedRowTable.from eq from })
        repo.deleteBy { ReservedRowTable.token eq token; ReservedRowTable.order eq 2 }
        assertNull(repo.findById(token, 2))
    }

    @Test
    fun `counter increment with a reserved counter column`() = runBlocking {
        val repo = db.suspendRepository<ReservedCounterRow>()
        val token = "c-${UUID.randomUUID()}"
        repo.increment(ReservedCounterRow::limit, mapOf("token" to token), by = 3)
        repo.decrement(ReservedCounterRow::limit, mapOf("token" to token), by = 1)
        assertEquals(2L, repo.findById(token)?.limit)
    }

    @Test
    fun `soft delete and findActive with a reserved marker column`() = runBlocking {
        val repo = db.suspendRepository<ReservedSoftRow>()
        val live = ReservedSoftRow("s-${UUID.randomUUID()}", "keep")
        val gone = ReservedSoftRow("s-${UUID.randomUUID()}", "drop")
        repo.save(live)
        repo.save(gone)
        repo.delete(gone)
        val active = repo.findActive().map { it.token }
        assertTrue(live.token in active)
        assertTrue(gone.token !in active)
        assertNotNull(repo.findById(live.token))
    }

    @Test
    fun `every reserved word works as a quoted column on this database`() {
        KandraTestBackend.newSession(db.keyspace).use { s ->
            CqlNaming.RESERVED_WORDS.forEach { word ->
                val col = CqlNaming.quoteIfNeeded(word)
                s.execute("CREATE TABLE IF NOT EXISTS rw_$word ($col text PRIMARY KEY, v int)")
                s.execute(s.prepare("INSERT INTO rw_$word ($col, v) VALUES (?, ?)").bind("k", 1))
                val row = s.execute(s.prepare("SELECT $col, v FROM rw_$word WHERE $col = ?").bind("k")).one()
                assertEquals("k", row?.getString(word), "row read by the raw name '$word'")
            }
        }
    }
}
