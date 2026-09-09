package io.kandra.ktor

import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.KandraAuth
import io.kandra.core.KandraAuthProvider
import io.kandra.core.KandraConsistency
import io.kandra.core.KandraEventListener
import io.kandra.core.KandraMetrics
import io.kandra.core.KandraValidationError
import io.kandra.core.KandraValidator
import io.kandra.runtime.ConsistencyConfig
import io.kandra.runtime.DebugConfig
import io.kandra.runtime.RetryConfig
import io.kandra.runtime.codec.KandraCodec
import kotlin.reflect.KClass

enum class SchemaMode {
    /** `CREATE TABLE IF NOT EXISTS` for all registered entities (default). */
    AUTO_CREATE,
    /** `CREATE TABLE IF NOT EXISTS` + `ALTER TABLE ADD` for new entity columns not in Scylla. */
    AUTO_MIGRATE,
    /** Validate existing tables match the entity schema; throw on missing columns. */
    VALIDATE,
    /** Skip all DDL — you manage schema yourself. */
    NONE
}

sealed class ReplicationStrategy {
    data class SimpleStrategy(val replicationFactor: Int = 1) : ReplicationStrategy()
    data class NetworkTopologyStrategy(val dcReplicationMap: Map<String, Int>) : ReplicationStrategy()
}

class PoolConfig {
    // GH #69: `localRequestsPerConnection` was previously declared here but never read anywhere in
    // CqlSessionBuilder -- setting it had zero effect. The DataStax driver (4.17.0) has no
    // local/remote split for per-connection request limits (only CONNECTION_MAX_REQUESTS, which
    // maxRequestsPerConnection below already maps to; the driver's local/remote distinction only
    // applies to connection *pool size*, not per-connection request count), so there was no faithful
    // way to wire it -- removed rather than left as a config value that silently does nothing.
    var maxRequestsPerConnection: Int = 32768
    var heartbeatIntervalSeconds: Int = 30
    /** How long to wait for a CQL query response. Default is 5 000 ms (driver default is 2 000 ms). */
    var requestTimeoutMillis: Long = 5000
    /** How long to wait when establishing a TCP connection to a ScyllaDB node. Default is 5 000 ms. */
    var connectionTimeoutMillis: Long = 5000

    /**
     * Number of pooled connections held open to each node in the **local** datacenter. Wired to
     * the driver's `advanced.connection.pool.local.size` (`CONNECTION_POOL_LOCAL_SIZE`) option
     * (GH #80 / ISS-072); the driver's own default -- and this field's default -- is 1.
     *
     * Each pooled connection multiplexes up to [maxRequestsPerConnection] concurrent requests, so
     * the real per-node concurrency ceiling is `localPoolSize * maxRequestsPerConnection`. Raise
     * this only after profiling actual per-node concurrency under real load -- more connections
     * isn't free (each is a TCP socket plus driver-side heap bookkeeping), and going from 1 to a
     * handful of connections per node is almost always enough headroom before
     * [maxRequestsPerConnection] becomes the real bottleneck. Note Kandra uses the stock OSS
     * DataStax driver, not a shard-aware ScyllaDB driver, so pool size alone can't route directly
     * to the owning shard on wide nodes (see `docs/issues/ISS-069-assorted-low-severity-findings.md`
     * item 4) -- widening the pool is a coarser lever than shard-aware routing would be, but it's
     * the lever Kandra currently exposes.
     */
    var localPoolSize: Int = 1

    /**
     * Number of pooled connections held open to each node in a **remote** datacenter. Wired to
     * `advanced.connection.pool.remote.size` (`CONNECTION_POOL_REMOTE_SIZE`) option (GH #80 /
     * ISS-072); default is 1, matching the driver's own default. Only relevant once cross-DC
     * failover is actually enabled (`loadBalancing.dcAwareFailover = true` together with
     * `failover.onLocalDcUnavailable = FailoverPolicy.RETRY_REMOTE_DC`) -- otherwise the driver
     * never opens connections to remote-DC nodes at all and this value has no effect. Typically
     * left at or below [localPoolSize] since sustained traffic to a remote DC should be the
     * failover exception, not the steady state.
     */
    var remotePoolSize: Int = 1
}

