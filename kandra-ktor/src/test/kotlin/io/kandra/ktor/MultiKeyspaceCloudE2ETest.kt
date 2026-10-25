package io.kandra.ktor

import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.core.KandraAuth
import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.LookupIndex
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.annotations.SoftDelete
import io.kandra.migrate.KandraMigration
import io.kandra.migrate.KandraMigrationRunner
import io.kandra.test.KandraCloud
import io.ktor.server.application.Application
import io.ktor.server.application.install
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

private const val E2E_CORE = "kt_e2e111_core"
private const val E2E_A = "kt_e2e111_a"
private const val E2E_B = "kt_e2e111_b"

@ScyllaTable("e2e_core_item")
data class E2eCore(@PartitionKey val id: UUID, val v: String)

// Same simple table name "e2e_item" in two keyspaces, different shapes.
@ScyllaTable("e2e_item", keyspace = E2E_A)
data class E2eItemA(@PartitionKey val id: UUID, @LookupIndex(tableSuffix = "by_code") val code: String, val name: String)

@ScyllaTable("e2e_item", keyspace = E2E_A)
data class E2eItemA2(
    @PartitionKey val id: UUID,
    @LookupIndex(tableSuffix = "by_code") val code: String,
    val name: String,
    val note: String?
)

@ScyllaTable("e2e_item", keyspace = E2E_B)
@SoftDelete(ttlSeconds = 3600, markerProperty = "isDeleted")
data class E2eItemB(@PartitionKey val id: UUID, val label: String = "", val isDeleted: Boolean = false)

object E2eMig1 : KandraMigration(1, "create mig table") {
    override fun up(session: CqlSession) = throw UnsupportedOperationException()
    override fun up(session: CqlSession, keyspace: String?) {
        session.execute("CREATE TABLE IF NOT EXISTS ${keyspace!!}.e2e_mig (id int PRIMARY KEY)")
    }
}

/**
 * ISS-111: whole multi-keyspace feature through the real plugin against the live Scylla Cloud cluster over TLS.
 * Skipped when the KANDRA_CLOUD_* environment is not configured.
 */
