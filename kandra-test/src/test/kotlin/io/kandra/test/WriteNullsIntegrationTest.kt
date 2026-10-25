package io.kandra.test

import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.annotations.LookupIndex
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.annotations.Version
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** GH #189: a claim with a clearable column, the reporter's shape. */
@ScyllaTable("gh189_claims")
data class Gh189Claim(
    @PartitionKey val id: UUID,
    @LookupIndex(tableSuffix = "by_code") val code: String,
    val holder: String? = null,
    val releasedAt: Instant? = null,
)

/** GH #189: the same with optimistic locking, for the versioned update path. */
@ScyllaTable("gh189_versioned_claims")
data class Gh189VersionedClaim(
    @PartitionKey val id: UUID,
    val holder: String? = null,
    val releasedAt: Instant? = null,
    @Version val version: Long = 0L,
)

/** GH #189: a second table changed in the same batch. */
@ScyllaTable("gh189_audit")
data class Gh189Audit(@PartitionKey val id: UUID, val note: String? = null)

/**
 * Regression coverage for GH #189 against a real database: `saveInBatch` and `update()` left a
 * `null` property UNSET, and there was no way to clear a column through them. Checks
 * `saveWithNullsInBatch` and `updateWithNulls` (both repository types, both entity shapes), and
 * that the plain variants still keep the stored value.
 */
@OptIn(ExperimentalKandraApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WriteNullsIntegrationTest {

    private val db = KandraTestcontainers.freshKeyspace(Gh189Claim::class, Gh189VersionedClaim::class, Gh189Audit::class)

    @AfterAll
    fun cleanup() = db.close()

    private val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    private fun claim() = Gh189Claim(UUID.randomUUID(), "c-${UUID.randomUUID()}", holder = "alice", releasedAt = now)

    @Test
    fun `saveInBatch keeps a null column, saveWithNullsInBatch clears it atomically with other writes`() = runBlocking {
        val claims = db.suspendRepository<Gh189Claim>()
        val audit = db.suspendRepository<Gh189Audit>()
        val existing = claim()
        claims.save(existing)

        db.runtime.batch { claims.saveInBatch(existing.copy(releasedAt = null)) }
        assertEquals(now, claims.findById(existing.id)?.releasedAt, "plain saveInBatch leaves the column unchanged")

        val auditId = UUID.randomUUID()
        db.runtime.batch {
            claims.saveWithNullsInBatch(existing.copy(holder = null, releasedAt = null))
            audit.saveInBatch(Gh189Audit(auditId, "released"))
        }
        val cleared = claims.findById(existing.id)!!
        assertNull(cleared.releasedAt)
        assertNull(cleared.holder)
        assertEquals("released", audit.findById(auditId)?.note)
        assertEquals(cleared, claims.find { Gh189ClaimTable.code eq existing.code }, "lookup still resolves")
    }

    @Test
    fun `blocking batch saveWithNullsInBatch clears the column`() {
        val claims = db.repository<Gh189Claim>()
        val existing = claim()
        claims.save(existing)
        db.runtime.batchBlocking { claims.saveWithNullsInBatchAndGet(existing.copy(releasedAt = null)) }
        assertNull(claims.findById(existing.id)?.releasedAt)
    }

    @Test
    fun `update keeps a null column, updateWithNulls clears it`() = runBlocking {
        val claims = db.suspendRepository<Gh189Claim>()
        val existing = claim()
        claims.save(existing)

        claims.update(existing, existing.copy(releasedAt = null))
        assertEquals(now, claims.findById(existing.id)?.releasedAt)

        val updated = claims.updateWithNullsAndGet(existing, existing.copy(releasedAt = null))
        assertNull(updated.releasedAt)
        assertNull(claims.findById(existing.id)?.releasedAt)
        assertEquals("alice", claims.findById(existing.id)?.holder)
    }

    @Test
    fun `versioned update keeps a null column, versioned updateWithNulls clears it and bumps the version`() = runBlocking {
        val repo = db.suspendRepository<Gh189VersionedClaim>()
        val v0 = repo.saveAndGet(Gh189VersionedClaim(UUID.randomUUID(), holder = "alice", releasedAt = now))

        val v1 = repo.updateAndGet(v0, v0.copy(releasedAt = null))
        assertEquals(now, repo.findById(v0.id)?.releasedAt, "versioned update leaves a null column UNSET")
        assertEquals(v0.version + 1, v1.version)

        val v2 = repo.updateWithNullsAndGet(v1, v1.copy(releasedAt = null, holder = null))
        val stored = repo.findById(v0.id)!!
        assertNull(stored.releasedAt)
        assertNull(stored.holder)
        assertEquals(v1.version + 1, v2.version)
        assertEquals(v2.version, stored.version)
    }

    @Test
    fun `blocking versioned updateWithNulls clears the column`() {
        val repo = db.repository<Gh189VersionedClaim>()
        val v0 = repo.saveAndGet(Gh189VersionedClaim(UUID.randomUUID(), holder = "bob", releasedAt = now))
        repo.updateWithNulls(v0, v0.copy(holder = null))
        val stored = repo.findById(v0.id)!!
        assertNull(stored.holder)
        assertEquals(now, stored.releasedAt)
    }
}
