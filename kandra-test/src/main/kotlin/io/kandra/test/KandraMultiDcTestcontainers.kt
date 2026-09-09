package io.kandra.test

import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.core.DdlGenerator
import io.kandra.core.InternalKandraApi
import io.kandra.core.SchemaRegistry
import io.kandra.runtime.BatchEngine
import io.kandra.runtime.ConsistencyConfig
import io.kandra.runtime.KandraRuntime
import io.kandra.runtime.StatementBuilder
import io.kandra.runtime.codec.KandraCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.testcontainers.containers.ComposeContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import kotlin.reflect.KClass

/**
 * A real two-datacenter, one-node-per-DC Cassandra topology for DC-aware load-balancing/failover
 * tests (GH #84 / ISS-076), backed by `multidc-docker-compose.yml` (this module's
 * `src/main/resources`) via Testcontainers' [ComposeContainer]. Mirrors [KandraTestcontainers]'s
 * lazy-singleton, JVM-shared style -- one topology per JVM, reused across every test class that
 * touches it, rather than a fresh cluster per test.
 *
 * ### Why one node per DC (2 containers total), not 2+ nodes per DC
 *
 * A single node per DC is the minimum topology that has the property this fixture exists to test:
 * two *distinct* datacenters a client can genuinely fail over between, with `NetworkTopologyStrategy`
 * replication that means something (as opposed to a single-DC cluster where every "DC" in config is
 * cosmetic). Adding a second node per DC would let within-DC token-aware routing be exercised too,
 * but that's not what this issue is about -- it's about cross-DC failover/load-balancing -- and
 * real multi-node gossip convergence is significantly slower and flakier under Testcontainers than
 * a two-single-node topology (observed directly while building this fixture: a *single* node with
 * no custom config already takes ~15-20s to reach `Startup complete`; two nodes gossiping into one
 * cluster took closer to a minute). Doubling the node count per DC would roughly double that again,
 * for coverage this issue doesn't ask for. If a future issue specifically needs multi-node-per-DC
 * behavior (e.g. real token-range replica placement within a DC), scale `multidc-docker-compose.yml`
 * up rather than replacing this fixture.
 *
 * ### Topology
 *
 * - `cassandra-dc1` / `cassandra-dc2`, both plain `cassandra:4.1` (matching [KandraTestcontainers]'s
 *   single-node convention -- no custom `cassandra.yaml`).
 * - `GossipingPropertyFileSnitch` via `CASSANDRA_ENDPOINT_SNITCH`, DC/rack via `CASSANDRA_DC`/
 *   `CASSANDRA_RACK`, both nodes seeded from `cassandra-dc1` via `CASSANDRA_SEEDS` -- so they gossip
 *   into one cluster rather than forming two independent single-node clusters that happen to share
 *   a Docker network. `cassandra-dc2` waits for `cassandra-dc1` to report `service_healthy` before
 *   its own container even starts, since racing dc2's bootstrap against dc1's gossip start-up is a
 *   likely source of flakiness in a from-scratch two-node cluster.
 * - See `multidc-docker-compose.yml` itself for the exact environment/healthcheck configuration.
 *
 * ### Usage
 *
 * ```kotlin
 * class DcFailoverTest {
 *     private val db = KandraMultiDcTestcontainers.freshNetworkTopologyKeyspace(User::class)
 *     @AfterEach fun cleanup() { db.close(); KandraMultiDcTestcontainers.unpauseAll() }
 *
 *     @Test fun `fails over to dc2 when dc1 is unreachable`() {
 *         KandraMultiDcTestcontainers.pause(KandraMultiDcTestcontainers.DC1)
 *         // ... exercise install(Kandra) with loadBalancing.dcAwareFailover / failover.onLocalDcUnavailable ...
 *     }
 * }
 * ```
 */
object KandraMultiDcTestcontainers {

    const val DC1 = "dc1"
    const val DC2 = "dc2"

    private const val DC1_SERVICE = "cassandra-dc1"
    private const val DC2_SERVICE = "cassandra-dc2"

    // Distinct, fixed ports per node -- see multidc-docker-compose.yml's file-level comment for
    // why this pair can't be Testcontainers' usual same-port dynamic mapping: a Cassandra client
    // discovers peers via system.peers_v2 (which advertises each node's own native_transport_port)
    // and connects to them directly, so once both nodes are made host-reachable
    // (CASSANDRA_BROADCAST_RPC_ADDRESS=localhost), they need genuinely different ports.
    private const val DC1_CQL_PORT = 9042
    private const val DC2_CQL_PORT = 9043