/**
 * Authentication configuration.
 *
 * The default [provider] reads from environment variables `SCYLLA_USERNAME` and `SCYLLA_PASSWORD`.
 * This is safe by default — no credentials appear in source code.
 *
 * When [refreshIntervalSeconds] is set, credentials are re-fetched on that interval without
 * restarting the session (rolling rotation support).
 */
@OptIn(ExperimentalKandraApi::class)
class AuthConfig {
    var provider: KandraAuthProvider = KandraAuth.fromEnv()
    var refreshIntervalSeconds: Long? = null
}

/**
 * SSL/TLS configuration for encrypted connections to ScyllaDB.
 *
 * Enable with `ssl { enabled = true; trustStorePath = "..." }` for one-way TLS.
 * Add [keyStorePath] for mutual TLS (client certificate authentication).
 *
 * [minimumTlsVersion], [cipherSuites], and [hostnameVerification] are all applied through a
 * custom `SslEngineFactory` (GH #78 / ISS-070) rather than `CqlSession.builder().withSslContext(...)`
 * alone, which builds the driver's `ProgrammaticSslEngineFactory` with no cipher-suite restriction
 * and no hostname validation regardless of driver config -- an `SSLContext` has no place to express
 * a minimum protocol version or per-connection cipher restriction; that's an `SSLEngine`/
 * `SSLParameters` concern applied per connection.
 */
class SslConfig {
    var enabled: Boolean = false

    /**
     * When true, refuses to build a [com.datastax.oss.driver.api.core.CqlSession] unless [enabled]
     * is also true -- fails fast with [io.kandra.core.exception.KandraSchemaException] before any
     * connection attempt. Defaults to `false`: SSL itself is opt-in ([enabled] defaults to `false`),
     * so defaulting this to `true` would break every default [KandraConfig] that doesn't touch the
     * `ssl { }` block at all. Turn this on explicitly in deployments where TLS must never
     * accidentally be left off -- e.g. a staging/prod config bundle that sets
     * `ssl { enabled = true; requireEncryption = true; ... }` as a guard against that `enabled` line
     * being dropped later, or an environment-variable override silently disabling it. (GH #78 /
     * ISS-070 -- this field used to be declared but never enforced at any default; enforcing it at
     * its *old* default of `true` would have been a breaking change for every non-SSL deployment,
     * which is why the default moved to `false` alongside adding real enforcement.)
     */
    var requireEncryption: Boolean = false

    /**
     * Enables hostname validation (`HTTPS`-style endpoint identification) against the server
     * certificate. Applied directly on the [javax.net.ssl.SSLEngine] built for each connection
     * (see class doc) -- only takes effect when [enabled] is also true.
     */
    var hostnameVerification: Boolean = true
    var trustStorePath: String? = null
    var trustStorePassword: String? = null
    var trustStoreType: String = "JKS"
    var keyStorePath: String? = null
    var keyStorePassword: String? = null
    var keyStoreType: String = "JKS"

    /**
     * Minimum TLS protocol version accepted during the handshake. Must be one of `"TLSv1"`,
     * `"TLSv1.1"`, `"TLSv1.2"`, or `"TLSv1.3"` -- any other value throws
     * [io.kandra.core.exception.KandraSchemaException] at session-build time (only checked when
     * [enabled] is true). All protocol versions at or above this one that the JVM's SSL provider
     * actually supports are enabled; versions below it are disabled outright.
     */
    var minimumTlsVersion: String = "TLSv1.2"

    /**
     * Restricts the TLS cipher suites offered during the handshake to exactly this list, or `null`
     * (default) to use the JVM/JSSE provider's own default enabled suites. An unsupported suite
     * name throws `IllegalArgumentException` from the JSSE layer itself the first time the driver
     * opens a connection -- Kandra does not pre-validate suite names against the JVM's supported
     * list, since that list is provider- and JVM-version-dependent.
     */
    var cipherSuites: List<String>? = null
}

