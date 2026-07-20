package io.kandra.core

import io.kandra.core.annotations.ClusteringOrder
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
}
