package io.kandra.core.schema

import io.kandra.core.annotations.ClusteringOrder
import io.kandra.core.annotations.LookupConsistency
import io.kandra.core.annotations.UuidStrategy
import kotlin.reflect.KClass
import kotlin.reflect.KType

data class ColumnSchema(
    val propertyName: String,
    val cqlName: String,
    val type: KType,
    val isPartitionKey: Boolean = false,
    val clusteringKey: ClusteringKeySchema? = null,
    val lookupIndex: LookupIndexSchema? = null,
    val isTransient: Boolean = false,
    val isCounter: Boolean = false,
    val isCreatedAt: Boolean = false,
    val isUpdatedAt: Boolean = false,
    val isSecondaryIndex: Boolean = false,
    val isSensitive: Boolean = false,
    val isVersion: Boolean = false,
    val generatedUuidStrategy: UuidStrategy? = null
)

data class ClusteringKeySchema(val order: ClusteringOrder, val index: Int)

data class LookupIndexSchema(val tableName: String, val consistency: LookupConsistency)

data class TableSchema(
    val entityClass: KClass<*>,
    val tableName: String,
    /** All partition key columns, ordered by @PartitionKey(index). */
    val partitionKeys: List<ColumnSchema>,
    val clusteringKeys: List<ColumnSchema>,
    val columns: List<ColumnSchema>,
    val lookupTables: List<LookupTableSchema>,
    val defaultTtl: Int? = null,
    val isCounterTable: Boolean = false,
    val createdAtColumn: ColumnSchema? = null,
    val updatedAtColumn: ColumnSchema? = null,
    val secondaryIndexes: List<ColumnSchema> = emptyList(),
    val versionColumn: ColumnSchema? = null,
    val isSoftDelete: Boolean = false,
    val softDeleteTtlSeconds: Int? = null,
    /** The `markerProperty` column from `@SoftDelete`, if configured. Enables `findActive()`. */
    val softDeleteMarkerColumn: ColumnSchema? = null,
    val gcGraceSeconds: Int? = null,
    val cacheConfig: CacheResultConfig? = null,
    /** Columns marked `@GeneratedUuid`, auto-populated on INSERT. Excludes `@Transient` columns. */
    val generatedUuidColumns: List<ColumnSchema> = emptyList(),
    /**
     * The reflection surface (copy function, property map, primary constructor) for [entityClass],
     * resolved once at registration time. `kandra-runtime` reads/writes entity fields through this
     * instead of re-resolving via `entity::class`/`entityClass` on every call. See ISS-034.
     */
    val reflection: EntityReflection
)

data class LookupTableSchema(
    val tableName: String,
    val indexColumn: ColumnSchema,
    /** All partition key columns of the primary table — needed to reconstruct the primary key. */
    val partitionKeyColumns: List<ColumnSchema>,
    val consistency: LookupConsistency,
    /**
     * All clustering key columns of the primary table, stored alongside the partition key columns
     * so a lookup resolution can reconstruct the primary table's **full** key (partition +
     * clustering), not just its partition key. Empty for a primary table with no clustering key.
     * See ISS-029: before this field existed, lookup resolution could only ever supply
     * `selectById` with the partition key, which stopped being sufficient once ISS-025 made
     * `selectById` require the full key on any clustering-keyed entity.
     */
    val clusteringKeyColumns: List<ColumnSchema> = emptyList()
)

data class CacheResultConfig(val ttlSeconds: Int, val maxSize: Long)
