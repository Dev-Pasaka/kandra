package io.kandra.core

import io.kandra.core.exception.KandraSchemaException
import io.kandra.core.schema.LookupTableSchema
import io.kandra.core.schema.TableSchema
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.isSubclassOf

/**
 * Generates CQL DDL statements from [TableSchema] instances.
 *
 * - Composite partition keys are wrapped in double parens: `PRIMARY KEY ((user_id, chain), created_at)`
 * - `@Ttl` tables get `WITH default_time_to_live = N` — lookup tables never inherit TTL
 * - `@SecondaryIndex` columns get `CREATE INDEX IF NOT EXISTS` statements
 */
@InternalKandraApi
object DdlGenerator {

    fun primaryTable(schema: TableSchema): String {
        val allColumns = buildList {
            addAll(schema.partitionKeys)
            addAll(schema.clusteringKeys)
            addAll(schema.columns)
            addAll(schema.lookupTables.map { it.indexColumn })
        }.distinctBy { it.cqlName }

        val columnDefs = allColumns.joinToString(",\n    ") { col ->
            val cqlType = if (col.isCounter) "COUNTER" else kotlinTypeToCql(col.type)
            "${col.cqlName} $cqlType"
        }

        val partitionKeyPart = if (schema.partitionKeys.size == 1) {
            schema.partitionKeys.first().cqlName
        } else {
            "(${schema.partitionKeys.joinToString(", ") { it.cqlName }})"
        }

        val primaryKey = if (schema.clusteringKeys.isEmpty()) {
            "PRIMARY KEY ($partitionKeyPart)"
        } else {
            val clusteringCols = schema.clusteringKeys.joinToString(", ") { it.cqlName }
            "PRIMARY KEY ($partitionKeyPart, $clusteringCols)"
        }

        val options = buildList<String> {
            if (schema.clusteringKeys.isNotEmpty()) {
                val orderDefs = schema.clusteringKeys.joinToString(", ") { col ->
                    "${col.cqlName} ${col.clusteringKey!!.order.name}"
                }
                add("CLUSTERING ORDER BY ($orderDefs)")
            }
            if (schema.defaultTtl != null) {
                add("default_time_to_live = ${schema.defaultTtl}")
            }
            if (schema.gcGraceSeconds != null) {
                add("gc_grace_seconds = ${schema.gcGraceSeconds}")
            }
        }

        val withClause = if (options.isNotEmpty()) "\nWITH ${options.joinToString("\nAND ")}" else ""

        return "CREATE TABLE IF NOT EXISTS ${schema.tableName} (\n    $columnDefs,\n    $primaryKey\n)$withClause;"
    }

    fun lookupTable(lookup: LookupTableSchema): String {
        val cols = buildList {
            add("${lookup.indexColumn.cqlName} ${kotlinTypeToCql(lookup.indexColumn.type)}")
            lookup.partitionKeyColumns.forEach { pk ->
                add("${pk.cqlName} ${kotlinTypeToCql(pk.type)}")
            }
            lookup.clusteringKeyColumns.forEach { ck ->
                add("${ck.cqlName} ${kotlinTypeToCql(ck.type)}")
            }
        }
        val colDefs = cols.joinToString(",\n    ")
        return "CREATE TABLE IF NOT EXISTS ${lookup.tableName} (\n    $colDefs,\n    PRIMARY KEY (${lookup.indexColumn.cqlName})\n);"
    }

    fun secondaryIndex(schema: TableSchema, column: io.kandra.core.schema.ColumnSchema): String =
        "CREATE INDEX IF NOT EXISTS ${schema.tableName}_${column.cqlName}_idx ON ${schema.tableName} (${column.cqlName});"

    fun alterTableAddColumn(schema: TableSchema, column: io.kandra.core.schema.ColumnSchema): String =
        "ALTER TABLE ${schema.tableName} ADD ${column.cqlName} ${kotlinTypeToCql(column.type)};"

    /** Returns the CQL type name for a column as it would appear in DDL (e.g. "UUID", "TEXT", "BIGINT"). */
    fun cqlTypeString(column: io.kandra.core.schema.ColumnSchema): String =
        if (column.isCounter) "COUNTER" else kotlinTypeToCql(column.type)

    fun allStatements(schema: TableSchema): List<String> = buildList {
        add(primaryTable(schema))
        schema.lookupTables.forEach { add(lookupTable(it)) }
        schema.secondaryIndexes.forEach { col -> add(secondaryIndex(schema, col)) }
    }

    internal fun kotlinTypeToCql(type: KType): String {
        val classifier = type.classifier as? KClass<*>
            ?: throw KandraSchemaException("Unsupported type: $type")
        return mapType(classifier, type)
    }

    private fun mapType(klass: KClass<*>, type: KType): String = when {
        klass == UUID::class -> "UUID"
        klass == String::class -> "TEXT"
        klass == Int::class || klass == Integer::class -> "INT"
        klass == Long::class -> "BIGINT"
        klass == Boolean::class -> "BOOLEAN"
        klass == Double::class -> "DOUBLE"
        klass == Float::class -> "FLOAT"
        klass == Instant::class -> "TIMESTAMP"
        klass == LocalDate::class -> "DATE"
        klass == ByteArray::class -> "BLOB"
        klass == BigDecimal::class -> "DECIMAL"
        klass == List::class -> {
            val inner = type.arguments.firstOrNull()?.type
                ?: throw KandraSchemaException("List type argument missing in: $type")
            "LIST<${kotlinTypeToCql(inner)}>"
        }
        klass == Set::class -> {
            val inner = type.arguments.firstOrNull()?.type
                ?: throw KandraSchemaException("Set type argument missing in: $type")
            "SET<${kotlinTypeToCql(inner)}>"
        }
        klass == Map::class -> {
            val keyType = type.arguments.getOrNull(0)?.type
                ?: throw KandraSchemaException("Map key type argument missing in: $type")
            val valueType = type.arguments.getOrNull(1)?.type
                ?: throw KandraSchemaException("Map value type argument missing in: $type")
            "MAP<${kotlinTypeToCql(keyType)}, ${kotlinTypeToCql(valueType)}>"
        }
        klass.isSubclassOf(Enum::class) -> "TEXT"
        else -> throw KandraSchemaException(
            "Unsupported type: $klass — use @Column or register a custom encoder/decoder via KandraCodec."
        )
    }
}
