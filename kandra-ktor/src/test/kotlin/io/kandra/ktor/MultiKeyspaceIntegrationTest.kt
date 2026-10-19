package io.kandra.ktor

import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.core.KandraAuth
import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.exception.KandraSchemaException
import io.kandra.test.KandraCloud
import io.kandra.test.KandraTestcontainers
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * ISS-105 end-to-end: ONE install(Kandra), one CqlSession, two keyspaces. The entity keyspaces are
 * compile-time annotation constants, so the test keyspaces are fixed names ("mkdefault"/"mkbilling"
 * prefixed per run is impossible); to stay safe on a shared cluster they use a distinctive prefix
 * and are dropped before and after each test.
 *
 * Runs against Testcontainers (Docker) always, and additionally against the shared cloud cluster
 * when `KANDRA_CLOUD_CONTACT_POINTS` is set (plain-auth port 9042 only).
 */
class MultiKeyspaceIntegrationTest {

    // Names must match the @ScyllaTable(keyspace=...) constants below (annotation args are constants).
    private val ksDefault = "kt_mk105_core"
    private val ksBilling = "kt_mk105_billing"

    @AfterEach
    fun cleanup() {
        SchemaRegistry.clear()
        dropAll(Backend.DOCKER)
        if (KandraCloud.isConfigured) dropAll(Backend.CLOUD)
    }

    private enum class Backend { DOCKER, CLOUD }

    private fun admin(b: Backend): CqlSession = when (b) {
        Backend.DOCKER -> CqlSession.builder()
            .addContactPoint(KandraTestcontainers.container.contactPoint)
            .withLocalDatacenter(KandraTestcontainers.container.localDatacenter).build()
        Backend.CLOUD -> KandraCloud.newSession()
    }

    private fun dropAll(b: Backend) {
        runCatching { admin(b).use { s -> listOf(ksDefault, ksBilling).forEach { s.execute("DROP KEYSPACE IF EXISTS $it") } } }
    }

    private fun KandraConfig.connect(b: Backend, rf: Int) {
        when (b) {
            Backend.DOCKER -> {
                val cp = KandraTestcontainers.container.contactPoint
                contactPoints = "${cp.hostString}:${cp.port}"
                localDatacenter = KandraTestcontainers.container.localDatacenter
                auth { provider = KandraAuth.static("", "") }
            }
            Backend.CLOUD -> {
                contactPoints = System.getenv("KANDRA_CLOUD_CONTACT_POINTS")
                localDatacenter = KandraCloud.localDc
                val u = System.getenv("KANDRA_CLOUD_USERNAME"); val p = System.getenv("KANDRA_CLOUD_PASSWORD")
                auth { provider = KandraAuth.static(u ?: "", p ?: "") }
                pool { requestTimeoutMillis = 30_000 }
            }
        }
        // Scylla Cloud enables tablets by default and SimpleStrategy rejects them, so the cloud run
        // auto-creates with NetworkTopologyStrategy (tablets-compatible) instead.
        replicationStrategy = if (b == Backend.CLOUD) {
            ReplicationStrategy.NetworkTopologyStrategy(mapOf(KandraCloud.localDc to rf))
        } else {
            ReplicationStrategy.SimpleStrategy(rf)
        }
    }

    private fun columns(s: CqlSession, ks: String, table: String): Set<String> =
        s.execute("SELECT column_name FROM system_schema.columns WHERE keyspace_name = ? AND table_name = ?", ks, table)
            .all().map { it.getString("column_name")!! }.toSet()

    private fun tables(s: CqlSession, ks: String): Set<String> =
        s.execute("SELECT table_name FROM system_schema.tables WHERE keyspace_name = ?", ks)
            .all().map { it.getString("table_name")!! }.toSet()

    private fun install(b: Backend, rf: Int, mode: SchemaMode, vararg entities: kotlin.reflect.KClass<*>) {
        SchemaRegistry.clear()
        testApplication {
            application {
                install(Kandra) {
                    connect(b, rf)
                    keyspace = ksDefault
                    autoCreateKeyspace = true
                    keyspace(ksBilling) { autoCreate = true }
                    schemaMode = mode
                    register(*entities)
                }
            }
            startApplication()
        }
    }

