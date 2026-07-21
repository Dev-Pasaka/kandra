package io.kandra.core

import io.kandra.core.annotations.CacheResult
import io.kandra.core.annotations.ClusteringKey
import io.kandra.core.annotations.Column
import io.kandra.core.annotations.Counter
import io.kandra.core.annotations.CreatedAt
import io.kandra.core.annotations.LookupIndex
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.annotations.SecondaryIndex
import io.kandra.core.annotations.Sensitive
import io.kandra.core.annotations.SoftDelete
import io.kandra.core.annotations.Transient
import io.kandra.core.annotations.Ttl
import io.kandra.core.annotations.UpdatedAt
import io.kandra.core.annotations.Version
import io.kandra.core.exception.KandraSchemaException
import io.kandra.core.schema.CacheResultConfig
import io.kandra.core.schema.ClusteringKeySchema
import io.kandra.core.schema.ColumnSchema
import io.kandra.core.schema.LookupIndexSchema
import io.kandra.core.schema.LookupTableSchema
import io.kandra.core.schema.TableSchema
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties

/**
 * Thread-safe registry mapping entity classes to their [TableSchema].
 *
 * Call [register] for every `@ScyllaTable`-annotated class at startup.
 * All validation happens eagerly so schema errors surface before any query.
 */
@InternalKandraApi
object SchemaRegistry {

    private val registry = ConcurrentHashMap<KClass<*>, TableSchema>()

    fun <T : Any> register(klass: KClass<T>): TableSchema =
        registry.getOrPut(klass) { buildSchema(klass) }

    fun get(klass: KClass<*>): TableSchema =
        registry[klass] ?: throw KandraSchemaException(
            "Class '${klass.simpleName}' is not registered. Call register(${klass.simpleName}::class) first."
        )

    fun getOrNull(klass: KClass<*>): TableSchema? = registry[klass]

    fun all(): List<TableSchema> = registry.values.toList()

    fun clear() = registry.clear()

