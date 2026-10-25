package io.kandra.test

import io.kandra.core.annotations.ClusteringKey
import io.kandra.core.annotations.ClusteringOrder
import io.kandra.core.annotations.LookupIndex
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

/** GH #187: the reporter's exact shape — `@LookupIndex` on a `@ClusteringKey` column. */
@ScyllaTable(name = "support_recoveries")
data class SupportRecoveryRow(
    @PartitionKey val userId: UUID,
    @ClusteringKey(order = ClusteringOrder.DESC)
    @LookupIndex(tableSuffix = "by_request") val requestId: UUID,
    val status: String,
)

/** GH #187: same defect class with the indexed column in a composite partition key. */
@ScyllaTable(name = "tenant_documents")
data class TenantDocumentRow(
    @PartitionKey(index = 0) val tenantId: UUID,
    @PartitionKey(index = 1) @LookupIndex(tableSuffix = "by_doc") val docId: UUID,
    val title: String,
)

/**
 * Regression coverage for GH #187: a `@LookupIndex` on a property that is also a primary-table key
 * column generated a lookup table declaring that column twice
 * (`CREATE TABLE ... (request_id UUID, user_id UUID, request_id UUID, PRIMARY KEY (request_id))`),
 * which the database rejects, and the lookup INSERT named it twice too. [KandraTestcontainers.freshKeyspace]
 * runs the generated DDL against the real container, so before the fix this class failed to construct.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LookupIndexOnKeyColumnIntegrationTest {

    private val db = KandraTestcontainers.freshKeyspace(SupportRecoveryRow::class, TenantDocumentRow::class)

    @AfterAll
    fun cleanup() = db.close()

    private fun lookupRowCount(table: String, column: String, value: UUID): Int =
        KandraTestBackend.newSession(db.keyspace).use { s ->
            s.execute(s.prepare("SELECT * FROM $table WHERE $column = ?").bind(value)).all().size
        }

    @Test
    fun `clustering-key lookup supports save, find, update and delete`() = runBlocking {
        val repo = db.suspendRepository<SupportRecoveryRow>()
        val userId = UUID.randomUUID()
        val first = SupportRecoveryRow(userId, UUID.randomUUID(), "pending")
        val second = SupportRecoveryRow(userId, UUID.randomUUID(), "pending")
        repo.save(first)
        repo.save(second)

        val found = repo.find { SupportRecoveryRowTable.requestId eq second.requestId }
        assertEquals(second, found)
        assertEquals(1, lookupRowCount("support_recoveries_by_request", "request_id", second.requestId))

        repo.update(second, second.copy(status = "approved"))
        assertEquals("approved", repo.find { SupportRecoveryRowTable.requestId eq second.requestId }?.status)
        assertEquals("pending", repo.findById(userId, first.requestId)?.status)

        repo.delete(second.copy(status = "approved"))
        assertNull(repo.find { SupportRecoveryRowTable.requestId eq second.requestId })
        assertEquals(0, lookupRowCount("support_recoveries_by_request", "request_id", second.requestId))
        assertNotNull(repo.find { SupportRecoveryRowTable.requestId eq first.requestId })
    }

    @Test
    fun `clustering-key lookup works through the blocking repository and a batch`() {
        val repo = db.repository<SupportRecoveryRow>()
        val row = SupportRecoveryRow(UUID.randomUUID(), UUID.randomUUID(), "pending")
        @OptIn(io.kandra.core.ExperimentalKandraApi::class)
        db.runtime.batchBlocking { repo.saveInBatch(row) }
        assertEquals(row, repo.find { SupportRecoveryRowTable.requestId eq row.requestId })
    }

    @Test
    fun `partition-key lookup supports save, find and delete`() = runBlocking {
        val repo = db.suspendRepository<TenantDocumentRow>()
        val doc = TenantDocumentRow(UUID.randomUUID(), UUID.randomUUID(), "Q3 report")
        repo.save(doc)

        assertEquals(doc, repo.find { TenantDocumentRowTable.docId eq doc.docId })
        repo.delete(doc)
        assertNull(repo.find { TenantDocumentRowTable.docId eq doc.docId })
        assertEquals(0, lookupRowCount("tenant_documents_by_doc", "doc_id", doc.docId))
    }
}
