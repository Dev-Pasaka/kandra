package io.kandra.test

import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.core.InternalKandraApi
import io.kandra.core.SchemaRegistry
import io.kandra.runtime.BatchEngine
import io.kandra.runtime.StatementBuilder
import io.kandra.runtime.repository.KandraRepository
import io.kandra.runtime.repository.KandraSuspendRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.reflect.KClass

/**
 * A fully wired Kandra instance backed by a [FakeKandraSession].
 *
 * Provides access to typed repositories without requiring a live ScyllaDB connection.
 * Obtain one via [KandraTestUtils.inMemory] and call [close] after each test to cancel
 * any pending eventual-write coroutines.
 */
@OptIn(InternalKandraApi::class)
class KandraRuntime internal constructor(val session: CqlSession) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val batchEngine = BatchEngine(session, StatementBuilder(session), scope)

    fun <T : Any> repository(klass: KClass<T>): KandraRepository<T> {
        val schema = SchemaRegistry.get(klass)
        return KandraRepository(session, schema, klass, batchEngine)
    }

    fun <T : Any> suspendRepository(klass: KClass<T>): KandraSuspendRepository<T> {
        val schema = SchemaRegistry.get(klass)
        return KandraSuspendRepository(session, schema, klass, batchEngine)
    }

    /** Cancels all pending eventual-write coroutines. Call in @AfterEach / use try-with-resources. */
    override fun close() {
        scope.cancel("KandraRuntime test instance closed")
    }
}

/**
 * Factory object for creating in-memory Kandra runtimes suitable for unit tests.
 */
object KandraTestUtils {

    /**
     * Registers [classes] in [SchemaRegistry] and returns a [KandraRuntime] backed
     * by [FakeKandraSession] — no ScyllaDB connection required.
     *
     * ```kotlin
     * val runtime = KandraTestUtils.inMemory(User::class)
     * val repo = runtime.repository(User::class)
     * repo.save(User(...))
     * runtime.close()   // or use try-with-resources
     * ```
     */
    @Suppress("OPT_IN_USAGE")
    fun inMemory(vararg classes: KClass<*>): KandraRuntime {
        classes.forEach { SchemaRegistry.register(it) }
        return KandraRuntime(FakeKandraSession())
    }
}
