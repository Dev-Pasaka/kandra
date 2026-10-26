package io.kandra.ktor

import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.core.KandraAuth
import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.PartitionKey
import io.kandra.test.KandraCloud
import io.kandra.test.KandraTestcontainers
import io.ktor.server.application.install
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `SimpleStrategy(rf, disableTablets = true)` (opt-in `AND tablets = {'enabled': false}`).
 *
 * The flag is ScyllaDB-only: Apache Cassandra rejects the unknown `tablets` keyspace option. The Docker test
 * pins that behaviour (raw DDL only, never install(Kandra) with the flag on Cassandra). The cloud test
 * proves the flagged auto-create works end to end on a tablets-enabled Scylla Cloud cluster (TLS).
 */
class SimpleStrategyTabletsIntegrationTest {

    @Test
    fun `docker - Cassandra rejects the tablets option so the flag is Scylla-only`() {
        val name = "kt_tabdocker_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        KandraTestcontainers.newContainerSession().use { s ->
                try {
                    val failure = runCatching {
                        s.execute(keyspaceDdl(name, ReplicationStrategy.SimpleStrategy(1, disableTablets = true)))
                    }.exceptionOrNull()
                    assertTrue(failure != null, "Cassandra is expected to reject the tablets option")
                    // Default DDL (flag off) is accepted.
                    s.execute(keyspaceDdl(name, ReplicationStrategy.SimpleStrategy(1)))
                } finally {
                    runCatching { s.execute("DROP KEYSPACE IF EXISTS $name") }
                }
            }
    }

    @Test
    fun `cloud - SimpleStrategy with disableTablets auto-creates keyspace and table on tablets cluster`() {
        KandraCloud.assumeConfigured()
        val name = "kt_tablets_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val trust = KandraCloud.tempTruststore()
        try {
            SchemaRegistry.clear()
            kandraTestApplication {
                application {
                    install(Kandra) {
                        contactPoints = KandraCloud.contactPointsCsv()
                        localDatacenter = KandraCloud.localDc
                        auth { provider = KandraAuth.static(KandraCloud.username ?: "", KandraCloud.password ?: "") }
                        trust?.let { ts ->
                            ssl {
                                enabled = true
                                hostnameVerification = false // test only: node certs are not issued for contact-point hosts
                                trustStorePath = ts.path
                                trustStorePassword = ts.password
                                trustStoreType = ts.type
                            }
                        }
                        pool { requestTimeoutMillis = 30_000 }
                        keyspace = name
                        autoCreateKeyspace = true
                        replicationStrategy = ReplicationStrategy.SimpleStrategy(3, disableTablets = true)
                        register(TabletsItem::class)
                    }
                }
                startApplication()
            }
            KandraCloud.newSession().use { s ->
                val row = s.execute(
                    "SELECT keyspace_name FROM system_schema.tables WHERE keyspace_name = ? AND table_name = ?",
                    name, "tablets_item"
                ).one()
                assertTrue(row != null, "table created in the flagged SimpleStrategy keyspace")
                val repl = s.execute("SELECT replication FROM system_schema.keyspaces WHERE keyspace_name = ?", name)
                    .one()!!.getMap("replication", String::class.java, String::class.java)!!
                assertFalse(repl.isEmpty())
                assertTrue(repl["class"]!!.endsWith("SimpleStrategy"), "$repl")
            }
        } finally {
            SchemaRegistry.clear()
            runCatching { KandraCloud.newSession().use { it.execute("DROP KEYSPACE IF EXISTS $name") } }
            runCatching { trust?.close() }
        }
    }
}

@io.kandra.core.annotations.ScyllaTable("tablets_item")
data class TabletsItem(@PartitionKey val id: UUID, val v: String)
