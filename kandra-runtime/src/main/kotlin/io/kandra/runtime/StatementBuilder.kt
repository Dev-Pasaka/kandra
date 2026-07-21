package io.kandra.runtime

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel
import com.datastax.oss.driver.api.core.cql.BoundStatement
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kandra.core.InternalKandraApi
import io.kandra.core.KandraConsistency
import io.kandra.core.annotations.ReadConsistency
import io.kandra.core.annotations.WriteConsistency
import io.kandra.core.exception.KandraSchemaException
import io.kandra.core.schema.LookupTableSchema
import io.kandra.core.schema.TableSchema
import io.kandra.runtime.codec.KandraCodec
import io.kandra.runtime.codec.KandraUnset
import java.util.Collections
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties

private val logger = KotlinLogging.logger {}

/**
 * Builds [BoundStatement] instances for all CRUD operations.
 *
 * Prepared statements are cached in a bounded LRU cache keyed by CQL string.
 * Idempotency is set per statement type — SELECT and DELETE are idempotent;
 * plain INSERT and collection mutations are not (to prevent duplicate writes on retry).
 */
@InternalKandraApi
class StatementBuilder(
    private val session: CqlSession,
    private val codec: KandraCodec = KandraCodec.default,
    private val debugConfig: DebugConfig = DebugConfig(),
    private val consistencyConfig: ConsistencyConfig = ConsistencyConfig(),
    cacheSize: Int = 1000
) {
    private val cache: MutableMap<String, PreparedStatement> = Collections.synchronizedMap(
        object : LinkedHashMap<String, PreparedStatement>(cacheSize + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, PreparedStatement>): Boolean {
                if (size > cacheSize) {
                    logger.warn { "Prepared statement cache eviction: '${eldest.key}'. Consider increasing preparedStatementCacheSize." }
                    return true
                }
                return false
            }
        }
    )

    private fun prepare(cql: String): PreparedStatement {
        if (debugConfig.logQueries) logger.debug { "Kandra CQL: $cql" }
        return cache.getOrPut(cql) { session.prepare(cql) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun BoundStatement.setEncoded(idx: Int, value: Any): BoundStatement =
        set(idx, value, value::class.java as Class<Any>)

    private fun resolveWriteConsistency(schema: TableSchema, override: KandraConsistency?): KandraConsistency {
        override?.let { return it }
        schema.entityClass.findAnnotation<WriteConsistency>()?.let { return it.level }
        return consistencyConfig.defaultWrite
    }

    private fun resolveReadConsistency(schema: TableSchema, override: KandraConsistency?): KandraConsistency {
        override?.let { return it }
        schema.entityClass.findAnnotation<ReadConsistency>()?.let { return it.level }
        return consistencyConfig.defaultRead
    }

    private fun KandraConsistency.toDriverLevel() =
        DefaultConsistencyLevel.valueOf(this.name)

    fun insertPrimary(
        schema: TableSchema,
        entity: Any,
        ttlSeconds: Int? = null,
        ifNotExists: Boolean = false,
        timestampMicros: Long? = null,
        consistency: KandraConsistency? = null
    ): BoundStatement {
        val allCols = buildList {
            addAll(schema.partitionKeys)
            addAll(schema.clusteringKeys)
            addAll(schema.columns)
            addAll(schema.lookupTables.map { it.indexColumn })
        }.distinctBy { it.cqlName }

        val colNames = allCols.joinToString(", ") { it.cqlName }
        val placeholders = allCols.joinToString(", ") { "?" }

        val effectiveTtl = ttlSeconds ?: schema.defaultTtl
        val modifiers = buildList<String> {
            if (effectiveTtl != null) add("TTL $effectiveTtl")
            if (timestampMicros != null) add("TIMESTAMP $timestampMicros")
        }
        val usingClause = if (modifiers.isNotEmpty()) " USING ${modifiers.joinToString(" AND ")}" else ""
        val ifClause = if (ifNotExists) " IF NOT EXISTS" else ""

        val cql = "INSERT INTO ${schema.tableName} ($colNames) VALUES ($placeholders)$ifClause$usingClause"
        val prepared = prepare(cql)

        val props = entity::class.memberProperties.associateBy { it.name }
        var stmt = prepared.bind()
        allCols.forEachIndexed { idx, col ->
            val prop = props[col.propertyName]
            val value = prop?.call(entity)
            val encoded = codec.encode(value, col.type)
            if (encoded === KandraUnset) {
                if (col.isPartitionKey || col.clusteringKey != null) {
                    throw KandraSchemaException(
                        "Primary/clustering key column '${col.propertyName}' cannot be UNSET (null). " +
                            "Partition keys must always have a value."
                    )
                }
                stmt = stmt.unset(idx)
            } else {
                stmt = stmt.setEncoded(idx, encoded!!)
            }
        }

        // Plain INSERT is NOT idempotent — retry = duplicate risk
        // LWT INSERT IF NOT EXISTS is idempotent — safe to retry
        stmt = stmt.setIdempotent(ifNotExists)
        stmt = stmt.setConsistencyLevel(resolveWriteConsistency(schema, consistency).toDriverLevel())
        return stmt
    }

    /**
     * Same as [insertPrimary] but binds null as actual null (creates tombstones).
     * Use when the caller explicitly wants to clear optional columns.
     */
    fun insertPrimaryWithNulls(
        schema: TableSchema,
        entity: Any,
        ttlSeconds: Int? = null,
        ifNotExists: Boolean = false,
        timestampMicros: Long? = null,
        consistency: KandraConsistency? = null
    ): BoundStatement {
        val allCols = buildList {
            addAll(schema.partitionKeys)
            addAll(schema.clusteringKeys)
            addAll(schema.columns)
            addAll(schema.lookupTables.map { it.indexColumn })
        }.distinctBy { it.cqlName }

        val colNames = allCols.joinToString(", ") { it.cqlName }
        val placeholders = allCols.joinToString(", ") { "?" }
        val effectiveTtl = ttlSeconds ?: schema.defaultTtl
        val modifiers = buildList<String> {
            if (effectiveTtl != null) add("TTL $effectiveTtl")
            if (timestampMicros != null) add("TIMESTAMP $timestampMicros")
        }
        val usingClause = if (modifiers.isNotEmpty()) " USING ${modifiers.joinToString(" AND ")}" else ""
        val ifClause = if (ifNotExists) " IF NOT EXISTS" else ""
        val cql = "INSERT INTO ${schema.tableName} ($colNames) VALUES ($placeholders)$ifClause$usingClause"
        val prepared = prepare(cql)

        val props = entity::class.memberProperties.associateBy { it.name }
        var stmt = prepared.bind()
        allCols.forEachIndexed { idx, col ->
            val prop = props[col.propertyName]
            val value = prop?.call(entity)
            // Null is bound as null (tombstone) — intentional for saveWithNulls
            val encoded: Any? = if (value == null) null
            else codec.encode(value, col.type).let { if (it === KandraUnset) null else it }
            if (encoded == null) {
                stmt = stmt.setBytesUnsafe(idx, null)
            } else {
                stmt = stmt.setEncoded(idx, encoded)
            }
        }
        stmt = stmt.setIdempotent(ifNotExists)
        stmt = stmt.setConsistencyLevel(resolveWriteConsistency(schema, consistency).toDriverLevel())
        return stmt
    }

    fun insertLookup(lookup: LookupTableSchema, entity: Any): BoundStatement {
        val cols = listOf(lookup.indexColumn) + lookup.partitionKeyColumns
        val colNames = cols.joinToString(", ") { it.cqlName }
        val placeholders = cols.joinToString(", ") { "?" }
        val cql = "INSERT INTO ${lookup.tableName} ($colNames) VALUES ($placeholders)"
        val prepared = prepare(cql)

        val props = entity::class.memberProperties.associateBy { it.name }
        var stmt = prepared.bind()
        cols.forEachIndexed { idx, col ->
            val prop = props[col.propertyName]
            val value = prop?.call(entity)
            val encoded = codec.encode(value, col.type)
            stmt = if (encoded === KandraUnset) stmt.unset(idx)
                   else stmt.setEncoded(idx, encoded!!)
        }
        return stmt.setIdempotent(false) // lookup inserts are not idempotent
    }

    fun deleteLookup(lookup: LookupTableSchema, indexValue: Any): BoundStatement {
        val cql = "DELETE FROM ${lookup.tableName} WHERE ${lookup.indexColumn.cqlName} = ?"
        val prepared = prepare(cql)
        return prepared.bind(codec.encode(indexValue, lookup.indexColumn.type))
            .setIdempotent(true) // delete is idempotent
    }

    fun selectById(schema: TableSchema, vararg idValues: Any, consistency: KandraConsistency? = null): BoundStatement {
        val whereParts = schema.partitionKeys.joinToString(" AND ") { "${it.cqlName} = ?" }
        val cql = "SELECT * FROM ${schema.tableName} WHERE $whereParts"
        val prepared = prepare(cql)
        val encodedValues = schema.partitionKeys.zip(idValues.toList()).map { (col, v) ->
            codec.encode(v, col.type)
        }
        return prepared.bind(*encodedValues.toTypedArray())
            .setIdempotent(true)
            .setConsistencyLevel(resolveReadConsistency(schema, consistency).toDriverLevel())
    }

    fun selectByLookup(lookup: LookupTableSchema, value: Any, consistency: KandraConsistency? = null): BoundStatement {
        val pkCols = lookup.partitionKeyColumns.joinToString(", ") { it.cqlName }
        val cql = "SELECT $pkCols FROM ${lookup.tableName} WHERE ${lookup.indexColumn.cqlName} = ?"
        val prepared = prepare(cql)
        return prepared.bind(codec.encode(value, lookup.indexColumn.type))
            .setIdempotent(true)
    }

    fun selectByPartitionKeyIn(schema: TableSchema, ids: List<Any>, consistency: KandraConsistency? = null): BoundStatement {
        if (schema.partitionKeys.size != 1) throw KandraSchemaException(
            "IN on partition key is only supported for single-column partition keys. " +
            "Table '${schema.tableName}' has a composite partition key."
        )
        val pkCol = schema.partitionKeys.first()
        val cql = "SELECT * FROM ${schema.tableName} WHERE ${pkCol.cqlName} IN ?"
        val prepared = prepare(cql)
        val encoded = ids.map { codec.encode(it, pkCol.type) }
        return prepared.bind(encoded)
            .setIdempotent(true)
            .setConsistencyLevel(resolveReadConsistency(schema, consistency).toDriverLevel())
    }

    fun deleteById(schema: TableSchema, vararg idValues: Any): BoundStatement {
        val whereParts = schema.partitionKeys.joinToString(" AND ") { "${it.cqlName} = ?" }
        val cql = "DELETE FROM ${schema.tableName} WHERE $whereParts"
        val prepared = prepare(cql)
        val encodedValues = schema.partitionKeys.zip(idValues.toList()).map { (col, v) ->
            codec.encode(v, col.type)
        }
        return prepared.bind(*encodedValues.toTypedArray())
            .setIdempotent(true) // delete is idempotent
    }

    fun deleteByPartitionKeys(schema: TableSchema, vararg keyValues: Any): BoundStatement =
        deleteById(schema, *keyValues)

    fun appendToCollection(
        schema: TableSchema,
        partitionKeyValues: List<Any>,
        columnName: String,
        values: Any,
        consistency: KandraConsistency? = null
    ): BoundStatement {
        val col = (schema.columns + schema.lookupTables.map { it.indexColumn })
            .find { it.cqlName == columnName || it.propertyName == columnName }
            ?: throw KandraSchemaException("Column '$columnName' not found in schema '${schema.tableName}'")
        val whereParts = schema.partitionKeys.joinToString(" AND ") { "${it.cqlName} = ?" }
        val cql = "UPDATE ${schema.tableName} SET ${col.cqlName} = ${col.cqlName} + ? WHERE $whereParts"
        val prepared = prepare(cql)
        val encodedPks = schema.partitionKeys.zip(partitionKeyValues).map { (pkCol, v) ->
            codec.encode(v, pkCol.type)
        }
        return prepared.bind(values, *encodedPks.toTypedArray())
            .setIdempotent(false)
            .setConsistencyLevel(resolveWriteConsistency(schema, consistency).toDriverLevel())
    }

    fun removeFromCollection(
        schema: TableSchema,
        partitionKeyValues: List<Any>,
        columnName: String,
        values: Any,
        consistency: KandraConsistency? = null
    ): BoundStatement {
        val col = (schema.columns + schema.lookupTables.map { it.indexColumn })
            .find { it.cqlName == columnName || it.propertyName == columnName }
            ?: throw KandraSchemaException("Column '$columnName' not found in schema '${schema.tableName}'")
        val whereParts = schema.partitionKeys.joinToString(" AND ") { "${it.cqlName} = ?" }
        val cql = "UPDATE ${schema.tableName} SET ${col.cqlName} = ${col.cqlName} - ? WHERE $whereParts"
        val prepared = prepare(cql)
        val encodedPks = schema.partitionKeys.zip(partitionKeyValues).map { (pkCol, v) ->
            codec.encode(v, pkCol.type)
        }
        return prepared.bind(values, *encodedPks.toTypedArray())
            .setIdempotent(false)
            .setConsistencyLevel(resolveWriteConsistency(schema, consistency).toDriverLevel())
    }

    fun counterUpdate(
        schema: TableSchema,
        columnName: String,
        partitionKeys: Map<String, Any>,
        delta: Long,
        consistency: KandraConsistency? = null
    ): BoundStatement {
        val col = schema.columns.find { it.propertyName == columnName || it.cqlName == columnName }
            ?: throw KandraSchemaException("Counter column '$columnName' not found in '${schema.tableName}'")
        val whereParts = schema.partitionKeys.joinToString(" AND ") { "${it.cqlName} = ?" }
        val op = if (delta >= 0) "+" else "-"
        val cql = "UPDATE ${schema.tableName} SET ${col.cqlName} = ${col.cqlName} $op ? WHERE $whereParts"
        val prepared = prepare(cql)
        val pkValues = schema.partitionKeys.map { pk ->
            partitionKeys[pk.propertyName] ?: partitionKeys[pk.cqlName]
                ?: throw KandraSchemaException("Missing partition key value for '${pk.cqlName}'")
        }
        return prepared.bind(Math.abs(delta), *pkValues.toTypedArray())
            .setIdempotent(false)
            .setConsistencyLevel(resolveWriteConsistency(schema, consistency).toDriverLevel())
    }

    fun existsQuery(schema: TableSchema, whereCql: String, values: List<Any?>): BoundStatement {
        val pkCols = schema.partitionKeys.joinToString(", ") { it.cqlName }
        val cql = "SELECT $pkCols FROM ${schema.tableName} WHERE $whereCql LIMIT 1"
        val prepared = prepare(cql)
        return prepared.bind(*values.toTypedArray()).setIdempotent(true)
    }
}