    private fun scenario(b: Backend, rf: Int) {
        dropAll(b)
        val builtBefore = cqlSessionsBuilt.get()

        // 1. AUTO_CREATE: both keyspaces auto-created, each entity lands in its own keyspace.
        install(b, rf, SchemaMode.AUTO_CREATE, MkCoreV1::class, MkBillingV1::class)
        assertEquals(1, cqlSessionsBuilt.get() - builtBefore, "exactly one CqlSession per install(Kandra)")
        admin(b).use { s ->
            assertTrue("mk_core_item" in tables(s, ksDefault), "default-keyspace entity in default keyspace")
            assertFalse("mk_core_item" in tables(s, ksBilling))
            assertTrue("mk_billing_item" in tables(s, ksBilling), "billing entity in billing keyspace")
            assertFalse("mk_billing_item" in tables(s, ksDefault))
            // lock tables live per keyspace
            assertTrue("kandra_ddl_locks" in tables(s, ksDefault))
            assertTrue("kandra_ddl_locks" in tables(s, ksBilling))
        }

        // 2. AUTO_MIGRATE: a new column only on the billing entity.
        install(b, rf, SchemaMode.AUTO_MIGRATE, MkCoreV1::class, MkBillingV2::class)
        admin(b).use { s ->
            assertTrue("note" in columns(s, ksBilling, "mk_billing_item"), "column added in billing")
            assertFalse("note" in columns(s, ksDefault, "mk_core_item"), "default keyspace untouched")
            assertFalse("note" in columns(s, ksDefault, "mk_billing_item"))
        }

        // 3. VALIDATE: passes when schema matches, fails when an entity has a column the DB lacks.
        install(b, rf, SchemaMode.VALIDATE, MkCoreV1::class, MkBillingV2::class)
        val ex = assertThrows(Exception::class.java) {
            install(b, rf, SchemaMode.VALIDATE, MkCoreV2::class, MkBillingV2::class)
        }
        var t: Throwable? = ex
        while (t != null && t !is KandraSchemaException) t = t.cause
        assertTrue(t is KandraSchemaException && t.message!!.contains("mk_core_item"), "VALIDATE flags the keyspace that drifted: $ex")
        dropAll(b)
    }

    @Test
    fun `two keyspaces one session - docker`() = scenario(Backend.DOCKER, 1)

    @Test
    fun `two keyspaces one session - cloud`() {
        KandraCloud.assumeConfigured()
        scenario(Backend.CLOUD, 3)
    }

    @Test
    fun `entity in undeclared keyspace fails startup - docker`() {
        SchemaRegistry.clear()
        val before = cqlSessionsBuilt.get()
        val ex = assertThrows(Exception::class.java) {
            testApplication {
                application {
                    install(Kandra) {
                        connect(Backend.DOCKER, 1)
                        keyspace = ksDefault
                        autoCreateKeyspace = true
                        register(MkCoreV1::class, MkBillingV1::class) // billing never declared
                    }
                }
                startApplication()
            }
        }
        var t: Throwable? = ex
        while (t != null && t !is KandraSchemaException) t = t.cause
        assertTrue(t is KandraSchemaException && t.message!!.contains("MkBillingV1") && t.message!!.contains(ksBilling), "$ex")
        assertEquals(before, cqlSessionsBuilt.get(), "no session opened for a config that cannot start")
        admin(Backend.DOCKER).use { assertTrue(tables(it, ksDefault).isEmpty(), "nothing created") }
    }
}

@io.kandra.core.annotations.ScyllaTable("mk_core_item")
data class MkCoreV1(@PartitionKey val id: UUID, val v: String)

@io.kandra.core.annotations.ScyllaTable("mk_core_item")
data class MkCoreV2(@PartitionKey val id: UUID, val v: String, val extra: String?)

@io.kandra.core.annotations.ScyllaTable("mk_billing_item", keyspace = "kt_mk105_billing")
data class MkBillingV1(@PartitionKey val id: UUID, val v: String)

@io.kandra.core.annotations.ScyllaTable("mk_billing_item", keyspace = "kt_mk105_billing")
data class MkBillingV2(@PartitionKey val id: UUID, val v: String, val note: String?)
