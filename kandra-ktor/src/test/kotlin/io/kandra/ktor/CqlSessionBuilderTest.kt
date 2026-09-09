package io.kandra.ktor

import com.datastax.oss.driver.api.core.config.DefaultDriverOption
import com.datastax.oss.driver.internal.core.metadata.DefaultEndPoint
import com.datastax.oss.driver.internal.core.session.throttling.ConcurrencyLimitingRequestThrottler
import io.kandra.core.exception.KandraSchemaException
import java.net.InetSocketAddress
import javax.net.ssl.SSLContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [CqlSessionBuilder.kt] internals that don't require a live cluster:
 *  - [AllowedDcNodeDistanceEvaluator]'s pure decision logic (GH #58)
 *  - [keyspaceDdl]'s identifier validation and DDL rendering (GH #65)
 *  - [KandraSslEngineFactory] and [SslConfig] enforcement (GH #78 / ISS-070)
 *  - [buildDriverConfig]'s pool-size wiring (GH #80 / ISS-072)
 *  - [buildDriverConfig]'s request-throttler wiring (GH #81 / ISS-073)
 */
class CqlSessionBuilderTest {

    // ── AllowedDcNodeDistanceEvaluator (GH #58) ──────────────────────────────

    @Test
    fun `local DC always defers to the policy's own default distance`() {
        val evaluator = AllowedDcNodeDistanceEvaluator("us-east-1", setOf("eu-west-1"))
        assertEquals(null, evaluator.evaluate("us-east-1"))
    }

    @Test
    fun `an allowed remote DC defers to the policy's own default distance`() {
        val evaluator = AllowedDcNodeDistanceEvaluator("us-east-1", setOf("eu-west-1", "ap-southeast-1"))
        assertEquals(null, evaluator.evaluate("eu-west-1"))
        assertEquals(null, evaluator.evaluate("ap-southeast-1"))
    }

    @Test
    fun `a remote DC not in the allow-list is forced IGNORED`() {
        val evaluator = AllowedDcNodeDistanceEvaluator("us-east-1", setOf("eu-west-1"))
        assertEquals(
            com.datastax.oss.driver.api.core.loadbalancing.NodeDistance.IGNORED,
            evaluator.evaluate("ap-southeast-1")
        )
    }

    @Test
    fun `an empty allow-list ignores every remote DC`() {
        val evaluator = AllowedDcNodeDistanceEvaluator("us-east-1", emptySet())
        assertEquals(
            com.datastax.oss.driver.api.core.loadbalancing.NodeDistance.IGNORED,
            evaluator.evaluate("eu-west-1")
        )
        assertEquals(null, evaluator.evaluate("us-east-1"))
    }

    @Test
    fun `a null datacenter defers to the policy's own default distance`() {
        val evaluator = AllowedDcNodeDistanceEvaluator("us-east-1", setOf("eu-west-1"))
        assertEquals(null, evaluator.evaluate(null))
    }

    // ── keyspaceDdl identifier validation (GH #65) ───────────────────────────

    @Test
    fun `keyspaceDdl renders SimpleStrategy DDL for a valid keyspace`() {
        val ddl = keyspaceDdl("coinx", ReplicationStrategy.SimpleStrategy(replicationFactor = 3))
        assertEquals(
            "CREATE KEYSPACE IF NOT EXISTS coinx WITH replication = " +
                "{'class': 'SimpleStrategy', 'replication_factor': 3}",
            ddl
        )
    }

    @Test
    fun `keyspaceDdl renders NetworkTopologyStrategy DDL for valid DC names`() {
        val ddl = keyspaceDdl(
            "coinx",
            ReplicationStrategy.NetworkTopologyStrategy(mapOf("us_east" to 3, "eu_west" to 2))
        )
        assertTrue(ddl.startsWith("CREATE KEYSPACE IF NOT EXISTS coinx WITH replication = "))
        assertTrue(ddl.contains("'class': 'NetworkTopologyStrategy'"))
        assertTrue(ddl.contains("'us_east': 3"))
        assertTrue(ddl.contains("'eu_west': 2"))
    }

    @Test
    fun `keyspaceDdl rejects a keyspace name that is not a valid CQL identifier`() {
        assertThrows(KandraSchemaException::class.java) {
            keyspaceDdl("coinx'; DROP KEYSPACE other; --", ReplicationStrategy.SimpleStrategy())
        }
    }

    @Test
    fun `keyspaceDdl rejects a keyspace name with whitespace`() {
        assertThrows(KandraSchemaException::class.java) {
            keyspaceDdl("my keyspace", ReplicationStrategy.SimpleStrategy())
        }
    }

    @Test
    fun `keyspaceDdl rejects a DC name in dcReplicationMap that is not a valid CQL identifier`() {
        assertThrows(KandraSchemaException::class.java) {
            keyspaceDdl(
                "coinx",
                ReplicationStrategy.NetworkTopologyStrategy(mapOf("us-east' } ; --" to 3))
            )
        }
    }

    @Test
    fun `keyspaceDdl accepts a keyspace name starting with an underscore`() {
        val ddl = keyspaceDdl("_coinx", ReplicationStrategy.SimpleStrategy())
        assertTrue(ddl.contains("_coinx"))
    }

    // ── KandraSslEngineFactory.protocolAtLeast (GH #78) ──────────────────────

    @Test
    fun `protocolAtLeast orders TLS versions ascending`() {
        assertTrue(KandraSslEngineFactory.protocolAtLeast("TLSv1.3", "TLSv1.2"))
        assertTrue(KandraSslEngineFactory.protocolAtLeast("TLSv1.2", "TLSv1.2"))
        assertFalse(KandraSslEngineFactory.protocolAtLeast("TLSv1.1", "TLSv1.2"))
        assertFalse(KandraSslEngineFactory.protocolAtLeast("TLSv1", "TLSv1.1"))
    }

    @Test
    fun `protocolAtLeast rejects unrecognized protocol or minimum strings`() {
        assertFalse(KandraSslEngineFactory.protocolAtLeast("SSLv3", "TLSv1.2"))
        assertFalse(KandraSslEngineFactory.protocolAtLeast("TLSv1.2", "TLSv9.9"))
    }

    // ── KandraSslEngineFactory.newSslEngine (GH #78) ─────────────────────────

    private fun trustAllSslContext(): SSLContext =
        SSLContext.getInstance("TLS").also { it.init(null, null, null) }

    private val fakeEndpoint = DefaultEndPoint(InetSocketAddress.createUnresolved("example.invalid", 9042))

    @Test
    fun `newSslEngine restricts enabledProtocols to at least minimumTlsVersion`() {
        val sslContext = trustAllSslContext()
        val supported = sslContext.createSSLEngine().supportedProtocols.toSet()
        val expected = KandraSslEngineFactory.TLS_PROTOCOL_ORDER.filter {
            it in supported && KandraSslEngineFactory.protocolAtLeast(it, "TLSv1.2")
        }

        val engine = KandraSslEngineFactory(sslContext, "TLSv1.2", null, hostnameVerification = false)
            .newSslEngine(fakeEndpoint)

        assertTrue(expected.isNotEmpty(), "test JVM must support at least one protocol >= TLSv1.2")
        assertEquals(expected.toSet(), engine.enabledProtocols.toSet())
        assertFalse(engine.enabledProtocols.contains("TLSv1"))
        assertFalse(engine.enabledProtocols.contains("TLSv1.1"))
    }

    @Test
    fun `newSslEngine applies configured cipherSuites verbatim`() {
        val sslContext = trustAllSslContext()
        val aSupportedCipher = sslContext.createSSLEngine().supportedCipherSuites.first()

        val engine = KandraSslEngineFactory(sslContext, "TLSv1.2", listOf(aSupportedCipher), hostnameVerification = false)
            .newSslEngine(fakeEndpoint)

        assertEquals(listOf(aSupportedCipher), engine.enabledCipherSuites.toList())
    }

    @Test
    fun `newSslEngine leaves the default enabled cipher suites when cipherSuites is null`() {
        val sslContext = trustAllSslContext()
        val defaultEnabled = sslContext.createSSLEngine().enabledCipherSuites.toSet()

        val engine = KandraSslEngineFactory(sslContext, "TLSv1.2", null, hostnameVerification = false)
            .newSslEngine(fakeEndpoint)

        assertEquals(defaultEnabled, engine.enabledCipherSuites.toSet())
    }

    @Test
    fun `newSslEngine sets HTTPS endpoint identification when hostnameVerification is true`() {
        val sslContext = trustAllSslContext()

        val engine = KandraSslEngineFactory(sslContext, "TLSv1.2", null, hostnameVerification = true)
            .newSslEngine(fakeEndpoint)

        assertEquals("HTTPS", engine.sslParameters.endpointIdentificationAlgorithm)
    }

    @Test
    fun `newSslEngine leaves endpoint identification unset when hostnameVerification is false`() {
        val sslContext = trustAllSslContext()

        val engine = KandraSslEngineFactory(sslContext, "TLSv1.2", null, hostnameVerification = false)
            .newSslEngine(fakeEndpoint)

        assertTrue(engine.sslParameters.endpointIdentificationAlgorithm.isNullOrEmpty())
    }

    // ── SslConfig.requireEncryption / minimumTlsVersion enforcement (GH #78) ─

    @Test
    fun `buildCqlSession throws when requireEncryption is true but ssl is not enabled`() {
        val config = KandraConfig().apply {
            contactPoints = "localhost:19999"
            localDatacenter = "dc1"
            ssl {
                requireEncryption = true
                enabled = false
            }
        }
        val ex = assertThrows(KandraSchemaException::class.java) { buildCqlSession(config) }
        assertTrue(ex.message!!.contains("requireEncryption"))
    }

    @Test
    fun `buildCqlSession throws for an unrecognized minimumTlsVersion when ssl is enabled`() {
        val config = KandraConfig().apply {
            contactPoints = "localhost:19999"
            localDatacenter = "dc1"
            ssl {
                enabled = true
                minimumTlsVersion = "TLSv1.4"
            }
        }
        val ex = assertThrows(KandraSchemaException::class.java) { buildCqlSession(config) }
        assertTrue(ex.message!!.contains("minimumTlsVersion"))
    }

    @Test
    fun `buildCqlSession does not validate minimumTlsVersion when ssl is disabled`() {
        // ssl.enabled = false (default) -- an invalid minimumTlsVersion should be a no-op, not a
        // startup failure, since it's never read when SSL itself is off.
        val config = KandraConfig().apply {
            contactPoints = "localhost:19999"
            localDatacenter = "dc1"
            ssl { minimumTlsVersion = "not-a-real-version" }
        }
        val ex = assertThrows(Exception::class.java) { buildCqlSession(config) }
        assertFalse(ex is KandraSchemaException, "should fail trying to connect, not on minimumTlsVersion validation")
    }

    @Test
    fun `buildCqlSession reaches connection attempt once ssl enabled + requireEncryption gate is satisfied`() {
        // Both ssl.enabled = true and requireEncryption = true together must clear the
        // requireEncryption gate and proceed past config validation -- the only failure here
        // should come from the (deliberately unreachable) contact point, not KandraSchemaException.
        val config = KandraConfig().apply {
            contactPoints = "localhost:19999"
            localDatacenter = "dc1"
            ssl {
                enabled = true
                requireEncryption = true
            }
        }
        val ex = assertThrows(Exception::class.java) { buildCqlSession(config) }
        assertFalse(ex is KandraSchemaException, "requireEncryption + enabled = true must clear validation")
    }

    // ── PoolConfig.localPoolSize / remotePoolSize (GH #80) ───────────────────

    @Test
    fun `buildDriverConfig defaults pool size to 1 local and 1 remote`() {
        val profile = buildDriverConfig(KandraConfig()).initialConfig.defaultProfile
        assertEquals(1, profile.getInt(DefaultDriverOption.CONNECTION_POOL_LOCAL_SIZE))
        assertEquals(1, profile.getInt(DefaultDriverOption.CONNECTION_POOL_REMOTE_SIZE))
    }

    @Test
    fun `buildDriverConfig picks up configured pool sizes`() {
        val config = KandraConfig().apply {
            pool {
                localPoolSize = 4
                remotePoolSize = 2
            }
        }
        val profile = buildDriverConfig(config).initialConfig.defaultProfile
        assertEquals(4, profile.getInt(DefaultDriverOption.CONNECTION_POOL_LOCAL_SIZE))
        assertEquals(2, profile.getInt(DefaultDriverOption.CONNECTION_POOL_REMOTE_SIZE))
    }

    // ── ThrottleConfig (GH #81) ───────────────────────────────────────────────

    @Test
    fun `buildDriverConfig leaves the driver's default PassThroughRequestThrottler when throttle is disabled`() {
        // The driver's reference.conf always defines advanced.throttler.class (default
        // "PassThroughRequestThrottler", which never queues or rejects) -- Kandra's throttle {}
        // block being unconfigured/disabled must leave that default untouched rather than
        // overriding it to ConcurrencyLimitingRequestThrottler with no size configured.
        val profile = buildDriverConfig(KandraConfig()).initialConfig.defaultProfile
        assertEquals("PassThroughRequestThrottler", profile.getString(DefaultDriverOption.REQUEST_THROTTLER_CLASS))
    }

    @Test
    fun `buildDriverConfig wires ConcurrencyLimitingRequestThrottler with configured limits when enabled`() {
        val config = KandraConfig().apply {
            throttle {
                enabled = true
                maxConcurrentRequests = 500
                maxQueueSize = 250
            }
        }
        val profile = buildDriverConfig(config).initialConfig.defaultProfile
        assertEquals(
            ConcurrencyLimitingRequestThrottler::class.java.name,
            profile.getString(DefaultDriverOption.REQUEST_THROTTLER_CLASS)
        )
        assertEquals(500, profile.getInt(DefaultDriverOption.REQUEST_THROTTLER_MAX_CONCURRENT_REQUESTS))
        assertEquals(250, profile.getInt(DefaultDriverOption.REQUEST_THROTTLER_MAX_QUEUE_SIZE))
    }
}
