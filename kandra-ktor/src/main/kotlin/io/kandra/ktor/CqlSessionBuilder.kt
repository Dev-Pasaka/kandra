package io.kandra.ktor

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.auth.ProgrammaticPlainTextAuthProvider
import com.datastax.oss.driver.api.core.config.DefaultDriverOption
import com.datastax.oss.driver.api.core.config.DriverConfigLoader
import com.datastax.oss.driver.api.core.loadbalancing.NodeDistance
import com.datastax.oss.driver.api.core.loadbalancing.NodeDistanceEvaluator
import com.datastax.oss.driver.api.core.metadata.EndPoint
import com.datastax.oss.driver.api.core.metadata.Node
import com.datastax.oss.driver.api.core.ssl.SslEngineFactory
import com.datastax.oss.driver.internal.core.loadbalancing.DefaultLoadBalancingPolicy
import com.datastax.oss.driver.internal.core.session.throttling.ConcurrencyLimitingRequestThrottler
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kandra.core.CqlNaming
import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.exception.KandraAuthException
import io.kandra.core.exception.KandraQueryException
import io.kandra.core.exception.KandraSchemaException
import java.io.FileInputStream
import java.net.InetSocketAddress
import java.time.Duration
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLParameters
import javax.net.ssl.TrustManagerFactory
import java.security.KeyStore

private val logger = KotlinLogging.logger {}

/**
 * Result of [buildCqlSession]: the live [CqlSession] plus, when the session was built with
 * non-blank credentials, the [ProgrammaticPlainTextAuthProvider] backing it. Kandra.kt's
 * credential-rotation loop (GH #61) holds onto [liveAuthProvider] and calls
 * `setUsername`/`setPassword` on it when the configured [io.kandra.core.KandraAuthProvider]
 * returns refreshed credentials -- the driver picks up the new values on every subsequent
 * authentication (new connections, reconnects), without a session rebuild.
 *
 * [liveAuthProvider] is `null` when the session was opened without auth at all (the configured
 * provider returned a blank username at startup, e.g. against a cluster with `AllowAllAuthenticator`)
 * -- there is no live auth to rotate in that case.
 */
internal class CqlSessionHandle(
    val session: CqlSession,
    val liveAuthProvider: ProgrammaticPlainTextAuthProvider?
)

/**
 * Restricts driver-native cross-DC failover (GH #58) to exactly the datacenters listed in
 * [LoadBalancingConfig.allowedRemoteDcs], in addition to the local datacenter.
 *
 * The DataStax driver's own `DefaultLoadBalancingPolicy` (via `BasicLoadBalancingPolicy`) already
 * supports genuine cross-DC failover through the
 * `advanced.load-balancing-policy.dc-failover.max-nodes-per-remote-dc` option -- but once that's
 * greater than zero, the driver considers nodes in *every* remote datacenter uniformly, with no
 * concept of an allow-list of its own. This evaluator is registered via
 * `CqlSessionBuilder.withNodeDistanceEvaluator(...)` to fill that gap: any node whose datacenter
 * isn't the local one and isn't in [allowedRemoteDcs] is forced to [NodeDistance.IGNORED] (the
 * driver will never open a connection to it), while the local DC and any allowed remote DC defer
 * to the policy's own default distance computation (`null` return).
 */
internal class AllowedDcNodeDistanceEvaluator(
    private val localDatacenter: String,
    private val allowedRemoteDcs: Set<String>
) : NodeDistanceEvaluator {
    override fun evaluateDistance(node: Node, currentLocalDc: String?): NodeDistance? =
        evaluate(node.datacenter)

    /** Pure decision logic, split out so it's unit-testable without a real driver [Node]. */
    internal fun evaluate(nodeDatacenter: String?): NodeDistance? = when {
        nodeDatacenter == null -> null
        nodeDatacenter == localDatacenter -> null
        nodeDatacenter in allowedRemoteDcs -> null
        else -> NodeDistance.IGNORED
    }
}

/**
 * Kandra's own [SslEngineFactory] (GH #78 / ISS-070), replacing the driver's own
 * `ProgrammaticSslEngineFactory` -- the class `CqlSession.builder().withSslContext(sslContext)`
 * wraps things into internally, with no cipher-suite restriction and no hostname validation
 * regardless of driver config. An [SSLContext] alone has no place to express a minimum protocol
 * version either; that's a per-[SSLEngine] [SSLParameters] concern applied on every connection.
 * This factory applies [SslConfig.minimumTlsVersion], [SslConfig.cipherSuites], and
 * [SslConfig.hostnameVerification] to every [SSLEngine] the driver creates.
 */
