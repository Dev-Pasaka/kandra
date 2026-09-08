package io.kandra.runtime

import io.kandra.core.InternalKandraApi
import io.kandra.core.schema.ColumnSchema
import io.kandra.core.schema.EntityReflection
import io.kandra.core.schema.TableSchema
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.typeOf

/** Placeholder entity solely so [TableSchema.entityClass] has something real to point at. */
private data class LoggerTestEntity(val id: Int, val secret: String)

/**
 * Regression coverage for GH #70 (ISS-069 item 1) — [KandraEntityLogger.safeToString] previously had
 * zero call sites; it's now wired into [QueryExecutor]'s debug-query logging. These tests exercise the
 * redaction logic directly.
 */
@OptIn(InternalKandraApi::class)
class KandraEntityLoggerTest {

    private fun schema(idSensitive: Boolean): TableSchema {
        val idCol = ColumnSchema("id", "id", typeOf<Int>(), isPartitionKey = true)
        val secretCol = ColumnSchema("secret", "secret", typeOf<String>(), isSensitive = idSensitive)
        return TableSchema(
            entityClass = LoggerTestEntity::class,
            tableName = "logger_test_entities",
            partitionKeys = listOf(idCol),
            clusteringKeys = emptyList(),
            columns = listOf(secretCol),
            lookupTables = emptyList(),
            reflection = EntityReflection(
                copyFunction = LoggerTestEntity::class.memberFunctions.find { it.name == "copy" },
                copyParameters = LoggerTestEntity::class.memberFunctions.find { it.name == "copy" }?.parameters ?: emptyList(),
                propertiesByName = LoggerTestEntity::class.memberProperties.associateBy { it.name },
                primaryConstructor = LoggerTestEntity::class.primaryConstructor,
                constructorParameters = LoggerTestEntity::class.primaryConstructor?.parameters ?: emptyList(),
                columnsByProperty = listOf(idCol, secretCol).associateBy { it.propertyName }
            )
        )
    }

    @Test
    fun `safeToString redacts columns marked @Sensitive`() {
        val entity = LoggerTestEntity(id = 12345, secret = "hunter2")
        val redacted = KandraEntityLogger.safeToString(entity, schema(idSensitive = true))
        assertTrue(redacted.contains("secret=***"), "Expected sensitive field to be redacted, got: $redacted")
        assertFalse(redacted.contains("hunter2"), "Expected sensitive value not to be logged, got: $redacted")
        assertTrue(redacted.contains("id=12345"), "Expected non-sensitive field to be logged normally, got: $redacted")
    }

    @Test
    fun `safeToString logs non-sensitive columns as-is`() {
        val entity = LoggerTestEntity(id = 12345, secret = "hunter2")
        val plain = KandraEntityLogger.safeToString(entity, schema(idSensitive = false))
        assertTrue(plain.contains("secret=hunter2"), "Expected non-sensitive field to be logged normally, got: $plain")
    }
}