/**
 * Concurrency-limiting request admission control (GH #81 / ISS-073). Wires the DataStax driver's
 * own `ConcurrencyLimitingRequestThrottler` -- caps how many requests the driver will have
 * in-flight against the cluster at once, queuing additional requests up to [maxQueueSize] instead
 * of dispatching everything unbounded, and rejecting anything beyond that queue immediately with
 * `RequestThrottlingException`.
 *
 * Off by default: the driver's own default throttler (`PassThroughRequestThrottler`) never queues
 * or rejects, which is what every existing Kandra deployment already runs under -- turning this on
 * is an explicit choice a deployment makes once it wants controlled client-side backpressure
 * against a slow or overloaded cluster (compaction storms, a partial multi-DC outage, a burst of
 * application-side concurrency), rather than a behavior change that should surprise anyone who
 * hasn't touched this block.
 *
 * This only throttles at the driver/session level (`session.execute`/`executeAsync` admission).
 * It is unrelated to and does not replace `BatchEngine.inFlightCount`, which is Kandra's own
 * separate in-flight tracker used purely for graceful-shutdown draining.
 *
 * A rejection surfaces to callers as `io.kandra.core.exception.KandraThrottledException` (GH #103 /
 * ISS-090), not the raw driver `RequestThrottlingException` -- it participates in the same
 * catch-Kandra's-documented-exceptions story as every other write/read failure. It is never
 * retried by `RetryConfig`, regardless of `retryOn`: retrying a throttle rejection immediately just
 * adds another request on top of an already-overloaded throttler.
 *
 * **Interacts with `speculativeExecution`**: enabling both means each speculative retry counts as
 * an additional request toward [maxConcurrentRequests] — turning both on can cause self-inflicted
 * throttling under tail latency, since a burst of slow requests each spawn extra in-flight
 * speculative copies right when the throttle is most likely to already be near its limit. Worth
 * tuning [maxConcurrentRequests]/[maxQueueSize] up, or being conservative with speculative
 * execution's own concurrency, if both are enabled together.
 */
class ThrottleConfig {
    var enabled: Boolean = false

    /** Maximum number of requests the driver will have in flight at once, once [enabled]. */
    var maxConcurrentRequests: Int = 10_000

    /**
     * Maximum number of additional requests allowed to queue once [maxConcurrentRequests] is
     * already in flight. Requests beyond this queue are rejected immediately with
     * `RequestThrottlingException` rather than queued indefinitely.
     */
    var maxQueueSize: Int = 10_000
}

/**
 * Load balancing policy for multi-datacenter deployments. [dcAwareFailover], [allowedRemoteDcs],
 * and [FailoverConfig.onLocalDcUnavailable] are wired into the DataStax driver's own
 * `DefaultLoadBalancingPolicy` (GH #58) -- setting `dcAwareFailover = true` together with
 * `failover { onLocalDcUnavailable = FailoverPolicy.RETRY_REMOTE_DC }` genuinely enables the
 * driver's cross-DC failover mechanism, restricted to the datacenters listed in [allowedRemoteDcs].
 * Setting only one of the two leaves failover inert, by design (see [FailoverPolicy]).
 */
class LoadBalancingConfig {
    /**
     * Route queries to the token owner — avoids coordinator hop (always recommended). The driver's
     * `DefaultLoadBalancingPolicy` is token-aware unconditionally by design (there is no driver-level
     * toggle to disable it without switching to a materially different policy), so this flag
     * currently only documents the recommended posture rather than being read anywhere.
     */
    var tokenAware: Boolean = true

    /**
     * Allow the driver to use replicas in remote DCs when the local DC is unavailable. Must be
     * combined with `failover { onLocalDcUnavailable = FailoverPolicy.RETRY_REMOTE_DC }` to actually
     * take effect -- see [FailoverPolicy].
     */
    var dcAwareFailover: Boolean = false