internal class KandraSslEngineFactory(
    private val sslContext: SSLContext,
    private val minimumTlsVersion: String,
    private val cipherSuites: List<String>?,
    private val hostnameVerification: Boolean
) : SslEngineFactory {

    override fun newSslEngine(remoteEndpoint: EndPoint): SSLEngine {
        val remoteAddress = remoteEndpoint.resolve()
        val engine = if (remoteAddress is InetSocketAddress) {
            // GH #105: `.hostName` performs a blocking reverse-DNS lookup whenever the address was
            // constructed from a raw IP (exactly how the driver builds EndPoints for peers discovered
            // via gossip/system.peers -- no hostname, just the IP), which (a) blocks the driver's I/O
            // thread on every new connection to a newly-discovered peer, and (b) falls back to the IP
            // string when reverse DNS isn't configured for cluster nodes, which then fails HTTPS
            // endpoint identification against a DNS-named cert with no IP SANs. `.hostString` never
            // triggers a reverse lookup -- it returns the original hostname/IP literal the address was
            // constructed with. This matches the driver's own DefaultSslEngineFactory.
            sslContext.createSSLEngine(remoteAddress.hostString, remoteAddress.port)
        } else {
            sslContext.createSSLEngine()
        }
        engine.useClientMode = true

        // GH #105: resolveEnabledProtocols throws when the intersection of JVM/provider-supported
        // protocols and "at least minimumTlsVersion" is empty, rather than silently leaving the
        // engine's own (unrestricted) default protocol set in place -- a compliance-motivated
        // minimumTlsVersion floor must fail loudly, not silently downgrade the security posture it
        // exists to guard.
        engine.enabledProtocols = resolveEnabledProtocols(engine.supportedProtocols.toSet(), minimumTlsVersion)
            .toTypedArray()

        if (cipherSuites != null) {
            engine.enabledCipherSuites = cipherSuites.toTypedArray()
        }

        if (hostnameVerification) {
            val parameters: SSLParameters = engine.sslParameters
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            engine.sslParameters = parameters
        }

        return engine
    }

    override fun close() {
        // Nothing to release here -- the KeyStore/TrustManagerFactory handles used to build
        // sslContext in buildSslContext() are already closed by the time this factory exists.
    }

    internal companion object {
        /** Ascending TLS protocol order -- index position doubles as a comparable "floor" rank. */
        internal val TLS_PROTOCOL_ORDER = listOf("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3")

        /**
         * True when [protocol] is at or above [minimum] in [TLS_PROTOCOL_ORDER]'s ascending order.
         * [minimum] is validated against [TLS_PROTOCOL_ORDER] up front in [buildCqlSession], so by
         * the time this runs it is always a recognized value; an unrecognized [protocol] (e.g.
         * `"SSLv3"`, which intentionally isn't in [TLS_PROTOCOL_ORDER]) is never considered "at
         * least" anything and is excluded.
         */
        internal fun protocolAtLeast(protocol: String, minimum: String): Boolean {
            val protocolRank = TLS_PROTOCOL_ORDER.indexOf(protocol)
            val minimumRank = TLS_PROTOCOL_ORDER.indexOf(minimum)
            if (protocolRank == -1 || minimumRank == -1) return false
            return protocolRank >= minimumRank
        }

        /**
         * Intersects [supportedProtocols] (whatever the JSSE provider actually supports on this
         * JVM) with "at least [minimumTlsVersion]", in [TLS_PROTOCOL_ORDER]'s ascending order.
         *
         * GH #105: an earlier version left `engine.enabledProtocols` at the JSSE provider's own
         * default set whenever this intersection came back empty (e.g. `minimumTlsVersion =
         * "TLSv1.3"` configured against a JVM/provider that only supports up to TLSv1.2) -- silently
         * downgrading the security posture the configured floor exists to enforce, with nothing
         * logged. Now this fails loudly instead: logs an ERROR naming the requested floor and the
         * actually-supported protocols, then throws [KandraSchemaException], matching the
         * fail-closed philosophy `ssl.requireEncryption` already applies elsewhere in this file.
         */
        internal fun resolveEnabledProtocols(supportedProtocols: Set<String>, minimumTlsVersion: String): List<String> {
            val enabledProtocols = TLS_PROTOCOL_ORDER.filter {
                it in supportedProtocols && protocolAtLeast(it, minimumTlsVersion)
            }
            if (enabledProtocols.isEmpty()) {
                logger.error {
                    "Kandra: ssl.minimumTlsVersion = '$minimumTlsVersion' is not supported by this " +
                        "JVM/TLS provider. Supported protocols: $supportedProtocols. Refusing to fall " +
                        "back to the provider's unrestricted default protocol set."
                }
                throw KandraSchemaException(
                    "ssl.minimumTlsVersion = '$minimumTlsVersion' is not supported by this JVM/TLS " +
                        "provider. Supported protocols: $supportedProtocols. Lower minimumTlsVersion to " +
                        "a supported protocol or upgrade the JVM/TLS provider."
                )
            }
            return enabledProtocols
        }
    }
}

