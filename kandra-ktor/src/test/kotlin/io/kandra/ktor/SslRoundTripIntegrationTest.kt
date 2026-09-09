package io.kandra.ktor

import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.KandraAuth
import io.kandra.core.SchemaRegistry
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.CassandraContainer
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.net.ssl.SSLException

/**
 * A real, live self-signed-cert SSL round trip (GH #84 / ISS-076).
 *
 * [ISS-070]/GH #78 added solid unit-level coverage for [SslConfig]/[buildSslContext]/
 * [KandraSslEngineFactory] (see [CqlSessionBuilderTest]) but explicitly stopped short of an actual
 * Testcontainers round trip -- "too large/flaky relative to direct unit tests" per that PR's own
 * notes. This is exactly the gap ISS-076 calls out: a config surface can be unit-tested down to the
 * last field and still be wired to nothing real (that's literally what ISS-070 itself was). So this
 * test stands up a genuinely TLS-configured single-node Cassandra container -- `client_encryption_options.enabled:
 * true` in a real `cassandra.yaml`, a real self-signed keypair loaded into a real Java keystore --
 * and drives a real `install(Kandra)` / [CqlSessionBuilder] connection through it, then executes a
 * real query and asserts it actually returns data. If the handshake, the keystore, or the driver
 * wiring were broken, this fails; a config-shape unit test alone could not have caught that.
 *
 * ### Why this is a separate, independently-run container rather than [io.kandra.test.KandraTestcontainers]
 *
 * The shared singleton container in `kandra-test` is deliberately plain-text (no TLS) and shared
 * across the whole JVM -- retrofitting SSL onto it would either break every other test that reuses
 * it or require spinning up a second container anyway. This test owns its own
 * [CassandraContainer] instance, configured from scratch, and tears it down itself.
 *
 * ### Why `hostnameVerification = false` here
 *
 * The self-signed certificate is issued for CN=localhost, but the actual contact point Testcontainers
 * hands back (`container.host`) varies by Docker backend (plain `localhost`, a `docker-machine` IP, a
 * remote Docker host address, ...) -- pinning hostname validation to succeed in every one of those
 * environments would make this test about Docker networking, not about SSL wiring. Encryption itself
 * (the actual thing this test exists to prove) is fully exercised either way: a real TLS handshake,
 * real certificate validation against the truststore, and a real encrypted query round trip. Hostname
 * verification itself already has direct unit coverage in [CqlSessionBuilderTest]
 * (`newSslEngine sets HTTPS endpoint identification when hostnameVerification is true`), and, as of
 * GH #108 / ISS-095, real end-to-end coverage that a mismatched cert is actually rejected --
 * see `hostname mismatch is rejected end-to-end when hostnameVerification is enabled` below, which
 * runs with `hostnameVerification = true` against a deliberately wrong-hostname cert.
 *
 * ### Why this is tagged out of the default `test` task
 *
 * Bringing up a single-node Cassandra with a mounted custom `cassandra.yaml` + keystore is slower
 * and more environment-sensitive (Docker file-copy timing, keytool availability, TLS handshake
 * timing on a cold JVM) than the rest of the ktor suite, which all shares one warm, plain-text
 * container across the whole JVM run. Per ISS-076's own guidance ("acceptable to keep as a
 * manually-run/tagged test... rather than deleting it -- but attempt the real thing first"), this
 * is excluded from `:kandra-ktor:test` (see `excludeTags("manual")` in `build.gradle.kts`) and
 * exposed instead as `./gradlew :kandra-ktor:sslIntegrationTest`.
 */
@Tag("manual")
class SslRoundTripIntegrationTest {

