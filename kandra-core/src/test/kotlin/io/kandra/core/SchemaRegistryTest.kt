package io.kandra.core

import io.kandra.core.annotations.ClusteringKey
import io.kandra.core.annotations.ClusteringOrder
import io.kandra.core.annotations.Column
import io.kandra.core.annotations.Counter
import io.kandra.core.annotations.CreatedAt
import io.kandra.core.annotations.GeneratedUuid
import io.kandra.core.annotations.LookupConsistency
import io.kandra.core.annotations.LookupIndex
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.annotations.Transient
import io.kandra.core.annotations.Ttl
import io.kandra.core.annotations.UpdatedAt
import io.kandra.core.annotations.UuidStrategy
import io.kandra.core.exception.KandraSchemaException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

// ── Test entities ──────────────────────────────────────────────────────────

@ScyllaTable("users")
data class User(
    @PartitionKey
    val userId: UUID,
    @ClusteringKey(order = ClusteringOrder.ASC, index = 0)
    val createdAt: Instant,
    @LookupIndex(tableSuffix = "by_email", consistency = LookupConsistency.BATCH)
    val email: String,
    @LookupIndex(tableSuffix = "by_phone", consistency = LookupConsistency.EVENTUAL)
    val phone: String,
    @Column("full_name")
    val name: String,
    @Transient
    val cachedToken: String? = null
)

@ScyllaTable("simple_entities")
data class SimpleEntity(
    @PartitionKey val id: UUID,
    val value: String
)

@ScyllaTable("compound_pk_entities")
data class CompoundPkEntity(
    @PartitionKey val tenantId: UUID,
    @ClusteringKey(order = ClusteringOrder.DESC, index = 0)
    val timestamp: Instant,
    @ClusteringKey(order = ClusteringOrder.ASC, index = 1)
    val eventId: UUID,
    val payload: String
)

enum class TransactionStatus { PENDING, CONFIRMED, FAILED }

@ScyllaTable("transactions_by_user_chain")
data class Transaction(
    @PartitionKey(index = 0) val userId: UUID,
    @PartitionKey(index = 1) val chain: String,
    @ClusteringKey(order = ClusteringOrder.DESC, index = 0)
    val createdAt: Instant,
    val amount: Double,
    val status: TransactionStatus
)

@ScyllaTable("no_pk_entities")
data class NoPkEntity(val id: UUID, val value: String)

@ScyllaTable("duplicate_pk_index_entities")
data class DuplicatePkIndexEntity(
    @PartitionKey(index = 0) val id: UUID,
    @PartitionKey(index = 0) val secondId: UUID // same index
)

@ScyllaTable("duplicate_lookup_entities")
data class DuplicateLookupEntity(
    @PartitionKey val id: UUID,
    @LookupIndex(tableSuffix = "by_email") val email1: String,
    @LookupIndex(tableSuffix = "by_email") val email2: String
)

@ScyllaTable("ttl_entities")
@Ttl(300)
data class TtlEntity(@PartitionKey val id: UUID, val payload: String)

@ScyllaTable("chain_stats")
data class ChainStats(
    @PartitionKey val chain: String,
    @Counter val totalTransactions: Long,
    @Counter val totalVolumeUsd: Long
)

@ScyllaTable("mixed_counter_bad")
data class MixedCounterEntity(
    @PartitionKey val id: UUID,
    @Counter val count: Long,
    val name: String  // non-counter — should throw
)

@ScyllaTable("created_at_entity")
data class CreatedAtEntity(
    @PartitionKey val id: UUID,
    @CreatedAt val createdAt: Instant = Instant.EPOCH,
    @UpdatedAt val updatedAt: Instant = Instant.EPOCH,
    val value: String = ""
)

@ScyllaTable("bad_created_at")
data class BadCreatedAtEntity(
    @PartitionKey val id: UUID,
    @CreatedAt val name: String = ""  // wrong type
)

@ScyllaTable("generated_uuid_events")
data class GeneratedUuidEvent(
    @PartitionKey val streamId: UUID,
    @GeneratedUuid @ClusteringKey val eventId: UUID,
    @GeneratedUuid(strategy = UuidStrategy.RANDOM) val externalRef: UUID,
    val payload: String
)

@ScyllaTable("bad_generated_uuid")
data class BadGeneratedUuidEntity(
    @PartitionKey val id: UUID,
    @GeneratedUuid val name: String = ""  // wrong type
)

class SchemaRegistryTest {

    @AfterEach
    fun cleanup() = SchemaRegistry.clear()

    @Test
    fun `registers simple entity`() {
        val schema = SchemaRegistry.register(SimpleEntity::class)
        assertEquals("simple_entities", schema.tableName)
        assertEquals(1, schema.partitionKeys.size)
        assertEquals("id", schema.partitionKeys.first().cqlName)
        assertTrue(schema.clusteringKeys.isEmpty())
    }

    @Test
    fun `registers compound pk entity`() {
        val schema = SchemaRegistry.register(CompoundPkEntity::class)
        assertEquals("compound_pk_entities", schema.tableName)
        assertEquals("tenant_id", schema.partitionKeys.first().cqlName)
        assertEquals(2, schema.clusteringKeys.size)
        assertEquals("timestamp", schema.clusteringKeys[0].cqlName)
        assertEquals(ClusteringOrder.DESC, schema.clusteringKeys[0].clusteringKey!!.order)
        assertEquals("event_id", schema.clusteringKeys[1].cqlName)
    }

    @Test
    fun `composite PK sorts by index`() {
        val schema = SchemaRegistry.register(Transaction::class)
        assertEquals(2, schema.partitionKeys.size)
        assertEquals("user_id", schema.partitionKeys[0].cqlName)
        assertEquals("chain", schema.partitionKeys[1].cqlName)
    }

