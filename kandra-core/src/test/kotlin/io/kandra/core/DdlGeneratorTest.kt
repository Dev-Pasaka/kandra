package io.kandra.core

import io.kandra.core.annotations.ClusteringKey
import io.kandra.core.annotations.ClusteringOrder
import io.kandra.core.annotations.LookupIndex
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.exception.KandraSchemaException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.reflect.typeOf

// ── GH-31: frozen<> collection test entities ────────────────────────────────

@ScyllaTable("collection_pk_entities")
data class CollectionPartitionKeyEntity(
    @PartitionKey val tags: Set<String>,
    val value: String
)

@ScyllaTable("collection_ck_entities")
data class CollectionClusteringKeyEntity(
    @PartitionKey val id: UUID,
    @ClusteringKey val history: List<String>
)

@ScyllaTable("regular_collection_entities")
data class RegularCollectionEntity(
    @PartitionKey val id: UUID,
    val tags: Set<String>
)

@ScyllaTable("nested_collection_entities")
data class NestedCollectionEntity(
    @PartitionKey val id: UUID,
    val metadata: Map<String, List<String>>
)

@ScyllaTable("collection_lookup_index_entities")
data class CollectionLookupIndexEntity(
    @PartitionKey val id: UUID,
    @LookupIndex(tableSuffix = "by_tags")
    val tags: Set<String>
)

class DdlGeneratorTest {

    @AfterEach
    fun cleanup() = SchemaRegistry.clear()

    @Test
    fun `simple entity DDL is valid CQL`() {
        val schema = SchemaRegistry.register(SimpleEntity::class)
        val ddl = DdlGenerator.primaryTable(schema)
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS simple_entities"))
        assertTrue(ddl.contains("PRIMARY KEY (id)"))
        assertTrue(ddl.contains("id UUID"))
        assertTrue(ddl.contains("value TEXT"))
    }

    @Test
    fun `compound pk DDL includes CLUSTERING ORDER BY`() {
        val schema = SchemaRegistry.register(CompoundPkEntity::class)
        val ddl = DdlGenerator.primaryTable(schema)
        assertTrue(ddl.contains("WITH CLUSTERING ORDER BY"))
        assertTrue(ddl.contains("timestamp DESC"))
        assertTrue(ddl.contains("event_id ASC"))
        assertTrue(ddl.contains("PRIMARY KEY (tenant_id, timestamp, event_id)"))
    }

    @Test
    fun `composite partition key DDL wraps in double parens`() {
        val schema = SchemaRegistry.register(Transaction::class)
        val ddl = DdlGenerator.primaryTable(schema)
        assertTrue(ddl.contains("PRIMARY KEY ((user_id, chain), created_at)"),
            "Expected double parens for composite PK in: $ddl")
    }

    @Test
    fun `lookup table DDL has exactly two columns for simple PK`() {
        val schema = SchemaRegistry.register(User::class)
        val lookup = schema.lookupTables.find { it.tableName == "users_by_email" }!!
        val ddl = DdlGenerator.lookupTable(lookup)
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS users_by_email"))
        assertTrue(ddl.contains("PRIMARY KEY (email)"))
        assertTrue(ddl.contains("email TEXT"))
        assertTrue(ddl.contains("user_id UUID"))
    }

    @Test
    fun `allStatements returns primary plus all lookup DDLs`() {
        val schema = SchemaRegistry.register(User::class)
        val statements = DdlGenerator.allStatements(schema)
        assertEquals(3, statements.size) // 1 primary + 2 lookups
    }

    @Test
    fun `Ttl DDL appends WITH default_time_to_live`() {
        val schema = SchemaRegistry.register(TtlEntity::class)
        val ddl = DdlGenerator.primaryTable(schema)
        assertTrue(ddl.contains("default_time_to_live = 300"), "Expected TTL in: $ddl")
    }

    @Test
    fun `lookup table DDL does not include TTL`() {
        // TtlEntity has no lookup indexes so test via a hypothetical — verify the function omits TTL
        val schema = SchemaRegistry.register(User::class)
        val lookup = schema.lookupTables.first()
        val ddl = DdlGenerator.lookupTable(lookup)
        assertFalse(ddl.contains("time_to_live"), "Lookup table DDL must not contain TTL")
    }

    @Test
    fun `counter table DDL uses COUNTER type`() {
        val schema = SchemaRegistry.register(ChainStats::class)
        val ddl = DdlGenerator.primaryTable(schema)
        assertTrue(ddl.contains("total_transactions COUNTER"))
        assertTrue(ddl.contains("total_volume_usd COUNTER"))
    }

    // ── Type mapping tests ─────────────────────────────────────────────────

