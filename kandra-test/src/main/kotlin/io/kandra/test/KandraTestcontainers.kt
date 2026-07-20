package io.kandra.test

import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.core.InternalKandraApi
import io.kandra.core.SchemaRegistry
import io.kandra.core.exception.KandraSchemaException
import io.kandra.runtime.BatchEngine
import io.kandra.runtime.KandraRuntime
import io.kandra.runtime.StatementBuilder
import io.kandra.runtime.codec.KandraCodec
import io.kandra.runtime.repository.KandraRepository
import io.kandra.runtime.repository.KandraSuspendRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.testcontainers.containers.CassandraContainer
import java.util.UUID
import kotlin.reflect.KClass

/**
 * Integration test utilities for running Kandra against a real ScyllaDB/Cassandra instance.
 *
 * Starts one shared container per JVM and creates a fresh, randomly-named keyspace
 * per test class — ensuring complete isolation between test classes running in parallel.
 *
 * ```kotlin
 * class UserRepositoryTest {
 *     private val db = KandraTestcontainers.freshKeyspace(User::class)
 *     private val userRepo = db.suspendRepository<User>()
 *
 *     @AfterEach fun cleanup() = db.close()
 * }
 * ```
 */
object KandraTestcontainers {

    /** Shared container — started once per JVM, reused across all test classes. */
    val container: CassandraContainer<*> by lazy {
        @Suppress("UNCHECKED_CAST")
        (CassandraContainer("cassandra:4.1") as CassandraContainer<*>)
            .withExposedPorts(9042)
            .also { it.start() }
    }

    /**
     * Creates a fresh keyspace with a random name, registers [classes], and creates tables.
     *
     * Call [KandraRuntimeHandle.close] in `@AfterEach` to drop the keyspace.
     */
    @OptIn(InternalKandraApi::class)
    fun freshKeyspace(vararg classes: KClass<*>): KandraRuntimeHandle {
        val keyspace = "kandra_test_${UUID.randomUUID().toString().replace("-", "")}"
        val cp = container.contactPoint
        val contactPoint = "${cp.hostString}:${cp.port}"

        val bootstrapSession = CqlSession.builder()
            .addContactPoint(container.contactPoint)
            .withLocalDatacenter(container.localDatacenter)
            .build()

        bootstrapSession.execute(
            "CREATE KEYSPACE IF NOT EXISTS $keyspace WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}"
        )
        bootstrapSession.close()

        val session = CqlSession.builder()
            .addContactPoint(container.contactPoint)
            .withLocalDatacenter(container.localDatacenter)
            .withKeyspace(keyspace)
            .build()

        // Register schemas and create tables
        val registry = mutableListOf<io.kandra.core.schema.TableSchema>()
        for (klass in classes) {
            val schema = SchemaRegistry.register(klass)
            registry.add(schema)
            io.kandra.core.DdlGenerator.allStatements(schema).forEach { session.execute(it) }
        }

        val codec = KandraCodec.default
        val statementBuilder = StatementBuilder(session, codec)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val batchEngine = BatchEngine(session, statementBuilder, scope)
        val runtime = KandraRuntime(session, batchEngine, codec)

        return KandraRuntimeHandle(session, runtime, scope, keyspace)
    }
}

/** A runtime handle for an isolated test keyspace. Close it in `@AfterEach`. */
class KandraRuntimeHandle @OptIn(InternalKandraApi::class) constructor(
    private val session: CqlSession,
    val runtime: KandraRuntime,
    private val scope: CoroutineScope,
    val keyspace: String
) {
    @OptIn(InternalKandraApi::class)
    inline fun <reified T : Any> suspendRepository(): KandraSuspendRepository<T> =
        runtime.suspendRepository()

    @OptIn(InternalKandraApi::class)
    inline fun <reified T : Any> repository(): KandraRepository<T> =
        runtime.repository()

    fun close() {
        runCatching { session.execute("DROP KEYSPACE IF EXISTS $keyspace") }
        runCatching { session.close() }
        scope.cancel("KandraRuntimeHandle closed")
    }
}
