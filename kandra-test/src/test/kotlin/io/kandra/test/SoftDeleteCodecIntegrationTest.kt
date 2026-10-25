package io.kandra.test

import io.kandra.core.annotations.ClusteringKey
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.annotations.SoftDelete
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

enum class Gh198Kind { INVOICE, RECEIPT }
enum class Gh198State { OPEN, CLOSED }

/** GH #198: enum columns, including an enum clustering key, on a soft-deleted entity. */
@ScyllaTable("gh198_documents")
@SoftDelete(ttlSeconds = 60, markerProperty = "deleted")
data class Gh198Document(
    @PartitionKey val ownerId: UUID,
    @ClusteringKey val kind: Gh198Kind,
    val state: Gh198State? = null,
    val title: String? = null,
    val deleted: Boolean = false,
)

/**
 * Regression coverage for GH #198: soft delete built its own UPDATE/INSERT and bound raw property
 * values, skipping KandraCodec, so an enum column (stored as TEXT) failed with a driver codec error.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SoftDeleteCodecIntegrationTest {

    private val db = KandraTestcontainers.freshKeyspace(Gh198Document::class)

    @AfterAll
    fun cleanup() = db.close()

    @Test
    fun `soft delete of an entity with enum columns succeeds and hides the row from findActive`() = runBlocking {
        val repo = db.suspendRepository<Gh198Document>()
        val owner = UUID.randomUUID()
        val doc = Gh198Document(owner, Gh198Kind.INVOICE, Gh198State.OPEN, "Q3")
        val keep = Gh198Document(owner, Gh198Kind.RECEIPT, null, "keep")
        repo.save(doc)
        repo.save(keep)

        repo.delete(doc)

        val stillThere = repo.findById(owner, Gh198Kind.INVOICE)!!
        assertEquals(Gh198State.OPEN, stillThere.state, "data columns are rewritten (with TTL), not lost")
        assertTrue(stillThere.deleted)
        val active = repo.findActive(allowFullScan = true).filter { it.ownerId == owner }
        assertEquals(listOf(Gh198Kind.RECEIPT), active.map { it.kind })
    }

    @Test
    fun `blocking soft delete with a null enum column succeeds`() {
        val repo = db.repository<Gh198Document>()
        val doc = Gh198Document(UUID.randomUUID(), Gh198Kind.RECEIPT, null, null)
        repo.save(doc)
        repo.delete(doc)
        assertTrue(repo.findById(doc.ownerId, doc.kind)!!.deleted)
    }
}
