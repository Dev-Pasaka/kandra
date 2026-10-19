package io.kandra.migrate

import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.core.exception.KandraMigrationException
import io.kandra.test.KandraCloud
import io.kandra.test.KandraTestcontainers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Records which keyspace (if any) the runner handed to the migration body. */
val seenKeyspaces: MutableList<String?> = CopyOnWriteArrayList()
val msApplyCount = AtomicInteger()

object MsV1 : KandraMigration(1, "create t1") {
    override fun up(session: CqlSession) = throw UnsupportedOperationException()
    override fun up(session: CqlSession, keyspace: String?) {
        seenKeyspaces += keyspace
        msApplyCount.incrementAndGet()
        session.execute("CREATE TABLE IF NOT EXISTS ${keyspace!!}.t1 (id int PRIMARY KEY)")
    }
}

object MsV2 : KandraMigration(2, "create t2") {
    override fun up(session: CqlSession) = throw UnsupportedOperationException()
    override fun up(session: CqlSession, keyspace: String?) {
        session.execute("CREATE TABLE IF NOT EXISTS ${keyspace!!}.t2 (id int PRIMARY KEY)")
    }
}

/** Same version/name as [MsV1] but a different body: checksum differs. */
object MsV1Edited : KandraMigration(1, "create t1") {
    override fun up(session: CqlSession) = throw UnsupportedOperationException()
    override fun up(session: CqlSession, keyspace: String?) {
        session.execute("CREATE TABLE IF NOT EXISTS ${keyspace!!}.t1 (id int PRIMARY KEY, extra text)")
    }
}

/** A plain legacy-style migration (only `up(session)`), run in the session keyspace. */
object MsLegacy : KandraMigration(1, "legacy") {
    override fun up(session: CqlSession) {
        session.execute("CREATE TABLE IF NOT EXISTS legacy_t (id int PRIMARY KEY)")
    }
}

abstract class MultiKeyspaceScenarios {
    /** Runs [block] with an UNBOUND session and two fresh keyspaces; cleans everything up. */
    protected abstract fun withTwoKeyspaces(block: (session: CqlSession, a: String, b: String) -> Unit)
    protected abstract fun withBoundKeyspace(block: (session: CqlSession, ks: String) -> Unit)

    private fun tables(s: CqlSession, ks: String): Set<String> =
        s.execute("SELECT table_name FROM system_schema.tables WHERE keyspace_name = '$ks'")
            .map { it.getString("table_name")!! }.toSet()

    @Test
    fun `two keyspaces keep independent histories with the same version numbers`() = withTwoKeyspaces { s, a, b ->
        KandraMigrationRunner(s, keyspace = a).run(MsV1)
        // B has not run anything yet: A's v1 must not count as applied for B.
        assertTrue(KandraMigrationRunner(s, keyspace = b).history().isEmpty())
        KandraMigrationRunner(s, keyspace = b).run(MsV1, MsV2)

        assertEquals(listOf(1), KandraMigrationRunner(s, keyspace = a).history().map { it.version })
        assertEquals(listOf(1, 2), KandraMigrationRunner(s, keyspace = b).history().map { it.version })
        assertEquals(setOf("t1", "kandra_migrations", "kandra_ddl_locks"), tables(s, a))
        assertEquals(setOf("t1", "t2", "kandra_migrations", "kandra_ddl_locks"), tables(s, b))
        assertTrue(seenKeyspaces.containsAll(listOf(a, b)))

        // Re-running is a no-op per keyspace; A can now pick up v2 independently.
        val before = msApplyCount.get()
        KandraMigrationRunner(s, keyspace = a).run(MsV1, MsV2)
        assertEquals(before, msApplyCount.get())
        assertEquals(listOf(1, 2), KandraMigrationRunner(s, keyspace = a).history().map { it.version })
    }

    @Test
    fun `checksum validation is per keyspace`() = withTwoKeyspaces { s, a, b ->
        KandraMigrationRunner(s, keyspace = a).run(MsV1)
        // B never applied v1, so an "edited" v1 is simply new there.
        KandraMigrationRunner(s, keyspace = b).run(MsV1Edited)
        assertThrows(KandraMigrationException::class.java) { KandraMigrationRunner(s, keyspace = a).run(MsV1Edited) }
        assertThrows(KandraMigrationException::class.java) { KandraMigrationRunner(s, keyspace = b).run(MsV1) }
        // Unchanged migrations still validate in each.
        KandraMigrationRunner(s, keyspace = a).run(MsV1)
        KandraMigrationRunner(s, keyspace = b).run(MsV1Edited)
    }

