package io.kandra.core

import io.kandra.core.annotations.CacheResult
import io.kandra.core.annotations.ClusteringKey
import io.kandra.core.annotations.Column
import io.kandra.core.annotations.Counter
import io.kandra.core.annotations.CreatedAt
import io.kandra.core.annotations.GeneratedUuid
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
import io.kandra.core.schema.EntityReflection
import io.kandra.core.schema.LookupIndexSchema
import io.kandra.core.schema.LookupTableSchema
import io.kandra.core.schema.TableSchema
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

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
        if (!CqlNaming.isValidIdentifier(tableName)) {
            throw KandraSchemaException(
                "Class '${klass.simpleName}' has an invalid @ScyllaTable name '$tableName' — " +
                    "table names must be non-blank, start with a letter or underscore, and contain " +
                    "only letters, digits, and underscores."
            )
        }
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
            val generatedUuidAnn = prop.findAnnotation<GeneratedUuid>()
            val cqlName = CqlNaming.resolveColumnName(columnAnn?.name, prop.name)

            // @PartitionKey and @ClusteringKey are mutually exclusive on the same property — a
            // column that's both lands in both partitionKeys and clusteringKeys, and DdlGenerator
            // would emit it on both sides of the PRIMARY KEY (...) clause, which ScyllaDB rejects
            // at CREATE TABLE time. See GH-30.
            if (partitionKeyAnn != null && clusteringKeyAnn != null) {
                throw KandraSchemaException(
                    "Property '${klass.simpleName}.${prop.name}' is annotated both @PartitionKey " +
                        "and @ClusteringKey — a column cannot be both."
                )
            }

            // Resolved cqlName must be a valid CQL identifier — this also catches a blank
            // @Column("") name, since resolveColumnName already falls back to camelToSnake in
            // that case, but a bad @Column override (leading digit, punctuation, etc.) still needs
            // to be caught here. See GH-30.
            if (!CqlNaming.isValidIdentifier(cqlName)) {
                throw KandraSchemaException(
                    "Property '${klass.simpleName}.${prop.name}' resolves to an invalid CQL column " +
                        "name '$cqlName' — column names must be non-blank, start with a letter or " +
                        "underscore, and contain only letters, digits, and underscores."
                )
            }

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

            // @GeneratedUuid must be on a UUID field
            if (generatedUuidAnn != null) {
                val classifier = prop.returnType.classifier as? KClass<*>
                if (classifier != UUID::class) {
                    throw KandraSchemaException(
                        "@GeneratedUuid on '${klass.simpleName}.${prop.name}' must be a UUID field, got: ${prop.returnType}"
                    )
                }
            }

            // The composed lookup table name ("{tableName}_{tableSuffix}") is spliced directly
            // into DDL by DdlGenerator.lookupTable, just like the primary tableName — validate it
            // the same way. See GH-30.
            if (lookupIndexAnn != null) {
                val composedName = "${tableName}_${lookupIndexAnn.tableSuffix}"
                if (!CqlNaming.isValidIdentifier(composedName)) {
                    throw KandraSchemaException(
                        "@LookupIndex on '${klass.simpleName}.${prop.name}' resolves to an invalid " +
                            "lookup table name '$composedName' — table names must be non-blank, start " +
                            "with a letter or underscore, and contain only letters, digits, and underscores."
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
                isVersion = isVersion,
                generatedUuidStrategy = generatedUuidAnn?.strategy
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

        // ── Duplicate cqlName validation (GH-31) ──────────────────────────────
        // partitionKeys + clusteringKeys + regularColumns is exactly the full set of columns that
        // ends up spliced into DDL/DML (regularColumns already includes @LookupIndex columns —
        // they're excluded only from partitionKeys/clusteringKeys, not from "regular"). Two columns
        // resolving to the same cqlName — a typo'd @Column override, or camelToSnake's
        // trimStart('_') colliding two differently-named properties (e.g. `_archived` and
        // `archived` both becoming `archived`) — must fail loudly here, rather than being silently
        // dropped later by the `.distinctBy { it.cqlName }` calls in DdlGenerator/StatementBuilder,
        // which exist to de-duplicate the *same* lookup column appearing twice in a list
        // construction, not to arbitrate between two genuinely different colliding properties.
        val fullColumnSet = partitionKeys + clusteringKeys + regularColumns
        val duplicateCqlNameGroups = fullColumnSet.groupBy { it.cqlName }.filter { it.value.size > 1 }
        duplicateCqlNameGroups.entries.firstOrNull()?.let { (cqlName, cols) ->
            throw KandraSchemaException(
                "Class '${klass.simpleName}' has duplicate CQL column name '$cqlName' — properties " +
                    "${cols.joinToString(", ") { it.propertyName }} all resolve to the same column " +
                    "name. Rename one via @Column(name = \"...\") or rename the property."
            )
        }

        val lookupTables = lookupColumns.map { col ->
            LookupTableSchema(
                tableName = col.lookupIndex!!.tableName,
                indexColumn = col,
                partitionKeyColumns = partitionKeys,
                consistency = col.lookupIndex.consistency,
                clusteringKeyColumns = clusteringKeys
            )
        }

        val secondaryIndexes = columnSchemas.filter { it.isSecondaryIndex && !it.isTransient }
        val generatedUuidColumns = columnSchemas.filter { it.generatedUuidStrategy != null && !it.isTransient }

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
            cacheConfig = cacheResultAnn?.let { CacheResultConfig(it.ttlSeconds, it.maxSize) },
            generatedUuidColumns = generatedUuidColumns,
            reflection = buildEntityReflection(klass)
        )
    }

    /**
     * Resolves the reflection surface (`copy`, member properties, primary constructor) for [klass]
     * exactly once — called only from [buildSchema], itself only reached once per class via
     * [register]'s `getOrPut`. See ISS-034 / GitHub #13.
     */
    private fun <T : Any> buildEntityReflection(klass: KClass<T>): EntityReflection {
        val copyFunction = klass.memberFunctions.find { it.name == "copy" }
        val primaryConstructor = klass.primaryConstructor
        return EntityReflection(
            copyFunction = copyFunction,
            copyParameters = copyFunction?.parameters ?: emptyList(),
            propertiesByName = klass.memberProperties.associateBy { it.name },
            primaryConstructor = primaryConstructor,
            constructorParameters = primaryConstructor?.parameters ?: emptyList()
        )
    }

    internal fun camelToSnake(name: String): String = CqlNaming.camelToSnake(name)
}
