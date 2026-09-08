package io.kandra.runtime

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel
import com.datastax.oss.driver.api.core.cql.ResultSet
import com.datastax.oss.driver.api.core.cql.Row
import com.datastax.oss.driver.api.core.cql.Statement
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kandra.core.InternalKandraApi
import io.kandra.core.KandraConsistency
import io.kandra.core.exception.KandraQueryException
import io.kandra.core.exception.KandraSchemaException
import io.kandra.core.schema.TableSchema
import io.kandra.runtime.codec.KandraCodec
import io.kandra.runtime.driver.executeSuspend
import io.kandra.runtime.driver.executeSuspendAll
import io.kandra.runtime.driver.executeSuspendUpTo
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
 * Matches a single-quoted string literal (`'...'`) or a double-quoted identifier immediately
 * followed by `=` (`"col"=`) anywhere in a CQL string. See [QueryExecutor.checkRawInjectionRisk].
 */
private val SUSPICIOUS_LITERAL_PATTERN = Regex("""'[^']*'|"[^"]*"\s*=""")

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
    private val debugConfig: DebugConfig = DebugConfig(),
    /**
     * Hard cap on rows materialized into memory by an unpaged read (`findAll`/`find`/`exists`'s IN
     * and direct-CQL branches — `findById`/lookup-index reads are inherently bounded to one row, and
     * `findPage` is explicitly paged). See ISS-066 / GH #67: without this, a predicate matching a wide
     * partition or many partitions could materialize an unbounded result set into application heap.
     * Exceeding it logs a loud WARN and truncates rather than throwing, so an existing large-but-legal
     * result set doesn't turn into a runtime failure — `findPage` is the documented alternative for
     * genuinely unbounded reads.
     */
    private val maxUnpagedResultRows: Int = 10_000
) {

    fun <T : Any> findById(entityClass: KClass<T>, vararg idValues: Any, consistency: KandraConsistency? = null): T? {
        val rs = session.execute(statementBuilder.selectById(schema, *idValues, consistency = consistency))
        val row = rs.one() ?: return null
        val entity = decodeEntity(row, entityClass)
        if (debugConfig.logQueries) {
            logger.debug { "Decoded entity: ${KandraEntityLogger.safeToString(entity, schema)}" }
        }
        return entity
    }

    fun <T : Any> findAll(entityClass: KClass<T>, consistency: KandraConsistency? = null, block: QueryContext.() -> Unit): List<T> {
        val ctx = QueryContext().also(block)
        val rows = resolveRows(ctx, consistency = consistency)
        val entities = rows.map { decodeEntity(it, entityClass) }
        if (debugConfig.logQueries) {
            entities.forEach { entity ->
                logger.debug { "Decoded entity: ${KandraEntityLogger.safeToString(entity, schema)}" }
            }
        }
        return entities
    }

    fun <T : Any> find(entityClass: KClass<T>, consistency: KandraConsistency? = null, block: QueryContext.() -> Unit): T? =
        findAll(entityClass, consistency, block).firstOrNull()

    fun <T : Any> findPage(
        entityClass: KClass<T>,
        pageSize: Int,
        pageToken: String?,
        consistency: KandraConsistency? = null,
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
            val lookupRow = session.execute(statementBuilder.selectByLookup(lookup, lookupValue!!, consistency))
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
        val resolvedConsistency = statementBuilder.resolveReadConsistency(schema, consistency)
        var stmt = prepared.bind(*values.toTypedArray())
            .setPageSize(pageSize)
            .setConsistencyLevel(DefaultConsistencyLevel.valueOf(resolvedConsistency.name))

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

    fun exists(consistency: KandraConsistency? = null, block: QueryContext.() -> Unit): Boolean {
        val ctx = QueryContext().also(block)
        val rows = resolveRows(ctx, consistency = consistency, limitOne = true, selectKeys = true)
        return rows.isNotEmpty()
    }

    fun raw(cql: String, vararg params: Any?): List<Row> {
        checkRawInjectionRisk(cql, "raw")
        val rs = session.execute(session.prepare(cql).bind(*params))
        return rs.all()
    }

    fun rawQuery(query: KandraRawQuery): List<Row> {
        checkRawInjectionRisk(query.cql, "rawQuery")
        val rs = session.execute(session.prepare(query.cql).bind(*query.params.toTypedArray()))
        return rs.all()
    }

    // ── Suspend variants — same logic, never block the calling coroutine dispatcher ──

    suspend fun <T : Any> findByIdSuspend(entityClass: KClass<T>, vararg idValues: Any, consistency: KandraConsistency? = null): T? {
        val rs = session.executeSuspend(statementBuilder.selectByIdSuspend(schema, *idValues, consistency = consistency))
        val row = rs.currentPage().firstOrNull() ?: return null
        val entity = decodeEntity(row, entityClass)
        if (debugConfig.logQueries) {
            logger.debug { "Decoded entity: ${KandraEntityLogger.safeToString(entity, schema)}" }
        }
        return entity
    }

    suspend fun <T : Any> findAllSuspend(entityClass: KClass<T>, consistency: KandraConsistency? = null, block: QueryContext.() -> Unit): List<T> {
        val ctx = QueryContext().also(block)
        val rows = resolveRowsSuspend(ctx, consistency = consistency)
        val entities = rows.map { decodeEntity(it, entityClass) }
        if (debugConfig.logQueries) {
            entities.forEach { entity ->
                logger.debug { "Decoded entity: ${KandraEntityLogger.safeToString(entity, schema)}" }
            }
        }
        return entities
    }

    suspend fun <T : Any> findSuspend(entityClass: KClass<T>, consistency: KandraConsistency? = null, block: QueryContext.() -> Unit): T? =
        findAllSuspend(entityClass, consistency, block).firstOrNull()

    suspend fun <T : Any> findPageSuspend(
        entityClass: KClass<T>,
        pageSize: Int,
        pageToken: String?,
        consistency: KandraConsistency? = null,
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
            val lookupRow = session.executeSuspend(statementBuilder.selectByLookupSuspend(lookup, lookupValue!!, consistency))
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
        val resolvedConsistency = statementBuilder.resolveReadConsistency(schema, consistency)
        var stmt = prepared.bind(*values.toTypedArray())
            .setPageSize(pageSize)
            .setConsistencyLevel(DefaultConsistencyLevel.valueOf(resolvedConsistency.name))

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

    suspend fun existsSuspend(consistency: KandraConsistency? = null, block: QueryContext.() -> Unit): Boolean {
        val ctx = QueryContext().also(block)
        val rows = resolveRowsSuspend(ctx, consistency = consistency, limitOne = true, selectKeys = true)
        return rows.isNotEmpty()
    }

    suspend fun rawSuspend(cql: String, vararg params: Any?): List<Row> {
        checkRawInjectionRisk(cql, "rawSuspend")
        val prepared = session.prepareSuspend(cql)
        return session.executeSuspendAll(prepared.bind(*params))
    }

    suspend fun rawQuerySuspend(query: KandraRawQuery): List<Row> {
        checkRawInjectionRisk(query.cql, "rawQuerySuspend")
        val prepared = session.prepareSuspend(query.cql)
        return session.executeSuspendAll(prepared.bind(*query.params.toTypedArray()))
    }

    /**
     * Heuristic CQL-injection guard shared by [raw]/[rawSuspend]/[rawQuery]/[rawQuerySuspend].
     *
     * Fires whenever [cql] appears to have a value spliced directly into the string — a single-quoted
     * string literal (`'...'`), or a double-quoted identifier immediately followed by `=` (a common
     * shape for `"col"='value'`-style splicing) — **regardless of whether any parameters are bound**.
     * Unlike the pre-fix version, one legitimately bound `?` elsewhere in the same CQL string no
     * longer suppresses this check: an embedded literal is a risk independent of how many other
     * placeholders happen to be present (GH #32 / ISS-050).
     *
     * This remains a heuristic, not a CQL parser: it will not catch quote-less injection shapes (e.g.
     * a numeric-context tautology or bare keyword injection), and it can false-positive on CQL that
     * legitimately embeds a fixed, non-user-supplied literal. Absence of the warning is therefore not
     * proof a query is safe, and presence of it is not proof a query is unsafe — it is a prompt to
     * double check.
     *
     * By default this only logs a WARN. If [DebugConfig.rawQueryStrictMode] is enabled, it throws
     * [KandraQueryException] instead, so callers who want `raw()`/`rawQuery()` to fail closed can opt in.
     */
    private fun checkRawInjectionRisk(cql: String, callerName: String) {
        if (!SUSPICIOUS_LITERAL_PATTERN.containsMatchIn(cql)) return
        val message = "$callerName() CQL appears to contain a string literal spliced directly into the " +
            "query (independent of any other bound parameters). If any of it came from user input this " +
            "is a CQL injection risk. Use parameterised queries: raw(\"SELECT * FROM t WHERE col = ?\", value)"
        if (debugConfig.rawQueryStrictMode) {
            throw KandraQueryException(message)
        } else {
            logger.warn { message }
        }
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

    /**
     * Builds the CQL for `findActive()`. If the marker column has a `@SecondaryIndex`, the query
     * is answered by the index and no `ALLOW FILTERING` is needed. Otherwise, `ALLOW FILTERING` is
     * required — Kandra does not emit it implicitly; the caller must opt in with
     * `allowFullScan = true`, mirroring how the predicate DSL refuses to emit it at all.
     */
    private fun buildActiveQueryCql(marker: io.kandra.core.schema.ColumnSchema, allowFullScan: Boolean): String {
        val hasSecondaryIndex = schema.secondaryIndexes.any { it.cqlName == marker.cqlName }
        if (hasSecondaryIndex) {
            return "SELECT * FROM ${schema.tableName} WHERE ${marker.cqlName} = ?"
        }
        if (!allowFullScan) {
            throw KandraQueryException(
                "findActive() on '${schema.tableName}' requires ALLOW FILTERING on '${marker.cqlName}', " +
                "which Kandra does not emit implicitly. Add a @SecondaryIndex to the marker column, or " +
                "pass allowFullScan = true to findActive() to opt in explicitly."
            )
        }
        activeMarkerWarning(marker)
        return "SELECT * FROM ${schema.tableName} WHERE ${marker.cqlName} = ? ALLOW FILTERING"
    }

    fun <T : Any> findActive(entityClass: KClass<T>, allowFullScan: Boolean = false): List<T> {
        val marker = requireActiveMarker()
        val cql = buildActiveQueryCql(marker, allowFullScan)
        val rs = session.execute(session.prepare(cql).bind(false))
        return rs.all().map { decodeEntity(it, entityClass) }
    }

    suspend fun <T : Any> findActiveSuspend(entityClass: KClass<T>, allowFullScan: Boolean = false): List<T> {
        val marker = requireActiveMarker()
        val cql = buildActiveQueryCql(marker, allowFullScan)
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
        consistency: KandraConsistency? = null,
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
            val rs = session.execute(statementBuilder.selectByPartitionKeyIn(schema, encodedIds, consistency))
            return boundedAll(rs)
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

            val lookupRow = session.execute(statementBuilder.selectByLookup(lookup, lookupValue, consistency))
                .one() ?: return emptyList()

            // Full key (partition + clustering) -- selectById requires all of it (see ISS-029).
            val keyValues = (lookup.partitionKeyColumns + lookup.clusteringKeyColumns).map { keyCol ->
                lookupRow.getObject(keyCol.cqlName)
                    ?: throw KandraQueryException("Null key column '${keyCol.cqlName}' from lookup table")
            }
            val primaryRs = session.execute(statementBuilder.selectById(schema, *keyValues.toTypedArray(), consistency = consistency))
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
        val resolvedConsistency = statementBuilder.resolveReadConsistency(schema, consistency)
        val stmt = prepared.bind(*values.toTypedArray())
            .setConsistencyLevel(DefaultConsistencyLevel.valueOf(resolvedConsistency.name))
        val rs = session.execute(stmt)
        return boundedAll(rs)
    }

    /**
     * Collects rows off [rs] up to [maxUnpagedResultRows], logging a loud WARN and truncating instead
     * of materializing further if the query has more than that. See ISS-066 / GH #67.
     */
    private fun boundedAll(rs: ResultSet): List<Row> {
        val rows = ArrayList<Row>(minOf(maxUnpagedResultRows + 1, 256))
        val iterator = rs.iterator()
        while (iterator.hasNext() && rows.size <= maxUnpagedResultRows) rows.add(iterator.next())
        if (rows.size > maxUnpagedResultRows) {
            logger.warn {
                "Query on '${schema.tableName}' returned more than $maxUnpagedResultRows rows without " +
                "paging — truncating to $maxUnpagedResultRows. Use findPage() for large or unbounded result sets."
            }
            return rows.subList(0, maxUnpagedResultRows)
        }
        return rows
    }

    private suspend fun resolveRowsSuspend(
        ctx: QueryContext,
        consistency: KandraConsistency? = null,
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
            return boundedSuspendAll(statementBuilder.selectByPartitionKeyInSuspend(schema, encodedIds, consistency))
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

            val lookupRow = session.executeSuspend(statementBuilder.selectByLookupSuspend(lookup, lookupValue, consistency))
                .one() ?: return emptyList()

            // Full key (partition + clustering) -- selectById requires all of it (see ISS-029).
            val keyValues = (lookup.partitionKeyColumns + lookup.clusteringKeyColumns).map { keyCol ->
                lookupRow.getObject(keyCol.cqlName)
                    ?: throw KandraQueryException("Null key column '${keyCol.cqlName}' from lookup table")
            }
            return session.executeSuspendAll(
                statementBuilder.selectByIdSuspend(schema, *keyValues.toTypedArray(), consistency = consistency)
            )
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
        val resolvedConsistency = statementBuilder.resolveReadConsistency(schema, consistency)
        val stmt = prepared.bind(*values.toTypedArray())
            .setConsistencyLevel(DefaultConsistencyLevel.valueOf(resolvedConsistency.name))
        return boundedSuspendAll(stmt)
    }

    /**
     * Suspend counterpart of [boundedAll] — see ISS-066 / GH #67. [executeSuspendUpTo] may return
     * slightly more than [maxUnpagedResultRows] (whatever the last fetched page contained); this
     * truncates to the exact cap and logs.
     */
    private suspend fun boundedSuspendAll(statement: Statement<*>): List<Row> {
        val rows = session.executeSuspendUpTo(statement, maxUnpagedResultRows)
        if (rows.size > maxUnpagedResultRows) {
            logger.warn {
                "Query on '${schema.tableName}' returned more than $maxUnpagedResultRows rows without " +
                "paging — truncating to $maxUnpagedResultRows. Use findPageSuspend() for large or unbounded result sets."
            }
            return rows.subList(0, maxUnpagedResultRows)
        }
        return rows
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
        val columnsByProperty = schema.reflection.columnsByProperty

        val args = ctorParams.associateWith { param ->
            val col = columnsByProperty[param.name]
            if (col == null) null else codec.decode(row, col)
        }

        return ctor.callBy(args)
    }
}
