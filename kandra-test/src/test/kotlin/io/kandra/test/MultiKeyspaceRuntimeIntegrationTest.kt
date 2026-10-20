package io.kandra.test

import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.core.DdlGenerator
import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.Counter
import io.kandra.core.annotations.LookupIndex
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.annotations.SoftDelete
import io.kandra.runtime.BatchEngine
import io.kandra.runtime.ConsistencyConfig
import io.kandra.runtime.KandraRuntime
import io.kandra.runtime.StatementBuilder
import io.kandra.runtime.codec.KandraCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.UUID

private const val KS_A = "kt_rt104_a"
private const val KS_B = "kt_rt104_b"

// Same table name "mk_items" in both keyspaces, different shapes and different data.
@ScyllaTable("mk_items", keyspace = KS_A)
data class MkItemA(
    @PartitionKey val id: UUID,
    @LookupIndex(tableSuffix = "by_code") val code: String,
    val name: String
)

@ScyllaTable("mk_items", keyspace = KS_B)
data class MkItemB(
    @PartitionKey val id: UUID,
    val label: String,
    val qty: Int
)

@ScyllaTable("mk_counters", keyspace = KS_A)
data class MkCounterA(@PartitionKey val id: UUID, @Counter val hits: Long = 0L)

@ScyllaTable("mk_counters", keyspace = KS_B)
data class MkCounterB(@PartitionKey val id: UUID, @Counter val hits: Long = 0L)

@ScyllaTable("mk_soft", keyspace = KS_B)
@SoftDelete(ttlSeconds = 3600, markerProperty = "isDeleted")
data class MkSoftB(
    @PartitionKey val id: UUID,
    val name: String = "",
    val isDeleted: Boolean = false
)

/**
 * ISS-104: two keyspaces holding same-named tables with different entities, driven through one
 * session that has NO current keyspace -- every statement must be fully qualified.
 * Docker (Testcontainers) by default; `KANDRA_TEST_BACKEND=cloud` runs against the KANDRA_CLOUD_* cluster.
 */