    @Test
    fun `concurrent runners racing on one keyspace apply each migration once`() = withTwoKeyspaces { s, a, b ->
        msApplyCount.set(0)
        val n = 6
        val pool = Executors.newFixedThreadPool(n)
        val gate = CountDownLatch(1)
        val futures = (1..n).map {
            pool.submit {
                gate.await()
                KandraMigrationRunner(s, keyspace = a).run(MsV1, MsV2)
            }
        }
        gate.countDown()
        // A loser may halt on a recent CLAIMED row (by design); the end state must still be exactly-once.
        futures.forEach { it.get(120, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(1, msApplyCount.get(), "v1 body must run exactly once")
        KandraMigrationRunner(s, keyspace = a).run(MsV1, MsV2)
        assertEquals(listOf(1, 2), KandraMigrationRunner(s, keyspace = a).history().map { it.version })
        // The other keyspace is untouched.
        assertFalse("kandra_migrations" in tables(s, b))
    }

    @Test
    fun `lock rows live in the target keyspace`() = withTwoKeyspaces { s, a, b ->
        KandraMigrationRunner(s, keyspace = a)
        val rows = s.execute("SELECT lock_name, status FROM $a.kandra_ddl_locks").all()
        assertEquals(1, rows.size)
        assertEquals("DONE", rows[0].getString("status"))
        assertFalse("kandra_ddl_locks" in tables(s, b))
    }

    @Test
    fun `invalid keyspace is rejected`() = withTwoKeyspaces { s, _, _ ->
        assertThrows(IllegalArgumentException::class.java) { KandraMigrationRunner(s, keyspace = "bad; DROP KEYSPACE x") }
    }

    @Test
    fun `null keyspace behaves exactly as before on a bound session`() = withBoundKeyspace { s, ks ->
        KandraMigrationRunner(s).run(MsLegacy)
        assertEquals(setOf("legacy_t", "kandra_migrations", "kandra_ddl_locks"), tables(s, ks))
        assertEquals(listOf(1), KandraMigrationRunner(s).history().map { it.version })
        // Explicitly naming the session's own keyspace shares the same history.
        assertEquals(listOf(1), KandraMigrationRunner(s, keyspace = ks).history().map { it.version })
        // Legacy checksum scheme is untouched (no keyspace in the hash).
        assertEquals(MsLegacy.checksum(), KandraMigrationRunner(s).history().single().checksum)
    }
}

class KandraMigrationMultiKeyspaceDockerTest : MultiKeyspaceScenarios() {
    private fun open(ks: String? = null): CqlSession {
        val b = CqlSession.builder()
            .addContactPoint(KandraTestcontainers.container.contactPoint)
            .withLocalDatacenter(KandraTestcontainers.container.localDatacenter)
        if (ks != null) b.withKeyspace(ks)
        return b.build()
    }

    private fun ksName() = "kandra_ms_${UUID.randomUUID().toString().replace("-", "").take(10)}"

    private fun create(admin: CqlSession, name: String) {
        admin.execute("CREATE KEYSPACE $name WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}")
    }

    override fun withTwoKeyspaces(block: (CqlSession, String, String) -> Unit) {
        val a = ksName(); val b = ksName()
        open().use { s ->
            try {
                create(s, a); create(s, b)
                block(s, a, b)
            } finally {
                runCatching { s.execute("DROP KEYSPACE IF EXISTS $a") }
                runCatching { s.execute("DROP KEYSPACE IF EXISTS $b") }
            }
        }
    }

    override fun withBoundKeyspace(block: (CqlSession, String) -> Unit) {
        val ks = ksName()
        open().use { admin ->
            try {
                create(admin, ks)
                open(ks).use { block(it, ks) }
            } finally {
                runCatching { admin.execute("DROP KEYSPACE IF EXISTS $ks") }
            }
        }
    }
}

class KandraMigrationMultiKeyspaceCloudTest : MultiKeyspaceScenarios() {
    companion object {
        @BeforeAll @JvmStatic fun gate() = KandraCloud.assumeConfigured()
    }

    override fun withTwoKeyspaces(block: (CqlSession, String, String) -> Unit) {
        KandraCloud.freshKeyspace("mig", rf = 3).use { a ->
            KandraCloud.freshKeyspace("mig", rf = 3).use { b ->
                // Unbound admin session targeting both keyspaces by name.
                block(a.admin, a.name, b.name)
            }
        }
    }

    override fun withBoundKeyspace(block: (CqlSession, String) -> Unit) {
        KandraCloud.freshKeyspace("mig", rf = 3).use { ks -> block(ks.session, ks.name) }
    }
}