    @Test
    fun `duplicate PartitionKey index throws KandraSchemaException`() {
        val ex = assertThrows<KandraSchemaException> {
            SchemaRegistry.register(DuplicatePkIndexEntity::class)
        }
        assertTrue(ex.message!!.contains("Duplicate @PartitionKey index"))
    }

    @Test
    fun `single PartitionKey with index 0 works as simple PK`() {
        val schema = SchemaRegistry.register(SimpleEntity::class)
        assertEquals(1, schema.partitionKeys.size)
        assertEquals("id", schema.partitionKeys.first().cqlName)
    }

    @Test
    fun `registers multiple lookup index fields`() {
        val schema = SchemaRegistry.register(User::class)
        assertEquals(2, schema.lookupTables.size)
        val tableNames = schema.lookupTables.map { it.tableName }
        assertTrue("users_by_email" in tableNames)
        assertTrue("users_by_phone" in tableNames)
    }

    @Test
    fun `throws KandraSchemaException on missing PartitionKey`() {
        val ex = assertThrows<KandraSchemaException> {
            SchemaRegistry.register(NoPkEntity::class)
        }
        assertTrue(ex.message!!.contains("no @PartitionKey"))
    }

    @Test
    fun `Column annotation rename reflected in cqlName`() {
        val schema = SchemaRegistry.register(User::class)
        val nameCol = schema.columns.find { it.propertyName == "name" }
        assertNotNull(nameCol)
        assertEquals("full_name", nameCol!!.cqlName)
    }

    @Test
    fun `Transient property excluded from columns`() {
        val schema = SchemaRegistry.register(User::class)
        val allCols = schema.columns + schema.clusteringKeys + schema.partitionKeys +
            schema.lookupTables.map { it.indexColumn }
        assertNull(allCols.find { it.propertyName == "cachedToken" })
    }

    @Test
    fun `camelToSnake converts correctly`() {
        assertEquals("user_id", SchemaRegistry.camelToSnake("userId"))
        assertEquals("created_at", SchemaRegistry.camelToSnake("createdAt"))
        assertEquals("full_name", SchemaRegistry.camelToSnake("fullName"))
        assertEquals("id", SchemaRegistry.camelToSnake("id"))
        assertEquals("some_long_property_name", SchemaRegistry.camelToSnake("someLongPropertyName"))
    }

    @Test
    fun `throws KandraSchemaException on duplicate lookup suffix`() {
        val ex = assertThrows<KandraSchemaException> {
            SchemaRegistry.register(DuplicateLookupEntity::class)
        }
        assertTrue(ex.message!!.contains("duplicate"))
    }

    @Test
    fun `lookup table consistency is preserved`() {
        val schema = SchemaRegistry.register(User::class)
        val emailLookup = schema.lookupTables.find { it.tableName == "users_by_email" }!!
        val phoneLookup = schema.lookupTables.find { it.tableName == "users_by_phone" }!!
        assertEquals(LookupConsistency.BATCH, emailLookup.consistency)
        assertEquals(LookupConsistency.EVENTUAL, phoneLookup.consistency)
    }

    @Test
    fun `Ttl annotation populates defaultTtl`() {
        val schema = SchemaRegistry.register(TtlEntity::class)
        assertEquals(300, schema.defaultTtl)
    }

    @Test
    fun `counter table is recognized`() {
        val schema = SchemaRegistry.register(ChainStats::class)
        assertTrue(schema.isCounterTable)
    }

    @Test
    fun `mixed counter and non-counter throws KandraSchemaException`() {
        assertThrows<KandraSchemaException> {
            SchemaRegistry.register(MixedCounterEntity::class)
        }
    }

    @Test
    fun `CreatedAt and UpdatedAt columns detected`() {
        val schema = SchemaRegistry.register(CreatedAtEntity::class)
        assertNotNull(schema.createdAtColumn)
        assertNotNull(schema.updatedAtColumn)
        assertEquals("created_at", schema.createdAtColumn!!.cqlName)
        assertEquals("updated_at", schema.updatedAtColumn!!.cqlName)
    }

    @Test
    fun `CreatedAt on non-Instant field throws KandraSchemaException`() {
        assertThrows<KandraSchemaException> {
            SchemaRegistry.register(BadCreatedAtEntity::class)
        }
    }

    @Test
    fun `GeneratedUuid columns are collected with their strategy`() {
        val schema = SchemaRegistry.register(GeneratedUuidEvent::class)
        assertEquals(2, schema.generatedUuidColumns.size)
        val eventId = schema.generatedUuidColumns.find { it.propertyName == "eventId" }
        val externalRef = schema.generatedUuidColumns.find { it.propertyName == "externalRef" }
        assertNotNull(eventId)
        assertNotNull(externalRef)
        assertEquals(UuidStrategy.TIME_ORDERED, eventId!!.generatedUuidStrategy)
        assertEquals(UuidStrategy.RANDOM, externalRef!!.generatedUuidStrategy)
    }

    @Test
    fun `GeneratedUuid on non-UUID field throws KandraSchemaException`() {
        val ex = assertThrows<KandraSchemaException> {
            SchemaRegistry.register(BadGeneratedUuidEntity::class)
        }
        assertTrue(ex.message!!.contains("@GeneratedUuid"))
        assertTrue(ex.message!!.contains("must be a UUID field"))
    }

    @Test
    fun `get returns schema after registration`() {
        SchemaRegistry.register(SimpleEntity::class)
        val schema = SchemaRegistry.get(SimpleEntity::class)
        assertEquals("simple_entities", schema.tableName)
    }

    @Test
    fun `getOrNull returns null for unregistered class`() {
        assertNull(SchemaRegistry.getOrNull(SimpleEntity::class))
    }
}