@OptIn(ExperimentalKandraApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiKeyspaceRuntimeIntegrationTest {

    private val cloud = System.getenv("KANDRA_TEST_BACKEND")?.lowercase() == "cloud"
    private lateinit var session: CqlSession
    private lateinit var runtime: KandraRuntime
    private lateinit var scope: CoroutineScope

    private fun newSession(): CqlSession =
        KandraTestBackend.newSession()

    private fun createKeyspace(name: String, rf: Int) {
        session.execute("DROP KEYSPACE IF EXISTS $name")
        session.execute(
            "CREATE KEYSPACE $name WITH replication = {'class': 'SimpleStrategy', 'replication_factor': $rf}" +
                if (cloud) " AND tablets = {'enabled': false}" else ""
        )
    }

    @BeforeAll
    fun setUp() {
        if (cloud) KandraCloud.assumeConfigured()
        session = newSession()
        // Cloud: RF=3 for both. Docker (one node): RF=1 so LOCAL_QUORUM works; the RF-check test
        // temporarily ALTERs KS_A to RF=3 (metadata only).
        createKeyspace(KS_A, if (cloud) 3 else 1)
        createKeyspace(KS_B, if (cloud) 3 else 1)
        for (k in listOf(MkItemA::class, MkItemB::class, MkCounterA::class, MkCounterB::class, MkSoftB::class)) {
            DdlGenerator.allStatements(SchemaRegistry.register(k)).forEach { session.execute(it) }
        }
        val codec = KandraCodec.default
        val builder = StatementBuilder(session, codec)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runtime = KandraRuntime(session, BatchEngine(session, builder, scope), codec)
    }

    @AfterAll
    fun tearDown() {
        runCatching { session.execute("DROP KEYSPACE IF EXISTS $KS_A") }
        runCatching { session.execute("DROP KEYSPACE IF EXISTS $KS_B") }
        runCatching { session.close() }
        if (::scope.isInitialized) scope.cancel()
    }

    @Test
    fun `CRUD and lookup table work independently in two keyspaces with the same table name`() = runBlocking {
        val repoA = runtime.suspendRepository<MkItemA>()
        val repoB = runtime.suspendRepository<MkItemB>()
        val id = UUID.randomUUID() // same id in both keyspaces on purpose
        repoA.save(MkItemA(id, "code-1", "from A"))
        repoB.save(MkItemB(id, "from B", 7))

        assertEquals("from A", repoA.findById(id)?.name)
        assertEquals("from B", repoB.findById(id)?.label)
        assertEquals(7, repoB.findById(id)?.qty)

        // lookup table (ks_a.mk_items_by_code)
        assertEquals(id, repoA.find { MkItemATable.code eq "code-1" }?.id)

        repoA.update(MkItemA(id, "code-1", "from A"), MkItemA(id, "code-1", "A renamed"))
        assertEquals("A renamed", repoA.findById(id)?.name)
        assertEquals("from B", repoB.findById(id)?.label) // untouched

        repoA.delete(MkItemA(id, "code-1", "A renamed"))
        assertNull(repoA.findById(id))
        assertNotNull(repoB.findById(id))
        assertNull(repoA.find { MkItemATable.code eq "code-1" })

        assertEquals(
            1L,
            session.execute("SELECT count(*) FROM $KS_B.mk_items").one()!!.getLong(0)
        )
    }

    @Test
    fun `one logged batch spans both keyspaces`() = runBlocking {
        val repoA = runtime.suspendRepository<MkItemA>()
        val repoB = runtime.suspendRepository<MkItemB>()
        val a = MkItemA(UUID.randomUUID(), "batch-code", "batched A")
        val b = MkItemB(UUID.randomUUID(), "batched B", 3)

        runtime.batch {
            repoA.saveInBatch(a)
            repoB.saveInBatch(b)
        }
        assertEquals("batched A", repoA.findById(a.id)?.name)
        assertEquals(a.id, repoA.find { MkItemATable.code eq "batch-code" }?.id)
        assertEquals("batched B", repoB.findById(b.id)?.label)

        runtime.batch {
            repoA.deleteInBatch(a)
            repoB.deleteInBatch(b)
        }
        assertNull(repoA.findById(a.id))
        assertNull(repoB.findById(b.id))
    }

    @Test
    fun `soft delete in a non-default keyspace`() = runBlocking {
        val repo = runtime.suspendRepository<MkSoftB>()
        val e = MkSoftB(UUID.randomUUID(), "soft")
        repo.save(e)
        repo.delete(e)
        assertNotNull(repo.findById(e.id)) // TTL hasn't expired yet
        assertFalse(repo.findActive(allowFullScan = true).any { it.id == e.id })
    }

    @Test
    fun `counters with the same table name in two keyspaces stay separate`() = runBlocking {
        val repoA = runtime.suspendRepository<MkCounterA>()
        val repoB = runtime.suspendRepository<MkCounterB>()
        val id = UUID.randomUUID()
        repoA.increment(MkCounterA::hits, mapOf("id" to id), 5L)
        repoB.increment(MkCounterB::hits, mapOf("id" to id), 2L)
        repoB.decrement(MkCounterB::hits, mapOf("id" to id), 1L)

        assertEquals(5L, session.execute("SELECT hits FROM $KS_A.mk_counters WHERE id = $id").one()!!.getLong(0))
        assertEquals(1L, session.execute("SELECT hits FROM $KS_B.mk_counters WHERE id = $id").one()!!.getLong(0))
    }

    @Test
    fun `strict-mode RF check resolves each entity's own keyspace`() {
        val builder = StatementBuilder(session, consistencyConfig = ConsistencyConfig().apply { strictMode = true })
        val m = StatementBuilder::class.java.declaredMethods
            .single { it.name.startsWith("resolveWriteConsistency") }.apply { isAccessible = true }
        val schemaA = SchemaRegistry.get(MkItemA::class) // RF=3
        val schemaB = SchemaRegistry.get(MkItemB::class) // RF=1 on Docker

        if (!cloud) alterRf(KS_A, 3)
        try {
            // Session has no current keyspace: before ISS-104 neither would be checked at all.
            val outA = captureStderr { m.invoke(builder, schemaA, null) }
            assertTrue(outA.contains("replication factor 3"), "expected RF=3 warning for $KS_A, got: $outA")
            assertTrue(outA.contains("$KS_A.mk_items"), "label should include the keyspace, got: $outA")

            if (!cloud) {
                val outB = captureStderr { m.invoke(builder, schemaB, null) }
                assertFalse(outB.contains("strictMode"), "RF=1 keyspace must not warn, got: $outB")
            }
        } finally {
            if (!cloud) alterRf(KS_A, 1)
        }
    }

    private fun alterRf(ks: String, rf: Int) {
        session.execute("ALTER KEYSPACE $ks WITH replication = {'class': 'SimpleStrategy', 'replication_factor': $rf}")
        session.refreshSchema()
    }

    private fun captureStderr(block: () -> Unit): String {
        val original = System.err
        val buffer = ByteArrayOutputStream()
        System.setErr(PrintStream(buffer))
        try { block() } finally { System.setErr(original) }
        return buffer.toString()
    }
}
