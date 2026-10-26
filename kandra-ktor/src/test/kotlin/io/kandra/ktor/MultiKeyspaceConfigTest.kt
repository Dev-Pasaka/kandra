package io.kandra.ktor

import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.exception.KandraSchemaException
import io.ktor.server.application.install
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

@ScyllaTable("mk_default_item")
private data class MkDefaultItem(@PartitionKey val id: UUID, val v: String)

@ScyllaTable("mk_billing_item", keyspace = "billing")
private data class MkBillingItem(@PartitionKey val id: UUID, val v: String)

@ScyllaTable("mk_rogue_item", keyspace = "rogue")
private data class MkRogueItem(@PartitionKey val id: UUID, val v: String)

/** ISS-105: pure config validation (no cluster needed -- everything fails before connecting). */
class MultiKeyspaceConfigTest {

    @AfterEach
    fun cleanup() = SchemaRegistry.clear()

    private fun cfg(block: KandraConfig.() -> Unit) = KandraConfig().apply(block)

    private fun msg(block: KandraConfig.() -> Unit): String =
        assertThrows(KandraSchemaException::class.java) { resolveKeyspaceSpecs(cfg(block)) }.message!!

    @Test
    fun `single keyspace config resolves to one default spec`() {
        val specs = resolveKeyspaceSpecs(cfg { keyspace = "core"; autoCreateKeyspace = true })
        assertEquals(listOf(KeyspaceSpec("core", true, ReplicationStrategy.SimpleStrategy(1), true)), specs)
    }

    @Test
    fun `blank default with no declared keyspace keeps today's error`() {
        assertEquals("Kandra: 'keyspace' must be set in the plugin configuration.", msg { })
    }

    @Test
    fun `blank default is allowed when extra keyspaces are declared`() {
        val specs = resolveKeyspaceSpecs(cfg { keyspace("billing") { autoCreate = true } })
        assertEquals(listOf("billing"), specs.map { it.name })
        assertFalse(specs.single().isDefault)
    }

    @Test
    fun `extra keyspace inherits plugin replication unless overridden, regardless of DSL order`() {
        val specs = resolveKeyspaceSpecs(cfg {
            keyspace = "core"
            keyspace("a") { autoCreate = true }
            keyspace("b") { autoCreate = true; replicationStrategy = ReplicationStrategy.SimpleStrategy(2) }
            replicationStrategy = ReplicationStrategy.SimpleStrategy(3)
        })
        assertEquals(ReplicationStrategy.SimpleStrategy(3), specs.first { it.name == "a" }.strategy)
        assertEquals(ReplicationStrategy.SimpleStrategy(2), specs.first { it.name == "b" }.strategy)
        assertFalse(specs.first { it.name == "a" }.isDefault)
    }

    @Test
    fun `invalid extra keyspace name is rejected`() {
        assertTrue(msg { keyspace = "core"; keyspace("bad name; DROP KEYSPACE x") { } }.contains("not a valid CQL identifier"))
    }

    @Test
    fun `duplicate extra keyspace is rejected`() {
        assertTrue(msg { keyspace = "core"; keyspace("billing") { }; keyspace("Billing") { } }.contains("more than once"))
    }

    @Test
    fun `extra keyspace equal to the default is rejected`() {
        assertTrue(msg { keyspace = "core"; keyspace("core") { } }.contains("duplicates the default keyspace"))
    }

    @Test
    fun `invalid NetworkTopologyStrategy DC name on an extra keyspace is rejected`() {
        val m = msg {
            keyspace = "core"
            keyspace("billing") {
                autoCreate = true
                replicationStrategy = ReplicationStrategy.NetworkTopologyStrategy(mapOf("dc1' : 3}; DROP" to 3))
            }
        }
        assertTrue(m.contains("datacenter name") && m.contains("billing"), m)
    }

    @Test
    fun `invalid inherited NetworkTopologyStrategy DC name is rejected for an auto-created extra keyspace`() {
        val m = msg {
            keyspace = "core"
            replicationStrategy = ReplicationStrategy.NetworkTopologyStrategy(mapOf("bad dc" to 3))
            keyspace("billing") { autoCreate = true }
        }
        assertTrue(m.contains("datacenter name"), m)
    }

    @Test
    fun `entity naming an undeclared keyspace fails with an actionable message`() {
        SchemaRegistry.register(MkRogueItem::class)
        val c = cfg { keyspace = "core"; keyspace("billing") { } }
        val m = assertThrows(KandraSchemaException::class.java) {
            validateEntityKeyspaces(c, SchemaRegistry.all())
        }.message!!
        assertTrue(m.contains("MkRogueItem") && m.contains("rogue") && m.contains("core") && m.contains("billing"), m)
    }

    @Test
    fun `entities in default or declared keyspaces pass validation`() {
        SchemaRegistry.register(MkDefaultItem::class)
        SchemaRegistry.register(MkBillingItem::class)
        validateEntityKeyspaces(cfg { keyspace = "core"; keyspace("billing") { } }, SchemaRegistry.all())
    }

    @Test
    fun `entity without keyspace and no default keyspace fails`() {
        SchemaRegistry.register(MkDefaultItem::class)
        val m = assertThrows(KandraSchemaException::class.java) {
            validateEntityKeyspaces(cfg { keyspace("billing") { } }, SchemaRegistry.all())
        }.message!!
        assertTrue(m.contains("MkDefaultItem") && m.contains("default keyspace"), m)
    }

    @Test
    fun `lock names include the keyspace for non-default keyspaces only`() {
        SchemaRegistry.register(MkBillingItem::class)
        val schemas = SchemaRegistry.all()
        val legacy = ddlBootstrapLockName(schemas)
        assertTrue(ddlBootstrapLockName(schemas, "billing").startsWith("schema-bootstrap-billing-"))
        assertTrue(legacy.startsWith("schema-bootstrap-") && !legacy.contains("billing"))
        assertEquals("kandra_ddl_locks", ddlLockTableName(null))
        assertEquals("billing.kandra_ddl_locks", ddlLockTableName("billing"))
    }

    @Test
    fun `install fails fast on an undeclared entity keyspace without connecting`() {
        val before = cqlSessionsBuilt.get()
        val ex = assertThrows(Exception::class.java) {
            kandraTestApplication {
                application {
                    install(Kandra) {
                        contactPoints = "127.0.0.1:1" // unreachable: must never be dialed
                        keyspace = "core"
                        keyspace("billing") { }
                        register(MkRogueItem::class)
                    }
                }
            }
        }
        var t: Throwable? = ex
        while (t != null && t !is KandraSchemaException) t = t.cause
        assertTrue(t is KandraSchemaException, "expected KandraSchemaException, got $ex")
        assertTrue(t!!.message!!.contains("rogue"))
        assertEquals(before, cqlSessionsBuilt.get())
    }
}
