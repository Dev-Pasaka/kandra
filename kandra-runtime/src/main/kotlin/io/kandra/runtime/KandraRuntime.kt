package io.kandra.runtime

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.InternalKandraApi
import io.kandra.core.SchemaRegistry
import io.kandra.core.exception.KandraException
import io.kandra.core.exception.KandraQueryException
import io.kandra.core.exception.KandraSchemaException
import io.kandra.runtime.codec.KandraCodec
import io.kandra.runtime.driver.executeSuspend
import io.kandra.runtime.repository.KandraRepository
import io.kandra.runtime.repository.KandraSuspendRepository
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass

// re-exported so callers in kandra-ktor can access without importing BatchEngine directly


private val logger = KotlinLogging.logger {}

/**
 * Entry point for all Kandra operations. Obtained from `application.kandra` in Ktor routes.
 *
 * Tracks [inFlightCount] for graceful shutdown drain and [isShuttingDown] to reject new
 * queries after shutdown is signalled.
 *
 * ```kotlin
 * application.kandra.batch {
 *     userRepo.save(user)
 *     walletRepo.save(wallet)
 * }
 * ```
 */
class KandraRuntime(
    val session: CqlSession,
    val batchEngine: BatchEngine,
    val codec: KandraCodec
) {
    /** Delegating to batchEngine so the shutdown hook and execute guards share one AtomicBoolean. */
    val isShuttingDown: AtomicBoolean get() = batchEngine.isShuttingDown
    val inFlightCount: AtomicInteger get() = batchEngine.inFlightCount

    /**
     * Executes all operations in [block] as a single LOGGED batch.
     *
     * Only `save()` and `delete()` are allowed in a batch scope.
     * `findAll` / `findById` and `saveIfNotExists` will throw [KandraQueryException].
     */
    @ExperimentalKandraApi
    suspend fun batch(block: suspend KandraBatchScope.() -> Unit) {
        checkNotShuttingDown()
        val scope = KandraBatchScope(session, batchEngine)
        scope.block()
        scope.execute()
    }

    /**
     * Blocking variant of [batch] for use in non-suspend contexts.
     */
    @ExperimentalKandraApi
    fun batchBlocking(block: KandraBatchScope.() -> Unit) {
        checkNotShuttingDown()
        val scope = KandraBatchScope(session, batchEngine)
        scope.block()
        scope.execute()
    }

    /** Creates a blocking repository for [entityClass]. For blocking contexts only — use [suspendRepository] in Ktor routes. */
    @OptIn(InternalKandraApi::class)
    fun <T : Any> repository(entityClass: KClass<T>): KandraRepository<T> {
        val schema = SchemaRegistry.get(entityClass)
        return KandraRepository(session, schema, entityClass, batchEngine)
    }

    /** Creates a suspend (coroutine-friendly) repository for [entityClass]. Preferred for Ktor routes. */
    @OptIn(InternalKandraApi::class)
    fun <T : Any> suspendRepository(entityClass: KClass<T>): KandraSuspendRepository<T> {
        val schema = SchemaRegistry.get(entityClass)
        return KandraSuspendRepository(session, schema, entityClass, batchEngine)
    }

    inline fun <reified T : Any> repository(): KandraRepository<T> = repository(T::class)

    inline fun <reified T : Any> suspendRepository(): KandraSuspendRepository<T> = suspendRepository(T::class)

    /** Returns `true` when ScyllaDB is reachable. Safe to use in health check endpoints. */
    suspend fun isHealthy(): Boolean {
        return try {
            session.executeSuspend(
                SimpleStatement.newInstance("SELECT release_version FROM system.local")
            )
            true
        } catch (e: Exception) {
            logger.warn(e) { "ScyllaDB health check failed" }
            false
        }
    }

    internal fun checkNotShuttingDown() {
        if (batchEngine.isShuttingDown.get()) throw KandraException("Kandra is shutting down — new queries are rejected")
    }
}
