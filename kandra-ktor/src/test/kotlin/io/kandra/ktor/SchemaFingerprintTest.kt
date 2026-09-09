package io.kandra.ktor

import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.ClusteringKey
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.util.UUID

@ScyllaTable("fingerprint_test_a")
private data class FingerprintA(
    @PartitionKey val id: UUID,
    val label: String
)

@ScyllaTable("fingerprint_test_b")
private data class FingerprintB(
    @PartitionKey val id: UUID,
    val value: Int
)

@ScyllaTable("fingerprint_test_a")
private data class FingerprintAWithExtraColumn(
    @PartitionKey val id: UUID,
    val label: String,
    val addedLater: String
)

@ScyllaTable("fingerprint_test_a")
private data class FingerprintAWithClusteringKey(
    @PartitionKey val id: UUID,
    @ClusteringKey val version: Int,
    val label: String
)

/**
 * GH #90 / ISS-077 -- [schemaFingerprint] is what [ddlBootstrapLockName] keys the AUTO_CREATE/
 * AUTO_MIGRATE DDL bootstrap claim on, so that a claim marked DONE for one schema generation never
 * blocks a later deploy whose registered entities changed. These are pure, in-memory assertions on
 * the fingerprint function itself (no cluster needed -- SchemaRegistry.register is pure reflection).
 * See DdlBootstrapClaimTest / KandraPluginTest for the end-to-end proof against a real cluster.
 */
class SchemaFingerprintTest {

    @AfterEach
    fun cleanup() {
        SchemaRegistry.clear()
    }

    @Test
    fun `same registered schema produces the same fingerprint`() {
        val schemaA = SchemaRegistry.register(FingerprintA::class)
        val schemaB = SchemaRegistry.register(FingerprintB::class)

        val first = schemaFingerprint(listOf(schemaA, schemaB))
        val second = schemaFingerprint(listOf(schemaA, schemaB))

        assertEquals(first, second)
    }

    @Test
    fun `registration order does not affect the fingerprint`() {
        val schemaA = SchemaRegistry.register(FingerprintA::class)
        val schemaB = SchemaRegistry.register(FingerprintB::class)

        val forward = schemaFingerprint(listOf(schemaA, schemaB))
        val reversed = schemaFingerprint(listOf(schemaB, schemaA))

        assertEquals(forward, reversed)
    }

    @Test
    fun `adding a new entity changes the fingerprint`() {
        val schemaA = SchemaRegistry.register(FingerprintA::class)
        val schemaB = SchemaRegistry.register(FingerprintB::class)

        val beforeNewEntity = schemaFingerprint(listOf(schemaA))
        val afterNewEntity = schemaFingerprint(listOf(schemaA, schemaB))

        assertNotEquals(beforeNewEntity, afterNewEntity)
    }

    @Test
    fun `adding a column to an existing entity changes the fingerprint`() {
        SchemaRegistry.clear()
        val original = SchemaRegistry.register(FingerprintA::class)
        val fingerprintBefore = schemaFingerprint(listOf(original))

        SchemaRegistry.clear()
        val withExtraColumn = SchemaRegistry.register(FingerprintAWithExtraColumn::class)
        val fingerprintAfter = schemaFingerprint(listOf(withExtraColumn))

        assertNotEquals(fingerprintBefore, fingerprintAfter)
    }

    @Test
    fun `adding a clustering key to an existing entity changes the fingerprint`() {
        SchemaRegistry.clear()
        val original = SchemaRegistry.register(FingerprintA::class)
        val fingerprintBefore = schemaFingerprint(listOf(original))

        SchemaRegistry.clear()
        val withClusteringKey = SchemaRegistry.register(FingerprintAWithClusteringKey::class)
        val fingerprintAfter = schemaFingerprint(listOf(withClusteringKey))

        assertNotEquals(fingerprintBefore, fingerprintAfter)
    }

    @Test
    fun `ddlBootstrapLockName carries the fixed prefix plus the fingerprint`() {
        val schemaA = SchemaRegistry.register(FingerprintA::class)
        val lockName = ddlBootstrapLockName(listOf(schemaA))

        assertEquals(true, lockName.startsWith("schema-bootstrap-"))
        assertEquals(lockName, ddlBootstrapLockName(listOf(schemaA)))
    }
}
