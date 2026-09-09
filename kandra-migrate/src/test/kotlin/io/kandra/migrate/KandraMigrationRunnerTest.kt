package io.kandra.migrate

import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.core.exception.KandraMigrationException
import io.kandra.test.KandraTestcontainers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Real ScyllaDB/Cassandra-backed tests for [KandraMigrationRunner] (via [KandraTestcontainers] --
 * no fakes). `FakeKandraSession` can't be used here: every path in this runner goes through
 * `session.prepare(cql).bind(...)`, and `FakePreparedStatement.bind()` unconditionally throws
 * `UnsupportedOperationException` (see the kandra-test module), so a fake session would blow up
 * on the very first `claim()` call.
 *
 * Covers claim/skip/checksum-mismatch behavior, plus the GH-26 crash-safety regression: a
 * migration whose `up()` throws an `Error` (not `Exception`) simulates an unrecoverable crash
 * between claiming a version and completing it -- the runner's `catch (e: Exception)` block
 * deliberately does not catch `Error`, so the DELETE cleanup never runs and the row is left
 * CLAIMED. A subsequent `run()` must surface that loudly rather than silently skip or retry it.
 */
class KandraMigrationRunnerTest {

    private lateinit var keyspace: String
    private lateinit var session: CqlSession

    private fun freshSession(): CqlSession {
        // "kandra_migr_" (12 chars) + a 32-char dash-stripped UUID = 44 chars, safely under
        // Cassandra's 48-character keyspace name limit (mirrors the pattern used elsewhere, e.g.
        // KandraPluginTest's "kandra_ktor_" prefix).
        keyspace = "kandra_migr_${UUID.randomUUID().toString().replace("-", "")}"
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

    private class RecordingMigration(version: Int, name: String = "migration-$version") :
        KandraMigration(version, name) {
        var applyCount = 0
        override fun up(session: CqlSession) {
            applyCount++
        }
    }

    private class SimulatedCrash(message: String) : Error(message)

    private class CrashingMigration(version: Int) : KandraMigration(version, "crashing-migration") {
        override fun up(session: CqlSession) {
            throw SimulatedCrash("simulated unrecoverable crash between claim and completion")
        }
    }

    // ── Basic claim / apply / skip behavior ────────────────────────────────────────────────

    @Test
    fun `run applies a new migration and records it as APPLIED`() {
        val session = freshSession()
        val runner = KandraMigrationRunner(session)
        val migration = RecordingMigration(1)

        runner.run(migration)

        assertEquals(1, migration.applyCount)
        val history = runner.history()
        assertEquals(1, history.size)
        assertEquals(MigrationRowStatus.APPLIED, history.first().status)
        assertNotEquals(Instant.EPOCH, history.first().appliedAt)
    }

    @Test
    fun `run skips an already-applied migration on a second call, without re-running up()`() {
        val session = freshSession()
        val runner = KandraMigrationRunner(session)
        val migration = RecordingMigration(1)

        runner.run(migration)
        runner.run(migration)

        assertEquals(1, migration.applyCount)
    }

    @Test
    fun `run applies migrations in ascending version order regardless of vararg order`() {
        val session = freshSession()
        val runner = KandraMigrationRunner(session)
        val v1 = RecordingMigration(1)
        val v2 = RecordingMigration(2)

        runner.run(v2, v1) // passed out of order

        val history = runner.history()
        assertEquals(listOf(1, 2), history.map { it.version })
        assertTrue(history.all { it.status == MigrationRowStatus.APPLIED })
    }

    // ── Checksum mismatch ───────────────────────────────────────────────────────────────────

    @Test
    fun `run throws checksum mismatch when a previously applied migration's identity changes`() {
        val session = freshSession()
        val runner = KandraMigrationRunner(session)
        runner.run(RecordingMigration(1, name = "original-name"))

        val renamed = RecordingMigration(1, name = "renamed")
        val ex = assertThrows(KandraMigrationException::class.java) {
            runner.run(renamed)
        }
        assertTrue(ex.message!!.contains("checksum mismatch"))
    }

    // ── GH #101 / ISS-088: losing the claim race against an already-APPLIED row ───────────
    //
    // claim()'s "lost race" return can carry status = CLAIMED (genuinely in progress/crashed
    // elsewhere) or status = APPLIED (the other instance already finished). These two tests
    // deterministically force the latter, single-threaded: v1's up() simulates "another
    // instance" fully applying v2 (INSERT ... status='APPLIED') *after* run()'s one-time
    // loadApplied() snapshot (taken before v1 ran, so it still misses v2) but *before* the loop
    // reaches v2's own claim() call -- exactly the window the real bug report describes, just
    // pinned to a single thread instead of relying on a real race to land in it.

    @Test
    fun `losing the claim race against an already-APPLIED row verifies checksum and skips, without throwing`() {
        val session = freshSession()
        // Zero threshold: the pre-fix code unconditionally routed every lost race through
        // handleUnresolvedClaim, which treats any non-zero age as exceeding a Duration.ZERO
        // threshold and throws -- so this threshold is what makes the old bug fail loudly here
        // instead of merely warning.
        val runner = KandraMigrationRunner(session, staleClaimThreshold = Duration.ZERO)
        val v2 = RecordingMigration(2)

        val v1 = object : KandraMigration(1, "v1-that-races-v2") {
            override fun up(session: CqlSession) {
                session.execute(
                    "INSERT INTO kandra_migrations (version, name, status, claimed_at, applied_at, checksum) " +
                    "VALUES (2, ?, 'APPLIED', ?, ?, ?)",
                    v2.name, Instant.now(), Instant.now(), v2.checksum()
                )
            }
        }

        runner.run(v1, v2) // must not throw despite the zero staleness threshold

        assertEquals(0, v2.applyCount) // v2's up() must never run -- another instance already finished it
        val history = runner.history()
        assertEquals(2, history.size)
        assertEquals(MigrationRowStatus.APPLIED, history.first { it.version == 2 }.status)
    }

    @Test
    fun `losing the claim race against an already-APPLIED row with a mismatched checksum still throws`() {
        val session = freshSession()
        val runner = KandraMigrationRunner(session, staleClaimThreshold = Duration.ZERO)
        val v2 = RecordingMigration(2)

        val v1 = object : KandraMigration(1, "v1-that-races-v2-with-bad-checksum") {
            override fun up(session: CqlSession) {
                session.execute(
                    "INSERT INTO kandra_migrations (version, name, status, claimed_at, applied_at, checksum) " +
                    "VALUES (2, ?, 'APPLIED', ?, ?, 'stale-mismatched-checksum')",
                    v2.name, Instant.now(), Instant.now()
                )
            }
        }

        val ex = assertThrows(KandraMigrationException::class.java) {
            runner.run(v1, v2)
        }
        assertTrue(ex.message!!.contains("checksum mismatch"))
        assertEquals(0, v2.applyCount)
    }

    // ── Unresolved CLAIMED rows: fresh (halt, no throw) vs. stale (throw) ──────────────────

    @Test
    fun `a fresh CLAIMED row halts run before applying later migrations, without throwing`() {
        val session = freshSession()
        // Generous threshold -- this claim is "just now", so it must be treated as possibly live.
        val runner = KandraMigrationRunner(session, staleClaimThreshold = Duration.ofMinutes(10))

        // Simulate another instance having just claimed version 1 (its up() presumed mid-flight).
        session.execute(
            "INSERT INTO kandra_migrations (version, name, status, claimed_at, checksum) " +
            "VALUES (1, 'in-flight-elsewhere', 'CLAIMED', ?, 'irrelevant-checksum')",
            Instant.now()
        )

        val v1 = RecordingMigration(1)
        val v2 = RecordingMigration(2)

        runner.run(v1, v2) // must not throw

        assertEquals(0, v1.applyCount) // v1 is claimed elsewhere -- we must not touch it
        assertEquals(0, v2.applyCount) // v2 must not run ahead of an unresolved v1
    }

    @Test
    fun `a stale CLAIMED row throws instead of being silently skipped or retried`() {
        val session = freshSession()
        // Zero threshold -- any elapsed time counts as stale, no need to sleep in the test.
        val runner = KandraMigrationRunner(session, staleClaimThreshold = Duration.ZERO)

        session.execute(
            "INSERT INTO kandra_migrations (version, name, status, claimed_at, checksum) " +
            "VALUES (1, 'abandoned', 'CLAIMED', ?, 'irrelevant-checksum')",
            Instant.now().minus(1, ChronoUnit.HOURS)
        )

        val migration = RecordingMigration(1)
        val ex = assertThrows(KandraMigrationException::class.java) {
            runner.run(migration)
        }
        assertEquals(0, migration.applyCount) // never silently re-run
        assertTrue(ex.message!!.contains("CLAIMED"))
        assertTrue(ex.message!!.contains("kandra_migrations"))
    }

    // ── GH-26 crash-safety regression ──────────────────────────────────────────────────────

    @Test
    fun `a migration crashing with an Error leaves its row CLAIMED, and a later run surfaces it loudly`() {
        val session = freshSession()
        val crashingRunner = KandraMigrationRunner(session)
        val migration = CrashingMigration(1)

        // The Error propagates out of run() uncaught -- catch (e: Exception) does not catch it,
        // so the DELETE cleanup that normally runs on a genuine synchronous failure is skipped.
        assertThrows(SimulatedCrash::class.java) {
            crashingRunner.run(migration)
        }

        val history = crashingRunner.history()
        assertEquals(1, history.size)
        assertEquals(MigrationRowStatus.CLAIMED, history.first().status)
        assertEquals(Instant.EPOCH, history.first().appliedAt) // never confirmed complete

        // A subsequent boot -- even on a fresh runner instance, as a real restart would be --
        // must not silently skip past this (it isn't APPLIED) or silently re-run it (unsafe if
        // it's genuinely still in flight elsewhere). It must be surfaced as an actionable error.
        // Zero threshold stands in for "well past the staleness window" without sleeping in the test.
        val recoveryRunner = KandraMigrationRunner(session, staleClaimThreshold = Duration.ZERO)
        val ex = assertThrows(KandraMigrationException::class.java) {
            recoveryRunner.run(CrashingMigration(1))
        }
        assertTrue(ex.message!!.contains("CLAIMED"))
        assertTrue(ex.message!!.contains("crashed"))
    }

    // ── GH-64: staleness is measured by the DB cluster's clock, not app instance clocks ───

    @Test
    fun `claim() writes claimed_at via the cluster's own clock, not this JVM's Instant now`() {
        val session = freshSession()
        val runner = KandraMigrationRunner(session)
        // A migration whose up() blocks briefly, so the test can read the row while it's still
        // CLAIMED -- i.e. observe exactly what claim() persisted, with nothing else touching it.
        val latch = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blocking = object : KandraMigration(1, "blocking-migration") {
            override fun up(session: CqlSession) {
                latch.countDown()
                release.await()
            }
        }

        val thread = Thread { runner.run(blocking) }
        thread.start()
        latch.await()

        // Independently ask the same cluster what time it thinks it is right now.
        val serverNowRow = session.execute("SELECT toTimestamp(now()) AS n FROM kandra_migrations WHERE version = 1").one()!!
        val serverNow = serverNowRow.getInstant("n")!!

        val claimedRow = session.execute("SELECT claimed_at FROM kandra_migrations WHERE version = 1").one()!!
        val claimedAt = claimedRow.getInstant("claimed_at")!!

        release.countDown()
        thread.join()

        // claimed_at was assigned by the same cluster clock we just queried independently --
        // regardless of what this test JVM's own Instant.now() says, the two must be close.
        assertTrue(
            Duration.between(claimedAt, serverNow).abs() < Duration.ofSeconds(10),
            "claimed_at ($claimedAt) should be close to the cluster's own concept of now ($serverNow)"
        )
    }

    @Test
    fun `a claim that becomes stale purely through real elapsed time is detected via the cluster clock`() {
        val session = freshSession()
        // A tiny threshold plus a real sleep -- proves staleness is measured by actually letting
        // time pass and asking the cluster, end to end, rather than by forging a claimed_at value.
        val threshold = Duration.ofSeconds(2)
        val claimingRunner = KandraMigrationRunner(session, staleClaimThreshold = threshold)
        val migration = CrashingMigration(1)

        assertThrows(SimulatedCrash::class.java) { claimingRunner.run(migration) }
        // Row is left CLAIMED (see the GH-26 crash-safety test above for why).

        Thread.sleep(threshold.toMillis() + 1500)

        val checkingRunner = KandraMigrationRunner(session, staleClaimThreshold = threshold)
        val ex = assertThrows(KandraMigrationException::class.java) {
            checkingRunner.run(CrashingMigration(1))
        }
        assertTrue(ex.message!!.contains("exceeds the staleness threshold"))
    }

    @Test
    fun `a claim that is not yet stale by real elapsed time is treated as still in-progress`() {
        val session = freshSession()
        val threshold = Duration.ofMinutes(10)
        val claimingRunner = KandraMigrationRunner(session, staleClaimThreshold = threshold)
        val migration = CrashingMigration(1)

        assertThrows(SimulatedCrash::class.java) { claimingRunner.run(migration) }

        // No sleep: essentially zero real time has elapsed since the claim, so this must be
        // treated as (still) fresh purely by asking the cluster for its own current time, with
        // no dependence on any Instant.now() this JVM produces.
        val checkingRunner = KandraMigrationRunner(session, staleClaimThreshold = threshold)
        val v2 = RecordingMigration(2)
        checkingRunner.run(CrashingMigration(1), v2) // must not throw

        assertEquals(0, v2.applyCount) // still halts before later migrations
        assertEquals(MigrationRowStatus.CLAIMED, checkingRunner.history().first { it.version == 1 }.status)
    }

    // ── Backward compatibility: legacy rows with no status column value ───────────────────

    @Test
    fun `a legacy row with no status value is treated as applied`() {
        val session = freshSession()
        // Bootstrap the table via a real runner first so status/claimed_at columns exist, then
        // write a row the way a pre-GH-26 Kandra version would have -- no status, no claimed_at.
        KandraMigrationRunner(session)
        session.execute(
            "INSERT INTO kandra_migrations (version, name, applied_at, checksum) " +
            "VALUES (1, 'legacy-migration', ?, ?)",
            Instant.now(),
            RecordingMigration(1, name = "legacy-migration").checksum()
        )

        val runner = KandraMigrationRunner(session)
        val migration = RecordingMigration(1, name = "legacy-migration")

        runner.run(migration) // must skip, not throw and not re-apply

        assertEquals(0, migration.applyCount)
    }
}