    /**
     * DCs eligible for failover. Required (non-empty) when [dcAwareFailover] = true. Enforced as an
     * allow-list via a driver `NodeDistanceEvaluator` (GH #58) -- every other remote DC is kept at
     * `NodeDistance.IGNORED` and never connected to. The driver does not expose a priority/ordering
     * mechanism across multiple allowed remote DCs; all of them are equally eligible once enabled.
     */
    var allowedRemoteDcs: List<String> = emptyList()

    /**
     * Maximum number of remote replicas used per remote DC during failover. Wired directly to the
     * driver's `advanced.load-balancing-policy.dc-failover.max-nodes-per-remote-dc` option (GH #58)
     * whenever failover is actually enabled (see [FailoverPolicy]).
     */
    var maxRemoteNodesPerRemoteDc: Int = 1
}

enum class FailoverPolicy {
    /** Throw [com.datastax.oss.driver.api.core.NoNodeAvailableException] immediately (default). */
    THROW,
    /**
     * Enables the driver's native cross-DC failover (GH #58) — requires
     * [LoadBalancingConfig.dcAwareFailover] = true and a non-empty
     * [LoadBalancingConfig.allowedRemoteDcs]. The driver includes nodes from every DC in
     * [LoadBalancingConfig.allowedRemoteDcs] in its query plans once local-DC nodes are exhausted;
     * there is no ordering/priority across multiple allowed DCs.
     */
    RETRY_REMOTE_DC
}

class FailoverConfig {
    var onLocalDcUnavailable: FailoverPolicy = FailoverPolicy.THROW

    /**
     * Declared for future use. The driver's native dc-failover mechanism (used to implement
     * [FailoverPolicy.RETRY_REMOTE_DC], GH #58) has no artificial pre-failover delay concept —
     * once local-DC nodes are exhausted for a request, eligible remote-DC nodes are used
     * immediately — so this value is not currently read anywhere.
     */
    var remoteRetryDelayMs: Long = 50
}

/** Speculative execution reduces tail latency by firing a second request if the first is slow. */
class SpeculativeExecutionConfig {
    var enabled: Boolean = false
    var delayMillis: Long = 100
    var maxAttempts: Int = 2
}

/** Graceful shutdown drain configuration. */
class ShutdownConfig {
    /** Maximum time to wait for in-flight queries to complete before forcing session close. */
    var drainTimeoutMs: Long = 5000

    /** When true, waits for in-flight queries to drain before closing the session. */
    var graceful: Boolean = true
}

/** Metrics configuration. */
class MetricsConfig {
    var enabled: Boolean = false

    /**
     * The recorder that receives table name, operation, and duration for every query.
     * Use this to bridge into any metrics backend (Micrometer, Dropwizard, etc.).
     *
     * ```kotlin
     * metrics {
     *     enabled = true
     *     recorder = KandraMetrics { table, op, durationMs ->
     *         meterRegistry.timer("kandra.query", "table", table, "operation", op)
     *             .record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS)
     *     }
     * }
     * ```
     */
    var recorder: KandraMetrics? = null
}

/**
 * Configuration for the [Kandra] Ktor plugin.
 *
 * ```kotlin
 * install(Kandra) {
 *     contactPoints = "localhost:9042"
 *     keyspace = "coinx"
 *     localDatacenter = "datacenter1"
 *     autoCreateKeyspace = true
 *     schemaMode = SchemaMode.AUTO_CREATE
 *     register(User::class, Wallet::class)
 *     pool { requestTimeoutMillis = 10_000 }
 *     auth { provider = KandraAuth.fromEnv() }
 *     retry { maxAttempts = 5 }
 *     debug { logQueries = true; logSlowQueriesMs = 500 }
 *     validate<User> { user ->
 *         buildList { if (user.email.isBlank()) add(KandraValidationError("email", "cannot be blank")) }
 *     }
 * }
 * ```
 */
@OptIn(ExperimentalKandraApi::class)
class KandraConfig {
    var contactPoints: String = "localhost:9042"
    var keyspace: String = ""
    var localDatacenter: String = "datacenter1"

    var autoCreateKeyspace: Boolean = false
    var replicationStrategy: ReplicationStrategy = ReplicationStrategy.SimpleStrategy(replicationFactor = 1)

