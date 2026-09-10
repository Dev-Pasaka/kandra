package io.kandra.runtime

import com.datastax.oss.driver.api.core.cql.BatchableStatement
import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.InternalKandraApi
import io.kandra.core.exception.KandraQueryException
import io.kandra.core.schema.TableSchema
import io.kandra.runtime.repository.KandraRepository
import io.kandra.runtime.repository.KandraSuspendRepository

/**
 * DSL scope for building a caller-controlled LOGGED batch inside [KandraRuntime.batch] (suspend).
 *
 * All `saveInBatch()` and `deleteInBatch()` calls inside the block are collected and executed as a
 * single atomic `LOGGED BATCH` when the block exits. See [KandraBlockingBatchScope] for the
 * `batchBlocking` (non-suspend) counterpart.
 *
 * **Deliberately not named `save`/`delete`**: Kotlin resolves a member function of the
 * extension receiver over an extension function with a matching name unconditionally, even
 * when the extension is a member-extension of an implicit receiver in closer scope (as these
 * are, being declared inside this class). Since every repository already has its own real
 * `save`/`delete` member, `repo.save(entity)` (or `with(repo) { save(entity) }`) inside a batch
 * block would always silently call the repository's own immediately-executing method — never
 * this class's statement-collecting one — with no compiler warning. Distinct names route the
 * call correctly and make it a compile error to reach for the wrong one.
 *
 * **Suspend-only, and deliberately a distinct type from [KandraBlockingBatchScope] (GH #99 /
 * ISS-086)**: this class exposes `saveInBatch`/`deleteInBatch` only as extensions on
 * [KandraSuspendRepository], collected via [BatchEngine.collectSaveSuspend]/
 * [BatchEngine.collectDeleteSuspend] — which use [StatementBuilder]'s suspend prepare path
 * ([StatementBuilder.insertPrimarySuspend] etc.) so a prepared-statement cache miss during
 * collection never blocks the calling coroutine's dispatcher thread. Earlier, a single
 * `KandraBatchScope` exposed both this suspend overload set *and* [KandraRepository] (blocking)
 * overloads at once, so `blockingRepo.saveInBatch(entity)` inside a suspend `batch { }` block
 * compiled cleanly and silently resolved to the *blocking* `collectSave`/`prepare()` path,
 * blocking the calling coroutine's dispatcher thread — exactly the problem #60/ISS-059 fixed for
 * the common case, reachable again through the other repository type. Splitting into two scope
 * types closes this: [KandraBlockingBatchScope]'s extensions simply aren't in scope inside a
 * suspend `batch { }` block, so a mixed call is now a compile error (unresolved reference)
 * instead of a silent dispatcher-blocking bug.
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
     * Adds the entity save (primary + BATCH lookups) to this batch. Uses the suspend prepare path
     * (see class doc, ISS-059 / GH #60) so a prepared-statement cache miss never blocks the calling
     * coroutine's dispatcher thread.
     */
    suspend fun <T : Any> KandraSuspendRepository<T>.saveInBatch(entity: T, ttlSeconds: Int? = null) {
        saveInBatchAndGet(entity, ttlSeconds)
    }

    /**
     * Same as [saveInBatch], but returns the entity that will be persisted when this batch
     * commits — with any `@GeneratedUuid`/`@CreatedAt`/`@UpdatedAt` values already resolved (they
     * are computed at collection time by [BatchEngine.injectTimestamps], not deferred to commit
     * time), instead of discarding that copy the way [saveInBatch] does. See
     * [KandraRepository.saveAndGet]/[KandraSuspendRepository.saveAndGet]'s doc for the underlying
     * gap this closes for standalone `save()`. Note this does **not** inject an initial `@Version`
     * value the way the standalone `save()`/`saveAndGet()` path does.
     */
    @Suppress("UNCHECKED_CAST")
    @OptIn(InternalKandraApi::class)
    suspend fun <T : Any> KandraSuspendRepository<T>.saveInBatchAndGet(entity: T, ttlSeconds: Int? = null): T {
        val (stmts, stamped) = batchEngine.collectSaveAndGetSuspend(schema, entity, ttlSeconds)
        statements.addAll(stmts)
        this@KandraBatchScope.schema = schema
        return stamped as T
    }

    /** Adds the entity delete (primary + all lookup tables) to this batch. See [saveInBatch]'s doc. */
    suspend fun <T : Any> KandraSuspendRepository<T>.deleteInBatch(entity: T) {
        @OptIn(InternalKandraApi::class)
        statements.addAll(batchEngine.collectDeleteSuspend(schema, entity))
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
     * Suspend counterpart of [KandraBlockingBatchScope.execute] — used by [KandraRuntime.batch],
     * which is itself a `suspend fun`. Routed through [BatchEngine.executeBatchScopeSuspend], which
     * uses `session.executeSuspend` for the final commit instead of blocking the calling coroutine's
     * thread, while still applying the same shutdown gate / retry / in-flight tracking.
     */
    internal suspend fun executeSuspend() {
        if (statements.isEmpty()) return
        @OptIn(InternalKandraApi::class)
        val schema = schema ?: throw KandraQueryException("Empty batch scope")
        batchEngine.executeBatchScopeSuspend(schema, statements)
    }
}

/**
 * DSL scope for building a caller-controlled LOGGED batch inside [KandraRuntime.batchBlocking]
 * (non-suspend). See [KandraBatchScope]'s class doc for the naming rationale and the GH #99 /
 * ISS-086 reasoning behind keeping this a distinct type rather than sharing one scope class with
 * both overload sets.
 */
@ExperimentalKandraApi
class KandraBlockingBatchScope internal constructor(
    private val batchEngine: BatchEngine
) {
    private val statements = mutableListOf<BatchableStatement<*>>()
    private var schema: TableSchema? = null

    /** Adds the entity save (primary + BATCH lookups) to this batch. Blocking — for [KandraRuntime.batchBlocking] only. */
    fun <T : Any> KandraRepository<T>.saveInBatch(entity: T, ttlSeconds: Int? = null) {
        saveInBatchAndGet(entity, ttlSeconds)
    }

    /**
     * Same as [saveInBatch], but returns the entity that will be persisted when this batch
     * commits — with any `@GeneratedUuid`/`@CreatedAt`/`@UpdatedAt` values already resolved. See
     * [KandraBatchScope.saveInBatchAndGet]'s doc (the suspend counterpart) for the underlying gap
     * this closes.
     */
    @Suppress("UNCHECKED_CAST")
    @OptIn(InternalKandraApi::class)
    fun <T : Any> KandraRepository<T>.saveInBatchAndGet(entity: T, ttlSeconds: Int? = null): T {
        val (stmts, stamped) = batchEngine.collectSaveAndGet(schema, entity, ttlSeconds)
        statements.addAll(stmts)
        this@KandraBlockingBatchScope.schema = schema
        return stamped as T
    }

    /** Adds the entity delete (primary + all lookup tables) to this batch. Blocking — for [KandraRuntime.batchBlocking] only. */
    fun <T : Any> KandraRepository<T>.deleteInBatch(entity: T) {
        @OptIn(InternalKandraApi::class)
        statements.addAll(batchEngine.collectDelete(schema, entity))
        this@KandraBlockingBatchScope.schema = schema
    }

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
}