@OptIn(ExperimentalKandraApi::class)
internal fun buildCqlSession(config: KandraConfig, withKeyspace: Boolean = true): CqlSessionHandle {
    // Validate failover config
    if (config.loadBalancing.dcAwareFailover && config.loadBalancing.allowedRemoteDcs.isEmpty()) {
        throw KandraSchemaException(
            "loadBalancing.dcAwareFailover = true but allowedRemoteDcs is empty. " +
            "Provide at least one remote DC or set dcAwareFailover = false."
        )
    }
    if (config.failover.onLocalDcUnavailable == FailoverPolicy.RETRY_REMOTE_DC &&
        config.loadBalancing.allowedRemoteDcs.isEmpty()
    ) {
        throw KandraSchemaException(
            "failover.onLocalDcUnavailable = RETRY_REMOTE_DC but loadBalancing.allowedRemoteDcs is empty."
        )
    }

    // GH #78 / ISS-070: requireEncryption is a hard gate, checked before any connection attempt --
    // see SslConfig.requireEncryption's doc comment for why the default is `false` rather than `true`.
    if (config.ssl.requireEncryption && !config.ssl.enabled) {
        throw KandraSchemaException(
            "ssl.requireEncryption = true but ssl.enabled = false. Enable SSL (ssl { enabled = true; " +
            "trustStorePath = \"...\" }) or set ssl.requireEncryption = false."
        )
    }
    if (config.ssl.enabled && config.ssl.minimumTlsVersion !in KandraSslEngineFactory.TLS_PROTOCOL_ORDER) {
        throw KandraSchemaException(
            "ssl.minimumTlsVersion = '${config.ssl.minimumTlsVersion}' is not one of " +
            "${KandraSslEngineFactory.TLS_PROTOCOL_ORDER}."
        )
    }

    // GH #105 / ISS-072 follow-up: pool.localPoolSize/remotePoolSize previously accepted 0 or
    // negative values with no validation. A localPoolSize of 0 means the driver opens no
    // connections to the local DC at all -- every request fails once the session is live, but this
    // didn't surface at config-build time; it surfaced later as a request-level
    // NoNodeAvailableException, disguising a config typo as a cluster-health problem. Validated
    // eagerly here, alongside the other config-shape checks in this function, so it fails at
    // startup instead.
    if (config.pool.localPoolSize < 1) {
        throw KandraSchemaException(
            "pool.localPoolSize = ${config.pool.localPoolSize} but must be >= 1. A pool size of 0 " +
            "means the driver opens no connections to the local datacenter, and every request " +
            "would fail once the session is live."
        )
    }
    if (config.pool.remotePoolSize < 1) {
        throw KandraSchemaException(
            "pool.remotePoolSize = ${config.pool.remotePoolSize} but must be >= 1. A pool size of " +
            "0 means the driver opens no connections to remote datacenters, defeating " +
            "loadBalancing/failover configuration that relies on them."
        )
    }

    val configLoader = buildDriverConfig(config)

    val builder = CqlSession.builder()
        .addContactPoints(
            config.contactPoints.split(",").map { entry ->
                val trimmed = entry.trim()
                val lastColon = trimmed.lastIndexOf(':')
                val host = if (lastColon > 0) trimmed.substring(0, lastColon) else trimmed
                val port = if (lastColon > 0) trimmed.substring(lastColon + 1).toInt() else 9042
                InetSocketAddress(host, port)
            }
        )
        .withLocalDatacenter(config.localDatacenter)
        .withConfigLoader(configLoader)

    // Multi-DC failover (GH #58): only restrict eligible remote DCs once the driver-native
    // dc-failover mechanism is actually enabled below (buildDriverConfig) -- i.e. both
    // dcAwareFailover and failover.onLocalDcUnavailable = RETRY_REMOTE_DC are set, matching the
    // documented "two knobs must be set together" contract validated above.
    val dcFailoverEnabled = config.loadBalancing.dcAwareFailover &&
        config.failover.onLocalDcUnavailable == FailoverPolicy.RETRY_REMOTE_DC
    if (dcFailoverEnabled) {
        builder.withNodeDistanceEvaluator(
            AllowedDcNodeDistanceEvaluator(
                localDatacenter = config.localDatacenter,
                allowedRemoteDcs = config.loadBalancing.allowedRemoteDcs.toSet()
            )
        )
    }

    if (withKeyspace && config.keyspace.isNotBlank()) {
        builder.withKeyspace(config.keyspace)
    }

    // Auth credentials -- built as a ProgrammaticPlainTextAuthProvider (rather than the simpler
    // `withAuthCredentials(...)`) so the reference can be retained and mutated later for live
    // credential rotation (GH #61). Skipped entirely when the provider returns a blank username,
    // matching the previous behavior of not attempting auth against a cluster that doesn't require it.
    var liveAuthProvider: ProgrammaticPlainTextAuthProvider? = null
    try {
        val creds = config.auth.provider.getCredentials()
        if (creds.username.isNotBlank()) {
            val provider = ProgrammaticPlainTextAuthProvider(creds.username, creds.password)
            builder.withAuthProvider(provider)
            liveAuthProvider = provider
        } else {
            // GH #107: this used to be a silent no-op -- against a cluster with
            // AllowAllAuthenticator, a misconfigured KandraAuthProvider (or an env var explicitly
            // set to "" instead of left unset -- KandraAuth.fromEnv() only throws on null, not
            // blank) put the deployment into permanent no-auth mode with zero startup signal. The
            // only place this was ever surfaced was the credential-rotation WARN branch in
            // Kandra.kt, which only exists when auth.refreshIntervalSeconds is configured (not the
            // default). Logged unconditionally here instead, independent of rotation config.
            logger.warn {
                "Kandra: auth.provider returned a blank username -- opening the ScyllaDB session " +
                    "WITHOUT authentication. This is only safe against a cluster configured with " +
                    "AllowAllAuthenticator. If this is unintentional, check auth.provider / the " +
                    "environment variables it reads from (an env var set to an empty string is " +
                    "indistinguishable from a real blank username here)."
            }
        }
    } catch (e: KandraAuthException) {
        throw e
    } catch (e: Exception) {
        throw KandraAuthException("Failed to retrieve credentials from auth provider: ${e.message}", e)
    }

    // SSL/TLS -- routed through KandraSslEngineFactory rather than the driver's own
    // `withSslContext(sslContext)` convenience method, so minimumTlsVersion/cipherSuites/
    // hostnameVerification are actually applied per connection (GH #78 / ISS-070; see
    // KandraSslEngineFactory's doc comment for why `withSslContext` alone can't do this).
    if (config.ssl.enabled) {
        try {
            val sslContext = buildSslContext(config.ssl)
            builder.withSslEngineFactory(
                KandraSslEngineFactory(
                    sslContext = sslContext,
                    minimumTlsVersion = config.ssl.minimumTlsVersion,
                    cipherSuites = config.ssl.cipherSuites,
                    hostnameVerification = config.ssl.hostnameVerification
                )
            )
        } catch (e: KandraAuthException) {
            throw e
        } catch (e: Exception) {
            throw KandraAuthException("Failed to build SSL context: ${e.message}", e)
        }
    }

    val session = try {
        builder.build()
    } catch (e: com.datastax.oss.driver.api.core.AllNodesFailedException) {
        val authErrors = e.errors.values
            .filterIsInstance<com.datastax.oss.driver.api.core.auth.AuthenticationException>()
        if (authErrors.isNotEmpty()) {
            throw KandraAuthException(
                "ScyllaDB authentication failed. Check credentials or certificate config. " +
                "Contact point: ${e.errors.keys.firstOrNull()}",
                authErrors.first()
            )
        }
        throw KandraQueryException("Failed to connect to ScyllaDB: ${e.message}", e)
    } catch (e: com.datastax.oss.driver.api.core.auth.AuthenticationException) {
        throw KandraAuthException("ScyllaDB authentication failed: ${e.message}", e)
    }

    return CqlSessionHandle(session, liveAuthProvider)
}