    companion object {
        private const val KEYSTORE_PASSWORD = "kandratest"

        private lateinit var certDir: Path
        private lateinit var serverKeystore: Path
        private lateinit var clientTruststore: Path

        // GH #108 / ISS-095 finding #3: a second, independent keypair whose certificate is issued
        // for a hostname that will never match wherever this test actually connects, used by
        // `hostname mismatch is rejected end-to-end when hostnameVerification is enabled` below to
        // prove a mismatched cert is actually rejected, not just that the config flag is wired
        // (that part is already covered by CqlSessionBuilderTest's unit test).
        private lateinit var wrongHostServerKeystore: Path
        private lateinit var wrongHostClientTruststore: Path

        @JvmStatic
        @BeforeAll
        fun generateSelfSignedCertificates() {
            certDir = Files.createTempDirectory("kandra-ssl-round-trip")
            serverKeystore = certDir.resolve("server.keystore.jks")
            clientTruststore = certDir.resolve("client.truststore.jks")
            val certFile = certDir.resolve("server.cert")

            generateKeystoreAndTruststore(
                alias = "kandra-ssl-test",
                dname = "CN=localhost, OU=Kandra, O=Kandra, L=Test, ST=Test, C=US",
                keystore = serverKeystore,
                certFile = certFile,
                truststore = clientTruststore
            )

            wrongHostServerKeystore = certDir.resolve("wrong-host.server.keystore.jks")
            wrongHostClientTruststore = certDir.resolve("wrong-host.client.truststore.jks")
            val wrongHostCertFile = certDir.resolve("wrong-host.server.cert")

            // A cert for a hostname that cannot possibly match wherever this test's Cassandra
            // container actually ends up reachable at (localhost, a docker-machine IP, ...) --
            // deliberately not "localhost" so hostname verification has something real to reject.
            generateKeystoreAndTruststore(
                alias = "kandra-ssl-wrong-host-test",
                dname = "CN=wrong-host.example.invalid, OU=Kandra, O=Kandra, L=Test, ST=Test, C=US",
                keystore = wrongHostServerKeystore,
                certFile = wrongHostCertFile,
                truststore = wrongHostClientTruststore
            )
        }

        /**
         * 1. Generates a self-signed RSA keypair straight into a real Java keystore -- this is the
         *    keystore Cassandra itself will load for `client_encryption_options`.
         * 2. Exports the (self-signed) public certificate.
         * 3. Imports it into a separate truststore -- this is what Kandra's own
         *    `ssl { trustStorePath = ... }` will load client-side, exactly mirroring a real
         *    deployment where the client only ever holds the server's public cert, never its key.
         */
        private fun generateKeystoreAndTruststore(alias: String, dname: String, keystore: Path, certFile: Path, truststore: Path) {
            keytool(
                "-genkeypair", "-alias", alias,
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
                "-storetype", "JKS",
                "-keystore", keystore.toString(), "-storepass", KEYSTORE_PASSWORD,
                "-keypass", KEYSTORE_PASSWORD,
                "-dname", dname
            )

            keytool(
                "-exportcert", "-alias", alias,
                "-keystore", keystore.toString(), "-storepass", KEYSTORE_PASSWORD,
                "-file", certFile.toString()
            )

            keytool(
                "-importcert", "-noprompt", "-alias", alias,
                "-file", certFile.toString(),
                "-keystore", truststore.toString(), "-storepass", KEYSTORE_PASSWORD,
                "-storetype", "JKS"
            )
        }

        @JvmStatic
        @AfterAll
        fun cleanupCertificates() {
            if (::certDir.isInitialized) {
                runCatching { certDir.toFile().deleteRecursively() }
            }
        }

        private fun keytool(vararg args: String) {
            val keytoolBin = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
            val process = ProcessBuilder(listOf(keytoolBin) + args)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            check(exitCode == 0) { "keytool ${args.joinToString(" ")} failed (exit=$exitCode):\n$output" }
        }
    }

    private var container: CassandraContainer<*>? = null

    @AfterEach
    fun tearDown() {
        SchemaRegistry.clear()
        runCatching { container?.stop() }
        container = null
    }

    @OptIn(ExperimentalKandraApi::class)
    @Test
    fun `install(Kandra) with ssl enabled connects and executes a real query over an encrypted connection`() {
        val cassandra = CassandraContainer("cassandra:4.1")
            .withExposedPorts(9042)
            // Overwrites only cassandra.yaml -- the rest of the image's /etc/cassandra directory
            // (logback.xml, jvm*.options, cassandra-rackdc.properties, ...) is left as shipped, since
            // this file is the real, unmodified default cassandra.yaml from the cassandra:4.1 image
            // with only client_encryption_options edited (enabled: true, an absolute keystore path).
            // The docker-entrypoint.sh script's env-var sed substitutions match on cassandra.yaml key
            // *names*, not on placeholder text, so overwriting the whole file ahead of time is safe.
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("ssl/cassandra-ssl.yaml"),
                "/etc/cassandra/cassandra.yaml"
            )
            .withCopyFileToContainer(
                MountableFile.forHostPath(serverKeystore),
                "/etc/cassandra/.keystore"
            )
        container = cassandra
        cassandra.start()

        val testKeyspace = "kandra_ssl_${UUID.randomUUID().toString().replace("-", "")}"