    /**
     * The compose-managed topology -- started once per JVM, on first access, exactly like
     * [KandraTestcontainers.container]'s `by lazy { ... start() }` pattern. Startup timeouts are
     * generous (see class doc: two-node gossip convergence from cold is meaningfully slower than
     * the single-node case).
     */
    val compose: ComposeContainer by lazy {
        ComposeContainer(extractComposeFile())
            .withLocalCompose(true)
            .withExposedService(
                DC1_SERVICE,
                DC1_CQL_PORT,
                Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(5))
            )
            .withExposedService(
                DC2_SERVICE,
                DC2_CQL_PORT,
                Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(5))
            )
            .also { it.start() }
    }

    /** The real CQL contact point for [dc] (`DC1`/`DC2`) once [compose] has started. */
    fun contactPoint(dc: String): InetSocketAddress {
        val service = serviceNameFor(dc)
        val port = cqlPortFor(dc)
        return InetSocketAddress(compose.getServiceHost(service, port), compose.getServicePort(service, port))
    }

    /**
     * Pauses (SIGSTOP, via the Docker API -- the process keeps its state but stops responding
     * entirely) the container backing [dc], simulating "this datacenter is unreachable" for
     * failover tests without tearing down and losing the ability to bring it back. Always pair
     * with [unpause] (or [unpauseAll]) in `@AfterEach` -- a paused container left paused breaks
     * every later test sharing this JVM-wide topology.
     */
    fun pause(dc: String) {
        val container = containerStateFor(dc)
        container.dockerClient.pauseContainerCmd(container.containerId).exec()
    }

    /** Reverses [pause] for [dc]. Safe to call even if [dc] isn't currently paused. */
    fun unpause(dc: String) {
        val container = containerStateFor(dc)
        runCatching { container.dockerClient.unpauseContainerCmd(container.containerId).exec() }
    }

    /** Unpauses both DCs -- a safe, idempotent reset to call from `@AfterEach` regardless of what a test paused. */
    fun unpauseAll() {
        unpause(DC1)
        unpause(DC2)
    }

    /**
     * Creates a fresh keyspace with genuine `NetworkTopologyStrategy` replication across both DCs
     * (default `{'dc1': 1, 'dc2': 1}` -- one real replica per DC, the most RF a one-node-per-DC
     * topology can actually serve reads/writes at; pass a higher factor only for metadata-only
     * assertions, mirroring [KandraTestcontainers.freshKeyspace]'s identical caveat), registers
     * [classes], and creates their tables against the DC named by [localDatacenter].
     *
     * @param localDatacenter which DC's contact point to bootstrap and connect through (`DC1` or
     *   `DC2`). Matters for tests that then pause the *other* DC and expect local-DC operations to
     *   keep working, or pause *this* DC and expect failover/unavailability behavior.
     * @param dcReplicationMap the `NetworkTopologyStrategy` replication map. Both DC names must be
     *   valid CQL identifiers; this is deliberately not re-validated here the way
     *   `kandra-ktor`'s `keyspaceDdl` does for app-facing config -- this is test-only scaffolding.
     */
    @OptIn(InternalKandraApi::class)
    fun freshNetworkTopologyKeyspace(
        vararg classes: KClass<*>,
        localDatacenter: String = DC1,
        dcReplicationMap: Map<String, Int> = mapOf(DC1 to 1, DC2 to 1),
        statementBuilderConsistencyConfig: ConsistencyConfig = ConsistencyConfig()
    ): KandraRuntimeHandle {
        val keyspace = "kandra_multidc_${UUID.randomUUID().toString().replace("-", "")}"
        val cp = contactPoint(localDatacenter)

        val bootstrapSession = CqlSession.builder()
            .addContactPoint(cp)
            .withLocalDatacenter(localDatacenter)
            .build()
        val replication = dcReplicationMap.entries.joinToString(", ") { (dc, rf) -> "'$dc': $rf" }
        bootstrapSession.execute(
            "CREATE KEYSPACE IF NOT EXISTS $keyspace WITH replication = " +
            "{'class': 'NetworkTopologyStrategy', $replication}"
        )
        bootstrapSession.close()

        val session = CqlSession.builder()
            .addContactPoint(cp)
            .withLocalDatacenter(localDatacenter)
            .withKeyspace(keyspace)
            .build()

        for (klass in classes) {
            val schema = SchemaRegistry.register(klass)
            DdlGenerator.allStatements(schema).forEach { session.execute(it) }
        }

        val codec = KandraCodec.default
        val statementBuilder = StatementBuilder(session, codec, consistencyConfig = statementBuilderConsistencyConfig)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val batchEngine = BatchEngine(session, statementBuilder, scope)
        val runtime = KandraRuntime(session, batchEngine, codec)

        return KandraRuntimeHandle(session, runtime, scope, keyspace)
    }

    private fun serviceNameFor(dc: String): String = when (dc) {
        DC1 -> DC1_SERVICE
        DC2 -> DC2_SERVICE
        else -> error("Unknown datacenter '$dc' -- expected '$DC1' or '$DC2'.")
    }

    private fun cqlPortFor(dc: String): Int = when (dc) {
        DC1 -> DC1_CQL_PORT
        DC2 -> DC2_CQL_PORT
        else -> error("Unknown datacenter '$dc' -- expected '$DC1' or '$DC2'.")
    }

    private fun containerStateFor(dc: String) =
        compose.getContainerByServiceName(serviceNameFor(dc)).orElseThrow {
            IllegalStateException("No running container found for service '${serviceNameFor(dc)}' -- is compose started?")
        }

    /**
     * [ComposeContainer] takes a [File], not a classpath resource. `multidc-docker-compose.yml`
     * references a sibling file (`multidc-dc2-cassandra.yaml`) via a relative volume path, so both
     * need to be extracted together into the same real temp directory -- not just the compose file
     * alone -- for that relative reference to resolve once Testcontainers reads it.
     */
    private fun extractComposeFile(): File {
        val tempDir = Files.createTempDirectory("kandra-multidc-compose")
        tempDir.toFile().deleteOnExit()

        val composeFile = extractResourceTo(tempDir, "multidc-docker-compose.yml")
        extractResourceTo(tempDir, "multidc-dc2-cassandra.yaml")
        return composeFile
    }

    private fun extractResourceTo(dir: java.nio.file.Path, resourceName: String): File {
        val resourceStream = requireNotNull(
            KandraMultiDcTestcontainers::class.java.classLoader.getResourceAsStream(resourceName)
        ) { "$resourceName not found on the classpath (expected in kandra-test's src/main/resources)." }

        val target = dir.resolve(resourceName).toFile()
        target.deleteOnExit()
        resourceStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        return target
    }
}
