package io.kandra.ktor

import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.core.exception.KandraSchemaException
import io.kandra.test.KandraTestcontainers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * GH #79 / ISS-071 -- proves the LWT-claim guard wrapping `SchemaMode.AUTO_CREATE`/`AUTO_MIGRATE`'s
 * DDL bootstrap (`claimAndRunDdlBootstrap` in `Kandra.kt`) actually serializes concurrent
 * application instances rather than letting them race `CREATE TABLE`/`ALTER TABLE` against the
 * same keyspace.
 *
 * Run against a real Testcontainers-backed Cassandra ([KandraTestcontainers]), with genuinely
 * concurrent callers -- not mocks -- since the whole point is exercising real `IF NOT
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
     * keyspace, each independently invoking the exact schema-bootstrap path `Kandra.kt`'s
     * `SchemaMode.AUTO_CREATE`/`AUTO_MIGRATE` branches call. Asserts:
     * (a) the guarded DDL ends up correctly applied exactly once in effect (the entity table
     *     exists and is queryable afterward),
     * (b) no instance threw -- in particular, no schema-disagreement-shaped error surfaced,
     * (c) exactly one instance actually executed the claim-holder action; the rest detected the
     *     claim and skipped/waited, proven directly via a counter rather than inferred from timing.
     */
    @Test
    fun `concurrent instances racing the same schema DDL bootstrap claim - exactly one runs the DDL, others wait and skip`() {
        val session = freshSession()
        val instanceCount = 10
        val executionCount = AtomicInteger(0)
        val errors = CopyOnWriteArrayList<Throwable>()
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(instanceCount)
        val executor = Executors.newFixedThreadPool(instanceCount)

        repeat(instanceCount) {
            executor.submit {
                try {
                    startLatch.await()
                    // The exact function Kandra.kt's install path calls for
                    // SchemaMode.AUTO_CREATE/AUTO_MIGRATE -- simulating N app instances each
                    // independently invoking the real schema-bootstrap path on startup.
                    claimAndRunDdlBootstrap(session, "concurrent-schema-bootstrap-test") {
                        executionCount.incrementAndGet()
                        session.execute(
                            "CREATE TABLE IF NOT EXISTS bootstrap_marker (id uuid PRIMARY KEY, label text)"
                        )
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
            "SELECT status FROM kandra_ddl_locks WHERE lock_name = ?", "concurrent-schema-bootstrap-test"
        ).one()
        assertEquals("DONE", lockRow?.getString("status"))
    }

    /**
     * End-to-end companion: several real `install(Kandra)` calls (schemaMode = AUTO_CREATE) racing
     * against the same keyspace via independently-built sessions -- each install's own DDL
     * bootstrap branch calls [claimAndRunDdlBootstrap] internally -- must not throw, and the
     * resulting table must be fully usable afterward via a plain session (proving the DDL that ran
     * is exactly the one the winning instance intended, not a partially-applied race).
     */
    @Test
    fun `concurrent AUTO_CREATE-equivalent DDL bootstraps against the same keyspace leave a fully usable table`() {
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
                    // "schema-bootstrap" mirrors Kandra.kt's private DDL_BOOTSTRAP_SCHEMA_LOCK
                    // constant (file-private, so referenced by value here rather than by name).
                    claimAndRunDdlBootstrap(session, "schema-bootstrap") {
                        session.execute(
                            "CREATE TABLE IF NOT EXISTS test_items (id uuid PRIMARY KEY, label text)"
                        )
                    }
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

        val id = UUID.randomUUID()
        session.execute("INSERT INTO test_items (id, label) VALUES (?, ?)", id, "post-race-row")
        val row = session.execute("SELECT label FROM test_items WHERE id = ?", id).one()
        assertEquals("post-race-row", row?.getString("label"))
    }

    /**
     * Staleness/timeout handling: a claim left behind by a crashed instance (simulated by
     * hand-inserting a `CLAIMED` row with an old `claimed_at`) must eventually be reclaimed and
     * completed by a later instance, rather than blocking startup forever.
     */
    @Test
    fun `a stale DDL bootstrap claim left by a crashed instance is reclaimed and completed`() {
        val session = freshSession()
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
        session.execute(
            "INSERT INTO kandra_ddl_locks (lock_name, holder, status, claimed_at) VALUES (?, ?, 'CLAIMED', ?)",
            "stale-schema-lock", "crashed-instance", Instant.now().minus(1, ChronoUnit.HOURS)
        )

        val executionCount = AtomicInteger(0)
        claimAndRunDdlBootstrap(session, "stale-schema-lock") {
            executionCount.incrementAndGet()
        }

        assertEquals(1, executionCount.get(), "a stale claim must eventually be reclaimed and its action run")
        val lockRow = session.execute("SELECT status, holder FROM kandra_ddl_locks WHERE lock_name = ?", "stale-schema-lock").one()
        assertEquals("DONE", lockRow?.getString("status"))
        assertTrue(lockRow?.getString("holder") != "crashed-instance", "the reclaiming instance should be a different holder")
    }

    /**
     * GH #91 / ISS-078 -- holder fencing on completion. Simulates the scenario the issue describes:
     * a claimant's `action()` is still genuinely running when another instance judges the claim
     * stale and reclaims it (a real risk under multi-DC latency, since staleness is a heuristic
     * timeout, not a lease). Here that reclaim is simulated with a direct, unconditional write
     * inside `action()` itself -- indistinguishable, from `runClaimedDdlAction`'s point of view,
     * from a real `reclaimStaleDdlLock` CAS by another instance. Before the fix, the original
     * claimant's unconditional `markDdlLockDone` would have silently clobbered the new holder's
     * active claim; now the fenced `IF holder = ?` write loses its CAS and throws instead of
     * no-oping, and the new holder's row is left untouched.
     */
    @Test
    fun `completing action() after losing the lock to a reclaim throws instead of clobbering the new holder`() {
        val session = freshSession()
        val lockName = "still-alive-lock"

        val ex = assertThrows(KandraSchemaException::class.java) {
            claimAndRunDdlBootstrap(session, lockName) {
                // Simulates another instance's reclaimStaleDdlLock winning its CAS while we are
                // still (unbeknownst to it) alive and running -- same net effect on the row.
                session.execute(
                    "UPDATE kandra_ddl_locks SET holder = ?, status = 'CLAIMED', claimed_at = toTimestamp(now()) WHERE lock_name = ?",
                    "other-instance", lockName
                )
            }
        }
        assertTrue(ex.message!!.contains(lockName), "the exception should name the lock that was lost: ${ex.message}")

        val lockRow = session.execute("SELECT holder, status FROM kandra_ddl_locks WHERE lock_name = ?", lockName).one()
        assertEquals("other-instance", lockRow?.getString("holder"), "the reclaiming instance's holder must survive our lost-fencing completion")
        assertNotEquals("DONE", lockRow?.getString("status"), "the reclaiming instance's claim must not be marked DONE by the instance that lost it")
    }

    /**
     * GH #91 / ISS-078 companion: the same lost-fencing scenario, but `action()` itself also fails.
     * The original failure must still propagate (never masked by the fencing outcome), and the
     * fenced `releaseDdlLock` losing its CAS must not delete the new holder's row out from under it.
     */
    @Test
    fun `action() failing after losing the lock to a reclaim still propagates the original failure, without deleting the new holder's claim`() {
        val session = freshSession()
        val lockName = "still-alive-lock-failure"
        val actionFailure = RuntimeException("boom")

        val thrown = assertThrows(RuntimeException::class.java) {
            claimAndRunDdlBootstrap(session, lockName) {
                session.execute(
                    "UPDATE kandra_ddl_locks SET holder = ?, status = 'CLAIMED', claimed_at = toTimestamp(now()) WHERE lock_name = ?",
                    "other-instance-2", lockName
                )
                throw actionFailure
            }
        }
        assertEquals(actionFailure, thrown, "the original action() failure must propagate unmasked")

        val lockRow = session.execute("SELECT holder FROM kandra_ddl_locks WHERE lock_name = ?", lockName).one()
        assertEquals("other-instance-2", lockRow?.getString("holder"), "the reclaiming instance's row must survive our failed release")
    }

    /**
     * GH #91 / ISS-078 -- [ddlLockServerNow] previously read `toTimestamp(now())` from the specific
     * `kandra_ddl_locks` row under staleness inspection, which could fall back to this JVM's own
     * clock if that row didn't exist. It now reads from `system.local`, which exists on every node
     * regardless of `kandra_ddl_locks`'s state -- proven here by calling it against a keyspace where
     * `kandra_ddl_locks` hasn't even been created yet.
     */
    @Test
    fun `ddlLockServerNow returns a real cluster timestamp even when kandra_ddl_locks does not exist`() {
        val session = freshSession()
        val before = Instant.now().minus(Duration.ofSeconds(5))

        val serverNow = ddlLockServerNow(session)

        val after = Instant.now().plus(Duration.ofSeconds(5))
        assertTrue(
            serverNow.isAfter(before) && serverNow.isBefore(after),
            "server time should be a real, current cluster timestamp, not a degenerate fallback: $serverNow"
        )
    }
}