internal fun buildDriverConfig(config: KandraConfig): DriverConfigLoader {
    val builder = DriverConfigLoader.programmaticBuilder()
        .withInt(DefaultDriverOption.CONNECTION_MAX_REQUESTS, config.pool.maxRequestsPerConnection)
        .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofMillis(config.pool.requestTimeoutMillis))
        .withDuration(DefaultDriverOption.CONNECTION_CONNECT_TIMEOUT, Duration.ofMillis(config.pool.connectionTimeoutMillis))
        .withDuration(DefaultDriverOption.HEARTBEAT_INTERVAL, Duration.ofSeconds(config.pool.heartbeatIntervalSeconds.toLong()))
        // GH #80 / ISS-072: pool *size* (distinct from per-connection request limits above), still
        // left at the driver's own default (1) unless overridden via pool { localPoolSize = ...;
        // remotePoolSize = ... }.
        .withInt(DefaultDriverOption.CONNECTION_POOL_LOCAL_SIZE, config.pool.localPoolSize)
        .withInt(DefaultDriverOption.CONNECTION_POOL_REMOTE_SIZE, config.pool.remotePoolSize)
        // Explicit, rather than relying on this being the driver's own default -- makes the link
        // between loadBalancing/failover config below and actual driver routing behavior grep-able
        // (GH #58: previously nothing in this file ever called .withLoadBalancingPolicy(...) or set
        // LOAD_BALANCING_POLICY_CLASS at all).
        .withClass(DefaultDriverOption.LOAD_BALANCING_POLICY_CLASS, DefaultLoadBalancingPolicy::class.java)

    // Note: SSL_HOSTNAME_VALIDATION is deliberately NOT set here. That option is only ever read by
    // the driver's own DefaultSslEngineFactory (activated via the `advanced.ssl-engine-factory`
    // config section) -- it has zero effect on a session built via
    // `CqlSessionBuilder.withSslEngineFactory(...)`/`withSslContext(...)`, which is what
    // buildCqlSession uses below. ssl.hostnameVerification is applied directly inside
    // KandraSslEngineFactory instead (GH #78 / ISS-070); setting this option here in addition would
    // just be a second, misleading source of truth that the driver silently ignores.

    // GH #81 / ISS-073: concurrency-limiting request throttler (backpressure/admission control).
    // Off by default -- the driver's own default throttler (PassThroughRequestThrottler) never
    // queues or rejects, so an unconfigured `throttle { }` block changes nothing.
    if (config.throttle.enabled) {
        builder
            .withClass(DefaultDriverOption.REQUEST_THROTTLER_CLASS, ConcurrencyLimitingRequestThrottler::class.java)
            .withInt(DefaultDriverOption.REQUEST_THROTTLER_MAX_CONCURRENT_REQUESTS, config.throttle.maxConcurrentRequests)
            .withInt(DefaultDriverOption.REQUEST_THROTTLER_MAX_QUEUE_SIZE, config.throttle.maxQueueSize)
    }

    // Multi-DC failover (GH #58). The driver's DefaultLoadBalancingPolicy natively supports
    // cross-DC failover via these two advanced options; previously neither was ever set here, so
    // `loadBalancing.dcAwareFailover = true` + `failover.onLocalDcUnavailable = RETRY_REMOTE_DC`
    // were validated at startup (buildCqlSession, above) but had zero effect on request routing --
    // a real local-DC outage behaved identically to FailoverPolicy.THROW. `allowedRemoteDcs` itself
    // is enforced by the AllowedDcNodeDistanceEvaluator registered in buildCqlSession, since the
    // driver's dc-failover options alone apply to every remote DC uniformly.
    //
    // allow-for-local-consistency-levels is forced true whenever failover is enabled: Kandra's own
    // consistency defaults (LOCAL_ONE/LOCAL_QUORUM/LOCAL_SERIAL) are all "local" levels, so leaving
    // this at the driver's own default (false) would make the failover configured above a no-op for
    // the vast majority of Kandra's traffic.
    if (config.loadBalancing.dcAwareFailover &&
        config.failover.onLocalDcUnavailable == FailoverPolicy.RETRY_REMOTE_DC
    ) {
        builder
            .withInt(
                DefaultDriverOption.LOAD_BALANCING_DC_FAILOVER_MAX_NODES_PER_REMOTE_DC,
                config.loadBalancing.maxRemoteNodesPerRemoteDc
            )
            .withBoolean(
                DefaultDriverOption.LOAD_BALANCING_DC_FAILOVER_ALLOW_FOR_LOCAL_CONSISTENCY_LEVELS,
                true
            )
    }

    if (config.speculativeExecution.enabled) {
        builder
            .withClass(
                DefaultDriverOption.SPECULATIVE_EXECUTION_POLICY_CLASS,
                com.datastax.oss.driver.internal.core.specex.ConstantSpeculativeExecutionPolicy::class.java
            )
            .withLong(
                DefaultDriverOption.SPECULATIVE_EXECUTION_DELAY,
                config.speculativeExecution.delayMillis
            )
            .withInt(
                DefaultDriverOption.SPECULATIVE_EXECUTION_MAX,
                config.speculativeExecution.maxAttempts - 1
            )
    }

    return builder.build()
}

