package io.kandra.test

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.CqlSessionBuilder
import com.datastax.oss.driver.api.core.config.DefaultDriverOption
import com.datastax.oss.driver.api.core.config.DriverConfigLoader
import java.time.Duration
import java.util.UUID

/**
 * Connection provider for live-cluster tests: one abstraction over the shared Testcontainers
 * Cassandra ([KandraTestcontainers]) and the env-driven Scylla Cloud cluster ([KandraCloud]).
 *
 * Selected by `KANDRA_TEST_BACKEND`: unset / `docker` (default, behaviour unchanged) or `cloud`
 * (requires the `KANDRA_CLOUD_*` env, see [KandraCloud]; TLS via `KANDRA_CLOUD_TLS=true`).
 *
 * Tests read connection facts from here instead of `KandraTestcontainers.container`:
 * ```kotlin
 * install(Kandra) {
 *     contactPoints = KandraTestBackend.contactPointsCsv
 *     localDatacenter = KandraTestBackend.localDatacenter
 *     keyspace = KandraTestBackend.keyspaceName("ktor")
 * }
 * KandraTestBackend.newSession().use { it.execute("DROP KEYSPACE IF EXISTS $ks") }
 * ```
 * All sessions built here use a 30s request timeout (the driver's 2s default is too tight for DDL against a
 * freshly started container under load, and for WAN latency to the cloud cluster).
 */
object KandraTestBackend {

    /** True when `KANDRA_TEST_BACKEND=cloud`. Fails fast if the cloud env is not configured. */
    val isCloud: Boolean by lazy {
        val cloud = System.getenv("KANDRA_TEST_BACKEND")?.lowercase() == "cloud"
        if (cloud) check(KandraCloud.isConfigured) { "KANDRA_TEST_BACKEND=cloud but KANDRA_CLOUD_CONTACT_POINTS is unset" }
        cloud
    }

    /** Contact points as a `host:port` CSV, ready for Kandra's `contactPoints` config string. */
    val contactPointsCsv: String
        get() = if (isCloud) KandraCloud.contactPointsCsv()
        else KandraTestcontainers.container.contactPoint.let { "${it.hostString}:${it.port}" }

    val localDatacenter: String
        get() = if (isCloud) KandraCloud.localDc else KandraTestcontainers.container.localDatacenter

    /** Auth credentials (never log). Docker's container runs AllowAllAuthenticator, so both are blank there. */
    val username: String get() = if (isCloud) KandraCloud.username ?: "" else ""
    val password: String get() = if (isCloud) KandraCloud.password ?: "" else ""

    val tlsEnabled: Boolean get() = isCloud && KandraCloud.tlsEnabled

    /** Shared truststore (cloud + TLS + CA PEM only; null otherwise). Built once per JVM, deleted on JVM exit. */
    val truststore: KandraCloud.TempTruststore? by lazy {
        if (!tlsEnabled) null
        else KandraCloud.tempTruststore()?.also { ts -> Runtime.getRuntime().addShutdownHook(Thread { ts.close() }) }
    }

    /**
     * Replication factor to actually create. The cloud cluster cannot serve an RF=1 keyspace reliably
     * (partitions with no live replica), so RF 1 is bumped to 3 there; tests that assert on replication
     * metadata must use this value rather than assume what they asked for.
     */
    fun effectiveRf(requested: Int): Int = if (isCloud && requested == 1) 3 else requested

    /**
     * Wall-clock budget, in seconds, for tests that race several simulated instances through the
     * LWT/DDL bootstrap paths. 30 s against the Docker container. 180 s against the cloud, where
     * each DDL statement takes 1-5 s (schema agreement over a ~245 ms WAN; measured 2026-10-25 on
     * ScyllaDB Cloud) and simultaneous claims contend in Paxos.
     */
    val concurrencyBudgetSeconds: Long get() = if (isCloud) 180 else 30

    /**
     * A unique keyspace name. Docker: `kandra_<tag>_<32 hex>` (as the tests always used); cloud: the
     * cleanup-friendly `kt_<tag>_<8 hex>`. Keep [tag] short (lowercase alphanumeric/underscore).
     */
    fun keyspaceName(tag: String): String =
        if (isCloud) "kt_${tag}_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        else "kandra_${tag}_${UUID.randomUUID().toString().replace("-", "")}"

    /** A configured (unbuilt) session builder with 30s timeouts. Caller owns the built session. */
    fun sessionBuilder(): CqlSessionBuilder =
        if (isCloud) KandraCloud.sessionBuilder()
        else CqlSession.builder()
            .addContactPoint(KandraTestcontainers.container.contactPoint)
            .withLocalDatacenter(KandraTestcontainers.container.localDatacenter)
            .withConfigLoader(longTimeoutConfig())

    /** Opens a session (optionally bound to [keyspace]). Caller must close it. */
    fun newSession(keyspace: String? = null): CqlSession {
        val b = sessionBuilder()
        if (keyspace != null) b.withKeyspace(keyspace)
        return b.build()
    }

    /** `CREATE KEYSPACE IF NOT EXISTS` (SimpleStrategy; on the cloud: [effectiveRf] and tablets disabled). */
    fun createKeyspace(name: String, rf: Int = 1) {
        newSession().use { it.execute(createKeyspaceCql(name, rf)) }
    }

    /** CQL used by [createKeyspace] (for tests that run it on their own session). */
    fun createKeyspaceCql(name: String, rf: Int = 1): String =
        "CREATE KEYSPACE IF NOT EXISTS $name WITH replication = " +
            "{'class': 'SimpleStrategy', 'replication_factor': ${effectiveRf(rf)}}" +
            if (isCloud) " AND tablets = {'enabled': false}" else ""

    fun dropKeyspace(name: String) {
        runCatching { newSession().use { it.execute("DROP KEYSPACE IF EXISTS $name") } }
    }

    /** Creates a unique keyspace and returns sessions for it; [FreshKeyspace.close] always drops it. */
    fun freshKeyspace(tag: String, rf: Int = 1): FreshKeyspace {
        val name = keyspaceName(tag)
        val admin = newSession()
        try {
            admin.execute(createKeyspaceCql(name, rf))
            return FreshKeyspace(name, newSession(name), admin)
        } catch (e: Throwable) {
            runCatching { admin.execute("DROP KEYSPACE IF EXISTS $name") }
            runCatching { admin.close() }
            throw e
        }
    }

    /** A temporary keyspace: [session] is bound to it, [admin] is unbound. */
    class FreshKeyspace internal constructor(
        val name: String,
        val session: CqlSession,
        val admin: CqlSession
    ) : AutoCloseable {
        override fun close() {
            runCatching { session.close() }
            runCatching { admin.execute("DROP KEYSPACE IF EXISTS $name") }
            runCatching { admin.close() }
        }
    }

    internal fun longTimeoutConfig() = DriverConfigLoader.programmaticBuilder()
        .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofSeconds(30))
        .withDuration(DefaultDriverOption.METADATA_SCHEMA_REQUEST_TIMEOUT, Duration.ofSeconds(30))
        .withDuration(DefaultDriverOption.CONTROL_CONNECTION_AGREEMENT_TIMEOUT, Duration.ofSeconds(30))
        // Connection-init and control-connection queries (system.peers, schema refresh, USE) have
        // their own timeout, 5 s by default, which a loaded cluster exceeded (GH #205).
        .withDuration(DefaultDriverOption.CONNECTION_INIT_QUERY_TIMEOUT, Duration.ofSeconds(30))
        .build()
}