    @Test fun `UUID maps to UUID`() = assertEquals("UUID", DdlGenerator.kotlinTypeToCql(typeOf<UUID>()))
    @Test fun `String maps to TEXT`() = assertEquals("TEXT", DdlGenerator.kotlinTypeToCql(typeOf<String>()))
    @Test fun `Int maps to INT`() = assertEquals("INT", DdlGenerator.kotlinTypeToCql(typeOf<Int>()))
    @Test fun `Long maps to BIGINT`() = assertEquals("BIGINT", DdlGenerator.kotlinTypeToCql(typeOf<Long>()))
    @Test fun `Boolean maps to BOOLEAN`() = assertEquals("BOOLEAN", DdlGenerator.kotlinTypeToCql(typeOf<Boolean>()))
    @Test fun `Double maps to DOUBLE`() = assertEquals("DOUBLE", DdlGenerator.kotlinTypeToCql(typeOf<Double>()))
    @Test fun `Float maps to FLOAT`() = assertEquals("FLOAT", DdlGenerator.kotlinTypeToCql(typeOf<Float>()))
    @Test fun `Instant maps to TIMESTAMP`() = assertEquals("TIMESTAMP", DdlGenerator.kotlinTypeToCql(typeOf<Instant>()))
    @Test fun `LocalDate maps to DATE`() = assertEquals("DATE", DdlGenerator.kotlinTypeToCql(typeOf<LocalDate>()))
    @Test fun `ByteArray maps to BLOB`() = assertEquals("BLOB", DdlGenerator.kotlinTypeToCql(typeOf<ByteArray>()))
    @Test fun `BigDecimal maps to DECIMAL`() = assertEquals("DECIMAL", DdlGenerator.kotlinTypeToCql(typeOf<BigDecimal>()))
    @Test fun `List of String maps to LIST TEXT`() = assertEquals("LIST<TEXT>", DdlGenerator.kotlinTypeToCql(typeOf<List<String>>()))
    @Test fun `Set of UUID maps to SET UUID`() = assertEquals("SET<UUID>", DdlGenerator.kotlinTypeToCql(typeOf<Set<UUID>>()))
    @Test fun `Map of String to Int maps to MAP TEXT INT`() = assertEquals("MAP<TEXT, INT>", DdlGenerator.kotlinTypeToCql(typeOf<Map<String, Int>>()))
    @Test fun `Enum subclass maps to TEXT`() = assertEquals("TEXT", DdlGenerator.kotlinTypeToCql(typeOf<ClusteringOrder>()))

    @Test
    fun `unsupported type throws KandraSchemaException`() {
        assertThrows<KandraSchemaException> {
            DdlGenerator.kotlinTypeToCql(typeOf<Exception>())
        }
    }

    // ── GH-31: frozen<> collection support ─────────────────────────────────

    @Test
    fun `Map value type that is itself a List is wrapped in FROZEN (nested collection)`() =
        assertEquals(
            "MAP<TEXT, FROZEN<LIST<TEXT>>>",
            DdlGenerator.kotlinTypeToCql(typeOf<Map<String, List<String>>>())
        )

    @Test
    fun `List of Set is rendered with the inner Set frozen`() =
        assertEquals(
            "LIST<FROZEN<SET<TEXT>>>",
            DdlGenerator.kotlinTypeToCql(typeOf<List<Set<String>>>())
        )

    @Test
    fun `top-level List is not frozen on its own (only nested collections are)`() =
        assertEquals("LIST<TEXT>", DdlGenerator.kotlinTypeToCql(typeOf<List<String>>()))

    @Test
    fun `collection-typed partition key wraps the column type in FROZEN in primary table DDL`() {
        val schema = SchemaRegistry.register(CollectionPartitionKeyEntity::class)
        val ddl = DdlGenerator.primaryTable(schema)
        assertTrue(ddl.contains("tags FROZEN<SET<TEXT>>"), "Expected FROZEN-wrapped collection PK in: $ddl")
    }

    @Test
    fun `collection-typed clustering key wraps the column type in FROZEN in primary table DDL`() {
        val schema = SchemaRegistry.register(CollectionClusteringKeyEntity::class)
        val ddl = DdlGenerator.primaryTable(schema)
        assertTrue(
            ddl.contains("history FROZEN<LIST<TEXT>>"),
            "Expected FROZEN-wrapped collection clustering key in: $ddl"
        )
    }

    @Test
    fun `regular non-key collection column is NOT wrapped in FROZEN`() {
        val schema = SchemaRegistry.register(RegularCollectionEntity::class)
        val ddl = DdlGenerator.primaryTable(schema)
        assertTrue(ddl.contains("tags SET<TEXT>"), "Expected plain SET<TEXT> in: $ddl")
        assertFalse(ddl.contains("FROZEN"), "A non-key collection column must not be frozen: $ddl")
    }

    @Test
    fun `Map column with a nested List value is wrapped in FROZEN in primary table DDL`() {
        val schema = SchemaRegistry.register(NestedCollectionEntity::class)
        val ddl = DdlGenerator.primaryTable(schema)
        assertTrue(
            ddl.contains("metadata MAP<TEXT, FROZEN<LIST<TEXT>>>"),
            "Expected nested collection frozen in: $ddl"
        )
    }

    @Test
    fun `LookupIndex column that is a collection type is frozen in the lookup table DDL`() {
        // The indexColumn becomes the lookup table's own PRIMARY KEY, regardless of whether it was
        // a partition/clustering key on the primary table's schema.
        val schema = SchemaRegistry.register(CollectionLookupIndexEntity::class)
        val lookup = schema.lookupTables.first()
        val ddl = DdlGenerator.lookupTable(lookup)
        assertTrue(ddl.contains("tags FROZEN<SET<TEXT>>"), "Expected FROZEN indexColumn in: $ddl")
    }
}
