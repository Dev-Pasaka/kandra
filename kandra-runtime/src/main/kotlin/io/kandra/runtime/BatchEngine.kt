package io.kandra.runtime

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel
import com.datastax.oss.driver.api.core.cql.AsyncResultSet
import com.datastax.oss.driver.api.core.cql.BatchStatement
import com.datastax.oss.driver.api.core.cql.BatchableStatement
import com.datastax.oss.driver.api.core.cql.BoundStatement
import com.datastax.oss.driver.api.core.cql.DefaultBatchType
import com.datastax.oss.driver.api.core.RequestThrottlingException
import com.datastax.oss.driver.api.core.cql.Statement
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.InternalKandraApi
import io.kandra.core.KandraConsistency
import io.kandra.core.KandraEventListener
import io.kandra.core.KandraMetrics
import io.kandra.core.KandraUuid
import io.kandra.core.KandraValidationException
import io.kandra.core.KandraValidator
import io.kandra.core.annotations.LookupConsistency
import io.kandra.core.annotations.UuidStrategy
import io.kandra.core.exception.KandraOptimisticLockException
import io.kandra.core.exception.KandraQueryException
import io.kandra.core.exception.KandraThrottledException
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
import kotlin.random.Random
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1

private val logger = KotlinLogging.logger {}

/**
 * Executes save, update, delete, and saveAll operations using LOGGED batch statements.
 *
 * BATCH-consistency lookups are included in the atomic batch.
 * EVENTUAL-consistency lookups fire asynchronously via [scope] after the batch commits, but are
 * routed through the same [executeWithRetry]/[executeWithRetrySuspend] path as every other write:
 * they retry on transient failures per [retryConfig.retryOn], are counted in [inFlightCount] so
 * graceful shutdown drains them before closing the session, and are rejected once [isShuttingDown]
 * is set — same as any synchronous query. Failures (including "rejected due to shutdown") are
 * forwarded to [eventListener] (if set) then logged.
 *
 * Transient failures listed in [retryConfig.retryOn] are retried with linear backoff — except the
 * `@Version` LWT update statement (see [update]/[updateSuspend]), which is executed exactly once via
 * [executeOnce]/[executeOnceSuspend] to avoid a blind retry masking a real success as a spurious
 * [KandraOptimisticLockException].
 * When [isShuttingDown] is set, all new queries — eventual lookups included — are rejected with
 * [KandraQueryException].
 */