    var schemaMode: SchemaMode = SchemaMode.AUTO_CREATE

    /** When true, validates keyspace permissions at startup (SELECT + MODIFY required). */
    var validatePermissions: Boolean = true

    var preparedStatementCacheSize: Int = 1000

    /** Log WARN when deleteBy/deleteAll would generate more than this many tombstones. */
    var tombstoneWarnThreshold: Int = 1000

    /** Batch warn threshold in KB — logs WARN when a batch exceeds this size estimate. */
    var batchWarnThresholdKb: Int = 5

    /** Maximum statements per batch chunk when auto-chunking. */
    var batchMaxChunkSize: Int = 100

    /** When true, large batches are automatically split into chunks of [batchMaxChunkSize]. */
    var batchAutoChunk: Boolean = true

    /**
     * When true, registers a `/kandra/health` route (and Ktor HealthCheck integration if
     * available).
     *
     * This route is unauthenticated by design — Kandra has no notion of application-level auth to
     * gate it with — so if it's reachable outside a private network, put it behind network-level
     * access control (e.g. only allow your orchestrator's internal probe network to reach it).
     */
    var healthCheck: Boolean = true

    /**
     * How long (in milliseconds) a `/kandra/health` result is cached before the next request
     * triggers a fresh `SELECT release_version FROM system.local` against the cluster (GH-36).
     * Without this, a probe storm (misconfigured monitoring, or deliberate abuse if the route is
     * reachable beyond an orchestrator's internal network) translates 1:1 into real cluster
     * queries. Set to `0` to disable caching and query on every request.
     */
    var healthCheckCacheTtlMs: Long = 500

    val pool: PoolConfig = PoolConfig()
    val retry: RetryConfig = RetryConfig()
    val debug: DebugConfig = DebugConfig()
    val codec: KandraCodec = KandraCodec()
    val consistency: ConsistencyConfig = ConsistencyConfig()
    val auth: AuthConfig = AuthConfig()
    val ssl: SslConfig = SslConfig()
    val loadBalancing: LoadBalancingConfig = LoadBalancingConfig()
    val failover: FailoverConfig = FailoverConfig()
    val speculativeExecution: SpeculativeExecutionConfig = SpeculativeExecutionConfig()
    val shutdown: ShutdownConfig = ShutdownConfig()
    val metrics: MetricsConfig = MetricsConfig()
    val throttle: ThrottleConfig = ThrottleConfig()

    var eventListener: KandraEventListener? = null

    internal val entities = mutableListOf<KClass<*>>()
    internal val validators = mutableMapOf<KClass<*>, KandraValidator<*>>()

    fun register(vararg classes: KClass<*>) { entities.addAll(classes) }

    fun pool(block: PoolConfig.() -> Unit) { pool.block() }
    fun retry(block: RetryConfig.() -> Unit) { retry.block() }
    fun debug(block: DebugConfig.() -> Unit) { debug.block() }
    fun consistency(block: ConsistencyConfig.() -> Unit) { consistency.block() }
    fun auth(block: AuthConfig.() -> Unit) { auth.block() }
    fun ssl(block: SslConfig.() -> Unit) { ssl.block() }
    fun loadBalancing(block: LoadBalancingConfig.() -> Unit) { loadBalancing.block() }
    fun failover(block: FailoverConfig.() -> Unit) { failover.block() }
    fun speculativeExecution(block: SpeculativeExecutionConfig.() -> Unit) { speculativeExecution.block() }
    fun shutdown(block: ShutdownConfig.() -> Unit) { shutdown.block() }
    fun metrics(block: MetricsConfig.() -> Unit) { metrics.block() }
    fun throttle(block: ThrottleConfig.() -> Unit) { throttle.block() }

    fun <T : Any> validate(klass: KClass<T>, validator: KandraValidator<T>) {
        validators[klass] = validator
    }

    inline fun <reified T : Any> validate(noinline validator: (T) -> List<KandraValidationError>) {
        validate(T::class, KandraValidator { validator(it) })
    }
}
