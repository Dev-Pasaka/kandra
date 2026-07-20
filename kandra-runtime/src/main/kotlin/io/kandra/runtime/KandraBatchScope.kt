package io.kandra.runtime

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.BatchStatement
import com.datastax.oss.driver.api.core.cql.BatchableStatement
import com.datastax.oss.driver.api.core.cql.DefaultBatchType
import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.InternalKandraApi
import io.kandra.core.exception.KandraQueryException
import io.kandra.runtime.repository.KandraRepository
import io.kandra.runtime.repository.KandraSuspendRepository

/**
 * DSL scope for building a caller-controlled LOGGED batch.
 *
 * All `save()` and `delete()` calls inside a [KandraRuntime.batch] or
 * [KandraRuntime.batchBlocking] block are collected and executed as a single
 * atomic `LOGGED BATCH` when the block exits.
 *
 * Restrictions:
 * - `findAll`, `findById`, and all read operations are **not** available — reads cannot be batched.
 * - `saveIfNotExists` throws [KandraQueryException] — LWT cannot be mixed with regular statements.
 * - EVENTUAL lookup writes are **not** included in the batch (they fire separately after commit).
 */
@ExperimentalKandraApi
class KandraBatchScope internal constructor(
    private val session: CqlSession,
    private val batchEngine: BatchEngine
) {
    private val statements = mutableListOf<BatchableStatement<*>>()

    /** Adds the entity save (primary + BATCH lookups) to this batch. */
    fun <T : Any> KandraSuspendRepository<T>.save(entity: T, ttlSeconds: Int? = null) {
        @OptIn(InternalKandraApi::class)
        statements.addAll(batchEngine.collectSave(schema, entity, ttlSeconds))
    }

    /** Adds the entity save (primary + BATCH lookups) to this batch. */
    fun <T : Any> KandraRepository<T>.save(entity: T, ttlSeconds: Int? = null) {
        @OptIn(InternalKandraApi::class)
        statements.addAll(batchEngine.collectSave(schema, entity, ttlSeconds))
    }

    /** Adds the entity delete (primary + all lookup tables) to this batch. */
    fun <T : Any> KandraSuspendRepository<T>.delete(entity: T) {
        @OptIn(InternalKandraApi::class)
        statements.addAll(batchEngine.collectDelete(schema, entity))
    }

    /** Adds the entity delete (primary + all lookup tables) to this batch. */
    fun <T : Any> KandraRepository<T>.delete(entity: T) {
        @OptIn(InternalKandraApi::class)
        statements.addAll(batchEngine.collectDelete(schema, entity))
    }

    /** Always throws — LWT cannot be mixed with regular batch statements. */
    @Suppress("UNUSED_PARAMETER")
    fun <T : Any> KandraSuspendRepository<T>.saveIfNotExists(entity: T): Boolean =
        throw KandraQueryException(
            "saveIfNotExists() cannot be used inside batch { } — LWT (IF NOT EXISTS) cannot be " +
            "mixed with non-LWT statements in the same LOGGED BATCH."
        )

    internal fun execute() {
        if (statements.isEmpty()) return
        val batch = statements.fold(BatchStatement.newInstance(DefaultBatchType.LOGGED)) { acc, s -> acc.add(s) }
        session.execute(batch)
    }
}
