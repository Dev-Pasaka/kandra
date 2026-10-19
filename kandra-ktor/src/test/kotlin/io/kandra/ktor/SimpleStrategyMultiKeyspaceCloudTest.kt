package io.kandra.ktor

import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.core.KandraAuth
import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.test.KandraCloud
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

private const val SS_CORE = "kt_ss_core"
private const val SS_A = "kt_ss_a"
private const val SS_B = "kt_ss_b"

@ScyllaTable("ss_core_item")
data class SsCore(@PartitionKey val id: UUID, val v: String)

@ScyllaTable("ss_item", keyspace = SS_A)
data class SsItemA(@PartitionKey val id: UUID, val name: String)

@ScyllaTable("ss_item", keyspace = SS_B)
data class SsItemB(@PartitionKey val id: UUID, val label: String)

/**
 * `SimpleStrategy` keyspaces (default + two declared) created through one `install(Kandra)`, against a
 * live Scylla Cloud cluster. Skipped when the KANDRA_CLOUD_* environment is not configured.
 *
 * Uses [ReplicationStrategy.SimpleStrategy] with `disableTablets = true`, which tablets-enabled clusters
 * (Scylla Cloud default) require; without it the cluster rejects the keyspace.
 */
class SimpleStrategyMultiKeyspaceCloudTest {

    private var trust: KandraCloud.TempTruststore? = null

    @BeforeEach
    fun setUp() {
        KandraCloud.assumeConfigured()
        trust = KandraCloud.tempTruststore()
        dropAll()
    }

    @AfterEach
    fun tearDown() {
        SchemaRegistry.clear()
        if (KandraCloud.isConfigured) dropAll()
        trust?.close(); trust = null
    }

    private fun dropAll() {
        runCatching {
            KandraCloud.newSession().use { s -> listOf(SS_CORE, SS_A, SS_B).forEach { s.execute("DROP KEYSPACE IF EXISTS $it") } }
        }
    }

    private fun replication(s: CqlSession, ks: String): Map<String, String> =
        s.execute("SELECT replication FROM system_schema.keyspaces WHERE keyspace_name = ?", ks)
            .one()?.getMap("replication", String::class.java, String::class.java) ?: emptyMap()

    @Test
    fun `SimpleStrategy with disableTablets for default plus two declared keyspaces over one session`() {
        try {
            scenario(ReplicationStrategy.SimpleStrategy(replicationFactor = 3, disableTablets = true), "org.apache.cassandra.locator.SimpleStrategy")
        } catch (e: Exception) {
            val msg = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            Assumptions.assumeFalse(
                "tablets_mode_for_new_keyspaces: enforced" in msg,
                "cluster enforces tablets server-side; SimpleStrategy cannot be created here (use NetworkTopologyStrategy)"
            )
            throw e
        }
    }

    @Test
    fun `NetworkTopologyStrategy for default plus two declared keyspaces over one session`() {
        scenario(ReplicationStrategy.NetworkTopologyStrategy(mapOf(KandraCloud.localDc to 3)), "org.apache.cassandra.locator.NetworkTopologyStrategy")
    }

    private fun scenario(strategy: ReplicationStrategy, expectedClass: String) {
        KandraCloud.newSession().use { s ->
            val row = s.execute("SELECT release_version, data_center FROM system.local").one()!!
            println("SS-PROBE release_version=${row.getString("release_version")} dc=${row.getString("data_center")}")
        }
        SchemaRegistry.clear()
        val built = cqlSessionsBuilt.get()
        testApplication {
            application {
                install(Kandra) {
                    contactPoints = KandraCloud.contactPointsCsv()
                    localDatacenter = KandraCloud.localDc
                    auth { provider = KandraAuth.static(KandraCloud.username ?: "", KandraCloud.password ?: "") }
                    pool { requestTimeoutMillis = 30_000 }
                    trust?.let { ts ->
                        ssl {
                            enabled = true
                            hostnameVerification = false
                            trustStorePath = ts.path
                            trustStorePassword = ts.password
                            trustStoreType = ts.type
                        }
                    }
                    replicationStrategy = strategy
                    keyspace = SS_CORE
                    autoCreateKeyspace = true
                    keyspace(SS_A) { autoCreate = true }
                    keyspace(SS_B) { autoCreate = true }
                    schemaMode = SchemaMode.AUTO_CREATE
                    register(SsCore::class, SsItemA::class, SsItemB::class)
                }
                val k = kandra
                runBlocking {
                    val id = UUID.randomUUID()
                    k.suspendRepository<SsCore>().save(SsCore(id, "core"))
                    k.suspendRepository<SsItemA>().save(SsItemA(id, "in-a"))
                    k.suspendRepository<SsItemB>().save(SsItemB(id, "in-b"))
                    assertEquals("core", k.suspendRepository<SsCore>().findById(id)?.v)
                    assertEquals("in-a", k.suspendRepository<SsItemA>().findById(id)?.name)
                    assertEquals("in-b", k.suspendRepository<SsItemB>().findById(id)?.label)
                }
                val s = kandraSession
                for (ks in listOf(SS_CORE, SS_A, SS_B)) {
                    val r = replication(s, ks)
                    println("SS-PROBE $ks replication=$r")
                    assertEquals(expectedClass, r["class"], "strategy for $ks")
                    val rf = r["replication_factor"] ?: r[KandraCloud.localDc]
                    assertEquals("3", rf, "RF for $ks")
                }
                val tables = s.execute("SELECT table_name FROM system_schema.tables WHERE keyspace_name = ?", SS_A).all()
                    .map { it.getString("table_name") }
                assertNotNull(tables.find { it == "ss_item" })
            }
            startApplication()
        }
        assertEquals(built + 1, cqlSessionsBuilt.get(), "exactly one CqlSession for three keyspaces")
    }
}
