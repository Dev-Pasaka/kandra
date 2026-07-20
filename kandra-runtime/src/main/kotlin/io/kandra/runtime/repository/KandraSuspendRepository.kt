package io.kandra.runtime.repository

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.Row
import io.kandra.core.KandraConsistency
import io.kandra.core.exception.KandraQueryException
import io.kandra.core.exception.KandraSchemaException
import io.kandra.core.schema.TableSchema
import io.kandra.runtime.BatchEngine
import io.kandra.runtime.QueryExecutor
import io.kandra.runtime.StatementBuilder
import io.kandra.runtime.driver.executeSuspend
import io.kandra.runtime.cache.KandraCache
import io.kandra.runtime.dsl.KandraPage
import io.kandra.runtime.dsl.KandraRawQuery
import io.kandra.runtime.dsl.QueryContext
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

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
    private val statementBuilder = StatementBuilder(session)
    private val executor = QueryExecutor(session, schema, statementBuilder)
    private val cache = KandraCache<Any, T>(schema.cacheConfig)

    private fun checkNotShuttingDown() {
        if (batchEngine.isShuttingDown.get()) throw KandraQueryException("Kandra is shutting down — new queries are rejected")
    }

    private fun partitionKeyOf(entity: T): Any {
        val props = entity::class.memberProperties.associateBy { it.name }
        val keys = schema.partitionKeys.map { pk -> props[pk.propertyName]?.call(entity) ?: pk.propertyName }
        return if (keys.size == 1) keys[0]!! else keys
    }

    suspend fun save(entity: T, ttlSeconds: Int? = null, timestampMicros: Long? = null, consistency: KandraConsistency? = null) {
        batchEngine.saveSuspend(schema, entity, ttlSeconds, timestampMicros, consistency)
        cache.invalidate(partitionKeyOf(entity))
    }

    suspend fun saveIfNotExists(entity: T, serialConsistency: KandraConsistency = KandraConsistency.LOCAL_SERIAL): Boolean =
        batchEngine.saveIfNotExistsSuspend(schema, entity, serialConsistency).also { if (it) cache.invalidate(partitionKeyOf(entity)) }

    suspend fun saveAll(entities: List<T>, useBatch: Boolean = true) {
        batchEngine.saveAllSuspend(schema, entities, useBatch = useBatch)
        entities.forEach { cache.invalidate(partitionKeyOf(it)) }
    }

    suspend fun update(old: T, new: T) {
        batchEngine.updateSuspend(schema, old, new)
        cache.invalidate(partitionKeyOf(new))
    }

    suspend fun updateForce(entity: T) {
        batchEngine.updateForceSuspend(schema, entity)
        cache.invalidate(partitionKeyOf(entity))
    }

    suspend fun saveWithNulls(entity: T, ttlSeconds: Int? = null) {
        batchEngine.saveWithNullsSuspend(schema, entity, ttlSeconds)
        cache.invalidate(partitionKeyOf(entity))
    }

    suspend fun delete(entity: T) {
        batchEngine.deleteSuspend(schema, entity)
        cache.invalidate(partitionKeyOf(entity))
    }

    suspend fun deleteAll(entities: List<T>) {
        batchEngine.deleteAllSuspend(schema, entities)
        entities.forEach { cache.invalidate(partitionKeyOf(it)) }
    }

    suspend fun deleteById(vararg keyValues: Any) {
        val entity = executor.findById(entityClass, *keyValues)
        if (entity != null) batchEngine.deleteSuspend(schema, entity)
        else session.executeSuspend(statementBuilder.deleteById(schema, *keyValues))
    }

    suspend fun deleteBy(block: QueryContext.() -> Unit) {
        val entity = executor.find(entityClass, block) ?: return
        batchEngine.deleteSuspend(schema, entity)
    }

    suspend fun findById(vararg idValues: Any, consistency: KandraConsistency? = null): T? {
        checkNotShuttingDown()
        val cacheKey: Any = if (idValues.size == 1) idValues[0] else idValues.toList()
        return cache.getIfPresent(cacheKey) ?: executor.findById(entityClass, *idValues, consistency = consistency)
            ?.also { cache.put(cacheKey, it) }
    }

    suspend fun find(block: QueryContext.() -> Unit): T? {
        checkNotShuttingDown()
        return executor.find(entityClass, block)
    }

    suspend fun findAll(limit: Int? = null, block: QueryContext.() -> Unit): List<T> {
        checkNotShuttingDown()
        val fullBlock: QueryContext.() -> Unit = {
            block()
            if (limit != null) limit(limit)
        }
        return executor.findAll(entityClass, fullBlock)
    }

    suspend fun findPage(
        pageSize: Int,
        pageToken: String? = null,
        block: QueryContext.() -> Unit = {}
    ): KandraPage<T> {
        checkNotShuttingDown()
        return executor.findPage(entityClass, pageSize, pageToken, block)
    }

    suspend fun exists(block: QueryContext.() -> Unit): Boolean {
        checkNotShuttingDown()
        return executor.exists(block)
    }

    suspend fun raw(cql: String, vararg params: Any?): List<Row> {
        checkNotShuttingDown()
        return executor.raw(cql, *params)
    }

    suspend fun rawQuery(query: KandraRawQuery): List<Row> {
        checkNotShuttingDown()
        return executor.rawQuery(query)
    }

    suspend fun <V> append(entity: T, field: KProperty1<T, Collection<V>?>, values: Collection<V>) {
        val col = schema.columns.find { it.propertyName == field.name }
            ?: throw KandraSchemaException("Field '${field.name}' not found in schema '${schema.tableName}'")
        val pkValues = schema.partitionKeys.map { pk ->
            entity::class.memberProperties.find { it.name == pk.propertyName }?.call(entity)
                ?: throw KandraQueryException("Partition key '${pk.propertyName}' is null")
        }
        session.execute(statementBuilder.appendToCollection(schema, pkValues, col.cqlName, values))
    }

    suspend fun <V> remove(entity: T, field: KProperty1<T, Collection<V>?>, values: Collection<V>) {
        val col = schema.columns.find { it.propertyName == field.name }
            ?: throw KandraSchemaException("Field '${field.name}' not found in schema '${schema.tableName}'")
        val pkValues = schema.partitionKeys.map { pk ->
            entity::class.memberProperties.find { it.name == pk.propertyName }?.call(entity)
                ?: throw KandraQueryException("Partition key '${pk.propertyName}' is null")
        }
        session.execute(statementBuilder.removeFromCollection(schema, pkValues, col.cqlName, values))
    }

    suspend fun <K, V> put(entity: T, field: KProperty1<T, Map<K, V>?>, entries: Map<K, V>) {
        val col = schema.columns.find { it.propertyName == field.name }
            ?: throw KandraSchemaException("Field '${field.name}' not found in schema '${schema.tableName}'")
        val pkValues = schema.partitionKeys.map { pk ->
            entity::class.memberProperties.find { it.name == pk.propertyName }?.call(entity)
                ?: throw KandraQueryException("Partition key '${pk.propertyName}' is null")
        }
        session.execute(statementBuilder.appendToCollection(schema, pkValues, col.cqlName, entries))
    }

    suspend fun increment(field: KProperty1<T, Long?>, partitionKeys: Map<String, Any>, by: Long = 1L) {
        if (!schema.isCounterTable) throw KandraSchemaException("increment() is only valid on counter tables.")
        session.execute(statementBuilder.counterUpdate(schema, field.name, partitionKeys, by))
    }

    suspend fun decrement(field: KProperty1<T, Long?>, partitionKeys: Map<String, Any>, by: Long = 1L) {
        if (!schema.isCounterTable) throw KandraSchemaException("decrement() is only valid on counter tables.")
        session.execute(statementBuilder.counterUpdate(schema, field.name, partitionKeys, -by))
    }
}
