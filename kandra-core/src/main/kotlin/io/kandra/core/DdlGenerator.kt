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
        // Duplicate cqlNames across this set are now impossible to reach here — SchemaRegistry.
        // buildSchema (GH-31) throws KandraSchemaException at registration time for any collision
        // across partition + clustering + regular (incl. @LookupIndex) columns. distinctBy is kept
        // anyway: it's structurally required regardless of that guarantee, since @LookupIndex
        // columns are deliberately listed twice here (once via `columns`, once via
        // `lookupTables.map { it.indexColumn }`) and must be de-duped to avoid emitting the same
        // column twice in DDL.
        val allColumns = buildList {
            addAll(schema.partitionKeys)
            addAll(schema.clusteringKeys)
            addAll(schema.columns)
            addAll(schema.lookupTables.map { it.indexColumn })
        }.distinctBy { it.cqlName }

        val columnDefs = allColumns.joinToString(",\n    ") { col ->
            val cqlType = if (col.isCounter) "COUNTER" else columnCqlType(col)
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
            // indexColumn is this lookup table's own PRIMARY KEY, regardless of whether it was a
            // partition/clustering key on the *primary* table's schema — force key-aware (frozen<>
            // if a collection) rendering here rather than trusting its origin-schema flags. See GH-31.
            add("${lookup.indexColumn.cqlName} ${columnCqlType(lookup.indexColumn, forceKey = true)}")
            lookup.partitionKeyColumns.forEach { pk ->
                add("${pk.cqlName} ${columnCqlType(pk)}")
            }
            lookup.clusteringKeyColumns.forEach { ck ->
                add("${ck.cqlName} ${columnCqlType(ck)}")
            }
        }
        val colDefs = cols.joinToString(",\n    ")
        return "CREATE TABLE IF NOT EXISTS ${lookup.tableName} (\n    $colDefs,\n    PRIMARY KEY (${lookup.indexColumn.cqlName})\n);"
    }

    fun secondaryIndex(schema: TableSchema, column: io.kandra.core.schema.ColumnSchema): String =
        "CREATE INDEX IF NOT EXISTS ${schema.tableName}_${column.cqlName}_idx ON ${schema.tableName} (${column.cqlName});"

    fun alterTableAddColumn(schema: TableSchema, column: io.kandra.core.schema.ColumnSchema): String =
        "ALTER TABLE ${schema.tableName} ADD ${column.cqlName} ${columnCqlType(column)};"

    /** Returns the CQL type name for a column as it would appear in DDL (e.g. "UUID", "TEXT", "BIGINT"). */
    fun cqlTypeString(column: io.kandra.core.schema.ColumnSchema): String =
        if (column.isCounter) "COUNTER" else columnCqlType(column)

    /**
     * Renders a column's CQL type, wrapping it in `FROZEN<...>` when it is a collection
     * (`List`/`Set`/`Map`) used as a partition or clustering key component — ScyllaDB (like
     * Cassandra) rejects a non-frozen collection in a primary-key position at `CREATE TABLE` time.
     * [forceKey] lets [lookupTable] treat its `indexColumn` as a key here even though that column's
     * `isPartitionKey`/`clusteringKey` flags describe its role on the *primary* table's schema, not
     * the lookup table (where it is always the sole PK column). See GH-31.
     */
    private fun columnCqlType(column: io.kandra.core.schema.ColumnSchema, forceKey: Boolean = false): String {
        val base = kotlinTypeToCql(column.type)
        val isKeyColumn = forceKey || column.isPartitionKey || column.clusteringKey != null
        return if (isKeyColumn && isCollectionType(column.type)) "FROZEN<$base>" else base
    }

    private fun isCollectionType(type: KType): Boolean =
        when (type.classifier as? KClass<*>) {
            List::class, Set::class, Map::class -> true
            else -> false
        }

    fun allStatements(schema: TableSchema): List<String> = buildList {
        add(primaryTable(schema))
        schema.lookupTables.forEach { add(lookupTable(it)) }
        schema.secondaryIndexes.forEach { col -> add(secondaryIndex(schema, col)) }
    }

    internal fun kotlinTypeToCql(type: KType): String = kotlinTypeToCql(type, nested = false)

    /**
     * [nested] is true when [type] is being rendered as a type *argument* of an enclosing
     * `List`/`Set`/`Map` (i.e. one level of collection-inside-collection) — ScyllaDB/Cassandra
     * require any collection nested inside another collection to be `frozen<...>` (e.g.
     * `Map<String, List<T>>` → `MAP<TEXT, FROZEN<LIST<...>>>`). The outermost call (from the
     * public [kotlinTypeToCql]) always passes `nested = false`, since a top-level collection
     * column's own frozen-ness is a separate, key-membership-driven decision made by
     * [columnCqlType] — not by this function.
     */
    private fun kotlinTypeToCql(type: KType, nested: Boolean): String {
        val classifier = type.classifier as? KClass<*>
            ?: throw KandraSchemaException("Unsupported type: $type")
        return mapType(classifier, type, nested)
    }

    private fun mapType(klass: KClass<*>, type: KType, nested: Boolean): String {
        val rendered = when {
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
                "LIST<${kotlinTypeToCql(inner, nested = true)}>"
            }
            klass == Set::class -> {
                val inner = type.arguments.firstOrNull()?.type
                    ?: throw KandraSchemaException("Set type argument missing in: $type")
                "SET<${kotlinTypeToCql(inner, nested = true)}>"
            }
            klass == Map::class -> {
                val keyType = type.arguments.getOrNull(0)?.type
                    ?: throw KandraSchemaException("Map key type argument missing in: $type")
                val valueType = type.arguments.getOrNull(1)?.type
                    ?: throw KandraSchemaException("Map value type argument missing in: $type")
                "MAP<${kotlinTypeToCql(keyType, nested = true)}, ${kotlinTypeToCql(valueType, nested = true)}>"
            }
            klass.isSubclassOf(Enum::class) -> "TEXT"
            else -> throw KandraSchemaException(
                "Unsupported type: $klass — use @Column or register a custom encoder/decoder via KandraCodec."
            )
        }
        val isCollection = klass == List::class || klass == Set::class || klass == Map::class
        return if (nested && isCollection) "FROZEN<$rendered>" else rendered
    }
}
