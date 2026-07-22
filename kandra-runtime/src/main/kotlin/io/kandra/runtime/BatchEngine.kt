package io.kandra.runtime

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel
import com.datastax.oss.driver.api.core.cql.AsyncResultSet
import com.datastax.oss.driver.api.core.cql.BatchStatement
import com.datastax.oss.driver.api.core.cql.BatchableStatement
import com.datastax.oss.driver.api.core.cql.BoundStatement
import com.datastax.oss.driver.api.core.cql.DefaultBatchType
import com.datastax.oss.driver.api.core.cql.Statement
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.InternalKandraApi
import io.kandra.core.KandraConsistency
import io.kandra.core.KandraEventListener
import io.kandra.core.KandraMetrics
import io.kandra.core.KandraValidationException
import io.kandra.core.KandraValidator
import io.kandra.core.annotations.LookupConsistency
import io.kandra.core.exception.KandraOptimisticLockException
import io.kandra.core.exception.KandraQueryException
import io.kandra.core.schema.ColumnSchema
import io.kandra.core.schema.LookupTableSchema
import io.kandra.core.schema.TableSchema
import io.kandra.runtime.codec.KandraCodec
import io.kandra.runtime.codec.KandraUnset
import io.kandra.runtime.driver.executeSuspend
import io.kandra.runtime.driver.prepareSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties

private val logger = KotlinLogging.logger {}

/**
 * Executes save, update, delete, and saveAll operations using LOGGED batch statements.
 *
 * BATCH-consistency lookups are included in the atomic batch.
 * EVENTUAL-consistency lookups fire asynchronously via [scope] after the batch commits;
 * failures are forwarded to [eventListener] (if set) then logged.
 *
 * Transient failures listed in [retryConfig.retryOn] are retried with linear backoff.
 * When [isShuttingDown] is set, all new queries are rejected with [KandraQueryException].
 */