@InternalKandraApi
class BatchEngine(
    private val session: CqlSession,
    // internal, not private: KandraRepository/KandraSuspendRepository (ISS-048) read this off the
    // batchEngine they already receive instead of constructing their own default StatementBuilder,
    // so plugin-configured codec/debug/consistency/cache-size actually reach the read path.
    internal val statementBuilder: StatementBuilder,
    private val scope: CoroutineScope,
    @OptIn(ExperimentalKandraApi::class)
    private val eventListener: KandraEventListener? = null,
    private val retryConfig: RetryConfig = RetryConfig(),
    internal val debugConfig: DebugConfig = DebugConfig(),
    internal val codec: KandraCodec = KandraCodec.default
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

    private fun newLoggedBatch(schema: TableSchema, consistency: KandraConsistency? = null): BatchStatement {
        val level = statementBuilder.resolveWriteConsistency(schema, consistency)
        return BatchStatement.newInstance(DefaultBatchType.LOGGED)
            .setConsistencyLevel(DefaultConsistencyLevel.valueOf(level.name))
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

    /**
     * Same shutdown gate as [checkNotShuttingDown], but also reports the rejection to
     * [metricsRecorder] via [KandraMetrics.recordFailure] (ISS-074 / GH #82) — a shutdown rejection
     * previously recorded nothing at all, leaving the failure invisible to metrics/alerting.
     */
    private fun checkNotShuttingDown(tableName: String, operation: String) {
        if (isShuttingDown.get()) {
            metricsRecorder?.recordFailure(tableName, operation, 0L, 0, "KandraQueryException")
            throw KandraQueryException("Kandra is shutting down — new queries are rejected")
        }
    }

    // ── Execute with retry ───────────────────────────────────────────────────

    /**
     * Linear backoff (`backoffMillis * (attempt + 1)`, capped at `maxBackoffMillis`) with optional
     * "equal jitter" — half the computed delay, plus a random amount up to the other half — so many
     * concurrent callers retrying the same transient failure don't all retry in lockstep. See
     * ISS-069 / GH #70 item 5.
     */
    private fun jitteredBackoff(attempt: Int): Long {
        val computed = minOf(retryConfig.backoffMillis * (attempt + 1), retryConfig.maxBackoffMillis)
        if (!retryConfig.jitter) return computed
        val half = computed / 2
        return half + Random.nextLong(half + 1)
    }

    private fun executeWithRetry(
        statement: Statement<*>,
        tableName: String = "unknown",
        operation: String = "query"
    ): com.datastax.oss.driver.api.core.cql.ResultSet {
        checkNotShuttingDown(tableName, operation)
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
                    metricsRecorder?.record(tableName, operation, elapsed, attempt + 1)
                    return rs
                } catch (e: RequestThrottlingException) {
                    // A backpressure rejection, not a transient network fault -- retrying it
                    // immediately just adds another request on top of an already-overloaded
                    // throttler, so this is deliberately never retried regardless of retryOn.
                    // Wrapped into Kandra's documented exception hierarchy instead of leaking the
                    // raw driver type. See GH #103 / ISS-090.
                    metricsRecorder?.recordFailure(tableName, operation, System.currentTimeMillis() - start, attempt + 1, e::class.qualifiedName ?: e::class.simpleName ?: "Throwable")
                    throw KandraThrottledException("Request throttled on '$tableName' ($operation): ${e.message}", e)
                } catch (e: Throwable) {
                    if (retryConfig.retryOn.none { it.isInstance(e) }) {
                        // Immediate non-retryable exception (ISS-074 / GH #82) — record it, since this
                        // never reaches the "exhausted retries" branch below.
                        metricsRecorder?.recordFailure(tableName, operation, System.currentTimeMillis() - start, attempt + 1, e::class.qualifiedName ?: e::class.simpleName ?: "Throwable")
                        throw e
                    }
                    // Never retry a statement that isn't explicitly marked idempotent — StatementBuilder
                    // sets this per-statement (see its setIdempotent call sites), and an unset flag on a
                    // BatchStatement (batches never explicitly mark themselves idempotent) is `null`,
                    // which fails safe here too. A blind retry of a non-idempotent write (plain INSERT,
                    // lookup INSERT, collection append/remove/put, counter increment/decrement) risks
                    // double-applying it server-side. See ISS-055 / GH #56.
                    if (statement.isIdempotent() != true) {
                        metricsRecorder?.recordFailure(tableName, operation, System.currentTimeMillis() - start, attempt + 1, e::class.qualifiedName ?: e::class.simpleName ?: "Throwable")
                        throw e
                    }
                    lastError = e
                    val backoff = jitteredBackoff(attempt)
                    logger.warn { "Retrying after ${e::class.simpleName} (attempt ${attempt + 1}/${retryConfig.maxAttempts}, backoff ${backoff}ms)" }
                    // GH-109 item 4: Thread.sleep(backoff) sits inside this catch block. If the
                    // thread is interrupted during this sleep (rather than during session.execute
                    // above), the resulting InterruptedException propagates out of executeWithRetry
                    // past every recordFailure call site above and below — an observability gap, not
                    // a leak (inFlightCount is still decremented via the outer finally). Flagged as
                    // plausible, not independently reproduced under live interruption.
                    Thread.sleep(backoff)
                }
            }
            // Retry loop exhausted (ISS-074 / GH #82) — this used to record nothing, silently hiding
            // every retry-exhaustion failure from metrics/alerting.
            metricsRecorder?.recordFailure(
                tableName, operation, System.currentTimeMillis() - start, retryConfig.maxAttempts,
                lastError?.let { it::class.qualifiedName ?: it::class.simpleName } ?: "Unknown"
            )
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
        checkNotShuttingDown(tableName, operation)
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
                    metricsRecorder?.record(tableName, operation, elapsed, attempt + 1)
                    return rs
                } catch (e: RequestThrottlingException) {
                    // See the blocking executeWithRetry's identical catch for why — GH #103 / ISS-090.
                    metricsRecorder?.recordFailure(tableName, operation, System.currentTimeMillis() - start, attempt + 1, e::class.qualifiedName ?: e::class.simpleName ?: "Throwable")
                    throw KandraThrottledException("Request throttled on '$tableName' ($operation): ${e.message}", e)
                } catch (e: Throwable) {
                    if (retryConfig.retryOn.none { it.isInstance(e) }) {
                        // Immediate non-retryable exception (ISS-074 / GH #82) — record it, since this
                        // never reaches the "exhausted retries" branch below.
                        metricsRecorder?.recordFailure(tableName, operation, System.currentTimeMillis() - start, attempt + 1, e::class.qualifiedName ?: e::class.simpleName ?: "Throwable")
                        throw e
                    }
                    // See the blocking executeWithRetry's identical check for why — ISS-055 / GH #56.
                    if (statement.isIdempotent() != true) {
                        metricsRecorder?.recordFailure(tableName, operation, System.currentTimeMillis() - start, attempt + 1, e::class.qualifiedName ?: e::class.simpleName ?: "Throwable")
                        throw e
                    }
                    lastError = e
                    val backoff = jitteredBackoff(attempt)
                    logger.warn { "Retrying after ${e::class.simpleName} (attempt ${attempt + 1}/${retryConfig.maxAttempts}, backoff ${backoff}ms)" }
                    // GH-109 item 4: see the blocking executeWithRetry's identical callout on
                    // Thread.sleep — a CancellationException raised during this delay() propagates
                    // past every recordFailure call site the same way.
                    delay(backoff)
                }
            }
            // Retry loop exhausted (ISS-074 / GH #82) — see the blocking counterpart's identical comment.
            metricsRecorder?.recordFailure(
                tableName, operation, System.currentTimeMillis() - start, retryConfig.maxAttempts,
                lastError?.let { it::class.qualifiedName ?: it::class.simpleName } ?: "Unknown"
            )
            throw KandraQueryException("Query failed after ${retryConfig.maxAttempts} attempts", lastError)
        } finally {
            inFlightCount.decrementAndGet()
        }
    }

    /**
     * Executes [statement] exactly once — no retry-on-transient-error loop — while still
     * participating in [inFlightCount] tracking and the [checkNotShuttingDown] gate.
     *
     * Used for the `@Version` LWT update statement (`UPDATE ... IF version = ?`): blindly retrying
     * a conditional update on a transient exception (timeout, etc.) is unsafe, because the server may
     * have already applied the write and advanced the version before the client observed the error —
     * a retry would then see `[applied] = false` and raise a spurious [KandraOptimisticLockException]
     * for a write that actually succeeded. By executing once, any transient exception propagates to
     * the caller as-is (never masked as an optimistic-lock conflict), and a real `[applied] = false`
     * result (no exception) still reflects a genuine concurrent modification.
     */
    private fun executeOnce(
        statement: Statement<*>,
        tableName: String = "unknown",
        operation: String = "query"
    ): com.datastax.oss.driver.api.core.cql.ResultSet {
        checkNotShuttingDown(tableName, operation)
        val start = System.currentTimeMillis()
        inFlightCount.incrementAndGet()
        try {
            val rs = session.execute(statement)
            val elapsed = System.currentTimeMillis() - start
            if (debugConfig.logSlowQueriesMs > 0 && elapsed > debugConfig.logSlowQueriesMs) {
                logger.warn { "Slow query detected: ${elapsed}ms (threshold ${debugConfig.logSlowQueriesMs}ms)" }
            }
            metricsRecorder?.record(tableName, operation, elapsed, 1)
            return rs
        } catch (e: RequestThrottlingException) {
            // See executeWithRetry's identical catch for why — GH #103 / ISS-090.
            metricsRecorder?.recordFailure(tableName, operation, System.currentTimeMillis() - start, 1, e::class.qualifiedName ?: e::class.simpleName ?: "Throwable")
            throw KandraThrottledException("Request throttled on '$tableName' ($operation): ${e.message}", e)
        } catch (e: Throwable) {
            // executeOnce has no retry loop, but an immediate failure here (e.g. a transient exception
            // propagated as-is per its doc) previously recorded nothing at all. See ISS-074 / GH #82.
            metricsRecorder?.recordFailure(tableName, operation, System.currentTimeMillis() - start, 1, e::class.qualifiedName ?: e::class.simpleName ?: "Throwable")
            throw e
        } finally {
            inFlightCount.decrementAndGet()
        }
    }

    /** Suspend counterpart of [executeOnce] — see its doc for why the versioned-update path skips retry. */
    private suspend fun executeOnceSuspend(
        statement: Statement<*>,
        tableName: String = "unknown",
        operation: String = "query"
    ): AsyncResultSet {
        checkNotShuttingDown(tableName, operation)
        val start = System.currentTimeMillis()
        inFlightCount.incrementAndGet()
        try {
            val rs = session.executeSuspend(statement)
            val elapsed = System.currentTimeMillis() - start
            if (debugConfig.logSlowQueriesMs > 0 && elapsed > debugConfig.logSlowQueriesMs) {
                logger.warn { "Slow query detected: ${elapsed}ms (threshold ${debugConfig.logSlowQueriesMs}ms)" }
            }
            metricsRecorder?.record(tableName, operation, elapsed, 1)
            return rs
        } catch (e: RequestThrottlingException) {
            // See the blocking executeOnce's identical catch — GH #103 / ISS-090.
            metricsRecorder?.recordFailure(tableName, operation, System.currentTimeMillis() - start, 1, e::class.qualifiedName ?: e::class.simpleName ?: "Throwable")
            throw KandraThrottledException("Request throttled on '$tableName' ($operation): ${e.message}", e)
        } catch (e: Throwable) {
            // See the blocking executeOnce's identical comment — ISS-074 / GH #82.
            metricsRecorder?.recordFailure(tableName, operation, System.currentTimeMillis() - start, 1, e::class.qualifiedName ?: e::class.simpleName ?: "Throwable")
            throw e
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
            newLoggedBatch(schema, consistency)
                .add(statementBuilder.insertPrimary(schema, stampedWithVersion, ttlSeconds, timestampMicros = timestampMicros, consistency = consistency))
        ) { acc, l -> acc.add(statementBuilder.insertLookup(schema, l, stampedWithVersion)) }
        if (debugConfig.logBatches) logger.debug { "Executing LOGGED BATCH with ${batchLookups.size + 1} statements for ${schema.tableName}" }
        executeWithRetry(batch, schema.tableName, "save")
        fireEventual(schema, eventualLookups, stampedWithVersion)
    }

    fun saveIfNotExists(schema: TableSchema, entity: Any, serialConsistency: KandraConsistency = KandraConsistency.LOCAL_SERIAL): Boolean {
        if (!serialConsistency.isSerial) throw KandraQueryException("saveIfNotExists serialConsistency must be LOCAL_SERIAL or SERIAL, got: $serialConsistency")
        if (schema.isCounterTable) throw KandraQueryException("Counter tables cannot use saveIfNotExists().")
        val stamped = injectTimestamps(schema, entity, isInsert = true)
        val primaryStmt = statementBuilder.insertPrimary(schema, stamped, ifNotExists = true)
            .setSerialConsistencyLevel(DefaultConsistencyLevel.valueOf(serialConsistency.name))
        // Not executeWithRetry: a blind retry of this LWT risks observing our own prior attempt's
        // success as a false "already exists" negative. See executeOnce's doc.
        val rs = executeOnce(primaryStmt, schema.tableName, "saveIfNotExists")
        val applied = rs.one()?.getBoolean("[applied]") ?: false
        if (!applied) return false
        val (batchLookups, eventualLookups) = schema.lookupTables.partition { it.consistency == LookupConsistency.BATCH }
        if (batchLookups.isNotEmpty()) {
            val lookupBatch = batchLookups.fold(newLoggedBatch(schema)) { acc, l -> acc.add(statementBuilder.insertLookup(schema, l, stamped)) }
            executeWithRetry(lookupBatch)
        }
        fireEventual(schema, eventualLookups, stamped)
        return true
    }

    fun saveWithNulls(schema: TableSchema, entity: Any, ttlSeconds: Int? = null, consistency: KandraConsistency? = null) {
        if (schema.isCounterTable) throw KandraQueryException("Counter tables cannot use saveWithNulls().")
        val stamped = injectTimestamps(schema, entity, isInsert = true)
        val (batchLookups, eventualLookups) = schema.lookupTables.partition { it.consistency == LookupConsistency.BATCH }
        val batch = batchLookups.fold(
            newLoggedBatch(schema, consistency)
                .add(statementBuilder.insertPrimaryWithNulls(schema, stamped, ttlSeconds, consistency = consistency))
        ) { acc, l -> acc.add(statementBuilder.insertLookup(schema, l, stamped)) }
        executeWithRetry(batch)
        fireEventual(schema, eventualLookups, stamped)
    }

    // ── Update ───────────────────────────────────────────────────────────────

    fun update(schema: TableSchema, old: Any, new: Any, consistency: KandraConsistency? = null, ttlSeconds: Int? = null) {
        validateEntity(new)
        val versionCol = schema.versionColumn
        val stamped = injectTimestamps(schema, new, isInsert = false)

        if (versionCol != null) {
            val oldProps = schema.reflection.propertiesByName
            val oldVersion = oldProps[versionCol.propertyName]?.call(old)
                ?: throw KandraQueryException("@Version field '${versionCol.propertyName}' is null")
            val newVersion = incrementVersion(versionCol, oldVersion)
            val stampedWithVersion = injectVersion(schema, stamped, versionCol.propertyName, newVersion)
            val stmt = buildVersionedUpdateStatement(schema, versionCol, stampedWithVersion, oldVersion, consistency, ttlSeconds)
            // Not executeWithRetry: a blind retry of this LWT would risk observing our own prior
            // attempt's success as a false optimistic-lock conflict. See executeOnce's doc.
            val rs = executeOnce(stmt, schema.tableName, "update")
            val applied = rs.one()?.getBoolean("[applied]") ?: false
            if (!applied) throwOptimisticLockException(schema, old, oldVersion)
            updateLookups(schema, old, stampedWithVersion, consistency)
            return
        }

        val (batchStmts, eventualStmts) = buildUpdateStatements(schema, old, stamped)
        val batch = batchStmts.fold(
            newLoggedBatch(schema, consistency).add(statementBuilder.insertPrimary(schema, stamped, consistency = consistency))
        ) { acc, stmt -> acc.add(stmt) }
        executeWithRetry(batch)
        fireEventualStatements(eventualStmts, new, "(update)", schema.tableName)
    }

    fun updateForce(schema: TableSchema, entity: Any, consistency: KandraConsistency? = null) {
        val stamped = injectTimestamps(schema, entity, isInsert = false)
        val (batchStmts, eventualStmts) = buildUpdateStatements(schema, entity, stamped)
        val batch = batchStmts.fold(
            newLoggedBatch(schema, consistency).add(statementBuilder.insertPrimary(schema, stamped, consistency = consistency))
        ) { acc, stmt -> acc.add(stmt) }
        executeWithRetry(batch)
        fireEventualStatements(eventualStmts, entity, "(updateForce)", schema.tableName)
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    fun delete(schema: TableSchema, entity: Any) {
        val props = schema.reflection.propertiesByName
        val keyValues = (schema.partitionKeys + schema.clusteringKeys).map { key ->
            props[key.propertyName]?.call(entity) ?: throw KandraQueryException("Key '${key.propertyName}' is null on delete")
        }
        if (schema.isSoftDelete && schema.softDeleteTtlSeconds != null) {
            softDeleteBlocking(schema, entity, props, keyValues)
            return
        }
        val batch = schema.lookupTables.fold(
            newLoggedBatch(schema).add(statementBuilder.deleteById(schema, *keyValues.toTypedArray()))
        ) { acc, lookup ->
            val indexValue = props[lookup.indexColumn.propertyName]?.call(entity) ?: return@fold acc
            acc.add(statementBuilder.deleteLookup(lookup, indexValue))
        }
        executeWithRetry(batch)
    }

    /**
     * Deletes a row by full primary key without looking up the entity first — used by
     * [io.kandra.runtime.repository.KandraRepository.deleteById]'s "not found" branch (nothing to
     * diff against, so there's no lookup-table cleanup possible here; unlike [delete] this never
     * touches lookup rows). Routed through [executeWithRetry] so it gets the same shutdown gate,
     * retry-on-transient-error, and [inFlightCount] tracking as every other write.
     */
    fun deleteById(schema: TableSchema, vararg keyValues: Any) {
        executeWithRetry(statementBuilder.deleteById(schema, *keyValues), schema.tableName, "deleteById")
    }

    /** Suspend counterpart of [deleteById] — see its doc. Uses [StatementBuilder.deleteByIdSuspend]
     *  (async prepare) rather than the blocking [StatementBuilder.deleteById] (GH #27 / ISS-049). */
    suspend fun deleteByIdSuspend(schema: TableSchema, vararg keyValues: Any) {
        executeWithRetrySuspend(statementBuilder.deleteByIdSuspend(schema, *keyValues), schema.tableName, "deleteById")
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

    // ── Counter / collection mutations ────────────────────────────────────────
    // append/remove/put/increment/decrement used to call session.execute/executeSuspend directly
    // from the repository classes, bypassing checkNotShuttingDown(), inFlightCount tracking, and
    // retry-on-transient-error entirely. Routed through executeWithRetry/executeWithRetrySuspend
    // here so they get the same safety net as every other write.

    fun append(schema: TableSchema, keyValues: List<Any>, columnName: String, values: Any, consistency: KandraConsistency? = null) {
        executeWithRetry(statementBuilder.appendToCollection(schema, keyValues, columnName, values, consistency), schema.tableName, "append")
    }

    /** Uses [StatementBuilder.appendToCollectionSuspend] (async prepare) — see GH #27 / ISS-049. */
    suspend fun appendSuspend(schema: TableSchema, keyValues: List<Any>, columnName: String, values: Any, consistency: KandraConsistency? = null) {
        executeWithRetrySuspend(statementBuilder.appendToCollectionSuspend(schema, keyValues, columnName, values, consistency), schema.tableName, "append")
    }

    fun remove(schema: TableSchema, keyValues: List<Any>, columnName: String, values: Any, consistency: KandraConsistency? = null) {
        executeWithRetry(statementBuilder.removeFromCollection(schema, keyValues, columnName, values, consistency), schema.tableName, "remove")
    }

    /** Uses [StatementBuilder.removeFromCollectionSuspend] (async prepare) — see GH #27 / ISS-049. */
    suspend fun removeSuspend(schema: TableSchema, keyValues: List<Any>, columnName: String, values: Any, consistency: KandraConsistency? = null) {
        executeWithRetrySuspend(statementBuilder.removeFromCollectionSuspend(schema, keyValues, columnName, values, consistency), schema.tableName, "remove")
    }

    /** Despite the name, this is a map merge/overwrite-by-key (`col = col + ?`) — see [StatementBuilder.appendToCollection]'s doc. */
    fun put(schema: TableSchema, keyValues: List<Any>, columnName: String, entries: Any, consistency: KandraConsistency? = null) {
        executeWithRetry(statementBuilder.appendToCollection(schema, keyValues, columnName, entries, consistency), schema.tableName, "put")
    }

    /** Uses [StatementBuilder.appendToCollectionSuspend] (async prepare) — see GH #27 / ISS-049. */
    suspend fun putSuspend(schema: TableSchema, keyValues: List<Any>, columnName: String, entries: Any, consistency: KandraConsistency? = null) {
        executeWithRetrySuspend(statementBuilder.appendToCollectionSuspend(schema, keyValues, columnName, entries, consistency), schema.tableName, "put")
    }

    fun increment(schema: TableSchema, columnName: String, partitionKeys: Map<String, Any>, by: Long, consistency: KandraConsistency? = null) {
        executeWithRetry(statementBuilder.counterUpdate(schema, columnName, partitionKeys, by, consistency), schema.tableName, "increment")
    }

    /** Uses [StatementBuilder.counterUpdateSuspend] (async prepare) — see GH #27 / ISS-049. */
    suspend fun incrementSuspend(schema: TableSchema, columnName: String, partitionKeys: Map<String, Any>, by: Long, consistency: KandraConsistency? = null) {
        executeWithRetrySuspend(statementBuilder.counterUpdateSuspend(schema, columnName, partitionKeys, by, consistency), schema.tableName, "increment")
    }

    fun decrement(schema: TableSchema, columnName: String, partitionKeys: Map<String, Any>, by: Long, consistency: KandraConsistency? = null) {
        executeWithRetry(statementBuilder.counterUpdate(schema, columnName, partitionKeys, -by, consistency), schema.tableName, "decrement")
    }

    /** Uses [StatementBuilder.counterUpdateSuspend] (async prepare) — see GH #27 / ISS-049. */
    suspend fun decrementSuspend(schema: TableSchema, columnName: String, partitionKeys: Map<String, Any>, by: Long, consistency: KandraConsistency? = null) {
        executeWithRetrySuspend(statementBuilder.counterUpdateSuspend(schema, columnName, partitionKeys, -by, consistency), schema.tableName, "decrement")
    }

    // ── saveAll ──────────────────────────────────────────────────────────────

    fun saveAll(schema: TableSchema, entities: List<Any>, ttlSeconds: Int? = null, useBatch: Boolean = true, consistency: KandraConsistency? = null) {
        if (entities.isEmpty()) return
        if (!useBatch) {
            entities.forEach { save(schema, it, ttlSeconds, consistency = consistency) }
            return
        }
        val stamped = entities.map { injectTimestamps(schema, it, isInsert = true) }
        val eventualInserts = mutableListOf<Any>()
        val allStatements = mutableListOf<BatchableStatement<*>>()
        stamped.forEach { entity ->
            allStatements.add(statementBuilder.insertPrimary(schema, entity, ttlSeconds))
            schema.lookupTables.forEach { lookup ->
                if (lookup.consistency == LookupConsistency.BATCH) allStatements.add(statementBuilder.insertLookup(schema, lookup, entity))
            }
            if (schema.lookupTables.any { it.consistency == LookupConsistency.EVENTUAL }) eventualInserts.add(entity)
        }
        val estimatedSize = allStatements.size * 512
        if (estimatedSize > batchWarnThresholdKb * 1024) {
            logger.warn { "Batch size ~${estimatedSize / 1024}KB exceeds warn threshold (${batchWarnThresholdKb}KB). Consider reducing batch size." }
        }
        if (batchAutoChunk && allStatements.size > batchMaxChunkSize) {
            allStatements.chunked(batchMaxChunkSize).forEach { chunk ->
                val chunkBatch = chunk.fold(newLoggedBatch(schema, consistency)) { acc, stmt -> acc.add(stmt) }
                if (debugConfig.logBatches) logger.debug { "Executing saveAll chunk of ${chunk.size} for ${schema.tableName}" }
                executeWithRetry(chunkBatch)
            }
        } else {
            val batch = allStatements.fold(newLoggedBatch(schema, consistency)) { acc, stmt -> acc.add(stmt) }
            if (debugConfig.logBatches) logger.debug { "Executing saveAll LOGGED BATCH for ${schema.tableName} (${entities.size} entities)" }
            executeWithRetry(batch)
        }
        if (eventualInserts.isNotEmpty()) {
            val eventualLookups = schema.lookupTables.filter { it.consistency == LookupConsistency.EVENTUAL }
            eventualInserts.forEach { entity -> fireEventual(schema, eventualLookups, entity) }
        }
    }

    // ── Statement collection (for KandraBatchScope) ──────────────────────────

    internal fun collectSave(schema: TableSchema, entity: Any, ttlSeconds: Int? = null): List<BatchableStatement<*>> {
        if (schema.isCounterTable) throw KandraQueryException("Counter tables cannot be saved in a batch scope.")
        val stamped = injectTimestamps(schema, entity, isInsert = true)
        return buildList {
            add(statementBuilder.insertPrimary(schema, stamped, ttlSeconds))
            schema.lookupTables.filter { it.consistency == LookupConsistency.BATCH }
                .forEach { add(statementBuilder.insertLookup(schema, it, stamped)) }
        }
    }

    internal fun collectDelete(schema: TableSchema, entity: Any): List<BatchableStatement<*>> {
        val props = schema.reflection.propertiesByName
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

    /**
     * Suspend counterpart of [collectSave] (ISS-059 / GH #60) — uses [StatementBuilder.insertPrimarySuspend]/
     * [StatementBuilder.insertLookupSuspend] (async prepare) instead of their blocking equivalents, so
     * collecting statements for [KandraRuntime.batch]'s suspend scope never blocks the calling coroutine's
     * dispatcher thread on a prepared-statement cache miss. Used only by the suspend `saveInBatch` overload
     * in [KandraBatchScope] — the blocking `batchBlocking { }` entry point keeps calling [collectSave].
     */
    internal suspend fun collectSaveSuspend(schema: TableSchema, entity: Any, ttlSeconds: Int? = null): List<BatchableStatement<*>> {
        if (schema.isCounterTable) throw KandraQueryException("Counter tables cannot be saved in a batch scope.")
        val stamped = injectTimestamps(schema, entity, isInsert = true)
        return buildList {
            add(statementBuilder.insertPrimarySuspend(schema, stamped, ttlSeconds))
            schema.lookupTables.filter { it.consistency == LookupConsistency.BATCH }
                .forEach { add(statementBuilder.insertLookupSuspend(schema, it, stamped)) }
        }
    }

    /**
     * Suspend counterpart of [collectDelete] (ISS-059 / GH #60) — uses [StatementBuilder.deleteByIdSuspend]/
     * [StatementBuilder.deleteLookupSuspend] (async prepare) instead of their blocking equivalents. See
     * [collectSaveSuspend]'s doc for why this exists and who calls it.
     */
    internal suspend fun collectDeleteSuspend(schema: TableSchema, entity: Any): List<BatchableStatement<*>> {
        val props = schema.reflection.propertiesByName
        val keyValues = (schema.partitionKeys + schema.clusteringKeys).map { key ->
            props[key.propertyName]?.call(entity) ?: throw KandraQueryException("Key '${key.propertyName}' is null on delete")
        }
        return buildList {
            add(statementBuilder.deleteByIdSuspend(schema, *keyValues.toTypedArray()))
            schema.lookupTables.forEach { lookup ->
                val indexValue = props[lookup.indexColumn.propertyName]?.call(entity) ?: return@forEach
                add(statementBuilder.deleteLookupSuspend(lookup, indexValue))
            }
        }
    }

    /**
     * Executes a [KandraBatchScope]-collected list of statements as a single `LOGGED BATCH`,
     * through [executeWithRetry] — used by [KandraBatchScope.execute] (invoked from
     * [KandraRuntime.batchBlocking]) so a caller-controlled batch gets the same shutdown gate,
     * retry-on-transient-error, and [inFlightCount] tracking as every other write, instead of
     * calling `session.execute` directly.
     */
    internal fun executeBatchScope(schema: TableSchema, statements: List<BatchableStatement<*>>, consistency: KandraConsistency? = null) {
        if (statements.isEmpty()) return
        val batch = statements.fold(newLoggedBatch(schema, consistency)) { acc, s -> acc.add(s) }
        executeWithRetry(batch)
    }

    /**
     * Suspend counterpart of [executeBatchScope] — used by [KandraBatchScope.executeSuspend]
     * (invoked from [KandraRuntime.batch]) so the final commit uses `session.executeSuspend`
     * instead of blocking the calling coroutine's thread, while still getting the same
     * shutdown gate / retry / [inFlightCount] tracking via [executeWithRetrySuspend].
     */
    internal suspend fun executeBatchScopeSuspend(schema: TableSchema, statements: List<BatchableStatement<*>>, consistency: KandraConsistency? = null) {
        if (statements.isEmpty()) return
        val batch = statements.fold(newLoggedBatch(schema, consistency)) { acc, s -> acc.add(s) }
        executeWithRetrySuspend(batch)
    }

    // ── Suspend variants ─────────────────────────────────────────────────────

    suspend fun saveSuspend(schema: TableSchema, entity: Any, ttlSeconds: Int? = null, timestampMicros: Long? = null, consistency: KandraConsistency? = null) {
        if (schema.isCounterTable) throw KandraQueryException("Counter tables cannot use save(). Use increment()/decrement() instead.")
        validateEntity(entity)
        val stamped = injectTimestamps(schema, entity, isInsert = true)
        val stampedWithVersion = injectInitialVersion(schema, stamped)
        val (batchLookups, eventualLookups) = schema.lookupTables.partition { it.consistency == LookupConsistency.BATCH }
        // Async prepare (statementBuilder.*Suspend) avoids blocking the dispatcher on a cache miss.
        val primaryStmt = statementBuilder.insertPrimarySuspend(schema, stampedWithVersion, ttlSeconds, timestampMicros = timestampMicros, consistency = consistency)
        val batch = batchLookups.fold(
            newLoggedBatch(schema, consistency).add(primaryStmt)
        ) { acc, l -> acc.add(statementBuilder.insertLookupSuspend(schema, l, stampedWithVersion)) }
        if (debugConfig.logBatches) logger.debug { "Executing LOGGED BATCH with ${batchLookups.size + 1} statements for ${schema.tableName}" }
        executeWithRetrySuspend(batch)
        fireEventualSuspend(schema, eventualLookups, stampedWithVersion)
    }

    suspend fun saveIfNotExistsSuspend(schema: TableSchema, entity: Any, serialConsistency: KandraConsistency = KandraConsistency.LOCAL_SERIAL): Boolean {
        if (!serialConsistency.isSerial) throw KandraQueryException("saveIfNotExists serialConsistency must be LOCAL_SERIAL or SERIAL, got: $serialConsistency")
        if (schema.isCounterTable) throw KandraQueryException("Counter tables cannot use saveIfNotExists().")
        val stamped = injectTimestamps(schema, entity, isInsert = true)
        val primaryStmt = statementBuilder.insertPrimarySuspend(schema, stamped, ifNotExists = true)
            .setSerialConsistencyLevel(DefaultConsistencyLevel.valueOf(serialConsistency.name))
        // Not executeWithRetrySuspend: a blind retry of this LWT risks observing our own prior
        // attempt's success as a false "already exists" negative. See executeOnce's doc.
        val rs = executeOnceSuspend(primaryStmt, schema.tableName, "saveIfNotExists")
        val applied = rs.currentPage().firstOrNull()?.getBoolean("[applied]") ?: false
        if (!applied) return false
        val (batchLookups, eventualLookups) = schema.lookupTables.partition { it.consistency == LookupConsistency.BATCH }
        if (batchLookups.isNotEmpty()) {
            val lookupBatch = batchLookups.fold(newLoggedBatch(schema)) { acc, l -> acc.add(statementBuilder.insertLookupSuspend(schema, l, stamped)) }
            executeWithRetrySuspend(lookupBatch)
        }
        fireEventualSuspend(schema, eventualLookups, stamped)
        return true
    }

    suspend fun saveWithNullsSuspend(schema: TableSchema, entity: Any, ttlSeconds: Int? = null, consistency: KandraConsistency? = null) {
        if (schema.isCounterTable) throw KandraQueryException("Counter tables cannot use saveWithNulls().")
        val stamped = injectTimestamps(schema, entity, isInsert = true)
        val (batchLookups, eventualLookups) = schema.lookupTables.partition { it.consistency == LookupConsistency.BATCH }
        val primaryStmt = statementBuilder.insertPrimaryWithNullsSuspend(schema, stamped, ttlSeconds, consistency = consistency)
        val batch = batchLookups.fold(
            newLoggedBatch(schema, consistency).add(primaryStmt)
        ) { acc, l -> acc.add(statementBuilder.insertLookupSuspend(schema, l, stamped)) }
        executeWithRetrySuspend(batch)
        fireEventualSuspend(schema, eventualLookups, stamped)
    }

    suspend fun updateSuspend(schema: TableSchema, old: Any, new: Any, consistency: KandraConsistency? = null, ttlSeconds: Int? = null) {
        validateEntity(new)
        val versionCol = schema.versionColumn
        val stamped = injectTimestamps(schema, new, isInsert = false)

        if (versionCol != null) {
            val oldProps = schema.reflection.propertiesByName
            val oldVersion = oldProps[versionCol.propertyName]?.call(old)
                ?: throw KandraQueryException("@Version field '${versionCol.propertyName}' is null")
            val newVersion = incrementVersion(versionCol, oldVersion)
            val stampedWithVersion = injectVersion(schema, stamped, versionCol.propertyName, newVersion)
            // Async prepare avoids blocking the dispatcher on the first call for this CQL string
            val stmt = buildVersionedUpdateStatementSuspend(schema, versionCol, stampedWithVersion, oldVersion, consistency, ttlSeconds)
            // Not executeWithRetrySuspend: a blind retry of this LWT would risk observing our own
            // prior attempt's success as a false optimistic-lock conflict. See executeOnceSuspend's doc.
            val rs = executeOnceSuspend(stmt, schema.tableName, "update")
            val applied = rs.currentPage().firstOrNull()?.getBoolean("[applied]") ?: false
            if (!applied) throwOptimisticLockException(schema, old, oldVersion)
            updateLookupsSuspend(schema, old, stampedWithVersion, consistency)
            return
        }

        val (batchStmts, eventualStmts) = buildUpdateStatementsSuspend(schema, old, stamped)
        val primaryStmt = statementBuilder.insertPrimarySuspend(schema, stamped, consistency = consistency)
        val batch = batchStmts.fold(
            newLoggedBatch(schema, consistency).add(primaryStmt)
        ) { acc, stmt -> acc.add(stmt) }
        executeWithRetrySuspend(batch)
        fireEventualStatementsSuspend(eventualStmts, new, "(update)", schema.tableName)
    }

    suspend fun updateForceSuspend(schema: TableSchema, entity: Any, consistency: KandraConsistency? = null) {
        val stamped = injectTimestamps(schema, entity, isInsert = false)
        val (batchStmts, eventualStmts) = buildUpdateStatementsSuspend(schema, entity, stamped)
        val primaryStmt = statementBuilder.insertPrimarySuspend(schema, stamped, consistency = consistency)
        val batch = batchStmts.fold(
            newLoggedBatch(schema, consistency).add(primaryStmt)
        ) { acc, stmt -> acc.add(stmt) }
        executeWithRetrySuspend(batch)
        fireEventualStatementsSuspend(eventualStmts, entity, "(updateForce)", schema.tableName)
    }

    suspend fun deleteSuspend(schema: TableSchema, entity: Any) {
        val props = schema.reflection.propertiesByName
        val keyValues = (schema.partitionKeys + schema.clusteringKeys).map { key ->
            props[key.propertyName]?.call(entity) ?: throw KandraQueryException("Key '${key.propertyName}' is null on delete")
        }
        if (schema.isSoftDelete && schema.softDeleteTtlSeconds != null) {
            softDeleteSuspend(schema, entity, props, keyValues)
            return
        }
        val primaryStmt = statementBuilder.deleteByIdSuspend(schema, *keyValues.toTypedArray())
        val batch = schema.lookupTables.fold(
            newLoggedBatch(schema).add(primaryStmt)
        ) { acc, lookup ->
            val indexValue = props[lookup.indexColumn.propertyName]?.call(entity) ?: return@fold acc
            acc.add(statementBuilder.deleteLookupSuspend(lookup, indexValue))
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

    suspend fun saveAllSuspend(schema: TableSchema, entities: List<Any>, ttlSeconds: Int? = null, useBatch: Boolean = true, consistency: KandraConsistency? = null) {
        if (entities.isEmpty()) return
        if (!useBatch) {
            entities.forEach { saveSuspend(schema, it, ttlSeconds, consistency = consistency) }
            return
        }
        val stamped = entities.map { injectTimestamps(schema, it, isInsert = true) }
        val eventualInserts = mutableListOf<Any>()
        val allStatements = mutableListOf<BatchableStatement<*>>()
        stamped.forEach { entity ->
            allStatements.add(statementBuilder.insertPrimarySuspend(schema, entity, ttlSeconds))
            schema.lookupTables.forEach { lookup ->
                if (lookup.consistency == LookupConsistency.BATCH) allStatements.add(statementBuilder.insertLookupSuspend(schema, lookup, entity))
            }
            if (schema.lookupTables.any { it.consistency == LookupConsistency.EVENTUAL }) eventualInserts.add(entity)
        }
        val estimatedSize = allStatements.size * 512
        if (estimatedSize > batchWarnThresholdKb * 1024) {
            logger.warn { "Batch size ~${estimatedSize / 1024}KB exceeds warn threshold (${batchWarnThresholdKb}KB). Consider reducing batch size." }
        }
        if (batchAutoChunk && allStatements.size > batchMaxChunkSize) {
            allStatements.chunked(batchMaxChunkSize).forEach { chunk ->
                val chunkBatch = chunk.fold(newLoggedBatch(schema, consistency)) { acc, stmt -> acc.add(stmt) }
                if (debugConfig.logBatches) logger.debug { "Executing saveAllSuspend chunk of ${chunk.size} for ${schema.tableName}" }
                executeWithRetrySuspend(chunkBatch)
            }
        } else {
            val batch = allStatements.fold(newLoggedBatch(schema, consistency)) { acc, stmt -> acc.add(stmt) }
            if (debugConfig.logBatches) logger.debug { "Executing saveAllSuspend LOGGED BATCH for ${schema.tableName} (${entities.size} entities)" }
            executeWithRetrySuspend(batch)
        }
        if (eventualInserts.isNotEmpty()) {
            val eventualLookups = schema.lookupTables.filter { it.consistency == LookupConsistency.EVENTUAL }
            eventualInserts.forEach { entity -> fireEventualSuspend(schema, eventualLookups, entity) }
        }
    }

    // ── Soft delete helpers ───────────────────────────────────────────────────

    private fun softDeleteBlocking(
        schema: TableSchema,
        entity: Any,
        props: Map<String, KProperty1<*, *>>,
        keyValues: List<Any>
    ) {
        val ttl = schema.softDeleteTtlSeconds!!
        val marker = schema.softDeleteMarkerColumn
        val nonKeyCols = schema.columns.filter { !it.isTransient && !it.isCounter && it != marker }
        val whereParts = (schema.partitionKeys + schema.clusteringKeys).joinToString(" AND ") { "${it.cqlName} = ?" }
        // Soft-delete builds its CQL directly (not via StatementBuilder), so it needs its own
        // consistency resolution — previously neither statement here called .setConsistencyLevel(...)
        // at all, silently downgrading every soft-delete to the driver's LOCAL_ONE default regardless
        // of configuration (see ISS-061 / GH #62, the same class of gap ISS-053 fixed for batches).
        val resolvedConsistency = DefaultConsistencyLevel.valueOf(statementBuilder.resolveWriteConsistency(schema, null).name)
        if (nonKeyCols.isNotEmpty()) {
            val setClauses = nonKeyCols.joinToString(", ") { "${it.cqlName} = ?" }
            val cql = "UPDATE ${schema.tableName} USING TTL $ttl SET $setClauses WHERE $whereParts"
            val prepared = session.prepare(cql)
            val values = mutableListOf<Any?>()
            nonKeyCols.forEach { col -> values.add(props[col.propertyName]?.call(entity)) }
            keyValues.forEach { values.add(it) }
            executeWithRetry(prepared.bind(*values.toTypedArray()).setConsistencyLevel(resolvedConsistency))
        }
        // Marker column is written without TTL — it must outlive the other columns so
        // findActive() can still tell this row apart from a live one after they expire.
        if (marker != null) {
            val cql = "UPDATE ${schema.tableName} SET ${marker.cqlName} = ? WHERE $whereParts"
            val prepared = session.prepare(cql)
            executeWithRetry(prepared.bind(true, *keyValues.toTypedArray()).setConsistencyLevel(resolvedConsistency))
        }
        // Lookup rows are deliberately left alone (see ISS-030) -- a soft-deleted row still "exists"
        // until its TTL expires, so it must remain resolvable via its @LookupIndex too, exactly like
        // a direct findById/findActive would still see it. Hard delete (the branch this function is
        // NOT called from) is the only path that should ever remove lookup rows.
    }

    private suspend fun softDeleteSuspend(
        schema: TableSchema,
        entity: Any,
        props: Map<String, KProperty1<*, *>>,
        keyValues: List<Any>
    ) {
        val ttl = schema.softDeleteTtlSeconds!!
        val marker = schema.softDeleteMarkerColumn
        val nonKeyCols = schema.columns.filter { !it.isTransient && !it.isCounter && it != marker }
        val whereParts = (schema.partitionKeys + schema.clusteringKeys).joinToString(" AND ") { "${it.cqlName} = ?" }
        // See softDeleteBlocking's identical comment — ISS-061 / GH #62.
        val resolvedConsistency = DefaultConsistencyLevel.valueOf(statementBuilder.resolveWriteConsistency(schema, null).name)
        if (nonKeyCols.isNotEmpty()) {
            val setClauses = nonKeyCols.joinToString(", ") { "${it.cqlName} = ?" }
            val cql = "UPDATE ${schema.tableName} USING TTL $ttl SET $setClauses WHERE $whereParts"
            // prepareSuspend avoids blocking the coroutine dispatcher; driver caches the result
            val prepared = session.prepareSuspend(cql)
            val values = mutableListOf<Any?>()
            nonKeyCols.forEach { col -> values.add(props[col.propertyName]?.call(entity)) }
            keyValues.forEach { values.add(it) }
            executeWithRetrySuspend(prepared.bind(*values.toTypedArray()).setConsistencyLevel(resolvedConsistency))
        }
        if (marker != null) {
            val cql = "UPDATE ${schema.tableName} SET ${marker.cqlName} = ? WHERE $whereParts"
            val prepared = session.prepareSuspend(cql)
            executeWithRetrySuspend(prepared.bind(true, *keyValues.toTypedArray()).setConsistencyLevel(resolvedConsistency))
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
        oldVersion: Any,
        consistency: KandraConsistency? = null,
        ttlSeconds: Int? = null
    ): BoundStatement {
        val nonKeyCols = buildList {
            addAll(schema.columns)
            addAll(schema.lookupTables.map { it.indexColumn })
        }.distinctBy { it.cqlName }.filter { !it.isTransient }

        val setClauses = nonKeyCols.joinToString(", ") { "${it.cqlName} = ?" }
        val whereParts = (schema.partitionKeys + schema.clusteringKeys).joinToString(" AND ") { "${it.cqlName} = ?" }
        // TTL is a per-cell property: an UPDATE with no USING TTL writes its touched cells with no
        // expiry, silently clearing the row's TTL on the first update after save() (see ISS-058 / GH #59).
        val effectiveTtl = ttlSeconds ?: schema.defaultTtl
        val usingClause = if (effectiveTtl != null) " USING TTL $effectiveTtl" else ""
        val cql = "UPDATE ${schema.tableName}$usingClause SET $setClauses WHERE $whereParts IF ${versionCol.cqlName} = ?"
        val prepared = session.prepareSuspend(cql)   // truly async prepare

        val entityProps = schema.reflection.propertiesByName
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

        val resolved = statementBuilder.resolveWriteConsistency(schema, consistency)
        return prepared.bind(*values.toTypedArray())
            .setConsistencyLevel(DefaultConsistencyLevel.valueOf(resolved.name))
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
        oldVersion: Any,
        consistency: KandraConsistency? = null,
        ttlSeconds: Int? = null
    ): BoundStatement {
        val nonKeyCols = buildList {
            addAll(schema.columns)
            addAll(schema.lookupTables.map { it.indexColumn })
        }.distinctBy { it.cqlName }.filter { !it.isTransient }

        val setClauses = nonKeyCols.joinToString(", ") { "${it.cqlName} = ?" }
        val whereParts = (schema.partitionKeys + schema.clusteringKeys).joinToString(" AND ") { "${it.cqlName} = ?" }
        // TTL is a per-cell property: an UPDATE with no USING TTL writes its touched cells with no
        // expiry, silently clearing the row's TTL on the first update after save() (see ISS-058 / GH #59).
        val effectiveTtl = ttlSeconds ?: schema.defaultTtl
        val usingClause = if (effectiveTtl != null) " USING TTL $effectiveTtl" else ""
        val cql = "UPDATE ${schema.tableName}$usingClause SET $setClauses WHERE $whereParts IF ${versionCol.cqlName} = ?"
        val prepared = session.prepare(cql)

        val entityProps = schema.reflection.propertiesByName
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

        val resolved = statementBuilder.resolveWriteConsistency(schema, consistency)
        return prepared.bind(*values.toTypedArray())
            .setConsistencyLevel(DefaultConsistencyLevel.valueOf(resolved.name))
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
            schema.reflection.propertiesByName[pk.propertyName]?.call(old)
        } ?: "unknown"
        throw KandraOptimisticLockException(
            "Optimistic lock conflict on ${schema.entityClass.simpleName}: version $oldVersion was modified concurrently",
            schema.entityClass,
            pkValue ?: "unknown"
        )
    }

    // ── Lookup update helpers ─────────────────────────────────────────────────

    private fun updateLookups(schema: TableSchema, old: Any, new: Any, consistency: KandraConsistency? = null) {
        val (batchStmts, eventualStmts) = buildUpdateStatements(schema, old, new)
        if (batchStmts.isNotEmpty()) {
            // GH #96: route through newLoggedBatch (resolveWriteConsistency + Strict Mode RF check),
            // same as every other write batch in this file -- this used to build a bare unconfigured
            // batch, bypassing consistency resolution entirely for the lookup-table half of every
            // @Version-checked update().
            val batch = batchStmts.fold(newLoggedBatch(schema, consistency)) { acc, s -> acc.add(s) }
            executeWithRetry(batch)
        }
        fireEventualStatements(eventualStmts, new, "(version update)", schema.tableName)
    }

    private suspend fun updateLookupsSuspend(schema: TableSchema, old: Any, new: Any, consistency: KandraConsistency? = null) {
        val (batchStmts, eventualStmts) = buildUpdateStatementsSuspend(schema, old, new)
        if (batchStmts.isNotEmpty()) {
            val batch = batchStmts.fold(newLoggedBatch(schema, consistency)) { acc, s -> acc.add(s) }
            executeWithRetrySuspend(batch)
        }
        fireEventualStatementsSuspend(eventualStmts, new, "(version update)", schema.tableName)
    }

    // ── Eventual write helpers ────────────────────────────────────────────────

    private fun fireEventual(schema: TableSchema, eventualLookups: List<LookupTableSchema>, entity: Any) {
        if (eventualLookups.isEmpty()) return
        scope.launch {
            eventualLookups.forEach { lookup ->
                runCatching { executeWithRetry(statementBuilder.insertLookup(schema, lookup, entity), lookup.tableName, "eventualLookupInsert") }
                    .onFailure { err ->
                        logger.error(err) { "EVENTUAL lookup insert failed for ${lookup.tableName}" }
                        @OptIn(ExperimentalKandraApi::class)
                        eventListener?.onEventualWriteFailed(lookup.tableName, entity, err)
                    }
            }
        }
    }

    private fun fireEventualSuspend(schema: TableSchema, eventualLookups: List<LookupTableSchema>, entity: Any) {
        if (eventualLookups.isEmpty()) return
        scope.launch {
            eventualLookups.forEach { lookup ->
                runCatching { executeWithRetrySuspend(statementBuilder.insertLookupSuspend(schema, lookup, entity), lookup.tableName, "eventualLookupInsert") }
                    .onFailure { err ->
                        logger.error(err) { "EVENTUAL lookup insert failed for ${lookup.tableName}" }
                        @OptIn(ExperimentalKandraApi::class)
                        eventListener?.onEventualWriteFailed(lookup.tableName, entity, err)
                    }
            }
        }
    }

    private fun fireEventualStatements(stmts: List<BatchableStatement<*>>, entity: Any, context: String, tableName: String = "unknown") {
        if (stmts.isEmpty()) return
        scope.launch {
            stmts.forEach { stmt ->
                runCatching { executeWithRetry(stmt, tableName, context) }
                    .onFailure { err ->
                        logger.error(err) { "EVENTUAL lookup $context failed" }
                        @OptIn(ExperimentalKandraApi::class)
                        eventListener?.onEventualWriteFailed(context, entity, err)
                    }
            }
        }
    }

    /** Suspend counterpart of [fireEventualStatements] — see its doc. Used by [updateSuspend],
     *  [updateLookupsSuspend], and [updateForceSuspend] for their EVENTUAL-consistency lookup writes,
     *  so those inherit retry-on-transient-error, [inFlightCount] tracking, and the shutdown gate the
     *  same way the blocking counterparts already do. */
    private suspend fun fireEventualStatementsSuspend(stmts: List<BatchableStatement<*>>, entity: Any, context: String, tableName: String = "unknown") {
        if (stmts.isEmpty()) return
        scope.launch {
            stmts.forEach { stmt ->
                runCatching { executeWithRetrySuspend(stmt, tableName, context) }
                    .onFailure { err ->
                        logger.error(err) { "EVENTUAL lookup $context failed" }
                        @OptIn(ExperimentalKandraApi::class)
                        eventListener?.onEventualWriteFailed(context, entity, err)
                    }
            }
        }
    }

    private fun buildUpdateStatements(schema: TableSchema, old: Any, new: Any): Pair<List<BatchableStatement<*>>, List<BatchableStatement<*>>> {
        val props = schema.reflection.propertiesByName
        val batchStmts = mutableListOf<BatchableStatement<*>>()
        val eventualStmts = mutableListOf<BatchableStatement<*>>()
        schema.lookupTables.forEach { lookup ->
            val oldVal = props[lookup.indexColumn.propertyName]?.call(old)
            val newVal = props[lookup.indexColumn.propertyName]?.call(new)
            val target = if (lookup.consistency == LookupConsistency.BATCH) batchStmts else eventualStmts
            if (oldVal != newVal && oldVal != null) target.add(statementBuilder.deleteLookup(lookup, oldVal))
            if (newVal != null) target.add(statementBuilder.insertLookup(schema, lookup, new))
        }
        return batchStmts to eventualStmts
    }

    /** Suspend counterpart of [buildUpdateStatements] (GH #27 / ISS-049) — uses
     *  [StatementBuilder.deleteLookupSuspend]/[StatementBuilder.insertLookupSuspend] (async prepare)
     *  instead of their blocking equivalents, for [updateSuspend]/[updateForceSuspend]/[updateLookupsSuspend]. */
    private suspend fun buildUpdateStatementsSuspend(schema: TableSchema, old: Any, new: Any): Pair<List<BatchableStatement<*>>, List<BatchableStatement<*>>> {
        val props = schema.reflection.propertiesByName
        val batchStmts = mutableListOf<BatchableStatement<*>>()
        val eventualStmts = mutableListOf<BatchableStatement<*>>()
        schema.lookupTables.forEach { lookup ->
            val oldVal = props[lookup.indexColumn.propertyName]?.call(old)
            val newVal = props[lookup.indexColumn.propertyName]?.call(new)
            val target = if (lookup.consistency == LookupConsistency.BATCH) batchStmts else eventualStmts
            if (oldVal != newVal && oldVal != null) target.add(statementBuilder.deleteLookupSuspend(lookup, oldVal))
            if (newVal != null) target.add(statementBuilder.insertLookupSuspend(schema, lookup, new))
        }
        return batchStmts to eventualStmts
    }

    // ── Timestamp / version injection ─────────────────────────────────────────

    internal fun injectTimestamps(schema: TableSchema, entity: Any, isInsert: Boolean): Any {
        val createdAt = schema.createdAtColumn
        val updatedAt = schema.updatedAtColumn
        val generatedUuidColumns = if (isInsert) schema.generatedUuidColumns else emptyList()
        if (createdAt == null && updatedAt == null && generatedUuidColumns.isEmpty()) return entity
        val copyFn = schema.reflection.copyFunction ?: return entity
        val copyParams = schema.reflection.copyParameters
        val now = Instant.now()
        val callArgs = mutableMapOf<KParameter, Any?>()
        callArgs[copyParams[0]] = entity
        copyParams.drop(1).forEach { param ->
            val generatedCol = generatedUuidColumns.find { it.propertyName == param.name }
            when {
                param.name == createdAt?.propertyName -> if (isInsert) callArgs[param] = now
                param.name == updatedAt?.propertyName -> callArgs[param] = now
                generatedCol != null -> callArgs[param] = when (generatedCol.generatedUuidStrategy) {
                    UuidStrategy.TIME_ORDERED -> KandraUuid.timeOrdered()
                    UuidStrategy.RANDOM -> KandraUuid.random()
                    null -> null
                }
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
        return injectVersion(schema, entity, versionCol.propertyName, initVersion)
    }

    private fun injectVersion(schema: TableSchema, entity: Any, propertyName: String, version: Any): Any {
        val copyFn = schema.reflection.copyFunction ?: return entity
        val copyParams = schema.reflection.copyParameters
        val callArgs = mutableMapOf<KParameter, Any?>()
        callArgs[copyParams[0]] = entity
        copyParams.drop(1).forEach { param ->
            if (param.name == propertyName) callArgs[param] = version
        }
        return copyFn.callBy(callArgs) ?: entity
    }
}
