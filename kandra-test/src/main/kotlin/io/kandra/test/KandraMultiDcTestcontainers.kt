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
import java.net.ServerSocket
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import kotlin.reflect.KClass

/**
 * Thrown by [KandraMultiDcTestcontainers] when the multi-DC Docker Compose fixture itself fails to
 * come up -- a dynamically-chosen port still collided (see [KandraMultiDcTestcontainers.compose]'s
 * doc for the small, inherent check-then-bind race), Docker isn't running, or Compose otherwise
 * failed to start. Wraps whatever Testcontainers/Docker raised with an actionable, Kandra-authored
 * message instead of leaving a caller to decode a raw Docker bind error (GH #108 / ISS-095).
 */
class KandraMultiDcFixtureException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

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

    /**
     * The dynamically-chosen ports actually used by the running [compose] topology (GH #108 /
     * ISS-095 -- previously hardcoded 9042/9043, which routinely collided with a developer's own
     * local Cassandra/Scylla instance or a concurrent run of this same fixture). Only meaningful
     * after [compose] has been successfully touched at least once -- every call site that reads
     * these ([cqlPortFor], via [contactPoint]) forces [compose] first for exactly this reason.
     * `-1` before that point, deliberately invalid so a same-JVM logic bug (reading these before
     * [compose] starts) fails loudly on the resulting bogus `InetSocketAddress` instead of silently
     * using port `0`.
     */
    private var dc1Port: Int = -1
    private var dc2Port: Int = -1

    /**
     * The compose-managed topology -- started once per JVM, on first access, exactly like
     * [KandraTestcontainers.container]'s `by lazy { ... start() }` pattern. Startup timeouts are
     * generous (see class doc: two-node gossip convergence from cold is meaningfully slower than
     * the single-node case).
     *
     * ### Port selection and retry (GH #108 / ISS-095)
     *
     * [findFreePort] only checks that a port is free at the moment it's picked -- there's an
     * inherent, small race between that check and Docker actually binding it (another process,
     * including an entirely unrelated Testcontainers-managed container from a concurrent test run,
     * could grab the same ephemeral port in between). A naive "pick ports once, retry the same pair
     * forever" approach makes this *worse* on retry, not better: if `docker compose up` fails after
     * partially creating containers (e.g. dc1 started and bound its port, then something else in the
     * topology failed), those containers are never automatically torn down here -- [ComposeContainer]
     * only runs its cleanup (`docker compose down`) from [ComposeContainer.stop], which is never
     * called if [ComposeContainer.start] itself throws -- so a same-port retry would immediately
     * collide with its own previous attempt's leftovers. To actually make retrying useful, each of
     * up to 3 attempts here picks a **fresh** port pair and best-effort tears down its own compose
     * project if it fails, before the next attempt tries again with different ports.
     *
     * If every attempt fails -- Docker isn't running, or something other than a port collision is
     * wrong -- the last failure is wrapped in [KandraMultiDcFixtureException] with an actionable
     * message instead of propagating a raw Testcontainers/Docker exception.
     */
    val compose: ComposeContainer by lazy {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            val attemptDc1Port = findFreePort()
            val attemptDc2Port = findFreePort(exclude = attemptDc1Port)
            var started: ComposeContainer? = null
            try {
                val candidate = ComposeContainer(extractComposeFile(attemptDc1Port, attemptDc2Port))
                    .withLocalCompose(true)
                    .withEnv("KANDRA_DC1_PORT", attemptDc1Port.toString())
                    .withEnv("KANDRA_DC2_PORT", attemptDc2Port.toString())
                    .withExposedService(
                        DC1_SERVICE,
                        attemptDc1Port,
                        Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(5))
                    )
                    .withExposedService(
                        DC2_SERVICE,
                        attemptDc2Port,
                        Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(5))
                    )
                candidate.start()
                started = candidate
                dc1Port = attemptDc1Port
                dc2Port = attemptDc2Port
                return@lazy candidate
            } catch (e: Exception) {
                lastError = e
                // Best-effort teardown of whatever this failed attempt managed to create, so a
                // *different*-ported retry doesn't inherit a poisoned Docker state -- and so this
                // attempt's own containers don't linger beyond Ryuk's eventual JVM-exit cleanup.
                // `started` is only non-null if `candidate.start()` itself returned normally, which
                // it didn't (we're in the catch block for that exact call) -- so this covers the
                // "started but a later step in this same try block threw" case; a `start()` failure
                // itself is handled by Testcontainers' own registerContainersForShutdown() call
                // (made before createServices() runs) plus Ryuk.
                runCatching { started?.stop() }
            }
        }
        throw KandraMultiDcFixtureException(
            "Failed to start the multi-DC Cassandra test fixture after 3 attempts with different " +
                "dynamically-chosen ports each time. Common causes: Docker isn't running (check " +
                "`docker info`), or the Docker daemon is under heavy concurrent load from other " +
                "processes/test runs (GH #108 / ISS-095 -- dynamic port selection avoids fixed-port " +
                "collisions, but can't fix a generally overloaded Docker daemon). Last error: " +
                "${lastError?.let { "${it::class.simpleName}: ${it.message}" }}",
            lastError
        )
    }

    /** The real CQL contact point for [dc] (`DC1`/`DC2`) once [compose] has started. */
    fun contactPoint(dc: String): InetSocketAddress {
        val service = serviceNameFor(dc)
        val startedCompose = compose // force startup first -- sets dc1Port/dc2Port, read by cqlPortFor below.
        val port = cqlPortFor(dc)
        return InetSocketAddress(startedCompose.getServiceHost(service, port), startedCompose.getServicePort(service, port))
    }

    /**
     * Pauses the container backing [dc] via the Docker API (`docker pause`), simulating "this
     * datacenter is unreachable" for failover tests without tearing down and losing the ability to
     * bring it back.
     *
     * ### What this actually simulates -- and what it doesn't (GH #108 / ISS-095)
     *
     * Docker `pause` freezes the container's userspace processes via the Linux cgroup freezer. It
     * does **not** sever the container's network namespace or stop the host kernel's TCP stack for
     * it -- any TCP connection already established to this node stays alive at the socket level,
     * simply going quiet because nothing on the far end is scheduled to read or write it anymore.
     * That's closer to "the Cassandra process hung" than "the network link went down": a real
     * network partition (a severed link, a firewall rule, an unplugged cable) typically produces
     * immediate TCP RSTs or ICMP host/port-unreachable on new connection attempts, and can also kill
     * already-established connections outright -- neither of which `pause` produces. No RST, no
     * ICMP unreachable, and existing sockets are never actively torn down by this call.
     *
     * The tests in [io.kandra.multidc.MultiDcFailoverTest] that use this are built around bounded
     * retry loops tolerant of either failure mode (both eventually manifest as the driver giving up
     * on the node), so this distinction hasn't been observed to change their outcome -- but don't
     * read a passing failover test here as proof of behavior under a *severed link*, only under an
     * unresponsive/hung node. Always pair with [unpause] (or [unpauseAll]) in `@AfterEach` -- a
     * paused container left paused breaks every later test sharing this JVM-wide topology.
     *
     * ### If the JVM dies between [pause] and cleanup
     *
     * `@AfterEach`/`try-finally` cleanup only runs if the JVM is alive to run it. If the JVM itself
     * dies mid-test (a crash, an OOM, `System.exit`, a killed build daemon) after [pause] but before
     * [unpause]/[unpauseAll] runs, `dc1`/`dc2` is left frozen with nothing left in this process to
     * unpause it. The actual backstop in that scenario is Testcontainers' own Ryuk resource-reaper
     * sidecar, which force-removes every container/network this JVM started once it detects the JVM
     * is gone -- a *removed* container rather than an indefinitely frozen one, but not an
     * immediate/synchronous guarantee. This reliance is implicit rather than something the fixture
     * enforces itself; there is no JVM-shutdown-hook-based unpause here.
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
        DC1 -> dc1Port
        DC2 -> dc2Port
        else -> error("Unknown datacenter '$dc' -- expected '$DC1' or '$DC2'.")
    }

    /**
     * Binds an ephemeral socket to port 0 (OS-assigned free port), reads back the port it got, and
     * immediately closes it -- the standard "ask the OS for a free port" trick. Retries against
     * [exclude] (used to guarantee [dc1Port] and [dc2Port] are never accidentally the same port).
     * There is an inherent, small race between this check and Docker actually binding the port
     * (see [compose]'s doc) -- this is a best-effort preflight, not a reservation.
     */
    private fun findFreePort(exclude: Int? = null): Int {
        repeat(10) {
            ServerSocket(0).use { socket ->
                val port = socket.localPort
                if (port != exclude) return port
            }
        }
        throw KandraMultiDcFixtureException(
            "Could not find two distinct free ports for the multi-DC test fixture after 10 attempts."
        )
    }

    private fun containerStateFor(dc: String) =
        compose.getContainerByServiceName(serviceNameFor(dc)).orElseThrow {
            IllegalStateException("No running container found for service '${serviceNameFor(dc)}' -- is compose started?")
        }

    /**
     * [ComposeContainer] takes a [File], not a classpath resource. `multidc-docker-compose.yml`
     * references two sibling files (`multidc-dc1-cassandra.yaml`, `multidc-dc2-cassandra.yaml`) via
     * relative volume paths, so all three need to end up in the same real temp directory for those
     * relative references to resolve once Testcontainers reads the compose file. The two per-node
     * yaml files are rendered here (not extracted verbatim) from the one shared
     * `multidc-cassandra-template.yaml` resource, substituting [dc1Port]/[dc2Port] (this attempt's
     * dynamically-chosen ports -- see [compose]'s doc for why each retry gets its own fresh pair)
     * into `native_transport_port` (GH #108 / ISS-095 -- see `multidc-docker-compose.yml`'s
     * file-level comment for why that value has to match the node's own published host port).
     */
    private fun extractComposeFile(dc1Port: Int, dc2Port: Int): File {
        val tempDir = Files.createTempDirectory("kandra-multidc-compose")
        tempDir.toFile().deleteOnExit()

        val composeFile = extractResourceTo(tempDir, "multidc-docker-compose.yml")
        renderCassandraYaml(tempDir, "multidc-dc1-cassandra.yaml", dc1Port)
        renderCassandraYaml(tempDir, "multidc-dc2-cassandra.yaml", dc2Port)
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

    /**
     * Renders `multidc-cassandra-template.yaml` (a full copy of the `cassandra:4.1` image's own
     * default `cassandra.yaml`, with `native_transport_port` replaced by a placeholder) to
     * [targetFileName] in [dir], substituting [port] for that placeholder.
     */
    private fun renderCassandraYaml(dir: java.nio.file.Path, targetFileName: String, port: Int) {
        val resourceStream = requireNotNull(
            KandraMultiDcTestcontainers::class.java.classLoader.getResourceAsStream("multidc-cassandra-template.yaml")
        ) { "multidc-cassandra-template.yaml not found on the classpath (expected in kandra-test's src/main/resources)." }

        val rendered = resourceStream.use { it.readBytes() }
            .toString(Charsets.UTF_8)
            .replace("__KANDRA_NATIVE_TRANSPORT_PORT__", port.toString())

        val target = dir.resolve(targetFileName).toFile()
        target.deleteOnExit()
        target.writeText(rendered)
    }
}
