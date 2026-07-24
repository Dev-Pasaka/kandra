package io.kandra.runtime

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.Row
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kandra.core.InternalKandraApi
import io.kandra.core.KandraConsistency
import io.kandra.core.exception.KandraQueryException
import io.kandra.core.exception.KandraSchemaException
import io.kandra.core.schema.TableSchema
import io.kandra.runtime.codec.KandraCodec
import io.kandra.runtime.driver.executeSuspend
import io.kandra.runtime.driver.executeSuspendAll
import io.kandra.runtime.driver.prepareSuspend
import io.kandra.runtime.dsl.KandraPage
import io.kandra.runtime.dsl.KandraPredicate
import io.kandra.runtime.dsl.KandraRawQuery
import io.kandra.runtime.dsl.QueryContext
import java.util.Base64
import kotlin.reflect.KClass
import kotlin.reflect.KFunction

private val logger = KotlinLogging.logger {}

/**
 * Translates [QueryContext] predicates into CQL SELECT statements and decodes results.
 *
 * Lookup predicates trigger a two-step query:
 * 1. Query the lookup table for the primary table's full key (partition + clustering columns).
 * 2. Query the primary table by that full key.
 *
 * `@SecondaryIndex` predicates query the primary table directly (no two-step needed).
 * A WARN is logged each time a secondary index query executes.
 *
 * `IN` on a single-column partition key is supported — a DEBUG message is logged
 * since it causes scatter-gather across partitions (legitimate but worth tracking).
 */
