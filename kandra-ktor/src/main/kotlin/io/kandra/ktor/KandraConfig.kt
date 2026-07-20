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
    var localRequestsPerConnection: Int = 1024
    var maxRequestsPerConnection: Int = 32768
    var heartbeatIntervalSeconds: Int = 30
    /** How long to wait for a CQL query response. Default is 5 000 ms (driver default is 2 000 ms). */
    var requestTimeoutMillis: Long = 5000
    /** How long to wait when establishing a TCP connection to a ScyllaDB node. Default is 5 000 ms. */
    var connectionTimeoutMillis: Long = 5000
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
 */
class SslConfig {
    var enabled: Boolean = false
    var requireEncryption: Boolean = true
    var hostnameVerification: Boolean = true
    var trustStorePath: String? = null
    var trustStorePassword: String? = null
    var trustStoreType: String = "JKS"
    var keyStorePath: String? = null
    var keyStorePassword: String? = null
    var keyStoreType: String = "JKS"
    var minimumTlsVersion: String = "TLSv1.2"
    var cipherSuites: List<String>? = null
}

/** Load balancing policy for multi-datacenter deployments. */
class LoadBalancingConfig {
    /** Route queries to the token owner — avoids coordinator hop (always recommended). */
    var tokenAware: Boolean = true

    /** Allow the driver to use replicas in remote DCs when the local DC is unavailable. */
    var dcAwareFailover: Boolean = false

    /** DCs to fail over to, in priority order. Required when [dcAwareFailover] = true. */
    var allowedRemoteDcs: List<String> = emptyList()

    /** Maximum number of remote replicas used per remote DC during failover. */
    var maxRemoteNodesPerRemoteDc: Int = 1
}

enum class FailoverPolicy {
    /** Throw [com.datastax.oss.driver.api.core.NoNodeAvailableException] immediately (default). */
    THROW,
    /** Retry against [LoadBalancingConfig.allowedRemoteDcs] in order. */
    RETRY_REMOTE_DC
}

class FailoverConfig {
    var onLocalDcUnavailable: FailoverPolicy = FailoverPolicy.THROW
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

    /** When true, registers a `/kandra/health` route (and Ktor HealthCheck integration if available). */
    var healthCheck: Boolean = true

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

    fun <T : Any> validate(klass: KClass<T>, validator: KandraValidator<T>) {
        validators[klass] = validator
    }

    inline fun <reified T : Any> validate(noinline validator: (T) -> List<KandraValidationError>) {
        validate(T::class, KandraValidator { validator(it) })
    }
}
