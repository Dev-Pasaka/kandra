package io.kandra.runtime

import com.datastax.oss.driver.api.core.CqlIdentifier
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.BoundStatement
import com.datastax.oss.driver.api.core.metadata.Metadata
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata
import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.Counter
import io.kandra.core.annotations.LookupIndex
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.Optional
import java.util.UUID

@ScyllaTable("qc_things", keyspace = "ks_a")
data class QcThingA(@PartitionKey val id: UUID, @LookupIndex(tableSuffix = "by_code") val code: String, val label: String? = null)

@ScyllaTable("qc_things")
data class QcThingDefault(@PartitionKey val id: UUID, @LookupIndex(tableSuffix = "by_code") val code: String, val label: String? = null)

@ScyllaTable("qc_hits", keyspace = "ks_a")
data class QcHitsA(@PartitionKey val id: UUID, @Counter val hits: Long = 0L)

/** ISS-104: keyspace-qualified CQL for keyspaced entities, byte-identical bare CQL for null-keyspace ones. */
class StatementBuilderQualifiedCqlTest {

    @AfterEach
    fun tearDown() = SchemaRegistry.clear()

    private fun cql(stmt: BoundStatement) = stmt.recorded().cql

    @Test
    fun `keyspaced entity emits qualified CQL everywhere`() {
        val schema = SchemaRegistry.register(QcThingA::class)
        val lookup = schema.lookupTables.single()
        val b = StatementBuilder(ControllableFakeSession())
        val id = UUID.randomUUID()
        val e = QcThingA(id, "c1", "x")

        assertTrue(cql(b.insertPrimary(schema, e)).startsWith("INSERT INTO ks_a.qc_things ("))
        assertTrue(lookup.qualifiedTableName.startsWith("ks_a."))
        assertTrue(cql(b.insertLookup(schema, lookup, e)).startsWith("INSERT INTO ks_a.qc_things_by_code ("))
        assertTrue(cql(b.deleteLookup(lookup, "c1")).startsWith("DELETE FROM ks_a.qc_things_by_code WHERE"))
        assertTrue(cql(b.selectByLookup(schema, lookup, "c1")).contains("FROM ks_a.qc_things_by_code WHERE"))
        assertTrue(cql(b.selectById(schema, id)).startsWith("SELECT * FROM ks_a.qc_things WHERE"))
        assertTrue(cql(b.selectByPartitionKeyIn(schema, listOf(id))).startsWith("SELECT * FROM ks_a.qc_things WHERE"))
        assertTrue(cql(b.deleteById(schema, id)).startsWith("DELETE FROM ks_a.qc_things WHERE"))
        assertTrue(cql(b.existsQuery(schema, "id = ?", listOf(id))).contains("FROM ks_a.qc_things WHERE"))
    }

    @Test
    fun `counter update emits qualified CQL`() {
        val schema = SchemaRegistry.register(QcHitsA::class)
        val b = StatementBuilder(ControllableFakeSession())
        val stmt = b.counterUpdate(schema, "hits", mapOf("id" to UUID.randomUUID()), 2L)
        assertTrue(cql(stmt).startsWith("UPDATE ks_a.qc_hits SET"), cql(stmt))
    }

    @Test
    fun `null-keyspace entity CQL stays unqualified and byte-identical`() {
        val schema = SchemaRegistry.register(QcThingDefault::class)
        val lookup = schema.lookupTables.single()
        val b = StatementBuilder(ControllableFakeSession())
        val id = UUID.randomUUID()

        assertEquals("qc_things", schema.qualifiedTableName)
        assertTrue(cql(b.insertPrimary(schema, QcThingDefault(id, "c", "l"))).startsWith("INSERT INTO qc_things ("))
        assertTrue(cql(b.insertLookup(schema, lookup, QcThingDefault(id, "c"))).startsWith("INSERT INTO qc_things_by_code ("))
        assertEquals("SELECT * FROM qc_things WHERE id = ?", cql(b.selectById(schema, id)))
        assertEquals("DELETE FROM qc_things WHERE id = ?", cql(b.deleteById(schema, id)))
        assertEquals("DELETE FROM qc_things_by_code WHERE code = ?", cql(b.deleteLookup(lookup, "c")))
    }

    @Test
    fun `same table name in two keyspaces never shares a prepared statement`() {
        val a = SchemaRegistry.register(QcThingA::class)
        val d = SchemaRegistry.register(QcThingDefault::class)
        val b = StatementBuilder(ControllableFakeSession())
        val id = UUID.randomUUID()
        assertNotEquals(cql(b.selectById(a, id)), cql(b.selectById(d, id)))
    }

    // -- RF check resolves the ENTITY's keyspace ------------------------------

    private fun proxy(clazz: Class<*>, respond: (String, Array<Any?>?) -> Any?): Any =
        Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz), InvocationHandler { _, m, args -> respond(m.name, args) })

    /** Session whose current keyspace is "ks_default" (RF=1); metadata also knows ks_a with RF=5. */
    private fun rfSession(): CqlSession {
        fun ks(rf: Int) = proxy(KeyspaceMetadata::class.java) { n, _ ->
            if (n == "getReplication") mapOf("class" to "SimpleStrategy", "replication_factor" to rf.toString()) else null
        }
        val rfs = mapOf("ks_default" to 1, "ks_a" to 5)
        val metadata = proxy(Metadata::class.java) { n, args ->
            if (n == "getKeyspace") Optional.ofNullable(rfs[(args!![0] as CqlIdentifier).asInternal()]?.let { ks(it) }) else null
        }
        return proxy(CqlSession::class.java) { n, _ ->
            when (n) {
                "getKeyspace" -> Optional.of(CqlIdentifier.fromCql("ks_default"))
                "getMetadata" -> metadata
                else -> null
            }
        } as CqlSession
    }

    private fun stderrOf(block: () -> Unit): String {
        val orig = System.err
        val buf = ByteArrayOutputStream()
        System.setErr(PrintStream(buf))
        try { block() } finally { System.setErr(orig) }
        return buf.toString()
    }

    @Test
    fun `strict-mode RF check uses the entity keyspace not the session keyspace`() {
        val a = SchemaRegistry.register(QcThingA::class)         // ks_a, RF=5
        val d = SchemaRegistry.register(QcThingDefault::class)   // null -> session ks_default, RF=1
        val b = StatementBuilder(rfSession(), consistencyConfig = ConsistencyConfig().apply { strictMode = true })
        val m = StatementBuilder::class.java.declaredMethods.single { it.name.startsWith("resolveWriteConsistency") }
            .apply { isAccessible = true }

        val outA = stderrOf { m.invoke(b, a, null) }
        assertTrue(outA.contains("replication factor 5"), "expected RF=5 from ks_a, got: $outA")
        assertTrue(outA.contains("ks_a.qc_things"), "log label should include the keyspace, got: $outA")

        val outD = stderrOf { m.invoke(b, d, null) }
        assertFalse(outD.contains("strictMode"), "RF=1 session keyspace must not warn, got: $outD")
    }
}
