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
import io.kandra.runtime.cache.KandraCache
import io.kandra.runtime.dsl.KandraPage
import io.kandra.runtime.dsl.KandraRawQuery
import io.kandra.runtime.dsl.QueryContext
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

/**
 * Blocking repository for performing CRUD operations on a ScyllaDB table.
 *
 * @param T the entity type annotated with `@ScyllaTable`
 */
class KandraRepository<T : Any>(
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

    fun save(entity: T, ttlSeconds: Int? = null, timestampMicros: Long? = null, consistency: KandraConsistency? = null) {
        batchEngine.save(schema, entity, ttlSeconds, timestampMicros, consistency)
        cache.invalidate(partitionKeyOf(entity))
    }

    fun saveIfNotExists(entity: T, serialConsistency: KandraConsistency = KandraConsistency.LOCAL_SERIAL): Boolean =
        batchEngine.saveIfNotExists(schema, entity, serialConsistency).also { if (it) cache.invalidate(partitionKeyOf(entity)) }

    fun saveAll(entities: List<T>, useBatch: Boolean = true) {
        batchEngine.saveAll(schema, entities, useBatch = useBatch)
        entities.forEach { cache.invalidate(partitionKeyOf(it)) }
    }

    fun update(old: T, new: T) {
        batchEngine.update(schema, old, new)
        cache.invalidate(partitionKeyOf(new))
    }

    fun updateForce(entity: T) {
        batchEngine.updateForce(schema, entity)
        cache.invalidate(partitionKeyOf(entity))
    }

    fun saveWithNulls(entity: T, ttlSeconds: Int? = null) {
        batchEngine.saveWithNulls(schema, entity, ttlSeconds)
        cache.invalidate(partitionKeyOf(entity))
    }

    fun delete(entity: T) {
        batchEngine.delete(schema, entity)
        cache.invalidate(partitionKeyOf(entity))
    }

    fun deleteAll(entities: List<T>) {
        batchEngine.deleteAll(schema, entities)
        entities.forEach { cache.invalidate(partitionKeyOf(it)) }
    }

    fun deleteById(vararg keyValues: Any) {
        val batch = com.datastax.oss.driver.api.core.cql.BatchStatement
            .newInstance(com.datastax.oss.driver.api.core.cql.DefaultBatchType.LOGGED)
            .add(statementBuilder.deleteById(schema, *keyValues))
        val entity = executor.findById(entityClass, *keyValues)
        if (entity != null) {
            val props = entity::class.memberProperties.associateBy { it.name }
            val b = schema.lookupTables.fold(batch) { acc, lookup ->
                val indexValue = props[lookup.indexColumn.propertyName]?.call(entity) ?: return@fold acc
                acc.add(statementBuilder.deleteLookup(lookup, indexValue))
            }
            session.execute(b)
        } else {
            session.execute(batch)
        }
    }

    fun deleteBy(block: QueryContext.() -> Unit) {
        val entity = executor.find(entityClass, block) ?: return
        batchEngine.delete(schema, entity)
    }

    fun findById(vararg idValues: Any, consistency: KandraConsistency? = null): T? {
        checkNotShuttingDown()
        val cacheKey: Any = if (idValues.size == 1) idValues[0] else idValues.toList()
        return cache.getIfPresent(cacheKey) ?: executor.findById(entityClass, *idValues, consistency = consistency)
            ?.also { cache.put(cacheKey, it) }
    }

    fun find(block: QueryContext.() -> Unit): T? {
        checkNotShuttingDown()
        return executor.find(entityClass, block)
    }

    fun findAll(limit: Int? = null, block: QueryContext.() -> Unit): List<T> {
        checkNotShuttingDown()
        val fullBlock: QueryContext.() -> Unit = {
            block()
            if (limit != null) limit(limit)
        }
        return executor.findAll(entityClass, fullBlock)
    }

    fun findPage(
        pageSize: Int,
        pageToken: String? = null,
        block: QueryContext.() -> Unit = {}
    ): KandraPage<T> = executor.findPage(entityClass, pageSize, pageToken, block)

    fun exists(block: QueryContext.() -> Unit): Boolean {
        checkNotShuttingDown()
        return executor.exists(block)
    }

    fun raw(cql: String, vararg params: Any?): List<Row> {
        checkNotShuttingDown()
        return executor.raw(cql, *params)
    }

    fun rawQuery(query: KandraRawQuery): List<Row> {
        checkNotShuttingDown()
        return executor.rawQuery(query)
    }

    fun <V> append(entity: T, field: KProperty1<T, Collection<V>?>, values: Collection<V>) {
        val col = schema.columns.find { it.propertyName == field.name }
            ?: throw KandraSchemaException("Field '${field.name}' not found in schema '${schema.tableName}'")
        val pkValues = schema.partitionKeys.map { pk ->
            entity::class.memberProperties.find { it.name == pk.propertyName }?.call(entity)
                ?: throw KandraQueryException("Partition key '${pk.propertyName}' is null")
        }
        session.execute(statementBuilder.appendToCollection(schema, pkValues, col.cqlName, values))
    }

    fun <V> remove(entity: T, field: KProperty1<T, Collection<V>?>, values: Collection<V>) {
        val col = schema.columns.find { it.propertyName == field.name }
            ?: throw KandraSchemaException("Field '${field.name}' not found in schema '${schema.tableName}'")
        val pkValues = schema.partitionKeys.map { pk ->
            entity::class.memberProperties.find { it.name == pk.propertyName }?.call(entity)
                ?: throw KandraQueryException("Partition key '${pk.propertyName}' is null")
        }
        session.execute(statementBuilder.removeFromCollection(schema, pkValues, col.cqlName, values))
    }

    fun <K, V> put(entity: T, field: KProperty1<T, Map<K, V>?>, entries: Map<K, V>) {
        val col = schema.columns.find { it.propertyName == field.name }
            ?: throw KandraSchemaException("Field '${field.name}' not found in schema '${schema.tableName}'")
        val pkValues = schema.partitionKeys.map { pk ->
            entity::class.memberProperties.find { it.name == pk.propertyName }?.call(entity)
                ?: throw KandraQueryException("Partition key '${pk.propertyName}' is null")
        }
        session.execute(statementBuilder.appendToCollection(schema, pkValues, col.cqlName, entries))
    }

    fun increment(field: KProperty1<T, Long?>, partitionKeys: Map<String, Any>, by: Long = 1L) {
        if (!schema.isCounterTable) throw KandraSchemaException("increment() is only valid on counter tables.")
        session.execute(statementBuilder.counterUpdate(schema, field.name, partitionKeys, by))
    }

    fun decrement(field: KProperty1<T, Long?>, partitionKeys: Map<String, Any>, by: Long = 1L) {
        if (!schema.isCounterTable) throw KandraSchemaException("decrement() is only valid on counter tables.")
        session.execute(statementBuilder.counterUpdate(schema, field.name, partitionKeys, -by))
    }
}