        testApplication {
            application {
                install(Kandra) {
                    contactPoints = "${cassandra.host}:${cassandra.getMappedPort(9042)}"
                    localDatacenter = cassandra.localDatacenter
                    keyspace = testKeyspace
                    autoCreateKeyspace = true
                    schemaMode = SchemaMode.AUTO_CREATE
                    register(TestItem::class)
                    // Cassandra's default AllowAllAuthenticator is untouched by our cassandra.yaml
                    // edits -- no credentials required, same as every other Testcontainers test here.
                    auth { provider = KandraAuth.static("", "") }
                    ssl {
                        enabled = true
                        trustStorePath = clientTruststore.toString()
                        trustStorePassword = KEYSTORE_PASSWORD
                        // See the class doc comment above for why hostname verification is off here.
                        hostnameVerification = false
                    }
                }

                val session = kandraSession
                val item = TestItem(id = UUID.randomUUID(), label = "over-the-wire, encrypted")
                val repo = kandra.repository<TestItem>()

                // The real assertion: an actual write + read round trip through the real driver
                // session, which only exists because the TLS handshake against our self-signed
                // cert -- loaded from a real keystore, by real Cassandra, via real client_encryption_options
                // -- already succeeded. A broken KandraSslEngineFactory, a bad truststore/keystore
                // pairing, or an unenforced minimumTlsVersion would all fail before this line runs.
                repo.save(item)
                val found = repo.findById(item.id)
                assertNotNull(found)
                assertEquals(item, found)

                // Independently confirm the same live, SSL-wrapped session can run a plain query too.
                val row = session.execute("SELECT release_version FROM system.local").one()
                assertNotNull(row?.getString("release_version"))
            }
        }
    }

    /**
     * GH #108 / ISS-095 finding #3: the round-trip test above deliberately runs with
     * `hostnameVerification = false` (see the class doc for why), which means until now nothing
     * anywhere -- unit or integration -- proved a certificate for the *wrong* hostname is actually
     * rejected end-to-end. [CqlSessionBuilderTest] only confirms `hostnameVerification = true` wires
     * `HTTPS` endpoint identification into the SSL parameters; it never drives a real handshake
     * against a real mismatched cert. This test does: the server presents [wrongHostServerKeystore]
     * (issued for `CN=wrong-host.example.invalid`, which cannot match wherever this container
     * actually ends up reachable at), the client trusts that exact cert (so the failure is purely
     * about hostname identity, not an untrusted signer), and `hostnameVerification` is left at its
     * default (`true`) -- the handshake must fail.
     */
    @OptIn(ExperimentalKandraApi::class)
    @Test
    fun `hostname mismatch is rejected end-to-end when hostnameVerification is enabled`() {
        val cassandra = CassandraContainer("cassandra:4.1")
            .withExposedPorts(9042)
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("ssl/cassandra-ssl.yaml"),
                "/etc/cassandra/cassandra.yaml"
            )
            .withCopyFileToContainer(
                MountableFile.forHostPath(wrongHostServerKeystore),
                "/etc/cassandra/.keystore"
            )
        container = cassandra
        cassandra.start()

        val testKeyspace = "kandra_ssl_${UUID.randomUUID().toString().replace("-", "")}"

        var thrown: Throwable? = null
        try {
            testApplication {
                application {
                    install(Kandra) {
                        contactPoints = "${cassandra.host}:${cassandra.getMappedPort(9042)}"
                        localDatacenter = cassandra.localDatacenter
                        keyspace = testKeyspace
                        autoCreateKeyspace = true
                        schemaMode = SchemaMode.AUTO_CREATE
                        register(TestItem::class)
                        auth { provider = KandraAuth.static("", "") }
                        ssl {
                            enabled = true
                            trustStorePath = wrongHostClientTruststore.toString()
                            trustStorePassword = KEYSTORE_PASSWORD
                            // Left at the default (true), unlike the round-trip test above -- this
                            // is exactly the behavior under test.
                            hostnameVerification = true
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            thrown = e
        }

        assertNotNull(thrown, "connecting with hostnameVerification=true against a cert for the wrong hostname must fail, not silently succeed")
        assertTrue(
            containsSslFailure(thrown!!),
            "expected an SSLException (e.g. hostname/certificate identity failure) somewhere in the failure " +
                "chain of $thrown, but none was found -- the connection may have failed for an unrelated reason"
        )
    }

    /**
     * Walks [t]'s `.cause` chain looking for an [SSLException] -- but the DataStax driver's
     * `AllNodesFailedException` (what a failed `install(Kandra)` connection attempt ultimately wraps
     * as its `cause`) does **not** put the actual per-node connection failure in its own `.cause`; it
     * aggregates them in [com.datastax.oss.driver.api.core.AllNodesFailedException.getAllErrors] (one
     * list of [Throwable] per attempted node) instead. So whenever an `AllNodesFailedException` is
     * encountered, this also walks the full cause chain of every throwable in that map.
     */
    private fun containsSslFailure(t: Throwable): Boolean {
        var current: Throwable? = t
        while (current != null) {
            if (current is SSLException) return true
            if (current is com.datastax.oss.driver.api.core.AllNodesFailedException) {
                val nodeErrorsContainSsl = current.allErrors.values
                    .flatten()
                    .any { nodeError -> generateSequence(nodeError) { it.cause }.any { it is SSLException } }
                if (nodeErrorsContainSsl) return true
            }
            current = current.cause
        }
        return false
    }
}
