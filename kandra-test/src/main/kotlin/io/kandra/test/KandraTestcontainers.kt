package io.kandra.test

import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.core.InternalKandraApi
import io.kandra.core.SchemaRegistry
import io.kandra.core.exception.KandraSchemaException
import io.kandra.runtime.BatchEngine
import io.kandra.runtime.ConsistencyConfig
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

    /**
     * A session on the shared Docker container with the same 30 s request/schema timeouts as
     * [KandraTestBackend.newSession], bound to [keyspace] when given. Unlike [KandraTestBackend] it
     * **always** targets the container, whatever `KANDRA_TEST_BACKEND` says — for tests that are
     * Docker-only by design (fixed keyspace names that would collide on a shared cloud cluster).
     * The caller closes it.
     *
     * Use this rather than a bare `CqlSession.builder()` for admin/cleanup DDL: the driver's default
     * 2 s request timeout is too tight for `CREATE/DROP KEYSPACE` on a loaded CI container.
     */
    fun newContainerSession(keyspace: String? = null): CqlSession {
        val b = CqlSession.builder()
            .addContactPoint(container.contactPoint)
            .withLocalDatacenter(container.localDatacenter)
            .withConfigLoader(KandraTestBackend.longTimeoutConfig())
        if (keyspace != null) b.withKeyspace(keyspace)
        return b.build()
    }

    /** Shared container — started once per JVM, reused across all test classes. */
    val container: CassandraContainer<*> by lazy {
        @Suppress("UNCHECKED_CAST")
        (CassandraContainer("cassandra:4.1") as CassandraContainer<*>)
            .withExposedPorts(9042)
            .also {
                it.start()
                awaitReady(it)
            }
    }

    /**
     * The container being "started" (port open) does not mean CQL is serving yet: under load the first
     * queries can time out. Poll a trivial query (and a DDL, the slowest first operation) until it
     * succeeds (deadline 3 min) so no test pays for the cold start, instead of sprinkling sleeps in tests.
     */
    private fun awaitReady(c: CassandraContainer<*>) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(3)
        var last: Throwable? = null
        while (System.nanoTime() < deadline) {
            try {
                CqlSession.builder()
                    .addContactPoint(c.contactPoint)
                    .withLocalDatacenter(c.localDatacenter)
                    .withConfigLoader(KandraTestBackend.longTimeoutConfig())
                    .build().use { s ->
                        s.execute("SELECT release_version FROM system.local").one()
                        s.execute("CREATE KEYSPACE IF NOT EXISTS kandra_ready_probe WITH replication = " +
                            "{'class': 'SimpleStrategy', 'replication_factor': 1}")
                        s.execute("DROP KEYSPACE IF EXISTS kandra_ready_probe")
                    }
                return
            } catch (e: Exception) {
                last = e
                Thread.sleep(1000)
            }
        }
        throw IllegalStateException("Cassandra container not ready after 3 minutes", last)
    }

    /**
     * Creates a fresh keyspace with a random name, registers [classes], and creates tables.
     *
     * Call [KandraRuntimeHandle.close] in `@AfterEach` to drop the keyspace.
     *
     * @param replicationFactor `SimpleStrategy` replication factor for the created keyspace. Defaults
     *   to `1` (the only sensible choice for a single-node Testcontainers cluster to actually serve
     *   reads/writes at). A higher value is useful when a test only needs the keyspace *metadata* to
     *   reflect a given RF — e.g. exercising Strict Mode's RF-vs-(R+W) check (ISS-075 / GH #83) — since
     *   `SimpleStrategy` will happily declare `replication_factor: 3` on a one-node cluster even though
     *   only one real replica ever exists; such a keyspace can't safely serve queries above `ONE`/
     *   `LOCAL_ONE`, so callers passing a higher factor should stick to metadata-only assertions.
     * @param statementBuilderConsistencyConfig [ConsistencyConfig] used for the [StatementBuilder]
     *   backing the returned runtime's repositories — e.g. to enable `strictMode` for a test. Defaults
     *   to a plain `ConsistencyConfig()`, matching prior behavior.
     */
    @OptIn(InternalKandraApi::class)
    fun freshKeyspace(
        vararg classes: KClass<*>,
        replicationFactor: Int = 1,
        statementBuilderConsistencyConfig: ConsistencyConfig = ConsistencyConfig()
    ): KandraRuntimeHandle {
        // KANDRA_TEST_BACKEND=cloud => the KANDRA_CLOUD_* cluster instead of the container (see KandraTestBackend).
        val keyspace = if (KandraTestBackend.isCloud) KandraTestBackend.keyspaceName("it")
            else "kandra_test_${UUID.randomUUID().toString().replace("-", "")}"

        KandraTestBackend.newSession().use { it.execute(KandraTestBackend.createKeyspaceCql(keyspace, replicationFactor)) }
        val session = KandraTestBackend.newSession(keyspace)

        // Register schemas and create tables
        val registry = mutableListOf<io.kandra.core.schema.TableSchema>()
        for (klass in classes) {
            val schema = SchemaRegistry.register(klass)
            registry.add(schema)
            io.kandra.core.DdlGenerator.allStatements(schema).forEach { session.execute(it) }
        }

        val codec = KandraCodec.default
        val statementBuilder = StatementBuilder(session, codec, consistencyConfig = statementBuilderConsistencyConfig)
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
