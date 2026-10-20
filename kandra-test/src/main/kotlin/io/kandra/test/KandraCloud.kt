package io.kandra.test

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.CqlSessionBuilder
import com.datastax.oss.driver.api.core.config.DefaultDriverOption
import com.datastax.oss.driver.api.core.config.DriverConfigLoader
import org.junit.jupiter.api.Assumptions
import java.time.Duration
import java.io.FileInputStream
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Live-cluster (e.g. Scylla Cloud) test harness. Env-driven, so no credentials ever live in the repo.
 *
 * Environment:
 * - `KANDRA_CLOUD_CONTACT_POINTS` comma-separated `host[:port]` (port defaults to 9042). Unset => unconfigured.
 * - `KANDRA_CLOUD_USERNAME` / `KANDRA_CLOUD_PASSWORD` plain auth credentials (optional pair).
 * - `KANDRA_CLOUD_LOCAL_DC` local datacenter (required by the driver; the verified value for the shared
 *   cluster is documented in docs/features/multi-keyspace-plan.md).
 * - TLS (optional, off by default: the shared cluster accepts plain auth on 9042 and TLS on 9142):
 *   `KANDRA_CLOUD_TLS=true` enables it (contact-point port 9042 is rewritten to 9142). Trust comes from
 *   `KANDRA_CLOUD_CA_PEM=/path/ca.pem` (in-memory trust store built from the PEM; never committed), else
 *   `KANDRA_CLOUD_TRUSTSTORE`, else the JVM default trust store. Legacy: `KANDRA_CLOUD_SSL=true` == TLS with JVM trust;
 *   `KANDRA_CLOUD_TRUSTSTORE=/path/to/truststore.jks` (+ `KANDRA_CLOUD_TRUSTSTORE_PASSWORD`) uses a custom one.
 *
 * Usage in a JUnit 5 test (skipped, not failed, when unconfigured):
 * ```kotlin
 * @BeforeAll @JvmStatic fun gate() = KandraCloud.assumeConfigured()
 *
 * @Test fun works() = KandraCloud.freshKeyspace("smoke").use { ks ->
 *     ks.session.execute("CREATE TABLE t (id int PRIMARY KEY)")   // session is bound to ks.name
 * }
 * ```
 * Keyspaces are named `kt_<prefix>_<random8>`; `close()` always drops them.
 */
object KandraCloud {

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    val isConfigured: Boolean get() = env("KANDRA_CLOUD_CONTACT_POINTS") != null

    val localDc: String get() = env("KANDRA_CLOUD_LOCAL_DC") ?: "AWS_US_EAST_1"

    /** JUnit assumption: aborts (skips) the test or class when the cloud env is not configured. */
    fun assumeConfigured() {
        Assumptions.assumeTrue(isConfigured, "KANDRA_CLOUD_CONTACT_POINTS not set; skipping live-cluster test")
    }

    private fun contactPoints(): List<InetSocketAddress> =
        (env("KANDRA_CLOUD_CONTACT_POINTS") ?: error("KANDRA_CLOUD_CONTACT_POINTS is not set")).split(',')
            .map { it.trim() }.filter { it.isNotEmpty() }
            .map { hp ->
                val host = hp.substringBeforeLast(':', hp)
                val port = (if (':' in hp) hp.substringAfterLast(':').toInt() else 9042)
                    .let { if (tlsEnabled && it == 9042) 9142 else it }
                InetSocketAddress.createUnresolved(host, port)
            }

    val tlsEnabled: Boolean
        get() = env("KANDRA_CLOUD_TLS")?.lowercase() == "true" || env("KANDRA_CLOUD_SSL")?.lowercase() == "true"

    /** Contact points as a `host:port` CSV (TLS port 9142 substituted when TLS is on), for config DSLs taking a string. */
    fun contactPointsCsv(): String = contactPoints().joinToString(",") { "${it.hostString}:${it.port}" }

    /** Username / password from the env (null when unset). Never log these. */
    val username: String? get() = env("KANDRA_CLOUD_USERNAME")
    val password: String? get() = env("KANDRA_CLOUD_PASSWORD")

