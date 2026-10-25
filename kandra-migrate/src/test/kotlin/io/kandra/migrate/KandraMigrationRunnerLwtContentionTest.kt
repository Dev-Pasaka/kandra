package io.kandra.migrate

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel
import com.datastax.oss.driver.api.core.cql.BoundStatement
import com.datastax.oss.driver.api.core.servererrors.DefaultWriteType
import com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException
import io.kandra.test.KandraTestBackend
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression coverage for GH #205: a CAS write that times out under Paxos contention has an
 * unknown outcome, and the driver's default retry policy rethrows it. The runner used to
 * propagate it (one replica of a rolling deploy crashed at startup). It now re-reads the row and
 * either continues from what it finds or retries.
 *
 * The timeouts are injected through a [CqlSession] proxy in front of a real session, in two
 * flavours: *before* the statement reaches the database (the claim did not land) and *after*
 * (the claim landed but the response was lost), each on a real Docker/cloud cluster.
 */
class KandraMigrationRunnerLwtContentionTest {

    private lateinit var keyspace: String
    private lateinit var real: CqlSession

    private fun freshSession(): CqlSession {
        keyspace = KandraTestBackend.keyspaceName("lwt")
        KandraTestBackend.createKeyspace(keyspace)
        real = KandraTestBackend.newSession(keyspace)
        return real
    }

    @AfterEach
    fun cleanup() {
        runCatching { real.execute("DROP KEYSPACE IF EXISTS $keyspace") }
        runCatching { real.close() }
    }

    private fun casTimeout() = WriteTimeoutException(
        real.metadata.nodes.values.first(), DefaultConsistencyLevel.SERIAL, 1, 2, DefaultWriteType.CAS
    )

    /**
     * Forwards to [delegate]; for the first [times] bound statements whose CQL contains [cqlMarker],
     * throws a CAS [WriteTimeoutException], after running the statement first when [afterExecuting].
     */
    private fun timingOut(delegate: CqlSession, cqlMarker: String, times: Int, afterExecuting: Boolean, hits: AtomicInteger = AtomicInteger()): CqlSession =
        Proxy.newProxyInstance(CqlSession::class.java.classLoader, arrayOf(CqlSession::class.java)) { _, method, args ->
            val stmt = args?.firstOrNull() as? BoundStatement
            val target = method.name == "execute" && stmt != null && stmt.preparedStatement.query.contains(cqlMarker)
            try {
                if (target && hits.get() < times) {
                    hits.incrementAndGet()
                    if (afterExecuting) method.invoke(delegate, *(args ?: emptyArray()))
                    throw casTimeout()
                }
                method.invoke(delegate, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) { throw e.targetException }
        } as CqlSession

    private class Counting(version: Int) : KandraMigration(version, "counting-$version") {
        val applied = AtomicInteger()
        override fun up(session: CqlSession) { applied.incrementAndGet() }
    }

    @Test
    fun `a claim that times out before landing is retried and the migration applies once`() {
        val session = freshSession()
        val hits = AtomicInteger()
        val runner = KandraMigrationRunner(timingOut(session, "IF NOT EXISTS", times = 2, afterExecuting = false, hits = hits))
        val m = Counting(1)
        runner.run(m)
        assertEquals(1, m.applied.get())
        assertEquals(listOf(MigrationRowStatus.APPLIED), runner.history().map { it.status })
        assertTrue(hits.get() >= 2, "the claim must have been retried after the injected timeouts")
    }

    @Test
    fun `a claim that landed but timed out on the wire is seen as the fresh CLAIMED row it is`() {
        val session = freshSession()
        val runner = KandraMigrationRunner(timingOut(session, "INSERT INTO kandra_migrations", times = 1, afterExecuting = true))
        val m = Counting(1)
        runner.run(m) // halts on the CLAIMED row with a WARN, by the documented unresolved-claim rule; never throws, never double-applies
        assertEquals(0, m.applied.get())
        assertEquals(listOf(MigrationRowStatus.CLAIMED), runner.history().map { it.status })
    }

    @Test
    fun `markApplied that landed but timed out on the wire is recognised from the APPLIED row`() {
        val session = freshSession()
        val runner = KandraMigrationRunner(timingOut(session, "SET status = ?, applied_at = ?", times = 1, afterExecuting = true))
        val m = Counting(1)
        runner.run(m)
        assertEquals(1, m.applied.get())
        assertEquals(listOf(MigrationRowStatus.APPLIED), runner.history().map { it.status })
    }

    @Test
    fun `markApplied that times out before landing is retried`() {
        val session = freshSession()
        val runner = KandraMigrationRunner(timingOut(session, "SET status = ?, applied_at = ?", times = 2, afterExecuting = false))
        val m = Counting(1)
        runner.run(m)
        assertEquals(listOf(MigrationRowStatus.APPLIED), runner.history().map { it.status })
    }

    @Test
    fun `a DDL-lock claim that landed but timed out on the wire still runs the bootstrap exactly once`() {
        val session = freshSession()
        val proxied = timingOut(session, "INSERT INTO kandra_ddl_locks", times = 1, afterExecuting = true)
        val runs = AtomicInteger()
        claimAndRunDdlBootstrap(proxied, "lwt-lock") { runs.incrementAndGet() }
        assertEquals(1, runs.get())
        assertEquals("DONE", session.execute("SELECT status FROM kandra_ddl_locks WHERE lock_name = 'lwt-lock'").one()?.getString("status"))
    }

    @Test
    fun `a DDL-lock claim that times out before landing is retried by the wait loop`() {
        val session = freshSession()
        val proxied = timingOut(session, "INSERT INTO kandra_ddl_locks", times = 1, afterExecuting = false)
        val runs = AtomicInteger()
        claimAndRunDdlBootstrap(proxied, "lwt-lock-2") { runs.incrementAndGet() }
        assertEquals(1, runs.get())
        assertEquals("DONE", session.execute("SELECT status FROM kandra_ddl_locks WHERE lock_name = 'lwt-lock-2'").one()?.getString("status"))
    }
}
