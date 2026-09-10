package io.kandra.runtime.repository

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.Row
import io.kandra.core.KandraConsistency
import io.kandra.core.exception.KandraQueryException
import io.kandra.core.exception.KandraSchemaException
import io.kandra.core.schema.TableSchema
import io.kandra.runtime.BatchEngine
import io.kandra.runtime.QueryExecutor
import io.kandra.runtime.cache.KandraCache
import io.kandra.runtime.dsl.KandraPage
import io.kandra.runtime.dsl.KandraRawQuery
import io.kandra.runtime.dsl.QueryContext
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

/**
 * Coroutine-friendly repository for performing CRUD operations on a ScyllaDB table.
 *
 * All write operations delegate to [BatchEngine] (which wraps blocking driver calls);
 * wrap in `withContext(Dispatchers.IO)` when needed in production.
 *
 * @param T the entity type annotated with `@ScyllaTable`
 */
class KandraSuspendRepository<T : Any>(
    private val session: CqlSession,
    internal val schema: TableSchema,
    private val entityClass: KClass<T>,
    private val batchEngine: BatchEngine
) {
    // ISS-048: read the plugin-configured StatementBuilder/codec/debugConfig off batchEngine
    // rather than building all-defaults copies — see BatchEngine's fields for why this is safe.
    private val statementBuilder = batchEngine.statementBuilder
    private val executor = QueryExecutor(session, schema, statementBuilder, batchEngine.codec, batchEngine.debugConfig)
    private val cache = KandraCache<Any, T>(schema.cacheConfig)

    private fun checkNotShuttingDown() {
        if (batchEngine.isShuttingDown.get()) throw KandraQueryException("Kandra is shutting down — new queries are rejected")
    }

    /**
     * Cache key for [entity], covering the **full** primary key (partition + clustering columns),
     * in the same shape [findById]'s cache key uses (a bare single value when the key is one column
     * total, otherwise an ordered [List]). Must stay in lockstep with `findById`'s key derivation —
     * see ISS-028: this used to be partition-key-only (`partitionKeyOf`), which silently never matched
     * `findById`'s real cache key for any clustering-keyed entity, so invalidation always missed.
     */
    private fun cacheKeyOf(entity: T): Any {
        val keys = keyValuesOf(entity)
        return if (keys.size == 1) keys[0] else keys
    }

    suspend fun save(entity: T, ttlSeconds: Int? = null, timestampMicros: Long? = null, consistency: KandraConsistency? = null) {
        saveAndGet(entity, ttlSeconds, timestampMicros, consistency)
    }

    /**
     * Same write as [save], but returns the entity actually persisted — including any
     * `@GeneratedUuid`/`@CreatedAt`/`@UpdatedAt`/`@Version` values generated for the write, which
     * [save] computes internally and then discards. Use this whenever the caller needs those
     * generated values (e.g. to build an HTTP response) instead of the placeholder ones on the
     * object it constructed — see GH #132 / ISS-096.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun saveAndGet(entity: T, ttlSeconds: Int? = null, timestampMicros: Long? = null, consistency: KandraConsistency? = null): T {
        val saved = batchEngine.saveAndGetSuspend(schema, entity, ttlSeconds, timestampMicros, consistency) as T
        cache.invalidate(cacheKeyOf(saved))
        return saved
    }

    suspend fun saveIfNotExists(entity: T, serialConsistency: KandraConsistency = KandraConsistency.LOCAL_SERIAL): Boolean =
        batchEngine.saveIfNotExistsSuspend(schema, entity, serialConsistency).also { if (it) cache.invalidate(cacheKeyOf(entity)) }

    suspend fun saveAll(entities: List<T>, useBatch: Boolean = true, consistency: KandraConsistency? = null) {
        batchEngine.saveAllSuspend(schema, entities, useBatch = useBatch, consistency = consistency)
        entities.forEach { cache.invalidate(cacheKeyOf(it)) }
    }

    /**
     * Optimistic-locked update: `UPDATE ... IF <version> = ?` when the entity has an `@Version`
     * column (otherwise a blind full-row overwrite — see the `kandra-runtime` skill doc).
     *
     * [serialConsistency] controls the LWT's Paxos scope for the `@Version` check — it does
     * **nothing** when the entity has no `@Version` column. Defaults to `LOCAL_SERIAL` (Paxos
     * consensus within the local DC only), matching every existing caller's current behavior.
     * **`LOCAL_SERIAL` does not protect against a concurrent update landing on a different DC**
     * during a network partition: each DC can independently "win" its own local Paxos round, and
     * when the partition heals, ordinary last-write-wins silently discards one of the two
     * "successful" updates with no error to either caller (GH #134). Pass `SERIAL` here — cross-DC
     * Paxos consensus — when the entity's optimistic lock must hold across every DC, not just the
     * one the caller happened to write through.
     */
    suspend fun update(
        old: T,
        new: T,
        consistency: KandraConsistency? = null,
        ttlSeconds: Int? = null,
        serialConsistency: KandraConsistency = KandraConsistency.LOCAL_SERIAL
    ) {
        updateAndGet(old, new, consistency, ttlSeconds, serialConsistency)
    }

    /**
     * Same write as [update], but returns the entity actually persisted — including the
     * post-update `@Version` (and `@UpdatedAt`) values [update] computes internally and then
     * discards. Use this whenever the caller needs those generated values, e.g. to make a
     * subsequent optimistic-locked `update()`/`updateAndGet()` call against the same row without
     * risking a spurious [io.kandra.core.exception.KandraOptimisticLockException] from reusing the
     * stale pre-update version it already had — see GH #136.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun updateAndGet(
        old: T,
        new: T,
        consistency: KandraConsistency? = null,
        ttlSeconds: Int? = null,
        serialConsistency: KandraConsistency = KandraConsistency.LOCAL_SERIAL
    ): T {
        val updated = batchEngine.updateAndGetSuspend(schema, old, new, consistency = consistency, ttlSeconds = ttlSeconds, serialConsistency = serialConsistency) as T
        cache.invalidate(cacheKeyOf(updated))
        return updated
    }

    suspend fun updateForce(entity: T, consistency: KandraConsistency? = null) {
        batchEngine.updateForceSuspend(schema, entity, consistency = consistency)
        cache.invalidate(cacheKeyOf(entity))
    }

    suspend fun saveWithNulls(entity: T, ttlSeconds: Int? = null, consistency: KandraConsistency? = null) {
        batchEngine.saveWithNullsSuspend(schema, entity, ttlSeconds, consistency = consistency)
        cache.invalidate(cacheKeyOf(entity))
    }

    suspend fun delete(entity: T) {
        batchEngine.deleteSuspend(schema, entity)
        cache.invalidate(cacheKeyOf(entity))
    }

    suspend fun deleteAll(entities: List<T>) {
        batchEngine.deleteAllSuspend(schema, entities)
        entities.forEach { cache.invalidate(cacheKeyOf(it)) }
    }

    suspend fun deleteById(vararg keyValues: Any) {
        val entity = executor.findByIdSuspend(entityClass, *keyValues)
        if (entity != null) {
            batchEngine.deleteSuspend(schema, entity)
            cache.invalidate(if (keyValues.size == 1) keyValues[0] else keyValues.toList())
        } else {
            batchEngine.deleteByIdSuspend(schema, *keyValues)
        }
    }

    suspend fun deleteBy(block: QueryContext.() -> Unit) {
        val entity = executor.findSuspend(entityClass, block = block) ?: return
        batchEngine.deleteSuspend(schema, entity)
    }

    suspend fun findById(vararg idValues: Any, consistency: KandraConsistency? = null): T? {
        checkNotShuttingDown()
        // A caller-supplied consistency override (e.g. LOCAL_QUORUM for read-your-writes) must
        // reach Scylla — a cache hit would silently serve a value that never honored it. Bypass
        // the cache entirely rather than caching this stronger/weaker-than-usual read (GH #95).
        if (consistency != null) {
            return executor.findByIdSuspend(entityClass, *idValues, consistency = consistency)
        }
        val cacheKey: Any = if (idValues.size == 1) idValues[0] else idValues.toList()
        cache.getIfPresent(cacheKey)?.let { return it }
        // Captured right before the DB read starts -- see KandraCache.put's ISS-087 doc for why:
        // a concurrent write's invalidate() after this point must prevent this read's result from
        // being cached, since it may already be stale by the time the read completes.
        val readStamp = cache.readStamp()
        return executor.findByIdSuspend(entityClass, *idValues, consistency = consistency)
            ?.also { cache.put(cacheKey, it, readStamp) }
    }

    suspend fun find(consistency: KandraConsistency? = null, block: QueryContext.() -> Unit): T? {
        checkNotShuttingDown()
        return executor.findSuspend(entityClass, consistency, block)
    }

    suspend fun findAll(limit: Int? = null, consistency: KandraConsistency? = null, block: QueryContext.() -> Unit): List<T> {
        checkNotShuttingDown()
        val fullBlock: QueryContext.() -> Unit = {
            block()
            if (limit != null) limit(limit)
        }
        return executor.findAllSuspend(entityClass, consistency, fullBlock)
    }

    suspend fun findPage(
        pageSize: Int,
        pageToken: String? = null,
        consistency: KandraConsistency? = null,
        block: QueryContext.() -> Unit = {}
    ): KandraPage<T> {
        checkNotShuttingDown()
        return executor.findPageSuspend(entityClass, pageSize, pageToken, consistency, block)
    }

    suspend fun exists(consistency: KandraConsistency? = null, block: QueryContext.() -> Unit): Boolean {
        checkNotShuttingDown()
        return executor.existsSuspend(consistency, block)
    }

    /**
     * Returns all rows not yet soft-deleted. Requires `@SoftDelete(markerProperty = "...")`
     * on [T] — throws [KandraSchemaException] otherwise.
     *
     * If the marker column has no `@SecondaryIndex`, answering this query requires
     * `ALLOW FILTERING`. Kandra does not emit that implicitly — this throws
     * [io.kandra.core.exception.KandraQueryException] unless you pass `allowFullScan = true`
     * to explicitly opt into the scatter-gather scan.
     */
    suspend fun findActive(allowFullScan: Boolean = false): List<T> {
        checkNotShuttingDown()
        return executor.findActiveSuspend(entityClass, allowFullScan)
    }

    suspend fun raw(cql: String, vararg params: Any?): List<Row> {
        checkNotShuttingDown()
        return executor.rawSuspend(cql, *params)
    }

    suspend fun rawQuery(query: KandraRawQuery): List<Row> {
        checkNotShuttingDown()
        return executor.rawQuerySuspend(query)
    }

    private fun keyValuesOf(entity: T): List<Any> = (schema.partitionKeys + schema.clusteringKeys).map { key ->
        schema.reflection.propertiesByName[key.propertyName]?.call(entity)
            ?: throw KandraQueryException("Key '${key.propertyName}' is null")
    }

    suspend fun <V> append(entity: T, field: KProperty1<T, Collection<V>?>, values: Collection<V>, consistency: KandraConsistency? = null) {
        val col = schema.columns.find { it.propertyName == field.name }
            ?: throw KandraSchemaException("Field '${field.name}' not found in schema '${schema.tableName}'")
        batchEngine.appendSuspend(schema, keyValuesOf(entity), col.cqlName, values, consistency)
    }

    suspend fun <V> remove(entity: T, field: KProperty1<T, Collection<V>?>, values: Collection<V>, consistency: KandraConsistency? = null) {
        val col = schema.columns.find { it.propertyName == field.name }
            ?: throw KandraSchemaException("Field '${field.name}' not found in schema '${schema.tableName}'")
        batchEngine.removeSuspend(schema, keyValuesOf(entity), col.cqlName, values, consistency)
    }

    suspend fun <K, V> put(entity: T, field: KProperty1<T, Map<K, V>?>, entries: Map<K, V>, consistency: KandraConsistency? = null) {
        val col = schema.columns.find { it.propertyName == field.name }
            ?: throw KandraSchemaException("Field '${field.name}' not found in schema '${schema.tableName}'")
        batchEngine.putSuspend(schema, keyValuesOf(entity), col.cqlName, entries, consistency)
    }

    suspend fun increment(field: KProperty1<T, Long?>, partitionKeys: Map<String, Any>, by: Long = 1L, consistency: KandraConsistency? = null) {
        if (!schema.isCounterTable) throw KandraSchemaException("increment() is only valid on counter tables.")
        batchEngine.incrementSuspend(schema, field.name, partitionKeys, by, consistency)
    }

    suspend fun decrement(field: KProperty1<T, Long?>, partitionKeys: Map<String, Any>, by: Long = 1L, consistency: KandraConsistency? = null) {
        if (!schema.isCounterTable) throw KandraSchemaException("decrement() is only valid on counter tables.")
        batchEngine.decrementSuspend(schema, field.name, partitionKeys, by, consistency)
    }
}