class MultiKeyspaceCloudE2ETest {

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
            KandraCloud.newSession().use { s -> listOf(E2E_CORE, E2E_A, E2E_B).forEach { s.execute("DROP KEYSPACE IF EXISTS $it") } }
        }
    }

    private fun KandraConfig.connectCloud() {
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
        replicationStrategy = ReplicationStrategy.NetworkTopologyStrategy(mapOf(KandraCloud.localDc to 3))
        keyspace = E2E_CORE
        autoCreateKeyspace = true
        keyspace(E2E_A) { autoCreate = true }
        keyspace(E2E_B) { autoCreate = true }
    }

    private fun columns(s: CqlSession, ks: String, table: String): Set<String> =
        s.execute("SELECT column_name FROM system_schema.columns WHERE keyspace_name = ? AND table_name = ?", ks, table)
            .all().map { it.getString("column_name")!! }.toSet()

    private fun tables(s: CqlSession, ks: String): Set<String> =
        s.execute("SELECT table_name FROM system_schema.tables WHERE keyspace_name = ?", ks)
            .all().map { it.getString("table_name")!! }.toSet()

    private fun run(mode: SchemaMode, vararg entities: kotlin.reflect.KClass<*>, body: Application.() -> Unit = {}) {
        SchemaRegistry.clear()
        kandraTestApplication {
            application {
                install(Kandra) {
                    connectCloud()
                    schemaMode = mode
                    register(*entities)
                }
                body()
            }
            startApplication()
        }
    }

    @Test
    fun `full multi-keyspace feature through one install(Kandra)`() {
        // ---- 1. AUTO_CREATE + CRUD / lookup / batch / soft delete, exactly one CqlSession ----
        val built = cqlSessionsBuilt.get()
        run(SchemaMode.AUTO_CREATE, E2eCore::class, E2eItemA::class, E2eItemB::class) {
            val kandra = kandra
            val session = kandraSession
            val core = kandra.suspendRepository<E2eCore>()
            val repoA = kandra.suspendRepository<E2eItemA>()
            val repoB = kandra.suspendRepository<E2eItemB>()
            val id = UUID.randomUUID() // same id in all keyspaces on purpose

            runBlocking {
                core.save(E2eCore(id, "core"))
                repoA.save(E2eItemA(id, "code-1", "from A"))
                repoB.save(E2eItemB(id, "from B"))

                assertEquals("core", core.findById(id)?.v)
                assertEquals("from A", repoA.findById(id)?.name)
                assertEquals("from B", repoB.findById(id)?.label)

                // lookup table lives in keyspace A and tracks save / update / delete
                fun lookupIds(code: String) = session.execute("SELECT id FROM $E2E_A.e2e_item_by_code WHERE code = ?", code).all().map { it.getUuid("id") }
                assertEquals(listOf(id), lookupIds("code-1"))
                repoA.update(E2eItemA(id, "code-1", "from A"), E2eItemA(id, "code-2", "A renamed"))
                assertEquals("A renamed", repoA.findById(id)?.name)
                assertTrue(lookupIds("code-1").isEmpty(), "old lookup row removed")
                assertEquals(listOf(id), lookupIds("code-2"))
                assertEquals("from B", repoB.findById(id)?.label, "other keyspace untouched")

                // one logged batch across two keyspaces
                val ba = E2eItemA(UUID.randomUUID(), "batch-code", "batched A")
                val bb = E2eItemB(UUID.randomUUID(), "batched B")
                kandra.batch {
                    repoA.saveInBatch(ba)
                    repoB.saveInBatch(bb)
                }
                assertEquals("batched A", repoA.findById(ba.id)?.name)
                assertEquals("batched B", repoB.findById(bb.id)?.label)
                assertEquals(listOf(ba.id), lookupIds("batch-code"))
                kandra.batch { repoA.deleteInBatch(ba) }
                assertNull(repoA.findById(ba.id))
                assertTrue(lookupIds("batch-code").isEmpty())

                // soft delete in keyspace B: row stays (TTL), marked deleted
                repoB.delete(E2eItemB(bb.id, "batched B"))
                assertNotNull(repoB.findById(bb.id))
                assertFalse(repoB.findActive(allowFullScan = true).any { it.id == bb.id })

                // hard delete in A
                repoA.delete(E2eItemA(id, "code-2", "A renamed"))
                assertNull(repoA.findById(id))
                assertNotNull(core.findById(id))
            }

            assertEquals(1, session.execute("SELECT count(*) FROM $E2E_B.e2e_item WHERE id = ${id}").one()!!.getLong(0).toInt())
        }
        assertEquals(1, cqlSessionsBuilt.get() - built, "exactly one CqlSession for the whole feature")

        KandraCloud.newSession().use { s ->
            assertTrue("e2e_core_item" in tables(s, E2E_CORE))
            assertTrue("e2e_item" in tables(s, E2E_A) && "e2e_item_by_code" in tables(s, E2E_A))
            assertTrue("e2e_item" in tables(s, E2E_B))
            assertFalse("e2e_item" in tables(s, E2E_CORE))
            assertFalse("note" in columns(s, E2E_A, "e2e_item"))
        }

        // ---- 2. AUTO_MIGRATE adds a column in keyspace A only ----
        run(SchemaMode.AUTO_MIGRATE, E2eCore::class, E2eItemA2::class, E2eItemB::class)
        KandraCloud.newSession().use { s ->
            assertTrue("note" in columns(s, E2E_A, "e2e_item"), "column added in A")
            assertFalse("note" in columns(s, E2E_B, "e2e_item"), "B untouched")
            assertFalse("note" in columns(s, E2E_CORE, "e2e_core_item"), "core untouched")
        }

        // ---- 3. VALIDATE passes when matching, fails (naming the table) when an entity drifts ----
        run(SchemaMode.VALIDATE, E2eCore::class, E2eItemA2::class, E2eItemB::class)
        val ex = assertThrows(Exception::class.java) {
            run(SchemaMode.VALIDATE, E2eCore::class, E2eItemA2::class, E2eItemB2::class)
        }
        var t: Throwable? = ex
        while (t != null && t !is io.kandra.core.exception.KandraSchemaException) t = t.cause
        assertTrue(t != null && t.message!!.contains("e2e_item"), "VALIDATE flags drift: $ex")

        // ---- 4. KandraMigrationRunner with keyspace= in two keyspaces, on the plugin's own session ----
        run(SchemaMode.VALIDATE, E2eCore::class, E2eItemA2::class, E2eItemB::class) {
            val session = kandraSession
            KandraMigrationRunner(session, keyspace = E2E_A).run(E2eMig1)
            assertTrue(KandraMigrationRunner(session, keyspace = E2E_B).history().isEmpty(), "B has its own history")
            KandraMigrationRunner(session, keyspace = E2E_B).run(E2eMig1)
            assertEquals(listOf(1), KandraMigrationRunner(session, keyspace = E2E_A).history().map { it.version })
            assertEquals(listOf(1), KandraMigrationRunner(session, keyspace = E2E_B).history().map { it.version })
        }
        KandraCloud.newSession().use { s ->
            assertTrue("kandra_migrations" in tables(s, E2E_A) && "e2e_mig" in tables(s, E2E_A))
            assertTrue("kandra_migrations" in tables(s, E2E_B) && "e2e_mig" in tables(s, E2E_B))
            assertFalse("kandra_migrations" in tables(s, E2E_CORE))
        }
    }
}

// Drifted entity: declares a column that does not exist in keyspace B's e2e_item.
@ScyllaTable("e2e_item", keyspace = E2E_B)
@SoftDelete(ttlSeconds = 3600, markerProperty = "isDeleted")
data class E2eItemB2(@PartitionKey val id: UUID, val label: String = "", val isDeleted: Boolean = false, val extra: String? = null)
