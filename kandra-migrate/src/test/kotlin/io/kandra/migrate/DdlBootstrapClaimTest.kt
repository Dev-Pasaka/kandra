package io.kandra.migrate

import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.test.KandraTestcontainers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * GH #79 / ISS-071 -- proves the LWT-claim guard added to [KandraMigrationRunner]'s bootstrap (and
 * shared, by design, with `kandra-ktor`'s identical mechanism) actually serializes concurrent
 * instances rather than letting them race `CREATE TABLE`/`ALTER TABLE` against the same keyspace.
 *
 * Run against a real Testcontainers-backed Cassandra ([KandraTestcontainers]), with genuinely
 * concurrent callers -- not mocks -- because the whole point is exercising the real `IF NOT
 * EXISTS`/compare-and-set LWT semantics under real concurrency, which a fake session can't provide.
 */
class DdlBootstrapClaimTest {

    private lateinit var keyspace: String
    private lateinit var session: CqlSession

    private fun freshSession(): CqlSession {
        keyspace = "kandra_ddlck_${UUID.randomUUID().toString().replace("-", "")}"
        val contactPoint = KandraTestcontainers.container.contactPoint
        val localDc = KandraTestcontainers.container.localDatacenter

        CqlSession.builder()
            .addContactPoint(contactPoint)
            .withLocalDatacenter(localDc)
            .build().use {
                it.execute(
                    "CREATE KEYSPACE IF NOT EXISTS $keyspace WITH replication = " +
                    "{'class': 'SimpleStrategy', 'replication_factor': 1}"
                )
            }

        session = CqlSession.builder()
            .addContactPoint(contactPoint)
            .withLocalDatacenter(localDc)
            .withKeyspace(keyspace)
            .build()
        return session
    }

    @AfterEach
    fun cleanup() {
        runCatching { session.execute("DROP KEYSPACE IF EXISTS $keyspace") }
        runCatching { session.close() }
    }

    /**
     * The core scenario from GH #79: several instances start concurrently against the same
     * keyspace and all independently invoke the schema-bootstrap path. Asserts:
     * (a) the guarded DDL ends up correctly applied exactly once in effect (the marker table
     *     exists and is queryable afterward),
     * (b) no instance threw -- in particular, no schema-disagreement-shaped error surfaced,
     * (c) exactly one instance actually executed the claim-holder action; the rest detected the
     *     claim and skipped/waited, proven directly via a counter rather than inferred from timing.
     */
    @Test
    fun `concurrent instances racing the same DDL bootstrap claim - exactly one runs the DDL, others wait and skip`() {
        val session = freshSession()
        val instanceCount = 8
        val executionCount = AtomicInteger(0)
        val errors = CopyOnWriteArrayList<Throwable>()
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(instanceCount)
        val executor = Executors.newFixedThreadPool(instanceCount)

        repeat(instanceCount) {
            executor.submit {
                try {
                    startLatch.await()
                    // This is literally the function KandraMigrationRunner's init block (and
                    // kandra-ktor's Kandra.kt install path) calls -- simulating N app instances
                    // each independently invoking the real schema-bootstrap path on startup.
                    claimAndRunDdlBootstrap(session, "concurrent-test-lock") {
                        executionCount.incrementAndGet()
                        session.execute("CREATE TABLE IF NOT EXISTS bootstrap_marker (id int PRIMARY KEY, note text)")
                        session.execute("ALTER TABLE bootstrap_marker ADD extra_col text")
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    doneLatch.countDown()
                }
            }
        }
        startLatch.countDown() // release every thread at (as close to) the same instant as possible
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "all simulated instances should finish within the timeout")
        executor.shutdown()

        assertTrue(errors.isEmpty(), "no instance should see a schema-disagreement-shaped error: $errors")
        assertEquals(1, executionCount.get(), "exactly one instance should have run the guarded DDL action")

        val tableRow = session.execute(
            "SELECT table_name FROM system_schema.tables WHERE keyspace_name = ? AND table_name = ?",
            keyspace, "bootstrap_marker"
        ).one()
        assertNotNull(tableRow, "the guarded DDL must still have been applied exactly once in effect")

        val lockRow = session.execute(
            "SELECT status, holder FROM kandra_ddl_locks WHERE lock_name = ?", "concurrent-test-lock"
        ).one()
        assertEquals("DONE", lockRow?.getString("status"))
    }

