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
 * All `saveInBatch()` and `deleteInBatch()` calls inside a [KandraRuntime.batch] or
 * [KandraRuntime.batchBlocking] block are collected and executed as a single
 * atomic `LOGGED BATCH` when the block exits.
 *
 * **Deliberately not named `save`/`delete`**: Kotlin resolves a member function of the
 * extension receiver over an extension function with a matching name unconditionally, even
 * when the extension is a member-extension of an implicit receiver in closer scope (as these
 * are, being declared inside [KandraBatchScope] itself). Since every repository already has
 * its own real `save`/`delete` member, `repo.save(entity)` (or `with(repo) { save(entity) }`)
 * inside a batch block would always silently call the repository's own immediately-executing
 * method — never this class's statement-collecting one — with no compiler warning. Distinct
 * names route the call correctly and make it a compile error to reach for the wrong one.
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
    fun <T : Any> KandraSuspendRepository<T>.saveInBatch(entity: T, ttlSeconds: Int? = null) {
        @OptIn(InternalKandraApi::class)
        statements.addAll(batchEngine.collectSave(schema, entity, ttlSeconds))
    }

    /** Adds the entity save (primary + BATCH lookups) to this batch. */
    fun <T : Any> KandraRepository<T>.saveInBatch(entity: T, ttlSeconds: Int? = null) {
        @OptIn(InternalKandraApi::class)
        statements.addAll(batchEngine.collectSave(schema, entity, ttlSeconds))
    }

    /** Adds the entity delete (primary + all lookup tables) to this batch. */
    fun <T : Any> KandraSuspendRepository<T>.deleteInBatch(entity: T) {
        @OptIn(InternalKandraApi::class)
        statements.addAll(batchEngine.collectDelete(schema, entity))
    }

    /** Adds the entity delete (primary + all lookup tables) to this batch. */
    fun <T : Any> KandraRepository<T>.deleteInBatch(entity: T) {
        @OptIn(InternalKandraApi::class)
        statements.addAll(batchEngine.collectDelete(schema, entity))
    }

    /**
     * Always throws — LWT cannot be mixed with regular batch statements. Named distinctly
     * (not `saveIfNotExists`) for the same shadowing reason as [saveInBatch]/[deleteInBatch]:
     * a same-named guard here would never actually be reachable, since `repo.saveIfNotExists(...)`
     * would always resolve to the repository's own real, immediately-executing member instead —
     * defeating the guard silently rather than enforcing it.
     */
    @Suppress("UNUSED_PARAMETER")
    fun <T : Any> KandraSuspendRepository<T>.saveIfNotExistsInBatch(entity: T): Boolean =
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