    private fun <T : Any> buildSchema(klass: KClass<T>): TableSchema {
        val tableAnnotation = klass.findAnnotation<ScyllaTable>()
            ?: throw KandraSchemaException("Class '${klass.simpleName}' is missing @ScyllaTable annotation.")
        val tableName = tableAnnotation.name
        val ttlAnnotation = klass.findAnnotation<Ttl>()

        val properties = klass.memberProperties

        val columnSchemas = properties.map { prop ->
            val isTransient = prop.findAnnotation<Transient>() != null
            val partitionKeyAnn = prop.findAnnotation<PartitionKey>()
            val clusteringKeyAnn = prop.findAnnotation<ClusteringKey>()
            val lookupIndexAnn = prop.findAnnotation<LookupIndex>()
            val columnAnn = prop.findAnnotation<Column>()
            val isCounter = prop.findAnnotation<Counter>() != null
            val isCreatedAt = prop.findAnnotation<CreatedAt>() != null
            val isUpdatedAt = prop.findAnnotation<UpdatedAt>() != null
            val isSecondaryIndex = prop.findAnnotation<SecondaryIndex>() != null
            val isSensitive = prop.findAnnotation<Sensitive>() != null
            val isVersion = prop.findAnnotation<Version>() != null
            val cqlName = columnAnn?.name ?: camelToSnake(prop.name)

            // @CreatedAt / @UpdatedAt must be on Instant fields
            if (isCreatedAt || isUpdatedAt) {
                val classifier = prop.returnType.classifier as? KClass<*>
                if (classifier != Instant::class) {
                    throw KandraSchemaException(
                        "@${if (isCreatedAt) "CreatedAt" else "UpdatedAt"} on '${klass.simpleName}.${prop.name}' " +
                            "must be an Instant field."
                    )
                }
            }

            ColumnSchema(
                propertyName = prop.name,
                cqlName = cqlName,
                type = prop.returnType,
                isPartitionKey = partitionKeyAnn != null,
                clusteringKey = clusteringKeyAnn?.let { ClusteringKeySchema(it.order, it.index) },
                lookupIndex = lookupIndexAnn?.let {
                    LookupIndexSchema("${tableName}_${it.tableSuffix}", it.consistency)
                },
                isTransient = isTransient,
                isCounter = isCounter,
                isCreatedAt = isCreatedAt,
                isUpdatedAt = isUpdatedAt,
                isSecondaryIndex = isSecondaryIndex,
                isSensitive = isSensitive,
                isVersion = isVersion
            )
        }

        // ── Partition key validation ──────────────────────────────────────────
        val pkColumns = columnSchemas.filter { it.isPartitionKey }
        if (pkColumns.isEmpty()) throw KandraSchemaException(
            "Class '${klass.simpleName}' has no @PartitionKey property. Exactly one is required."
        )

        // Validate unique indices
        val pkIndexCounts = properties
            .mapNotNull { it.findAnnotation<PartitionKey>()?.index }
            .groupingBy { it }.eachCount()
        pkIndexCounts.entries.firstOrNull { it.value > 1 }?.let { (idx, _) ->
            throw KandraSchemaException("Duplicate @PartitionKey index $idx on ${klass.simpleName}")
        }

        val partitionKeys = columnSchemas
            .filter { it.isPartitionKey }
            .sortedBy { prop ->
                properties.first { it.name == prop.propertyName }
                    .findAnnotation<PartitionKey>()!!.index
            }

        // ── Duplicate lookup suffix validation ────────────────────────────────
        val lookupColumns = columnSchemas.filter { it.lookupIndex != null && !it.isTransient }
        val suffixCounts = lookupColumns.groupBy { it.lookupIndex!!.tableName }
        suffixCounts.entries.firstOrNull { it.value.size > 1 }?.let { (tableSuffix, cols) ->
            throw KandraSchemaException(
                "Class '${klass.simpleName}' has duplicate @LookupIndex table name '$tableSuffix' " +
                    "on properties: ${cols.joinToString { it.propertyName }}"
            )
        }

        // ── Counter table validation ───────────────────────────────────────────
        val nonKeyColumns = columnSchemas.filter { col ->
            !col.isPartitionKey && col.clusteringKey == null && !col.isTransient &&
                !col.isCreatedAt && !col.isUpdatedAt
        }
        val counterColumns = nonKeyColumns.filter { it.isCounter }
        val nonCounterColumns = nonKeyColumns.filter { !it.isCounter }
        val isCounterTable = counterColumns.isNotEmpty()
        if (isCounterTable && nonCounterColumns.isNotEmpty()) {
            throw KandraSchemaException(
                "Class '${klass.simpleName}' mixes @Counter and non-@Counter columns. " +
                    "All non-key columns must be @Counter in a counter table."
            )
        }

        // ── @CreatedAt / @UpdatedAt ────────────────────────────────────────────
        val createdAtCols = columnSchemas.filter { it.isCreatedAt }
        val updatedAtCols = columnSchemas.filter { it.isUpdatedAt }
        if (createdAtCols.size > 1) throw KandraSchemaException("At most one @CreatedAt per entity (${klass.simpleName})")
        if (updatedAtCols.size > 1) throw KandraSchemaException("At most one @UpdatedAt per entity (${klass.simpleName})")

        // ── @Version validation ────────────────────────────────────────────────
        val versionColumns = columnSchemas.filter { it.isVersion }
        if (versionColumns.size > 1) throw KandraSchemaException(
            "At most one @Version column per entity (${klass.simpleName})"
        )
        versionColumns.firstOrNull()?.let { col ->
            val classifier = col.type.classifier as? KClass<*>
            if (classifier != Long::class && classifier != Instant::class) {
                throw KandraSchemaException(
                    "@Version column '${klass.simpleName}.${col.propertyName}' must be Long or Instant, got: ${col.type}"
                )
            }
        }

        val clusteringKeys = columnSchemas
            .filter { it.clusteringKey != null && !it.isTransient }
            .sortedBy { it.clusteringKey!!.index }

        val regularColumns = columnSchemas.filter { col ->
            !col.isPartitionKey && col.clusteringKey == null && !col.isTransient
        }

        val lookupTables = lookupColumns.map { col ->
            LookupTableSchema(
                tableName = col.lookupIndex!!.tableName,
                indexColumn = col,
                partitionKeyColumns = partitionKeys,
                consistency = col.lookupIndex.consistency
            )
        }

        val secondaryIndexes = columnSchemas.filter { it.isSecondaryIndex && !it.isTransient }

        val softDeleteAnn = klass.findAnnotation<SoftDelete>()
        val cacheResultAnn = klass.findAnnotation<CacheResult>()

        // ── @SoftDelete marker column validation ──────────────────────────────
        val softDeleteMarkerColumn = softDeleteAnn?.markerProperty
            ?.takeIf { it.isNotEmpty() }
            ?.let { markerProp ->
                val col = columnSchemas.find { it.propertyName == markerProp }
                    ?: throw KandraSchemaException(
                        "@SoftDelete(markerProperty = \"$markerProp\") on '${klass.simpleName}' — " +
                            "no property named '$markerProp' found."
                    )
                val classifier = col.type.classifier as? KClass<*>
                if (classifier != Boolean::class) {
                    throw KandraSchemaException(
                        "@SoftDelete markerProperty '${klass.simpleName}.$markerProp' must be a Boolean field, got: ${col.type}"
                    )
                }
                col
            }

        return TableSchema(
            entityClass = klass,
            tableName = tableName,
            partitionKeys = partitionKeys,
            clusteringKeys = clusteringKeys,
            columns = regularColumns,
            lookupTables = lookupTables,
            defaultTtl = ttlAnnotation?.seconds,
            isCounterTable = isCounterTable,
            createdAtColumn = createdAtCols.firstOrNull(),
            updatedAtColumn = updatedAtCols.firstOrNull(),
            secondaryIndexes = secondaryIndexes,
            versionColumn = versionColumns.firstOrNull(),
            isSoftDelete = softDeleteAnn != null,
            softDeleteTtlSeconds = softDeleteAnn?.ttlSeconds,
            softDeleteMarkerColumn = softDeleteMarkerColumn,
            gcGraceSeconds = tableAnnotation.gcGraceSeconds.takeIf { it >= 0 },
            cacheConfig = cacheResultAnn?.let { CacheResultConfig(it.ttlSeconds, it.maxSize) }
        )
    }

    internal fun camelToSnake(name: String): String =
        name.replace(Regex("([A-Z])")) { "_${it.value.lowercase()}" }.trimStart('_')
}
