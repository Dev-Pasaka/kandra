package io.kandra.runtime

import com.datastax.oss.driver.api.core.cql.BatchableStatement
import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.InternalKandraApi
import io.kandra.core.exception.KandraQueryException
import io.kandra.core.schema.TableSchema
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
 * **`suspend fun batch { }` vs. `fun batchBlocking { }` (ISS-059 / GH #60)**: the two
 * [KandraSuspendRepository] extensions (`saveInBatch`/`deleteInBatch`) below are themselves
 * declared `suspend` and collect statements via [BatchEngine.collectSaveSuspend]/
 * [BatchEngine.collectDeleteSuspend] — which use [StatementBuilder]'s suspend prepare path
 * ([StatementBuilder.insertPrimarySuspend] etc.) so a prepared-statement cache miss during
 * collection never blocks the calling coroutine's dispatcher thread. Being `suspend`, they can
 * only be called from [KandraRuntime.batch]'s suspend block — not from [KandraRuntime.batchBlocking]'s
 * plain `() -> Unit` block, which is a compile error, not a runtime foot-gun. The two
 * [KandraRepository] extensions (blocking) are unchanged: non-suspend, backed by
 * [BatchEngine.collectSave]/[BatchEngine.collectDelete], and remain the only pair usable from
 * [KandraRuntime.batchBlocking].
 *
 * Restrictions:
 * - `findAll`, `findById`, and all read operations are **not** available — reads cannot be batched.
 * - `saveIfNotExists` throws [KandraQueryException] — LWT cannot be mixed with regular statements.
 * - EVENTUAL lookup writes are **not** included in the batch (they fire separately after commit).
 */
@ExperimentalKandraApi
class KandraBatchScope internal constructor(
    private val batchEngine: BatchEngine
) {
    private val statements = mutableListOf<BatchableStatement<*>>()
    private var schema: TableSchema? = null

    /**
     * Adds the entity save (primary + BATCH lookups) to this batch. Suspend — only callable from
     * [KandraRuntime.batch]'s suspend block. Uses the suspend prepare path (see class doc, ISS-059 /
     * GH #60) so a prepared-statement cache miss never blocks the calling coroutine's dispatcher thread.
     */
    suspend fun <T : Any> KandraSuspendRepository<T>.saveInBatch(entity: T, ttlSeconds: Int? = null) {
        @OptIn(InternalKandraApi::class)
        statements.addAll(batchEngine.collectSaveSuspend(schema, entity, ttlSeconds))
        this@KandraBatchScope.schema = schema
    }

    /** Adds the entity save (primary + BATCH lookups) to this batch. Blocking — for [KandraRuntime.batchBlocking] only. */
    fun <T : Any> KandraRepository<T>.saveInBatch(entity: T, ttlSeconds: Int? = null) {
        @OptIn(InternalKandraApi::class)
        statements.addAll(batchEngine.collectSave(schema, entity, ttlSeconds))
        this@KandraBatchScope.schema = schema
    }

    /**
     * Adds the entity delete (primary + all lookup tables) to this batch. Suspend — only callable
     * from [KandraRuntime.batch]'s suspend block. See [saveInBatch]'s doc (ISS-059 / GH #60).
     */
    suspend fun <T : Any> KandraSuspendRepository<T>.deleteInBatch(entity: T) {
        @OptIn(InternalKandraApi::class)
        statements.addAll(batchEngine.collectDeleteSuspend(schema, entity))
        this@KandraBatchScope.schema = schema
    }

    /** Adds the entity delete (primary + all lookup tables) to this batch. Blocking — for [KandraRuntime.batchBlocking] only. */
    fun <T : Any> KandraRepository<T>.deleteInBatch(entity: T) {
        @OptIn(InternalKandraApi::class)
        statements.addAll(batchEngine.collectDelete(schema, entity))
        this@KandraBatchScope.schema = schema
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

    /**
     * Executes the collected statements as a single `LOGGED BATCH` — used by
     * [KandraRuntime.batchBlocking]. Routed through [BatchEngine.executeBatchScope] so this
     * caller-controlled batch gets the same shutdown gate, retry-on-transient-error, and
     * in-flight tracking as every other write, instead of calling `session.execute` directly.
     */
    internal fun execute() {
        if (statements.isEmpty()) return
        @OptIn(InternalKandraApi::class)
        val schema = schema ?: throw KandraQueryException("Empty batch scope")
        batchEngine.executeBatchScope(schema, statements)
    }

    /**
     * Suspend counterpart of [execute] — used by [KandraRuntime.batch], which is itself a
     * `suspend fun`. Routed through [BatchEngine.executeBatchScopeSuspend], which uses
     * `session.executeSuspend` for the final commit instead of blocking the calling coroutine's
     * thread, while still applying the same shutdown gate / retry / in-flight tracking.
     */
    internal suspend fun executeSuspend() {
        if (statements.isEmpty()) return
        @OptIn(InternalKandraApi::class)
        val schema = schema ?: throw KandraQueryException("Empty batch scope")
        batchEngine.executeBatchScopeSuspend(schema, statements)
    }
}