@OptIn(ExperimentalKandraApi::class)
private fun buildSslContext(ssl: SslConfig): SSLContext {
    val trustManagerFactory = ssl.trustStorePath?.let { path ->
        try {
            val trustStore = KeyStore.getInstance(ssl.trustStoreType)
            FileInputStream(path).use { stream ->
                trustStore.load(stream, ssl.trustStorePassword?.toCharArray())
            }
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).also {
                it.init(trustStore)
            }
        } catch (e: Exception) {
            throw KandraAuthException("Failed to load trust store from '$path': ${e.message}", e)
        }
    }

    val keyManagerFactory = ssl.keyStorePath?.let { path ->
        try {
            val keyStore = KeyStore.getInstance(ssl.keyStoreType)
            FileInputStream(path).use { stream ->
                keyStore.load(stream, ssl.keyStorePassword?.toCharArray())
            }
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).also {
                it.init(keyStore, ssl.keyStorePassword?.toCharArray())
            }
        } catch (e: Exception) {
            throw KandraAuthException("Failed to load key store from '$path': ${e.message}", e)
        }
    }

    return SSLContext.getInstance("TLS").also {
        it.init(
            keyManagerFactory?.keyManagers,
            trustManagerFactory?.trustManagers,
            null
        )
    }
}

/**
 * Renders the `CREATE KEYSPACE IF NOT EXISTS` DDL for [keyspace]/[strategy]. Both [keyspace] and,
 * for [ReplicationStrategy.NetworkTopologyStrategy], every key of `dcReplicationMap` are validated
 * as CQL identifiers (GH #65) before being spliced into the statement -- the same
 * `CqlNaming.isValidIdentifier` check `SchemaRegistry` applies to table/column names. `keyspace` is
 * already validated once at plugin-install time (`Kandra.kt`); this second check is defense in
 * depth for any other caller of this internal function.
 */
