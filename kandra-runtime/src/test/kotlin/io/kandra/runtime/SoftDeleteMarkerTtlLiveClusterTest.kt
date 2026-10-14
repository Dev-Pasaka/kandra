package io.kandra.runtime

import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.core.DdlGenerator
import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.annotations.SoftDelete
import io.kandra.runtime.codec.KandraCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress
import java.util.UUID

/**
 * **Manual, excluded from the default `test` task** (see `tasks.named<Test>("test")` in
 * `build.gradle.kts`, matching `kandra-ktor`'s identical `SslRoundTripIntegrationTest` convention).
 *
 * Connects **directly** to an already-running real ScyllaDB/Cassandra node at `localhost:9042`
 * instead of letting Testcontainers manage its own container — written specifically to verify
 * GH #154/#153's `@SoftDelete` marker-TTL fix against real `USING TTL` expiry, something a scripted
 * `ScriptedCqlSession` can't exercise (real elapsed time, not a mocked clock). Run explicitly via:
 *
 * ```bash
 * ./gradlew :kandra-runtime:softDeleteLiveClusterTest --no-daemon
 * ```
 *
 * Requires a reachable single-node (or any RF-1-reachable) cluster at `localhost:9042`; adjust
 * [LOCAL_DC]/[CONTACT_POINT] below if your local cluster uses a different datacenter name or port.
 */
@Tag("manual")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SoftDeleteMarkerTtlLiveClusterTest {

    companion object {
        private const val CONTACT_POINT = "127.0.0.1"
        private const val CONTACT_PORT = 9042
        private const val LOCAL_DC = "DC2"
    }

    @ScyllaTable("sd_live_cluster_test")
    @SoftDelete(ttlSeconds = 3, markerProperty = "deleted", markerTtlSeconds = 10)
    data class Todo(
        @PartitionKey val id: UUID,
        val deleted: Boolean = false,
        val title: String? = null // nullable, per GH #153's registration-time guard
    )

    private lateinit var session: CqlSession
    private lateinit var keyspace: String

    @BeforeAll
    fun setUp() {
        // ScyllaDB/Cassandra cap keyspace names at 48 chars -- "kandra_live_" (12) + a 32-char
        // no-dash UUID is 44, safely under that.
        keyspace = "kandra_live_${UUID.randomUUID().toString().replace("-", "")}"
        val bootstrap = CqlSession.builder()
            .addContactPoint(InetSocketAddress(CONTACT_POINT, CONTACT_PORT))
            .withLocalDatacenter(LOCAL_DC)
            .build()
        // NetworkTopologyStrategy pinned to DC2 specifically, not SimpleStrategy -- this cluster's
        // gossip ring still has a stale DC1 entry (from earlier multi-DC test infra) whose actual
        // node isn't reachable on this host. SimpleStrategy ignores DC and round-robins replicas by
        // token across the whole ring, so a partition could land its one RF=1 replica on the dead
        // DC1 and fail with UnavailableException at LOCAL_QUORUM -- pin to the DC this test actually
        // connects to.
        bootstrap.execute(
            "CREATE KEYSPACE IF NOT EXISTS $keyspace WITH replication = " +
                "{'class': 'NetworkTopologyStrategy', '$LOCAL_DC': 1}"
        )
        bootstrap.close()

        session = CqlSession.builder()
            .addContactPoint(InetSocketAddress(CONTACT_POINT, CONTACT_PORT))
            .withLocalDatacenter(LOCAL_DC)
            .withKeyspace(keyspace)
            .build()

        val schema = SchemaRegistry.register(Todo::class)
        DdlGenerator.allStatements(schema).forEach { session.execute(it) }
    }

    @AfterAll
    fun tearDown() {
        runCatching { session.execute("DROP KEYSPACE IF EXISTS $keyspace") }
        runCatching { session.close() }
        SchemaRegistry.clear()
    }

    @Test
    fun `soft-deleted row survives past data ttlSeconds, decodes fine, then fully expires at markerTtlSeconds`() {
        val schema = SchemaRegistry.register(Todo::class)
        val codec = KandraCodec.default
        val statementBuilder = StatementBuilder(session, codec)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val batchEngine = BatchEngine(session, statementBuilder, scope)

        val id = UUID.randomUUID()
        batchEngine.save(schema, Todo(id, deleted = false, title = "wash the dishes"))

        // Soft-delete: data columns get USING TTL 2 (ttlSeconds), marker gets USING TTL 5
        // (markerTtlSeconds) -- see the @SoftDelete annotation on Todo above.
        batchEngine.delete(schema, Todo(id, deleted = false, title = "wash the dishes"))

        // Past ttlSeconds (3s) but well before markerTtlSeconds (10s): data columns are NULL,
        // marker column is still `true`. Prior to the GH #153 fix this exact shape (a non-nullable
        // property) would throw KandraQueryException on decode -- Todo.title is nullable here,
        // so the read must succeed cleanly.
        Thread.sleep(5_000)
        val rs = session.execute("SELECT id, deleted, title FROM sd_live_cluster_test WHERE id = $id")
        val row = rs.one()
        requireNotNull(row) { "Row should still exist (husk) after ttlSeconds but before markerTtlSeconds" }
        assertEquals(true, row.getBoolean("deleted"))
        assertNull(row.getString("title"), "Data column should have expired (NULL) past ttlSeconds")

        // Well past markerTtlSeconds (10s total elapsed since soft-delete): the husk should be
        // fully gone -- this is GH #154's actual fix (marker now has a TTL at all, so the row
        // doesn't become a permanent key+marker remnant). Generous margin over the raw 10s to
        // absorb client/server clock and round-trip slack, not just the bare minimum.
        Thread.sleep(8_000)
        val rsAfter = session.execute("SELECT id, deleted, title FROM sd_live_cluster_test WHERE id = $id")
        val rowAfter = rsAfter.one()
        if (rowAfter != null) {
            println(
                "DEBUG: husk row still present ~13s after soft-delete -- " +
                    "deleted=${runCatching { rowAfter.getBoolean("deleted") }}, " +
                    "title=${runCatching { rowAfter.getString("title") }}"
            )
        }
        assertNull(rowAfter, "Husk row should be fully expired well after markerTtlSeconds elapses")

        scope.cancel("test complete")
    }
}
