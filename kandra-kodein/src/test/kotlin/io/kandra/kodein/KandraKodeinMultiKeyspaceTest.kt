package io.kandra.kodein

import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.KandraAuth
import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.exception.KandraSchemaException
import io.kandra.kodein.ksa.Invoice as KsaInvoice
import io.kandra.kodein.ksb.Invoice as KsbInvoice
import io.kandra.ktor.Kandra
import io.kandra.ktor.SchemaMode
import io.kandra.runtime.repository.KandraSuspendRepository
import io.kandra.test.KandraTestcontainers
import io.ktor.server.application.Application
import io.ktor.server.application.install
import kotlinx.coroutines.runBlocking
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.kodein.di.direct
import org.kodein.di.instance
import org.kodein.di.ktor.closestDI
import java.util.UUID

private const val DEFAULT_KS = "kandra_kodein_mks_default"
private const val OTHER_KS = "kandra_kodein_mks_other"

@ScyllaTable("kodein_mks_customers")
data class KodeinMksCustomer(@PartitionKey val id: UUID, val name: String)

@ScyllaTable("kodein_mks_orders", keyspace = OTHER_KS)
data class KodeinMksOrder(@PartitionKey val id: UUID, val amount: Long)

/** ISS-107: Kodein bindings for registry entities that live in different keyspaces. */
// Docker-only by design: the entities' keyspaces are compile-time constants in @ScyllaTable(keyspace = ...)
// (fixed, non-`kt_` names), so they cannot be pointed at the shared cloud cluster without name collisions
// between concurrent runs. The cloud-capable multi-keyspace coverage lives in kandra-ktor
// (MultiKeyspaceIntegrationTest / MultiKeyspaceCloudE2ETest) and kandra-test.
class KandraKodeinMultiKeyspaceTest {

    @AfterEach
    fun cleanup() {
        SchemaRegistry.clear()
        CqlSession.builder()
            .addContactPoint(KandraTestcontainers.container.contactPoint)
            .withLocalDatacenter(KandraTestcontainers.container.localDatacenter)
            .build().use {
                it.execute("DROP KEYSPACE IF EXISTS $DEFAULT_KS")
                it.execute("DROP KEYSPACE IF EXISTS $OTHER_KS")
            }
    }

    @Test
    fun `same simple name in different keyspaces still throws and the message names both keyspaces`() {
        SchemaRegistry.register(KsaInvoice::class)
        SchemaRegistry.register(KsbInvoice::class)

        testApplication {
            application { kandraKodein() }
            val thrown = assertThrows(Throwable::class.java) { runBlocking { startApplication() } }
            val ex = generateSequence(thrown) { it.cause }.filterIsInstance<KandraSchemaException>().firstOrNull()
            assertNotNull(ex, "Expected KandraSchemaException, got: $thrown")
            val msg = ex!!.message!!
            assertTrue(msg.contains("io.kandra.kodein.ksa.Invoice"), msg)
            assertTrue(msg.contains("io.kandra.kodein.ksb.Invoice"), msg)
            assertTrue(msg.contains("billing_kodein"), msg)
            assertTrue(msg.contains("archive_kodein"), msg)
            assertTrue(msg.contains("different keyspaces"), msg)
        }
    }

    @OptIn(ExperimentalKandraApi::class)
    @Test
    fun `entities in two keyspaces resolve and round-trip through Kodein repositories`() {
        val c = KandraTestcontainers.container
        val cp = c.contactPoint
        CqlSession.builder().addContactPoint(cp).withLocalDatacenter(c.localDatacenter).build().use { s ->
            for (ks in listOf(DEFAULT_KS, OTHER_KS)) {
                s.execute(
                    "CREATE KEYSPACE IF NOT EXISTS $ks WITH replication = " +
                        "{'class': 'SimpleStrategy', 'replication_factor': 1}"
                )
            }
            s.execute("CREATE TABLE IF NOT EXISTS $DEFAULT_KS.kodein_mks_customers (id uuid PRIMARY KEY, name text)")
            s.execute("CREATE TABLE IF NOT EXISTS $OTHER_KS.kodein_mks_orders (id uuid PRIMARY KEY, amount bigint)")
        }
        lateinit var app: Application

        testApplication {
            application {
                app = this
                install(Kandra) {
                    contactPoints = "${cp.hostString}:${cp.port}"
                    localDatacenter = c.localDatacenter
                    keyspace = DEFAULT_KS
                    keyspace(OTHER_KS) { } // declare the second keyspace (created by hand above)
                    schemaMode = SchemaMode.NONE // tables were created above, one per keyspace
                    register(KodeinMksCustomer::class)
                    register(KodeinMksOrder::class)
                    auth { provider = KandraAuth.static("", "") }
                }
                kandraKodein()
            }
            startApplication()

            @Suppress("UNCHECKED_CAST")
            val customers = app.closestDI().direct.instance<KandraSuspendRepository<*>>(tag = "KodeinMksCustomerSuspend") as KandraSuspendRepository<KodeinMksCustomer>
            @Suppress("UNCHECKED_CAST")
            val orders = app.closestDI().direct.instance<KandraSuspendRepository<*>>(tag = "KodeinMksOrderSuspend") as KandraSuspendRepository<KodeinMksOrder>

            val customer = KodeinMksCustomer(UUID.randomUUID(), "ada")
            val order = KodeinMksOrder(UUID.randomUUID(), 4200)
            customers.save(customer)
            orders.save(order)

            assertEquals(customer, customers.findById(customer.id))
            assertEquals(order, orders.findById(order.id))
        }
    }
}