    /**
     * A temporary PKCS12 truststore built from `KANDRA_CLOUD_CA_PEM`, for APIs that want a truststore file
     * (e.g. Kandra's `ssl { trustStorePath = ... }`). Deleted on close. Null when TLS is off or no PEM is set.
     */
    fun tempTruststore(): TempTruststore? {
        val pem = env("KANDRA_CLOUD_CA_PEM")
        if (!tlsEnabled || pem == null) return null
        val dir = java.nio.file.Files.createTempDirectory("kandra-cloud-ts")
        val file = dir.resolve("truststore.p12")
        val pw = UUID.randomUUID().toString()
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        FileInputStream(pem).use { ins ->
            CertificateFactory.getInstance("X.509").generateCertificates(ins).forEachIndexed { i, c ->
                ks.setCertificateEntry("kandra-cloud-ca-$i", c)
            }
        }
        java.io.FileOutputStream(file.toFile()).use { ks.store(it, pw.toCharArray()) }
        return TempTruststore(dir, file.toString(), pw)
    }

    class TempTruststore internal constructor(
        private val dir: java.nio.file.Path,
        val path: String,
        val password: String
    ) : AutoCloseable {
        val type: String = "PKCS12"
        override fun close() {
            runCatching { dir.toFile().deleteRecursively() }
        }
    }

    private fun sslContext(): SSLContext? {
        if (!tlsEnabled) return null
        val pem = env("KANDRA_CLOUD_CA_PEM")
        if (pem != null) {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            FileInputStream(pem).use { ins ->
                CertificateFactory.getInstance("X.509").generateCertificates(ins).forEachIndexed { i, c ->
                    ks.setCertificateEntry("kandra-cloud-ca-$i", c)
                }
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
            return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
        }
        val store = env("KANDRA_CLOUD_TRUSTSTORE")
        if (store != null) {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            FileInputStream(store).use { ks.load(it, env("KANDRA_CLOUD_TRUSTSTORE_PASSWORD")?.toCharArray()) }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
            return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
        }
        return SSLContext.getDefault()
    }

    /** A configured (not yet built) session builder. Caller owns the built session. */
    fun sessionBuilder(): CqlSessionBuilder {
        val b = CqlSession.builder()
            .addContactPoints(contactPoints())
            .withLocalDatacenter(localDc)
            // Default 2s is too tight for DDL / schema agreement over the WAN to a cloud cluster.
            .withConfigLoader(
                DriverConfigLoader.programmaticBuilder()
                    .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofSeconds(30))
                    .withDuration(DefaultDriverOption.METADATA_SCHEMA_REQUEST_TIMEOUT, Duration.ofSeconds(30))
                    .withDuration(DefaultDriverOption.CONTROL_CONNECTION_AGREEMENT_TIMEOUT, Duration.ofSeconds(30))
                    .build()
            )
        val user = env("KANDRA_CLOUD_USERNAME")
        val pass = env("KANDRA_CLOUD_PASSWORD")
        if (user != null && pass != null) b.withAuthCredentials(user, pass)
        sslContext()?.let { b.withSslContext(it) }
        return b
    }

    /** Opens a new session, optionally bound to [keyspace]. Caller must close it. */
    fun newSession(keyspace: String? = null): CqlSession {
        val b = sessionBuilder()
        if (keyspace != null) b.withKeyspace(keyspace)
        return b.build()
    }

    /**
     * Creates `kt_<prefix>_<random8>` (SimpleStrategy, [rf]) and returns a handle holding a session bound to it.
     * Use with `use {}`; [FreshKeyspace.close] drops the keyspace even if the test fails.
     */
    fun freshKeyspace(prefix: String, rf: Int = 1): FreshKeyspace {
        require(Regex("[a-z0-9]{1,20}").matches(prefix)) { "prefix must be lowercase alphanumeric (<=20 chars): '$prefix'" }
        val name = "kt_${prefix}_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val admin = newSession()
        try {
            admin.execute(
                "CREATE KEYSPACE $name WITH replication = {'class': 'SimpleStrategy', 'replication_factor': $rf} " +
                    // Scylla Cloud enables tablets by default and SimpleStrategy rejects them.
                    "AND tablets = {'enabled': false}"
            )
            return FreshKeyspace(name, newSession(name), admin)
        } catch (e: Throwable) {
            runCatching { admin.execute("DROP KEYSPACE IF EXISTS $name") }
            runCatching { admin.close() }
            throw e
        }
    }

    /** A temporary keyspace plus sessions. [session] is bound to it; [admin] is unbound. */
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
}