internal fun keyspaceDdl(keyspace: String, strategy: ReplicationStrategy): String {
    if (!CqlNaming.isValidIdentifier(keyspace)) {
        throw KandraSchemaException(
            "Kandra: keyspace '$keyspace' is not a valid CQL identifier. Identifiers must start " +
            "with a letter or underscore and contain only letters, digits, and underscores."
        )
    }
    return when (strategy) {
        is ReplicationStrategy.SimpleStrategy ->
            "CREATE KEYSPACE IF NOT EXISTS $keyspace WITH replication = " +
                "{'class': 'SimpleStrategy', 'replication_factor': ${strategy.replicationFactor}}"
        is ReplicationStrategy.NetworkTopologyStrategy -> {
            strategy.dcReplicationMap.keys.forEach { dc ->
                if (!CqlNaming.isValidIdentifier(dc)) {
                    throw KandraSchemaException(
                        "Kandra: replicationStrategy datacenter name '$dc' is not a valid CQL " +
                        "identifier. Identifiers must start with a letter or underscore and contain " +
                        "only letters, digits, and underscores."
                    )
                }
            }
            val dcMap = strategy.dcReplicationMap.entries.joinToString(", ") { (dc, rf) -> "'$dc': $rf" }
            "CREATE KEYSPACE IF NOT EXISTS $keyspace WITH replication = " +
                "{'class': 'NetworkTopologyStrategy', $dcMap}"
        }
    }
}
