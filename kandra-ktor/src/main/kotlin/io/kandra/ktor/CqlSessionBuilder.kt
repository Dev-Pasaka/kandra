package io.kandra.ktor

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.config.DefaultDriverOption
import com.datastax.oss.driver.api.core.config.DriverConfigLoader
import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.exception.KandraAuthException
import io.kandra.core.exception.KandraQueryException
import java.io.FileInputStream
import java.net.InetSocketAddress
import java.time.Duration
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import java.security.KeyStore

@OptIn(ExperimentalKandraApi::class)
internal fun buildCqlSession(config: KandraConfig, withKeyspace: Boolean = true): CqlSession {
    // Validate failover config
    if (config.loadBalancing.dcAwareFailover && config.loadBalancing.allowedRemoteDcs.isEmpty()) {
        throw io.kandra.core.exception.KandraSchemaException(
            "loadBalancing.dcAwareFailover = true but allowedRemoteDcs is empty. " +
            "Provide at least one remote DC or set dcAwareFailover = false."
        )
    }
    if (config.failover.onLocalDcUnavailable == FailoverPolicy.RETRY_REMOTE_DC &&
        config.loadBalancing.allowedRemoteDcs.isEmpty()
    ) {
        throw io.kandra.core.exception.KandraSchemaException(
            "failover.onLocalDcUnavailable = RETRY_REMOTE_DC but loadBalancing.allowedRemoteDcs is empty."
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

    if (withKeyspace && config.keyspace.isNotBlank()) {
        builder.withKeyspace(config.keyspace)
    }

    // Auth credentials
    try {
        val creds = config.auth.provider.getCredentials()
        if (creds.username.isNotBlank()) {
            builder.withAuthCredentials(creds.username, creds.password)
        }
    } catch (e: KandraAuthException) {
        throw e
    } catch (e: Exception) {
        throw KandraAuthException("Failed to retrieve credentials from auth provider: ${e.message}", e)
    }

    // SSL/TLS
    if (config.ssl.enabled) {
        try {
            builder.withSslContext(buildSslContext(config.ssl))
        } catch (e: KandraAuthException) {
            throw e
        } catch (e: Exception) {
            throw KandraAuthException("Failed to build SSL context: ${e.message}", e)
        }
    }

    return try {
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
}

private fun buildDriverConfig(config: KandraConfig): DriverConfigLoader {
    val builder = DriverConfigLoader.programmaticBuilder()
        .withInt(DefaultDriverOption.CONNECTION_MAX_REQUESTS, config.pool.maxRequestsPerConnection)
        .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofMillis(config.pool.requestTimeoutMillis))
        .withDuration(DefaultDriverOption.CONNECTION_CONNECT_TIMEOUT, Duration.ofMillis(config.pool.connectionTimeoutMillis))
        .withDuration(DefaultDriverOption.HEARTBEAT_INTERVAL, Duration.ofSeconds(config.pool.heartbeatIntervalSeconds.toLong()))

    if (config.ssl.enabled) {
        builder.withBoolean(DefaultDriverOption.SSL_HOSTNAME_VALIDATION, config.ssl.hostnameVerification)
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

internal fun keyspaceDdl(keyspace: String, strategy: ReplicationStrategy): String = when (strategy) {
    is ReplicationStrategy.SimpleStrategy ->
        "CREATE KEYSPACE IF NOT EXISTS $keyspace WITH replication = " +
            "{'class': 'SimpleStrategy', 'replication_factor': ${strategy.replicationFactor}}"
    is ReplicationStrategy.NetworkTopologyStrategy -> {
        val dcMap = strategy.dcReplicationMap.entries.joinToString(", ") { (dc, rf) -> "'$dc': $rf" }
        "CREATE KEYSPACE IF NOT EXISTS $keyspace WITH replication = " +
            "{'class': 'NetworkTopologyStrategy', $dcMap}"
    }
}