@InternalKandraApi
class QueryExecutor(
    private val session: CqlSession,
    private val schema: TableSchema,
    private val statementBuilder: StatementBuilder,
    private val codec: KandraCodec = KandraCodec.default,
    private val debugConfig: DebugConfig = DebugConfig()
) {

    fun <T : Any> findById(entityClass: KClass<T>, vararg idValues: Any, consistency: KandraConsistency? = null): T? {
        val rs = session.execute(statementBuilder.selectById(schema, *idValues, consistency = consistency))
        val row = rs.one() ?: return null
        return decodeEntity(row, entityClass)
    }

    fun <T : Any> findAll(entityClass: KClass<T>, block: QueryContext.() -> Unit): List<T> {
        val ctx = QueryContext().also(block)
        val rows = resolveRows(ctx)
        return rows.map { decodeEntity(it, entityClass) }
    }

    fun <T : Any> find(entityClass: KClass<T>, block: QueryContext.() -> Unit): T? =
        findAll(entityClass, block).firstOrNull()

    fun <T : Any> findPage(
        entityClass: KClass<T>,
        pageSize: Int,
        pageToken: String?,
        block: QueryContext.() -> Unit
    ): KandraPage<T> {
        val ctx = QueryContext().also(block)
        val lookupPredicate = ctx.predicates.firstOrNull { pred ->
            schema.lookupTables.any { it.indexColumn.cqlName == predicateColumn(pred) }
        }

        val primaryPredicates = if (lookupPredicate != null) {
            val lookupColName = predicateColumn(lookupPredicate)
            val lookup = schema.lookupTables.first { it.indexColumn.cqlName == lookupColName }
            val lookupValue = (lookupPredicate as? KandraPredicate.Eq)?.value
                ?: throw KandraQueryException("Lookup table pagination only supports equality predicates.")
            val lookupRow = session.execute(statementBuilder.selectByLookup(lookup, lookupValue!!))
                .one() ?: return KandraPage(emptyList(), null, false)

            // Full key (partition + clustering), not partition-only -- a lookup value maps to exactly
            // one primary-table row, but a partition-only WHERE would scatter across every clustering
            // row in that partition on a clustering-keyed entity (see ISS-029).
            (lookup.partitionKeyColumns + lookup.clusteringKeyColumns).map { keyCol ->
                KandraPredicate.Eq(keyCol.cqlName, lookupRow.getObject(keyCol.cqlName))
            }
        } else {
            ctx.predicates
        }

        // No predicates = token-range full scan. The DataStax driver pages across token
        // ranges automatically when no WHERE clause is present. This is the ScyllaDB-safe
        // way to iterate all rows — ALLOW FILTERING is never used.
        val cql = if (primaryPredicates.isEmpty()) {
            if (pageToken == null) {
                logger.info { "findPage on '${schema.tableName}' with no predicates — full token-range scan (driver paging). Use sparingly on large tables." }
            }
            "SELECT * FROM ${schema.tableName}"
        } else {
            val (whereParts, _) = buildWhere(primaryPredicates)
            "SELECT * FROM ${schema.tableName} WHERE $whereParts"
        }
        val values = if (primaryPredicates.isEmpty()) emptyList() else buildWhere(primaryPredicates).second
        val prepared = session.prepare(cql)
        var stmt = prepared.bind(*values.toTypedArray()).setPageSize(pageSize)

        if (pageToken != null) {
            val bytes = Base64.getDecoder().decode(pageToken)
            stmt = stmt.setPagingState(java.nio.ByteBuffer.wrap(bytes))
        }

        val rs = session.execute(stmt)
        val available = rs.getAvailableWithoutFetching()
        val rows = mutableListOf<Row>()
        repeat(available) { rs.one()?.let { rows.add(it) } }

        val pagingStateBytes = if (!rs.isFullyFetched) rs.getExecutionInfo().pagingState else null
        val nextToken = pagingStateBytes?.let { buf ->
            val bytes = ByteArray(buf.remaining())
            buf.duplicate().get(bytes)
            Base64.getEncoder().encodeToString(bytes)
        }

        return KandraPage(
            items = rows.map { decodeEntity(it, entityClass) },
            nextPageToken = nextToken,
            hasMore = nextToken != null
        )
    }

    fun exists(block: QueryContext.() -> Unit): Boolean {
        val ctx = QueryContext().also(block)
        val rows = resolveRows(ctx, limitOne = true, selectKeys = true)
        return rows.isNotEmpty()
    }

    fun raw(cql: String, vararg params: Any?): List<Row> {
        if (params.isEmpty() && (cql.contains("'") || cql.contains("\"="))) {
            logger.warn {
                "raw() called with no parameters but CQL contains string literals. " +
                "If any literal came from user input this is a CQL injection risk. " +
                "Use parameterised queries: raw(\"SELECT * FROM t WHERE col = ?\", value)"
            }
        }
        val rs = session.execute(session.prepare(cql).bind(*params))
        return rs.all()
    }

    fun rawQuery(query: KandraRawQuery): List<Row> {
        val rs = session.execute(session.prepare(query.cql).bind(*query.params.toTypedArray()))
        return rs.all()
    }

    // ── Suspend variants — same logic, never block the calling coroutine dispatcher ──

    suspend fun <T : Any> findByIdSuspend(entityClass: KClass<T>, vararg idValues: Any, consistency: KandraConsistency? = null): T? {
        val rs = session.executeSuspend(statementBuilder.selectById(schema, *idValues, consistency = consistency))
        val row = rs.one() ?: return null
        return decodeEntity(row, entityClass)
    }

    suspend fun <T : Any> findAllSuspend(entityClass: KClass<T>, block: QueryContext.() -> Unit): List<T> {
        val ctx = QueryContext().also(block)
        val rows = resolveRowsSuspend(ctx)
        return rows.map { decodeEntity(it, entityClass) }
    }

    suspend fun <T : Any> findSuspend(entityClass: KClass<T>, block: QueryContext.() -> Unit): T? =
        findAllSuspend(entityClass, block).firstOrNull()

    suspend fun <T : Any> findPageSuspend(
        entityClass: KClass<T>,
        pageSize: Int,
        pageToken: String?,
        block: QueryContext.() -> Unit
    ): KandraPage<T> {
        val ctx = QueryContext().also(block)
        val lookupPredicate = ctx.predicates.firstOrNull { pred ->
            schema.lookupTables.any { it.indexColumn.cqlName == predicateColumn(pred) }
        }

        val primaryPredicates = if (lookupPredicate != null) {
            val lookupColName = predicateColumn(lookupPredicate)
            val lookup = schema.lookupTables.first { it.indexColumn.cqlName == lookupColName }
            val lookupValue = (lookupPredicate as? KandraPredicate.Eq)?.value
                ?: throw KandraQueryException("Lookup table pagination only supports equality predicates.")
            val lookupRow = session.executeSuspend(statementBuilder.selectByLookup(lookup, lookupValue!!))
                .one() ?: return KandraPage(emptyList(), null, false)

            // Full key (partition + clustering), not partition-only -- a lookup value maps to exactly
            // one primary-table row, but a partition-only WHERE would scatter across every clustering
            // row in that partition on a clustering-keyed entity (see ISS-029).
            (lookup.partitionKeyColumns + lookup.clusteringKeyColumns).map { keyCol ->
                KandraPredicate.Eq(keyCol.cqlName, lookupRow.getObject(keyCol.cqlName))
            }
        } else {
            ctx.predicates
        }

        val cql = if (primaryPredicates.isEmpty()) {
            if (pageToken == null) {
                logger.info { "findPage on '${schema.tableName}' with no predicates — full token-range scan (driver paging). Use sparingly on large tables." }
            }
            "SELECT * FROM ${schema.tableName}"
        } else {
            val (whereParts, _) = buildWhere(primaryPredicates)
            "SELECT * FROM ${schema.tableName} WHERE $whereParts"
        }
        val values = if (primaryPredicates.isEmpty()) emptyList() else buildWhere(primaryPredicates).second
        val prepared = session.prepareSuspend(cql)
        var stmt = prepared.bind(*values.toTypedArray()).setPageSize(pageSize)

        if (pageToken != null) {
            val bytes = Base64.getDecoder().decode(pageToken)
            stmt = stmt.setPagingState(java.nio.ByteBuffer.wrap(bytes))
        }

        val rs = session.executeSuspend(stmt)
        val rows = rs.currentPage().toList()

        val pagingStateBytes = if (rs.hasMorePages()) rs.executionInfo.pagingState else null
        val nextToken = pagingStateBytes?.let { buf ->
            val bytes = ByteArray(buf.remaining())
            buf.duplicate().get(bytes)
            Base64.getEncoder().encodeToString(bytes)
        }

        return KandraPage(
            items = rows.map { decodeEntity(it, entityClass) },
            nextPageToken = nextToken,
            hasMore = nextToken != null
        )
    }

    suspend fun existsSuspend(block: QueryContext.() -> Unit): Boolean {
        val ctx = QueryContext().also(block)
        val rows = resolveRowsSuspend(ctx, limitOne = true, selectKeys = true)
        return rows.isNotEmpty()
    }

    suspend fun rawSuspend(cql: String, vararg params: Any?): List<Row> {
        if (params.isEmpty() && (cql.contains("'") || cql.contains("\"="))) {
            logger.warn {
                "raw() called with no parameters but CQL contains string literals. " +
                "If any literal came from user input this is a CQL injection risk. " +
                "Use parameterised queries: raw(\"SELECT * FROM t WHERE col = ?\", value)"
            }
        }
        val prepared = session.prepareSuspend(cql)
        return session.executeSuspendAll(prepared.bind(*params))
    }

    suspend fun rawQuerySuspend(query: KandraRawQuery): List<Row> {
        val prepared = session.prepareSuspend(query.cql)
        return session.executeSuspendAll(prepared.bind(*query.params.toTypedArray()))
    }

    private fun requireActiveMarker() = schema.softDeleteMarkerColumn
        ?: throw KandraSchemaException(
            "findActive() requires @SoftDelete(markerProperty = \"...\") on '${schema.tableName}'."
        )

    private fun activeMarkerWarning(marker: io.kandra.core.schema.ColumnSchema) {
        logger.warn {
            "findActive() on '${schema.tableName}' scans with ALLOW FILTERING on '${marker.cqlName}' — " +
            "scatter-gather across all nodes. Consider @SecondaryIndex on the marker column for large tables."
        }
    }

    fun <T : Any> findActive(entityClass: KClass<T>): List<T> {
        val marker = requireActiveMarker()
        activeMarkerWarning(marker)
        val cql = "SELECT * FROM ${schema.tableName} WHERE ${marker.cqlName} = ? ALLOW FILTERING"
        val rs = session.execute(session.prepare(cql).bind(false))
        return rs.all().map { decodeEntity(it, entityClass) }
    }

    suspend fun <T : Any> findActiveSuspend(entityClass: KClass<T>): List<T> {
        val marker = requireActiveMarker()
        activeMarkerWarning(marker)
        val cql = "SELECT * FROM ${schema.tableName} WHERE ${marker.cqlName} = ? ALLOW FILTERING"
        val prepared = session.prepareSuspend(cql)
        return session.executeSuspendAll(prepared.bind(false)).map { decodeEntity(it, entityClass) }
    }

    private fun predicateColumn(pred: KandraPredicate): String = when (pred) {
        is KandraPredicate.Eq -> pred.column
        is KandraPredicate.Gt -> pred.column
        is KandraPredicate.Gte -> pred.column
        is KandraPredicate.Lt -> pred.column
        is KandraPredicate.Lte -> pred.column
        is KandraPredicate.In -> pred.column
    }

    private fun resolveRows(
        ctx: QueryContext,
        limitOne: Boolean = false,
        selectKeys: Boolean = false
    ): List<Row> {
        if (ctx.predicates.isEmpty()) throw KandraQueryException("Query must have at least one predicate.")

        // ── IN on partition key ───────────────────────────────────────────────
        val inPredicate = ctx.predicates.firstOrNull { it is KandraPredicate.In } as? KandraPredicate.In
        if (inPredicate != null) {
            val pkCqlNames = schema.partitionKeys.map { it.cqlName }.toSet()
            val isOnPk = inPredicate.column in pkCqlNames

            if (!isOnPk) {
                val isSecondaryIdx = schema.secondaryIndexes.any { it.cqlName == inPredicate.column }
                if (!isSecondaryIdx) {
                    throw KandraQueryException(
                        "IN on column '${inPredicate.column}' requires either a partition key column or " +
                        "ALLOW FILTERING, which Kandra does not support. Add a @SecondaryIndex to the column instead."
                    )
                }
            }

            if (inPredicate.values.isEmpty()) return emptyList()

            logger.debug { "IN query on partition key '${inPredicate.column}' in '${schema.tableName}' — scatter-gather across partitions." }

            val encodedIds = inPredicate.values.filterNotNull()
            val rs = session.execute(statementBuilder.selectByPartitionKeyIn(schema, encodedIds))
            return rs.all()
        }

        // ── Lookup table predicate ────────────────────────────────────────────
        val lookupPredicate = ctx.predicates.firstOrNull { pred ->
            schema.lookupTables.any { it.indexColumn.cqlName == predicateColumn(pred) }
        }

        if (lookupPredicate != null) {
            val lookupColName = predicateColumn(lookupPredicate)
            val lookup = schema.lookupTables.first { it.indexColumn.cqlName == lookupColName }
            val lookupValue = when (lookupPredicate) {
                is KandraPredicate.Eq -> lookupPredicate.value
                else -> throw KandraQueryException("Lookup table queries only support equality predicates.")
            } ?: throw KandraQueryException("Lookup predicate value must not be null.")

            val lookupRow = session.execute(statementBuilder.selectByLookup(lookup, lookupValue))
                .one() ?: return emptyList()

            // Full key (partition + clustering) -- selectById requires all of it (see ISS-029).
            val keyValues = (lookup.partitionKeyColumns + lookup.clusteringKeyColumns).map { keyCol ->
                lookupRow.getObject(keyCol.cqlName)
                    ?: throw KandraQueryException("Null key column '${keyCol.cqlName}' from lookup table")
            }
            val primaryRs = session.execute(statementBuilder.selectById(schema, *keyValues.toTypedArray()))
            return primaryRs.all()
        }

        // ── @SecondaryIndex predicate ─────────────────────────────────────────
        val secondaryIndexPredicate = ctx.predicates.firstOrNull { pred ->
            schema.secondaryIndexes.any { it.cqlName == predicateColumn(pred) }
        }

        if (secondaryIndexPredicate != null) {
            logger.warn { "Secondary index query on '${predicateColumn(secondaryIndexPredicate)}' in '${schema.tableName}' — scatter-gather across all nodes." }
        }

        // ── Direct CQL query ──────────────────────────────────────────────────
        val (whereParts, values) = buildWhere(ctx.predicates)
        val selectCols = if (selectKeys) schema.partitionKeys.joinToString(", ") { it.cqlName } else "*"
        val limitClause = when {
            limitOne -> " LIMIT 1"
            ctx.limit != null -> " LIMIT ${ctx.limit}"
            else -> ""
        }
        val cql = "SELECT $selectCols FROM ${schema.tableName} WHERE $whereParts$limitClause"
        val prepared = session.prepare(cql)
        val rs = session.execute(prepared.bind(*values.toTypedArray()))
        return rs.all()
    }

    private suspend fun resolveRowsSuspend(
        ctx: QueryContext,
        limitOne: Boolean = false,
        selectKeys: Boolean = false
    ): List<Row> {
        if (ctx.predicates.isEmpty()) throw KandraQueryException("Query must have at least one predicate.")

        // ── IN on partition key ───────────────────────────────────────────────
        val inPredicate = ctx.predicates.firstOrNull { it is KandraPredicate.In } as? KandraPredicate.In
        if (inPredicate != null) {
            val pkCqlNames = schema.partitionKeys.map { it.cqlName }.toSet()
            val isOnPk = inPredicate.column in pkCqlNames

            if (!isOnPk) {
                val isSecondaryIdx = schema.secondaryIndexes.any { it.cqlName == inPredicate.column }
                if (!isSecondaryIdx) {
                    throw KandraQueryException(
                        "IN on column '${inPredicate.column}' requires either a partition key column or " +
                        "ALLOW FILTERING, which Kandra does not support. Add a @SecondaryIndex to the column instead."
                    )
                }
            }

            if (inPredicate.values.isEmpty()) return emptyList()

            logger.debug { "IN query on partition key '${inPredicate.column}' in '${schema.tableName}' — scatter-gather across partitions." }

            val encodedIds = inPredicate.values.filterNotNull()
            return session.executeSuspendAll(statementBuilder.selectByPartitionKeyIn(schema, encodedIds))
        }

        // ── Lookup table predicate ────────────────────────────────────────────
        val lookupPredicate = ctx.predicates.firstOrNull { pred ->
            schema.lookupTables.any { it.indexColumn.cqlName == predicateColumn(pred) }
        }

        if (lookupPredicate != null) {
            val lookupColName = predicateColumn(lookupPredicate)
            val lookup = schema.lookupTables.first { it.indexColumn.cqlName == lookupColName }
            val lookupValue = when (lookupPredicate) {
                is KandraPredicate.Eq -> lookupPredicate.value
                else -> throw KandraQueryException("Lookup table queries only support equality predicates.")
            } ?: throw KandraQueryException("Lookup predicate value must not be null.")

            val lookupRow = session.executeSuspend(statementBuilder.selectByLookup(lookup, lookupValue))
                .one() ?: return emptyList()

            // Full key (partition + clustering) -- selectById requires all of it (see ISS-029).
            val keyValues = (lookup.partitionKeyColumns + lookup.clusteringKeyColumns).map { keyCol ->
                lookupRow.getObject(keyCol.cqlName)
                    ?: throw KandraQueryException("Null key column '${keyCol.cqlName}' from lookup table")
            }
            return session.executeSuspendAll(statementBuilder.selectById(schema, *keyValues.toTypedArray()))
        }

        // ── @SecondaryIndex predicate ─────────────────────────────────────────
        val secondaryIndexPredicate = ctx.predicates.firstOrNull { pred ->
            schema.secondaryIndexes.any { it.cqlName == predicateColumn(pred) }
        }

        if (secondaryIndexPredicate != null) {
            logger.warn { "Secondary index query on '${predicateColumn(secondaryIndexPredicate)}' in '${schema.tableName}' — scatter-gather across all nodes." }
        }

        // ── Direct CQL query ──────────────────────────────────────────────────
        val (whereParts, values) = buildWhere(ctx.predicates)
        val selectCols = if (selectKeys) schema.partitionKeys.joinToString(", ") { it.cqlName } else "*"
        val limitClause = when {
            limitOne -> " LIMIT 1"
            ctx.limit != null -> " LIMIT ${ctx.limit}"
            else -> ""
        }
        val cql = "SELECT $selectCols FROM ${schema.tableName} WHERE $whereParts$limitClause"
        val prepared = session.prepareSuspend(cql)
        return session.executeSuspendAll(prepared.bind(*values.toTypedArray()))
    }

    private fun buildWhere(predicates: List<KandraPredicate>): Pair<String, List<Any?>> {
        val parts = mutableListOf<String>()
        val values = mutableListOf<Any?>()

        predicates.forEach { pred ->
            when (pred) {
                is KandraPredicate.Eq -> { parts += "${pred.column} = ?"; values += pred.value }
                is KandraPredicate.Gt -> { parts += "${pred.column} > ?"; values += pred.value }
                is KandraPredicate.Gte -> { parts += "${pred.column} >= ?"; values += pred.value }
                is KandraPredicate.Lt -> { parts += "${pred.column} < ?"; values += pred.value }
                is KandraPredicate.Lte -> { parts += "${pred.column} <= ?"; values += pred.value }
                is KandraPredicate.In -> {
                    val placeholders = pred.values.joinToString(", ") { "?" }
                    parts += "${pred.column} IN ($placeholders)"
                    values.addAll(pred.values)
                }
            }
        }
        return parts.joinToString(" AND ") to values
    }

    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> decodeEntity(row: Row, entityClass: KClass<T>): T {
        // Resolved once per entity KClass in SchemaRegistry.register() and cached on
        // TableSchema.reflection — entityClass is always schema.entityClass here, so this avoids
        // re-resolving primaryConstructor/its KParameter list via reflection on every row decoded.
        val ctor = schema.reflection.primaryConstructor as? KFunction<T>
            ?: throw KandraQueryException("Entity '${entityClass.simpleName}' has no primary constructor.")
        val ctorParams = schema.reflection.constructorParameters

        val allCols = buildList {
            addAll(schema.partitionKeys)
            addAll(schema.clusteringKeys)
            addAll(schema.columns)
            addAll(schema.lookupTables.map { it.indexColumn })
        }.associateBy { it.propertyName }

        val args = ctorParams.associateWith { param ->
            val col = allCols[param.name]
            if (col == null) null else codec.decode(row, col)
        }

        return ctor.callBy(args)
    }
}
