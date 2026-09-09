package io.kandra.migrate

import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.core.exception.KandraMigrationException
import io.kandra.test.KandraTestcontainers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * GH #84 / ISS-076 -- [KandraMigrationRunnerTest] exercises the per-migration claim/staleness/
 * checksum logic thoroughly, but every one of its scenarios drives the race by hand (a single
 * thread, sequential `run()` calls, hand-inserted rows standing in for "another instance"). That
 * proves the *logic* is correct given a particular interleaving, but never proves the underlying
 * `INSERT ... IF NOT EXISTS` LWT actually serializes real concurrent callers -- exactly the gap
 * `DdlBootstrapClaimTest` already closed for the bootstrap-lock path (GH #79) but which the older,
 * per-migration claim mechanism (ISS-018/ISS-043/ISS-063) never got.
 *
 * This file drives the per-migration claim with a real thread pool and real elapsed wall-clock
 * time -- no mocks, no hand-inserted rows standing in for a race, against a real Testcontainers-
 * backed Cassandra so the real LWT semantics are what's actually being exercised.
 */
class KandraMigrationClaimConcurrencyTest {

    private lateinit var keyspace: String
    private lateinit var session: CqlSession

    private fun freshSession(): CqlSession {
        keyspace = "kandra_migrc_${UUID.randomUUID().toString().replace("-", "")}"
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

    /** A migration whose [up] takes real, measurable time -- widens the window during which racing threads can observe its CLAIMED-but-not-yet-APPLIED row. */
    private class SlowMigration(version: Int, private val delayMs: Long, private val applyCount: AtomicInteger) :
        KandraMigration(version, "slow-migration-$version") {
        override fun up(session: CqlSession) {
            Thread.sleep(delayMs)
            applyCount.incrementAndGet()
        }
    }

    private class SimulatedCrash(message: String) : Error(message)

    private class CrashingMigration(version: Int) : KandraMigration(version, "crashing-migration-$version") {
        override fun up(session: CqlSession) {
            throw SimulatedCrash("simulated unrecoverable crash between claim and completion")
        }
    }

    /**
     * The core scenario: N simulated application instances (each with its own [KandraMigrationRunner]
     * -- distinct objects, exactly as distinct app instances in a rolling deploy would be, sharing
     * only the underlying keyspace/session) all race to claim and apply the same never-before-seen
     * migration version at the same instant. Only one may ever call [KandraMigration.up]; every
     * other racer must cleanly detect the claim and halt without throwing (per
     * [KandraMigrationRunner]'s documented "recently claimed -> warn and halt" behavior) rather than
     * double-applying it or erroring out.
     */
    @Test
    fun `concurrent instances racing to claim a new migration - exactly one applies it, the rest halt without throwing`() {
        val session = freshSession()
        val instanceCount = 8
        val applyCount = AtomicInteger(0)
        val errors = CopyOnWriteArrayList<Throwable>()
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(instanceCount)
        val executor = Executors.newFixedThreadPool(instanceCount)

        repeat(instanceCount) {
            executor.submit {
                try {
                    startLatch.await()
                    // Each racer builds its own runner -- simulating N genuinely separate
                    // application instances starting up concurrently, not N calls on one object.
                    val runner = KandraMigrationRunner(session, staleClaimThreshold = Duration.ofMinutes(10))
                    runner.run(SlowMigration(version = 1, delayMs = 500, applyCount = applyCount))
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

        assertTrue(errors.isEmpty(), "no racer should throw -- an unresolved-but-fresh claim must halt, not error: $errors")
        assertEquals(1, applyCount.get(), "exactly one racer should have actually executed up()")

        val history = KandraMigrationRunner(session).history()
        assertEquals(1, history.size)
        assertEquals(MigrationRowStatus.APPLIED, history.first().status)
    }

    /**
     * Companion scenario proving the *other* half of the guarantee: once the winning racer's
     * migration is genuinely, verifiably APPLIED (not just claimed), every other racer that shows
     * up afterward converges on treating it as done -- no double-apply, no error -- even though they
     * all started racing before the winner finished.
     */
    @Test
    fun `after a concurrent race resolves, every racer's runner agrees the migration is applied exactly once`() {
        val session = freshSession()
        val instanceCount = 6
        val applyCount = AtomicInteger(0)
        val errors = CopyOnWriteArrayList<Throwable>()
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(instanceCount)
        val executor = Executors.newFixedThreadPool(instanceCount)

        repeat(instanceCount) {
            executor.submit {
                try {
                    startLatch.await()
                    val runner = KandraMigrationRunner(session, staleClaimThreshold = Duration.ofMinutes(10))
                    // A little jitter so racers don't all hit the claim in lockstep -- still a real
                    // race, just closer to how independent JVMs actually start up.
                    Thread.sleep((Math.random() * 50).toLong())
                    runner.run(SlowMigration(version = 1, delayMs = 300, applyCount = applyCount))
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    doneLatch.countDown()
                }
            }
        }
        startLatch.countDown()
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS))
        executor.shutdown()

        assertTrue(errors.isEmpty(), "no racer should throw: $errors")
        assertEquals(1, applyCount.get())

        // A fresh runner constructed after the dust settles must see exactly one APPLIED row --
        // every racer converged on the same, correct final state.
        val finalRunner = KandraMigrationRunner(session)
        assertEquals(1, finalRunner.history().size)
        assertEquals(MigrationRowStatus.APPLIED, finalRunner.history().first().status)
    }

    /**
     * Genuine staleness, not a forged `claimed_at`: one instance actually crashes (an `Error`
     * escapes `up()`, leaving its row CLAIMED per the GH-26 crash-safety contract), real wall-clock
     * time actually elapses past the staleness threshold, and only then do several instances
     * concurrently discover the now-stale claim. Per [KandraMigrationRunner]'s documented behavior,
     * the per-migration claim path never auto-reclaims a stale claim the way the DDL-bootstrap lock
     * does (see [DdlBootstrapClaimTest]) -- it always throws, requiring manual operator resolution,
     * specifically because blindly re-running an unknown `up()` body is not safe in general. The
     * concurrency property worth proving here is that this holds under real concurrent discovery
     * too: every single racer that observes the stale claim throws -- none silently proceeds, and
     * none re-executes `up()`.
     */
    @Test
    fun `concurrent instances discovering a genuinely stale claim all throw, and none silently re-applies it`() {
        val session = freshSession()
        val threshold = Duration.ofSeconds(2)

        // Real crash: an Error escapes up(), leaving the row CLAIMED (not APPLIED) -- see
        // KandraMigrationRunnerTest's GH-26 test for the same technique, applied once here to set
        // up a genuine precondition rather than hand-inserting a row.
        val crashingRunner = KandraMigrationRunner(session, staleClaimThreshold = threshold)
        assertTrue(
            runCatching { crashingRunner.run(CrashingMigration(1)) }.exceptionOrNull() is SimulatedCrash
        )

        // Real elapsed time, not a forged timestamp -- past the threshold above.
        Thread.sleep(threshold.toMillis() + 1500)

        val instanceCount = 6
        val applyCount = AtomicInteger(0)
        val thrown = CopyOnWriteArrayList<Throwable>()
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(instanceCount)
        val executor = Executors.newFixedThreadPool(instanceCount)

        repeat(instanceCount) {
            executor.submit {
                try {
                    startLatch.await()
                    val runner = KandraMigrationRunner(session, staleClaimThreshold = threshold)
                    runner.run(SlowMigration(version = 1, delayMs = 50, applyCount = applyCount))
                } catch (t: Throwable) {
                    thrown.add(t)
                } finally {
                    doneLatch.countDown()
                }
            }
        }
        startLatch.countDown()
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals(instanceCount, thrown.size, "every single racer must surface the stale claim as an error")
        assertTrue(thrown.all { it is KandraMigrationException }, "all failures must be KandraMigrationException: $thrown")
        assertTrue(
            thrown.all { it.message!!.contains("CLAIMED") },
            "every failure should reference the unresolved CLAIMED row: $thrown"
        )
        assertEquals(0, applyCount.get(), "up() must never run again while the claim is unresolved")
    }
}