    /**
     * Companion scenario exercising the exact real-world entry point (`KandraMigrationRunner`'s
     * `init` block) rather than the internal helper directly: several instances constructing a
     * runner concurrently against a keyspace with no `kandra_migrations` table yet must not race
     * its bootstrap, must all complete without error, and the resulting table/runner must be fully
     * usable afterward.
     */
    @Test
    fun `constructing KandraMigrationRunner concurrently from many simulated instances safely bootstraps kandra_migrations exactly once`() {
        val session = freshSession()
        val instanceCount = 6
        val errors = CopyOnWriteArrayList<Throwable>()
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(instanceCount)
        val executor = Executors.newFixedThreadPool(instanceCount)

        repeat(instanceCount) {
            executor.submit {
                try {
                    startLatch.await()
                    KandraMigrationRunner(session) // simulates one app instance starting up
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    doneLatch.countDown()
                }
            }
        }
        startLatch.countDown()
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "all simulated instances should finish within the timeout")
        executor.shutdown()

        assertTrue(errors.isEmpty(), "no instance should see a schema-disagreement-shaped error: $errors")

        val columns = session.execute("SELECT * FROM kandra_migrations LIMIT 1")
            .columnDefinitions.map { it.name.toString() }.toSet()
        assertTrue(
            columns.containsAll(setOf("version", "name", "status", "claimed_at", "applied_at", "checksum")),
            "kandra_migrations should have its full expected schema after the race: $columns"
        )

        val lockRow = session.execute(
            "SELECT status FROM kandra_ddl_locks WHERE lock_name = ?", "kandra-migrations-bootstrap"
        ).one()
        assertEquals("DONE", lockRow?.getString("status"))

        // The table isn't just present -- it's actually usable afterward.
        val runner = KandraMigrationRunner(session)
        val migration = object : KandraMigration(1, "post-race-migration") {
            override fun up(session: CqlSession) { /* no-op -- proves run() works end-to-end */ }
        }
        runner.run(migration)
        assertEquals(1, runner.history().size)
        assertEquals(MigrationRowStatus.APPLIED, runner.history().first().status)
    }

    /**
     * Staleness/timeout handling: a claim left behind by a crashed instance (simulated here by
     * hand-inserting a `CLAIMED` row with an old `claimed_at`, the same technique
     * [KandraMigrationRunnerTest] uses for the analogous per-migration staleness tests) must
     * eventually be reclaimed and completed by a later instance, rather than blocking startup
     * forever.
     */
    @Test
    fun `a stale DDL bootstrap claim left by a crashed instance is reclaimed and completed`() {
        val session = freshSession()
        // Bootstrap kandra_ddl_locks itself first (mirrors what a real first caller would do).
        session.execute(
            """
            CREATE TABLE IF NOT EXISTS kandra_ddl_locks (
                lock_name   TEXT PRIMARY KEY,
                holder      TEXT,
                status      TEXT,
                claimed_at  TIMESTAMP
            )
            """.trimIndent()
        )
        // Simulate a crashed claimant: CLAIMED long enough ago to exceed the (fixed, 2-minute)
        // staleness threshold, with no DONE ever following.
        session.execute(
            "INSERT INTO kandra_ddl_locks (lock_name, holder, status, claimed_at) VALUES (?, ?, 'CLAIMED', ?)",
            "stale-lock", "crashed-instance", Instant.now().minus(1, ChronoUnit.HOURS)
        )

        val executionCount = AtomicInteger(0)
        claimAndRunDdlBootstrap(session, "stale-lock") {
            executionCount.incrementAndGet()
        }

        assertEquals(1, executionCount.get(), "a stale claim must eventually be reclaimed and its action run")
        val lockRow = session.execute("SELECT status, holder FROM kandra_ddl_locks WHERE lock_name = ?", "stale-lock").one()
        assertEquals("DONE", lockRow?.getString("status"))
        assertTrue(lockRow?.getString("holder") != "crashed-instance", "the reclaiming instance should be a different holder")
    }
}