@InternalKandraApi
class BatchEngine(
    private val session: CqlSession,
    private val statementBuilder: StatementBuilder,
    private val scope: CoroutineScope,
    @OptIn(ExperimentalKandraApi::class)
    private val eventListener: KandraEventListener? = null,
    private val retryConfig: RetryConfig = RetryConfig(),
    private val debugConfig: DebugConfig = DebugConfig(),
    private val codec: KandraCodec = KandraCodec.default
) {
    /** Set to true by the shutdown hook to stop accepting new queries. */
    val isShuttingDown: AtomicBoolean = AtomicBoolean(false)

    /** Tracks queries currently executing — used by graceful shutdown drain. */
    val inFlightCount: AtomicInteger = AtomicInteger(0)

    private var batchWarnThresholdKb: Int = 5
    private var batchMaxChunkSize: Int = 100
    private var batchAutoChunk: Boolean = true
    private var tombstoneWarnThreshold: Int = 1000

    private val validators = mutableMapOf<KClass<*>, KandraValidator<*>>()
    private var metricsRecorder: KandraMetrics? = null

    fun setMetrics(recorder: KandraMetrics) {
        metricsRecorder = recorder
    }

    fun configureBatchLimits(
        warnThresholdKb: Int,
        maxChunkSize: Int,
        autoChunk: Boolean,
        tombstoneWarnThreshold: Int = 1000
    ) {
        batchWarnThresholdKb = warnThresholdKb
        batchMaxChunkSize = maxChunkSize
        batchAutoChunk = autoChunk
        this.tombstoneWarnThreshold = tombstoneWarnThreshold
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> registerValidator(klass: KClass<T>, validator: KandraValidator<T>) {
        validators[klass] = validator
    }

    @Suppress("UNCHECKED_CAST")
    private fun validateEntity(entity: Any) {
        val validator = validators[entity::class] as? KandraValidator<Any> ?: return
        val errors = validator.validate(entity)
        if (errors.isNotEmpty()) throw KandraValidationException(errors)
    }

    private fun checkNotShuttingDown() {
        if (isShuttingDown.get()) throw KandraQueryException("Kandra is shutting down — new queries are rejected")
    }

    // ── Execute with retry ───────────────────────────────────────────────────

    private fun executeWithRetry(
        statement: Statement<*>,
        tableName: String = "unknown",
        operation: String = "query"
    ): com.datastax.oss.driver.api.core.cql.ResultSet {
        checkNotShuttingDown()
        var lastError: Throwable? = null
        val start = System.currentTimeMillis()
        inFlightCount.incrementAndGet()
        try {
            repeat(retryConfig.maxAttempts) { attempt ->
                try {
                    val rs = session.execute(statement)
                    val elapsed = System.currentTimeMillis() - start
                    if (debugConfig.logSlowQueriesMs > 0 && elapsed > debugConfig.logSlowQueriesMs) {
                        logger.warn { "Slow query detected: ${elapsed}ms (threshold ${debugConfig.logSlowQueriesMs}ms)" }
                    }
                    metricsRecorder?.record(tableName, operation, elapsed)
                    return rs
                } catch (e: Throwable) {
                    if (retryConfig.retryOn.none { it.isInstance(e) }) throw e
                    lastError = e
                    val backoff = minOf(retryConfig.backoffMillis * (attempt + 1), retryConfig.maxBackoffMillis)
                    logger.warn { "Retrying after ${e::class.simpleName} (attempt ${attempt + 1}/${retryConfig.maxAttempts}, backoff ${backoff}ms)" }
                    Thread.sleep(backoff)
                }
            }
            throw KandraQueryException("Query failed after ${retryConfig.maxAttempts} attempts", lastError)
        } finally {
            inFlightCount.decrementAndGet()
        }
    }

    private suspend fun executeWithRetrySuspend(
        statement: Statement<*>,
        tableName: String = "unknown",
        operation: String = "query"
    ): AsyncResultSet {
        checkNotShuttingDown()
        var lastError: Throwable? = null
        val start = System.currentTimeMillis()
        inFlightCount.incrementAndGet()
        try {
            repeat(retryConfig.maxAttempts) { attempt ->
                try {
                    val rs = session.executeSuspend(statement)
                    val elapsed = System.currentTimeMillis() - start
                    if (debugConfig.logSlowQueriesMs > 0 && elapsed > debugConfig.logSlowQueriesMs) {
                        logger.warn { "Slow query detected: ${elapsed}ms (threshold ${debugConfig.logSlowQueriesMs}ms)" }
                    }
                    metricsRecorder?.record(tableName, operation, elapsed)
                    return rs
                } catch (e: Throwable) {
                    if (retryConfig.retryOn.none { it.isInstance(e) }) throw e
                    lastError = e
                    val backoff = minOf(retryConfig.backoffMillis * (attempt + 1), retryConfig.maxBackoffMillis)
                    logger.warn { "Retrying after ${e::class.simpleName} (attempt ${attempt + 1}/${retryConfig.maxAttempts}, backoff ${backoff}ms)" }
                    delay(backoff)
                }
            }
            throw KandraQueryException("Query failed after ${retryConfig.maxAttempts} attempts", lastError)
        } finally {
            inFlightCount.decrementAndGet()
        }
    }

    // ── Save ─────────────────────────────────────────────────────────────────

    fun save(schema: TableSchema, entity: Any, ttlSeconds: Int? = null, timestampMicros: Long? = null, consistency: KandraConsistency? = null) {
        if (schema.isCounterTable) throw KandraQueryException("Counter tables cannot use save(). Use increment()/decrement() instead.")
        validateEntity(entity)
        val stamped = injectTimestamps(schema, entity, isInsert = true)
        val stampedWithVersion = injectInitialVersion(schema, stamped)
        val (batchLookups, eventualLookups) = schema.lookupTables.partition { it.consistency == LookupConsistency.BATCH }
        val batch = batchLookups.fold(
            BatchStatement.newInstance(DefaultBatchType.LOGGED)
                .add(statementBuilder.insertPrimary(schema, stampedWithVersion, ttlSeconds, timestampMicros = timestampMicros, consistency = consistency))
        ) { acc, l -> acc.add(statementBuilder.insertLookup(l, stampedWithVersion)) }
        if (debugConfig.logBatches) logger.debug { "Executing LOGGED BATCH with ${batchLookups.size + 1} statements for ${schema.tableName}" }
        executeWithRetry(batch, schema.tableName, "save")
        fireEventual(eventualLookups, stampedWithVersion)
    }

    fun saveIfNotExists(schema: TableSchema, entity: Any, serialConsistency: KandraConsistency = KandraConsistency.LOCAL_SERIAL): Boolean {
        if (!serialConsistency.isSerial) throw KandraQueryException("saveIfNotExists serialConsistency must be LOCAL_SERIAL or SERIAL, got: $serialConsistency")
        if (schema.isCounterTable) throw KandraQueryException("Counter tables cannot use saveIfNotExists().")
        val stamped = injectTimestamps(schema, entity, isInsert = true)
        val primaryStmt = statementBuilder.insertPrimary(schema, stamped, ifNotExists = true)
            .setSerialConsistencyLevel(DefaultConsistencyLevel.valueOf(serialConsistency.name))
        val rs = executeWithRetry(primaryStmt, schema.tableName, "saveIfNotExists")
        val applied = rs.one()?.getBoolean("[applied]") ?: false
        if (!applied) return false
        val (batchLookups, eventualLookups) = schema.lookupTables.partition { it.consistency == LookupConsistency.BATCH }
        if (batchLookups.isNotEmpty()) {
            val lookupBatch = batchLookups.fold(BatchStatement.newInstance(DefaultBatchType.LOGGED)) { acc, l -> acc.add(statementBuilder.insertLookup(l, stamped)) }
            executeWithRetry(lookupBatch)
        }
        fireEventual(eventualLookups, stamped)
        return true
    }

    fun saveWithNulls(schema: TableSchema, entity: Any, ttlSeconds: Int? = null) {
        if (schema.isCounterTable) throw KandraQueryException("Counter tables cannot use saveWithNulls().")
        val stamped = injectTimestamps(schema, entity, isInsert = true)
        val (batchLookups, eventualLookups) = schema.lookupTables.partition { it.consistency == LookupConsistency.BATCH }
        val batch = batchLookups.fold(
            BatchStatement.newInstance(DefaultBatchType.LOGGED)
                .add(statementBuilder.insertPrimaryWithNulls(schema, stamped, ttlSeconds))
        ) { acc, l -> acc.add(statementBuilder.insertLookup(l, stamped)) }
        executeWithRetry(batch)
        fireEventual(eventualLookups, stamped)
    }

    // ── Update ───────────────────────────────────────────────────────────────

    fun update(schema: TableSchema, old: Any, new: Any) {
        validateEntity(new)
        val versionCol = schema.versionColumn
        val stamped = injectTimestamps(schema, new, isInsert = false)

        if (versionCol != null) {
            val oldProps = old::class.memberProperties.associateBy { it.name }
            val oldVersion = oldProps[versionCol.propertyName]?.call(old)
                ?: throw KandraQueryException("@Version field '${versionCol.propertyName}' is null")
            val newVersion = incrementVersion(versionCol, oldVersion)
            val stampedWithVersion = injectVersion(stamped, versionCol.propertyName, newVersion)
            val stmt = buildVersionedUpdateStatement(schema, versionCol, stampedWithVersion, oldVersion)
            val rs = executeWithRetry(stmt)
            val applied = rs.one()?.getBoolean("[applied]") ?: false
            if (!applied) throwOptimisticLockException(schema, old, oldVersion)
            updateLookups(schema, old, stampedWithVersion)
            return
        }

        val (batchStmts, eventualStmts) = buildUpdateStatements(schema, old, stamped)
        val batch = batchStmts.fold(
            BatchStatement.newInstance(DefaultBatchType.LOGGED).add(statementBuilder.insertPrimary(schema, stamped))
        ) { acc, stmt -> acc.add(stmt) }
        executeWithRetry(batch)
        fireEventualStatements(eventualStmts, new, "(update)")
    }

    fun updateForce(schema: TableSchema, entity: Any) {
        val stamped = injectTimestamps(schema, entity, isInsert = false)
        val (batchStmts, eventualStmts) = buildUpdateStatements(schema, entity, stamped)
        val batch = batchStmts.fold(
            BatchStatement.newInstance(DefaultBatchType.LOGGED).add(statementBuilder.insertPrimary(schema, stamped))
        ) { acc, stmt -> acc.add(stmt) }
        executeWithRetry(batch)
        scope.launch { eventualStmts.forEach { runCatching { session.execute(it) }.onFailure { err -> logger.error(err) { "EVENTUAL updateForce failed" } } } }
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    fun delete(schema: TableSchema, entity: Any) {
        val props = entity::class.memberProperties.associateBy { it.name }
        val keyValues = (schema.partitionKeys + schema.clusteringKeys).map { key ->
            props[key.propertyName]?.call(entity) ?: throw KandraQueryException("Key '${key.propertyName}' is null on delete")
        }
        if (schema.isSoftDelete && schema.softDeleteTtlSeconds != null) {
            softDeleteBlocking(schema, entity, props, keyValues)
            return
        }
        val batch = schema.lookupTables.fold(
            BatchStatement.newInstance(DefaultBatchType.LOGGED).add(statementBuilder.deleteById(schema, *keyValues.toTypedArray()))
        ) { acc, lookup ->
            val indexValue = props[lookup.indexColumn.propertyName]?.call(entity) ?: return@fold acc
            acc.add(statementBuilder.deleteLookup(lookup, indexValue))
        }
        executeWithRetry(batch)
    }

    fun deleteAll(schema: TableSchema, entities: List<Any>) {
        if (entities.isEmpty()) return
        if (entities.size > tombstoneWarnThreshold) {
            logger.warn {
                "deleteAll() will delete ${entities.size} rows on table '${schema.tableName}', " +
                "generating up to ${entities.size} tombstones. " +
                "Consider using @SoftDelete or a TTL-based expiry strategy. " +
                "ScyllaDB tombstones persist for gc_grace_seconds (default 864000s / 10 days)."
            }
        }
        entities.forEach { delete(schema, it) }
    }

    // ── saveAll ──────────────────────────────────────────────────────────────

    fun saveAll(schema: TableSchema, entities: List<Any>, ttlSeconds: Int? = null, useBatch: Boolean = true) {
        if (entities.isEmpty()) return
        if (!useBatch) {
            entities.forEach { save(schema, it, ttlSeconds) }
            return
        }
        val stamped = entities.map { injectTimestamps(schema, it, isInsert = true) }
        val eventualInserts = mutableListOf<Any>()
        val allStatements = mutableListOf<BatchableStatement<*>>()
        stamped.forEach { entity ->
            allStatements.add(statementBuilder.insertPrimary(schema, entity, ttlSeconds))
            schema.lookupTables.forEach { lookup ->
                if (lookup.consistency == LookupConsistency.BATCH) allStatements.add(statementBuilder.insertLookup(lookup, entity))
            }
            if (schema.lookupTables.any { it.consistency == LookupConsistency.EVENTUAL }) eventualInserts.add(entity)
        }
        val estimatedSize = allStatements.size * 512
        if (estimatedSize > batchWarnThresholdKb * 1024) {
            logger.warn { "Batch size ~${estimatedSize / 1024}KB exceeds warn threshold (${batchWarnThresholdKb}KB). Consider reducing batch size." }
        }
        if (batchAutoChunk && allStatements.size > batchMaxChunkSize) {
            allStatements.chunked(batchMaxChunkSize).forEach { chunk ->
                val chunkBatch = chunk.fold(BatchStatement.newInstance(DefaultBatchType.LOGGED)) { acc, stmt -> acc.add(stmt) }
                if (debugConfig.logBatches) logger.debug { "Executing saveAll chunk of ${chunk.size} for ${schema.tableName}" }
                executeWithRetry(chunkBatch)
            }
        } else {
            val batch = allStatements.fold(BatchStatement.newInstance(DefaultBatchType.LOGGED)) { acc, stmt -> acc.add(stmt) }
            if (debugConfig.logBatches) logger.debug { "Executing saveAll LOGGED BATCH for ${schema.tableName} (${entities.size} entities)" }
            executeWithRetry(batch)
        }
        if (eventualInserts.isNotEmpty()) {
            val eventualLookups = schema.lookupTables.filter { it.consistency == LookupConsistency.EVENTUAL }
            eventualInserts.forEach { entity -> fireEventual(eventualLookups, entity) }
        }
    }

    // ── Statement collection (for KandraBatchScope) ──────────────────────────

    internal fun collectSave(schema: TableSchema, entity: Any, ttlSeconds: Int? = null): List<BatchableStatement<*>> {
        if (schema.isCounterTable) throw KandraQueryException("Counter tables cannot be saved in a batch scope.")
        val stamped = injectTimestamps(schema, entity, isInsert = true)
        return buildList {
            add(statementBuilder.insertPrimary(schema, stamped, ttlSeconds))
            schema.lookupTables.filter { it.consistency == LookupConsistency.BATCH }
                .forEach { add(statementBuilder.insertLookup(it, stamped)) }
        }
    }

    internal fun collectDelete(schema: TableSchema, entity: Any): List<BatchableStatement<*>> {
        val props = entity::class.memberProperties.associateBy { it.name }
        val keyValues = (schema.partitionKeys + schema.clusteringKeys).map { key ->
            props[key.propertyName]?.call(entity) ?: throw KandraQueryException("Key '${key.propertyName}' is null on delete")
        }
        return buildList {
            add(statementBuilder.deleteById(schema, *keyValues.toTypedArray()))
            schema.lookupTables.forEach { lookup ->
                val indexValue = props[lookup.indexColumn.propertyName]?.call(entity) ?: return@forEach
                add(statementBuilder.deleteLookup(lookup, indexValue))
            }
        }
    }

    // ── Suspend variants ─────────────────────────────────────────────────────

    suspend fun saveSuspend(schema: TableSchema, entity: Any, ttlSeconds: Int? = null, timestampMicros: Long? = null, consistency: KandraConsistency? = null) {
        if (schema.isCounterTable) throw KandraQueryException("Counter tables cannot use save(). Use increment()/decrement() instead.")
        validateEntity(entity)
        val stamped = injectTimestamps(schema, entity, isInsert = true)
        val stampedWithVersion = injectInitialVersion(schema, stamped)
        val (batchLookups, eventualLookups) = schema.lookupTables.partition { it.consistency == LookupConsistency.BATCH }
        val batch = batchLookups.fold(
            BatchStatement.newInstance(DefaultBatchType.LOGGED)
                .add(statementBuilder.insertPrimary(schema, stampedWithVersion, ttlSeconds, timestampMicros = timestampMicros, consistency = consistency))
        ) { acc, l -> acc.add(statementBuilder.insertLookup(l, stampedWithVersion)) }
        if (debugConfig.logBatches) logger.debug { "Executing LOGGED BATCH with ${batchLookups.size + 1} statements for ${schema.tableName}" }
        executeWithRetrySuspend(batch)
        fireEventualSuspend(eventualLookups, stampedWithVersion)
    }

    suspend fun saveIfNotExistsSuspend(schema: TableSchema, entity: Any, serialConsistency: KandraConsistency = KandraConsistency.LOCAL_SERIAL): Boolean {
        if (!serialConsistency.isSerial) throw KandraQueryException("saveIfNotExists serialConsistency must be LOCAL_SERIAL or SERIAL, got: $serialConsistency")
        if (schema.isCounterTable) throw KandraQueryException("Counter tables cannot use saveIfNotExists().")
        val stamped = injectTimestamps(schema, entity, isInsert = true)
        val primaryStmt = statementBuilder.insertPrimary(schema, stamped, ifNotExists = true)
            .setSerialConsistencyLevel(DefaultConsistencyLevel.valueOf(serialConsistency.name))
        val rs = executeWithRetrySuspend(primaryStmt)
        val applied = rs.currentPage().firstOrNull()?.getBoolean("[applied]") ?: false
        if (!applied) return false
        val (batchLookups, eventualLookups) = schema.lookupTables.partition { it.consistency == LookupConsistency.BATCH }
        if (batchLookups.isNotEmpty()) {
            val lookupBatch = batchLookups.fold(BatchStatement.newInstance(DefaultBatchType.LOGGED)) { acc, l -> acc.add(statementBuilder.insertLookup(l, stamped)) }
            executeWithRetrySuspend(lookupBatch)
        }
        fireEventualSuspend(eventualLookups, stamped)
        return true
    }

    suspend fun saveWithNullsSuspend(schema: TableSchema, entity: Any, ttlSeconds: Int? = null) {
        if (schema.isCounterTable) throw KandraQueryException("Counter tables cannot use saveWithNulls().")
        val stamped = injectTimestamps(schema, entity, isInsert = true)
        val (batchLookups, eventualLookups) = schema.lookupTables.partition { it.consistency == LookupConsistency.BATCH }
        val batch = batchLookups.fold(
            BatchStatement.newInstance(DefaultBatchType.LOGGED)
                .add(statementBuilder.insertPrimaryWithNulls(schema, stamped, ttlSeconds))
        ) { acc, l -> acc.add(statementBuilder.insertLookup(l, stamped)) }
        executeWithRetrySuspend(batch)
        fireEventualSuspend(eventualLookups, stamped)
    }

    suspend fun updateSuspend(schema: TableSchema, old: Any, new: Any) {
        validateEntity(new)
        val versionCol = schema.versionColumn
        val stamped = injectTimestamps(schema, new, isInsert = false)

        if (versionCol != null) {
            val oldProps = old::class.memberProperties.associateBy { it.name }
            val oldVersion = oldProps[versionCol.propertyName]?.call(old)
                ?: throw KandraQueryException("@Version field '${versionCol.propertyName}' is null")
            val newVersion = incrementVersion(versionCol, oldVersion)
            val stampedWithVersion = injectVersion(stamped, versionCol.propertyName, newVersion)
            // Async prepare avoids blocking the dispatcher on the first call for this CQL string
            val stmt = buildVersionedUpdateStatementSuspend(schema, versionCol, stampedWithVersion, oldVersion)
            val rs = executeWithRetrySuspend(stmt)
            val applied = rs.currentPage().firstOrNull()?.getBoolean("[applied]") ?: false
            if (!applied) throwOptimisticLockException(schema, old, oldVersion)
            updateLookupsSuspend(schema, old, stampedWithVersion)
            return
        }

        val (batchStmts, eventualStmts) = buildUpdateStatements(schema, old, stamped)
        val batch = batchStmts.fold(
            BatchStatement.newInstance(DefaultBatchType.LOGGED).add(statementBuilder.insertPrimary(schema, stamped))
        ) { acc, stmt -> acc.add(stmt) }
        executeWithRetrySuspend(batch)
        if (eventualStmts.isNotEmpty()) {
            scope.launch {
                eventualStmts.forEach { stmt ->
                    runCatching { session.executeSuspend(stmt) }
                        .onFailure { err ->
                            logger.error(err) { "EVENTUAL lookup update failed" }
                            @OptIn(ExperimentalKandraApi::class)
                            eventListener?.onEventualWriteFailed("(update)", new, err)
                        }
                }
            }
        }
    }

    suspend fun updateForceSuspend(schema: TableSchema, entity: Any) {
        val stamped = injectTimestamps(schema, entity, isInsert = false)
        val (batchStmts, eventualStmts) = buildUpdateStatements(schema, entity, stamped)
        val batch = batchStmts.fold(
            BatchStatement.newInstance(DefaultBatchType.LOGGED).add(statementBuilder.insertPrimary(schema, stamped))
        ) { acc, stmt -> acc.add(stmt) }
        executeWithRetrySuspend(batch)
        scope.launch { eventualStmts.forEach { runCatching { session.executeSuspend(it) }.onFailure { err -> logger.error(err) { "EVENTUAL updateForce failed" } } } }
    }

    suspend fun deleteSuspend(schema: TableSchema, entity: Any) {
        val props = entity::class.memberProperties.associateBy { it.name }
        val keyValues = (schema.partitionKeys + schema.clusteringKeys).map { key ->
            props[key.propertyName]?.call(entity) ?: throw KandraQueryException("Key '${key.propertyName}' is null on delete")
        }
        if (schema.isSoftDelete && schema.softDeleteTtlSeconds != null) {
            softDeleteSuspend(schema, entity, props, keyValues)
            return
        }
        val batch = schema.lookupTables.fold(
            BatchStatement.newInstance(DefaultBatchType.LOGGED).add(statementBuilder.deleteById(schema, *keyValues.toTypedArray()))
        ) { acc, lookup ->
            val indexValue = props[lookup.indexColumn.propertyName]?.call(entity) ?: return@fold acc
            acc.add(statementBuilder.deleteLookup(lookup, indexValue))
        }
        executeWithRetrySuspend(batch)
    }

    suspend fun deleteAllSuspend(schema: TableSchema, entities: List<Any>) {
        if (entities.isEmpty()) return
        if (entities.size > tombstoneWarnThreshold) {
            logger.warn {
                "deleteAll() will delete ${entities.size} rows on table '${schema.tableName}', " +
                "generating up to ${entities.size} tombstones. " +
                "Consider using @SoftDelete or a TTL-based expiry strategy. " +
                "ScyllaDB tombstones persist for gc_grace_seconds (default 864000s / 10 days)."
            }
        }
        entities.forEach { deleteSuspend(schema, it) }
    }

    suspend fun saveAllSuspend(schema: TableSchema, entities: List<Any>, ttlSeconds: Int? = null, useBatch: Boolean = true) {
        if (entities.isEmpty()) return
        if (!useBatch) {
            entities.forEach { saveSuspend(schema, it, ttlSeconds) }
            return
        }
        val stamped = entities.map { injectTimestamps(schema, it, isInsert = true) }
        val eventualInserts = mutableListOf<Any>()
        val allStatements = mutableListOf<BatchableStatement<*>>()
        stamped.forEach { entity ->
            allStatements.add(statementBuilder.insertPrimary(schema, entity, ttlSeconds))
            schema.lookupTables.forEach { lookup ->
                if (lookup.consistency == LookupConsistency.BATCH) allStatements.add(statementBuilder.insertLookup(lookup, entity))
            }
            if (schema.lookupTables.any { it.consistency == LookupConsistency.EVENTUAL }) eventualInserts.add(entity)
        }
        val estimatedSize = allStatements.size * 512
        if (estimatedSize > batchWarnThresholdKb * 1024) {
            logger.warn { "Batch size ~${estimatedSize / 1024}KB exceeds warn threshold (${batchWarnThresholdKb}KB). Consider reducing batch size." }
        }
        if (batchAutoChunk && allStatements.size > batchMaxChunkSize) {
            allStatements.chunked(batchMaxChunkSize).forEach { chunk ->
                val chunkBatch = chunk.fold(BatchStatement.newInstance(DefaultBatchType.LOGGED)) { acc, stmt -> acc.add(stmt) }
                if (debugConfig.logBatches) logger.debug { "Executing saveAllSuspend chunk of ${chunk.size} for ${schema.tableName}" }
                executeWithRetrySuspend(chunkBatch)
            }
        } else {
            val batch = allStatements.fold(BatchStatement.newInstance(DefaultBatchType.LOGGED)) { acc, stmt -> acc.add(stmt) }
            if (debugConfig.logBatches) logger.debug { "Executing saveAllSuspend LOGGED BATCH for ${schema.tableName} (${entities.size} entities)" }
            executeWithRetrySuspend(batch)
        }
        if (eventualInserts.isNotEmpty()) {
            val eventualLookups = schema.lookupTables.filter { it.consistency == LookupConsistency.EVENTUAL }
            eventualInserts.forEach { entity -> fireEventualSuspend(eventualLookups, entity) }
        }
    }

    // ── Soft delete helpers ───────────────────────────────────────────────────

    private fun softDeleteBlocking(
        schema: TableSchema,
        entity: Any,
        props: Map<String, kotlin.reflect.KProperty1<out Any, *>>,
        keyValues: List<Any>
    ) {
        val ttl = schema.softDeleteTtlSeconds!!
        val marker = schema.softDeleteMarkerColumn
        val nonKeyCols = schema.columns.filter { !it.isTransient && !it.isCounter && it != marker }
        val whereParts = (schema.partitionKeys + schema.clusteringKeys).joinToString(" AND ") { "${it.cqlName} = ?" }
        if (nonKeyCols.isNotEmpty()) {
            val setClauses = nonKeyCols.joinToString(", ") { "${it.cqlName} = ?" }
            val cql = "UPDATE ${schema.tableName} USING TTL $ttl SET $setClauses WHERE $whereParts"
            val prepared = session.prepare(cql)
            val values = mutableListOf<Any?>()
            nonKeyCols.forEach { col -> values.add(props[col.propertyName]?.call(entity)) }
            keyValues.forEach { values.add(it) }
            executeWithRetry(prepared.bind(*values.toTypedArray()))
        }
        // Marker column is written without TTL — it must outlive the other columns so
        // findActive() can still tell this row apart from a live one after they expire.
        if (marker != null) {
            val cql = "UPDATE ${schema.tableName} SET ${marker.cqlName} = ? WHERE $whereParts"
            val prepared = session.prepare(cql)
            executeWithRetry(prepared.bind(true, *keyValues.toTypedArray()))
        }
        // Lookup rows are deliberately left alone (see ISS-030) -- a soft-deleted row still "exists"
        // until its TTL expires, so it must remain resolvable via its @LookupIndex too, exactly like
        // a direct findById/findActive would still see it. Hard delete (the branch this function is
        // NOT called from) is the only path that should ever remove lookup rows.
    }

    private suspend fun softDeleteSuspend(
        schema: TableSchema,
        entity: Any,
        props: Map<String, kotlin.reflect.KProperty1<out Any, *>>,
        keyValues: List<Any>
    ) {
        val ttl = schema.softDeleteTtlSeconds!!
        val marker = schema.softDeleteMarkerColumn
        val nonKeyCols = schema.columns.filter { !it.isTransient && !it.isCounter && it != marker }
        val whereParts = (schema.partitionKeys + schema.clusteringKeys).joinToString(" AND ") { "${it.cqlName} = ?" }
        if (nonKeyCols.isNotEmpty()) {
            val setClauses = nonKeyCols.joinToString(", ") { "${it.cqlName} = ?" }
            val cql = "UPDATE ${schema.tableName} USING TTL $ttl SET $setClauses WHERE $whereParts"
            // prepareSuspend avoids blocking the coroutine dispatcher; driver caches the result
            val prepared = session.prepareSuspend(cql)
            val values = mutableListOf<Any?>()
            nonKeyCols.forEach { col -> values.add(props[col.propertyName]?.call(entity)) }
            keyValues.forEach { values.add(it) }
            executeWithRetrySuspend(prepared.bind(*values.toTypedArray()))
        }
        if (marker != null) {
            val cql = "UPDATE ${schema.tableName} SET ${marker.cqlName} = ? WHERE $whereParts"
            val prepared = session.prepareSuspend(cql)
            executeWithRetrySuspend(prepared.bind(true, *keyValues.toTypedArray()))
        }
        // Lookup rows are deliberately left alone (see ISS-030) -- a soft-deleted row still "exists"
        // until its TTL expires, so it must remain resolvable via its @LookupIndex too, exactly like
        // a direct findById/findActive would still see it. Hard delete (the branch this function is
        // NOT called from) is the only path that should ever remove lookup rows.
    }

    // ── Version helpers ───────────────────────────────────────────────────────

    /**
     * Async version of [buildVersionedUpdateStatement] for the suspend path.
     * Uses [CqlSession.prepareSuspend] so the first prepare call does not block the dispatcher.
     */
    private suspend fun buildVersionedUpdateStatementSuspend(
        schema: TableSchema,
        versionCol: ColumnSchema,
        stampedWithVersion: Any,
        oldVersion: Any
    ): BoundStatement {
        val nonKeyCols = buildList {
            addAll(schema.columns)
            addAll(schema.lookupTables.map { it.indexColumn })
        }.distinctBy { it.cqlName }.filter { !it.isTransient }

        val setClauses = nonKeyCols.joinToString(", ") { "${it.cqlName} = ?" }
        val whereParts = (schema.partitionKeys + schema.clusteringKeys).joinToString(" AND ") { "${it.cqlName} = ?" }
        val cql = "UPDATE ${schema.tableName} SET $setClauses WHERE $whereParts IF ${versionCol.cqlName} = ?"
        val prepared = session.prepareSuspend(cql)   // truly async prepare

        val entityProps = stampedWithVersion::class.memberProperties.associateBy { it.name }
        val values = mutableListOf<Any?>()
        nonKeyCols.forEach { col ->
            val encoded = codec.encode(entityProps[col.propertyName]?.call(stampedWithVersion), col.type)
            values.add(if (encoded === KandraUnset) null else encoded)
        }
        (schema.partitionKeys + schema.clusteringKeys).forEach { key ->
            val encoded = codec.encode(entityProps[key.propertyName]?.call(stampedWithVersion), key.type)
            values.add(if (encoded === KandraUnset) null else encoded)
        }
        val encodedOldVersion = codec.encode(oldVersion, versionCol.type)
        values.add(if (encodedOldVersion === KandraUnset) null else encodedOldVersion)

        return prepared.bind(*values.toTypedArray())
            .setSerialConsistencyLevel(DefaultConsistencyLevel.LOCAL_SERIAL)
    }

    /**
     * Builds the LWT bound statement without executing it.
     * Callers choose which executor (blocking vs suspend) to use.
     * session.prepare() is blocking but driver-cached after first call.
     */
    private fun buildVersionedUpdateStatement(
        schema: TableSchema,
        versionCol: ColumnSchema,
        stampedWithVersion: Any,
        oldVersion: Any
    ): BoundStatement {
        val nonKeyCols = buildList {
            addAll(schema.columns)
            addAll(schema.lookupTables.map { it.indexColumn })
        }.distinctBy { it.cqlName }.filter { !it.isTransient }

        val setClauses = nonKeyCols.joinToString(", ") { "${it.cqlName} = ?" }
        val whereParts = (schema.partitionKeys + schema.clusteringKeys).joinToString(" AND ") { "${it.cqlName} = ?" }
        val cql = "UPDATE ${schema.tableName} SET $setClauses WHERE $whereParts IF ${versionCol.cqlName} = ?"
        val prepared = session.prepare(cql)

        val entityProps = stampedWithVersion::class.memberProperties.associateBy { it.name }
        val values = mutableListOf<Any?>()
        nonKeyCols.forEach { col ->
            val encoded = codec.encode(entityProps[col.propertyName]?.call(stampedWithVersion), col.type)
            values.add(if (encoded === KandraUnset) null else encoded)
        }
        (schema.partitionKeys + schema.clusteringKeys).forEach { key ->
            val encoded = codec.encode(entityProps[key.propertyName]?.call(stampedWithVersion), key.type)
            values.add(if (encoded === KandraUnset) null else encoded)
        }
        val encodedOldVersion = codec.encode(oldVersion, versionCol.type)
        values.add(if (encodedOldVersion === KandraUnset) null else encodedOldVersion)

        return prepared.bind(*values.toTypedArray())
            .setSerialConsistencyLevel(DefaultConsistencyLevel.LOCAL_SERIAL)
    }

    private fun incrementVersion(versionCol: ColumnSchema, oldVersion: Any): Any =
        when (versionCol.type.classifier) {
            Long::class -> (oldVersion as Long) + 1L
            Instant::class -> Instant.now()
            else -> throw KandraQueryException("@Version field must be Long or Instant")
        }

    private fun throwOptimisticLockException(schema: TableSchema, old: Any, oldVersion: Any): Nothing {
        val pkValue = schema.partitionKeys.firstOrNull()?.let { pk ->
            old::class.memberProperties.find { p -> p.name == pk.propertyName }?.call(old)
        } ?: "unknown"
        throw KandraOptimisticLockException(
            "Optimistic lock conflict on ${schema.entityClass.simpleName}: version $oldVersion was modified concurrently",
            schema.entityClass,
            pkValue ?: "unknown"
        )
    }

    // ── Lookup update helpers ─────────────────────────────────────────────────

    private fun updateLookups(schema: TableSchema, old: Any, new: Any) {
        val (batchStmts, eventualStmts) = buildUpdateStatements(schema, old, new)
        if (batchStmts.isNotEmpty()) {
            val batch = batchStmts.fold(BatchStatement.newInstance(DefaultBatchType.LOGGED)) { acc, s -> acc.add(s) }
            executeWithRetry(batch)
        }
        fireEventualStatements(eventualStmts, new, "(version update)")
    }

    private suspend fun updateLookupsSuspend(schema: TableSchema, old: Any, new: Any) {
        val (batchStmts, eventualStmts) = buildUpdateStatements(schema, old, new)
        if (batchStmts.isNotEmpty()) {
            val batch = batchStmts.fold(BatchStatement.newInstance(DefaultBatchType.LOGGED)) { acc, s -> acc.add(s) }
            executeWithRetrySuspend(batch)
        }
        if (eventualStmts.isNotEmpty()) {
            scope.launch {
                eventualStmts.forEach { s ->
                    runCatching { session.executeSuspend(s) }
                        .onFailure { err -> logger.error(err) { "EVENTUAL lookup update failed" } }
                }
            }
        }
    }

    // ── Eventual write helpers ────────────────────────────────────────────────

    private fun fireEventual(eventualLookups: List<LookupTableSchema>, entity: Any) {
        if (eventualLookups.isEmpty()) return
        scope.launch {
            eventualLookups.forEach { lookup ->
                runCatching { session.execute(statementBuilder.insertLookup(lookup, entity)) }
                    .onFailure { err ->
                        logger.error(err) { "EVENTUAL lookup insert failed for ${lookup.tableName}" }
                        @OptIn(ExperimentalKandraApi::class)
                        eventListener?.onEventualWriteFailed(lookup.tableName, entity, err)
                    }
            }
        }
    }

    private fun fireEventualSuspend(eventualLookups: List<LookupTableSchema>, entity: Any) {
        if (eventualLookups.isEmpty()) return
        scope.launch {
            eventualLookups.forEach { lookup ->
                runCatching { session.executeSuspend(statementBuilder.insertLookup(lookup, entity)) }
                    .onFailure { err ->
                        logger.error(err) { "EVENTUAL lookup insert failed for ${lookup.tableName}" }
                        @OptIn(ExperimentalKandraApi::class)
                        eventListener?.onEventualWriteFailed(lookup.tableName, entity, err)
                    }
            }
        }
    }

    private fun fireEventualStatements(stmts: List<BatchableStatement<*>>, entity: Any, context: String) {
        if (stmts.isEmpty()) return
        scope.launch {
            stmts.forEach { stmt ->
                runCatching { session.execute(stmt) }
                    .onFailure { err ->
                        logger.error(err) { "EVENTUAL lookup $context failed" }
                        @OptIn(ExperimentalKandraApi::class)
                        eventListener?.onEventualWriteFailed(context, entity, err)
                    }
            }
        }
    }

    private fun buildUpdateStatements(schema: TableSchema, old: Any, new: Any): Pair<List<BatchableStatement<*>>, List<BatchableStatement<*>>> {
        val oldProps = old::class.memberProperties.associateBy { it.name }
        val newProps = new::class.memberProperties.associateBy { it.name }
        val batchStmts = mutableListOf<BatchableStatement<*>>()
        val eventualStmts = mutableListOf<BatchableStatement<*>>()
        schema.lookupTables.forEach { lookup ->
            val oldVal = oldProps[lookup.indexColumn.propertyName]?.call(old)
            val newVal = newProps[lookup.indexColumn.propertyName]?.call(new)
            val target = if (lookup.consistency == LookupConsistency.BATCH) batchStmts else eventualStmts
            if (oldVal != newVal && oldVal != null) target.add(statementBuilder.deleteLookup(lookup, oldVal))
            if (newVal != null) target.add(statementBuilder.insertLookup(lookup, new))
        }
        return batchStmts to eventualStmts
    }

    // ── Timestamp / version injection ─────────────────────────────────────────

    internal fun injectTimestamps(schema: TableSchema, entity: Any, isInsert: Boolean): Any {
        val createdAt = schema.createdAtColumn
        val updatedAt = schema.updatedAtColumn
        if (createdAt == null && updatedAt == null) return entity
        val copyFn = entity::class.memberFunctions.find { it.name == "copy" } ?: return entity
        val now = Instant.now()
        val callArgs = mutableMapOf<KParameter, Any?>()
        callArgs[copyFn.parameters[0]] = entity
        copyFn.parameters.drop(1).forEach { param ->
            when (param.name) {
                createdAt?.propertyName -> if (isInsert) callArgs[param] = now
                updatedAt?.propertyName -> callArgs[param] = now
            }
        }
        return copyFn.callBy(callArgs) ?: entity
    }

    private fun injectInitialVersion(schema: TableSchema, entity: Any): Any {
        val versionCol = schema.versionColumn ?: return entity
        val initVersion: Any = when (versionCol.type.classifier) {
            Long::class -> 1L
            Instant::class -> Instant.now()
            else -> throw KandraQueryException("@Version must be Long or Instant")
        }
        return injectVersion(entity, versionCol.propertyName, initVersion)
    }

    private fun injectVersion(entity: Any, propertyName: String, version: Any): Any {
        val copyFn = entity::class.memberFunctions.find { it.name == "copy" } ?: return entity
        val callArgs = mutableMapOf<KParameter, Any?>()
        callArgs[copyFn.parameters[0]] = entity
        copyFn.parameters.drop(1).forEach { param ->
            if (param.name == propertyName) callArgs[param] = version
        }
        return copyFn.callBy(callArgs) ?: entity
    }
}
