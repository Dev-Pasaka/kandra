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
) {
    /**
     * [cqlName] as it must appear in generated CQL: double-quoted when it is a reserved word
     * (`"token"`), unchanged otherwise. Use [cqlName] for comparisons, row reads and messages.
     * GH #188.
     */
    val cqlIdentifier: String
        get() = io.kandra.core.CqlNaming.quoteIfNeeded(cqlName)
}

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
    /**
     * Resolved TTL (seconds) for the `@SoftDelete` marker column — always strictly greater than
     * [softDeleteTtlSeconds], already defaulted to `softDeleteTtlSeconds + gcGraceSeconds` by
     * [io.kandra.core.SchemaRegistry] if `@SoftDelete.markerTtlSeconds` was left unset (GH #154).
     * `null` whenever [softDeleteMarkerColumn] is `null`.
     */
    val softDeleteMarkerTtlSeconds: Int? = null,
    val gcGraceSeconds: Int? = null,
    val cacheConfig: CacheResultConfig? = null,
    /** Columns marked `@GeneratedUuid`, auto-populated on INSERT. Excludes `@Transient` columns. */
    val generatedUuidColumns: List<ColumnSchema> = emptyList(),
    /**
     * The reflection surface (copy function, property map, primary constructor) for [entityClass],
     * resolved once at registration time. `kandra-runtime` reads/writes entity fields through this
     * instead of re-resolving via `entity::class`/`entityClass` on every call. See ISS-034.
     */
    val reflection: EntityReflection,
    /** Keyspace declared via `@ScyllaTable(keyspace)`; `null` = the plugin's default keyspace. */
    val keyspace: String? = null
) {
    /** `tableName` when [keyspace] is null, else `"$keyspace.$tableName"`. */
    val qualifiedTableName: String
        get() = if (keyspace == null) tableName else "$keyspace.$tableName"
}

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
    val clusteringKeyColumns: List<ColumnSchema> = emptyList(),
    /** Inherited from the parent entity; `null` = the plugin's default keyspace. */
    val keyspace: String? = null
) {
    /** `tableName` when [keyspace] is null, else `"$keyspace.$tableName"`. */
    val qualifiedTableName: String
        get() = if (keyspace == null) tableName else "$keyspace.$tableName"

    /**
     * The primary table's key columns ([partitionKeyColumns] + [clusteringKeyColumns]) that the
     * lookup table stores as plain columns next to [indexColumn], in that order. When the indexed
     * column is itself one of those key columns (`@LookupIndex` on a `@ClusteringKey` or
     * `@PartitionKey`), it is already the lookup table's own key and is left out here, so the
     * lookup DDL and INSERT name it once. GH #187: listing it twice produced
     * `CREATE TABLE ... (request_id UUID, user_id UUID, request_id UUID, ...)`, which the database rejects.
     */
    val storedKeyColumns: List<ColumnSchema>
        get() = (partitionKeyColumns + clusteringKeyColumns).filter { it.cqlName != indexColumn.cqlName }
}

data class CacheResultConfig(val ttlSeconds: Int, val maxSize: Long)
