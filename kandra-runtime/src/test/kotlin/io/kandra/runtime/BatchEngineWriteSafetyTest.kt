package io.kandra.runtime

import com.datastax.oss.driver.api.core.DefaultConsistencyLevel
import com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException
import com.datastax.oss.driver.api.core.servererrors.WriteType
import io.kandra.core.InternalKandraApi
import io.kandra.core.KandraConsistency
import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.annotations.SoftDelete
import io.kandra.core.annotations.Ttl
import io.kandra.core.annotations.Version
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.util.UUID

@ScyllaTable("bews_claims")
data class BewsClaim(@PartitionKey val id: UUID, val owner: String)

@ScyllaTable("bews_leases")
@Ttl(seconds = 999)
data class BewsLease(
    @PartitionKey val id: UUID,
    val holder: String,
    @Version val version: Long = 1L
)

@ScyllaTable("bews_sessions")
@SoftDelete(ttlSeconds = 3600, markerProperty = "deleted")
data class BewsSession(@PartitionKey val id: UUID, val deleted: Boolean = false)

/**
 * Regression coverage for GH #56 (ISS-056), GH #59 (ISS-058), GH #62 (ISS-061), and #70 item 5
 * (ISS-069) — all from the same pre-cluster-testing review batch as #54/#55/#67/#68.
 */
@OptIn(InternalKandraApi::class)
class BatchEngineWriteSafetyTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterEach
    fun tearDown() {
        scope.cancel()
        SchemaRegistry.clear()
    }

    private fun newEngine(session: ScriptedCqlSession, consistencyConfig: ConsistencyConfig = ConsistencyConfig()): BatchEngine =
        BatchEngine(session, StatementBuilder(session, consistencyConfig = consistencyConfig), scope)

    private fun transientError(): WriteTimeoutException =
        WriteTimeoutException(fakeNode(), DefaultConsistencyLevel.LOCAL_QUORUM, 1, 0, WriteType.SIMPLE)

    // ── GH #56: saveIfNotExists must not blindly retry its LWT ──────────────

    @Test
    fun `saveIfNotExists surfaces a transient exception instead of retrying and masking it as false`() {
        val schema = SchemaRegistry.register(BewsClaim::class)
        // Scripted like the GH #56 scenario: the server actually applied the INSERT, but the client
        // only observed a timeout. A blind retry would see [applied]=false (row already exists from
        // its own prior attempt) and wrongly report the claim as taken.
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Throw(transientError()), ExecuteOutcome.Applied(false)))
        val engine = newEngine(session)

        assertThrows(WriteTimeoutException::class.java) {
            engine.saveIfNotExists(schema, BewsClaim(UUID.randomUUID(), "alice"))
        }
        assertEquals(1, session.executeCount.get(), "the LWT insert must be executed exactly once, never retried")
    }

    @Test
    fun `saveIfNotExistsSuspend surfaces a transient exception instead of retrying and masking it as false`() = runBlocking {
        val schema = SchemaRegistry.register(BewsClaim::class)
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Throw(transientError()), ExecuteOutcome.Applied(false)))
        val engine = newEngine(session)

        assertThrows(WriteTimeoutException::class.java) {
            runBlocking { engine.saveIfNotExistsSuspend(schema, BewsClaim(UUID.randomUUID(), "alice")) }
        }
        assertEquals(1, session.executeCount.get())
    }

    @Test
    fun `saveIfNotExists still returns false on a genuine applied=false result`() {
        val schema = SchemaRegistry.register(BewsClaim::class)
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Applied(false)))
        val engine = newEngine(session)

        assertEquals(false, engine.saveIfNotExists(schema, BewsClaim(UUID.randomUUID(), "alice")))
        assertEquals(1, session.executeCount.get())
    }

    // ── GH #59: versioned update must not silently drop TTL ─────────────────

    @Test
    fun `versioned update applies the entity's @Ttl default to the UPDATE statement`() {
        val schema = SchemaRegistry.register(BewsLease::class)
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Applied(true)))
        val engine = newEngine(session)

        val old = BewsLease(UUID.randomUUID(), "worker-1", version = 1L)
        engine.update(schema, old, old.copy(holder = "worker-2"))

        assertTrue(
            session.lastPreparedCql?.contains("USING TTL 999") == true,
            "Expected the versioned UPDATE to carry the entity's @Ttl(seconds = 999) default, got: ${session.lastPreparedCql}"
        )
    }

    @Test
    fun `versioned update honors an explicit ttlSeconds override over the entity default`() {
        val schema = SchemaRegistry.register(BewsLease::class)
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Applied(true)))
        val engine = newEngine(session)

        val old = BewsLease(UUID.randomUUID(), "worker-1", version = 1L)
        engine.update(schema, old, old.copy(holder = "worker-2"), ttlSeconds = 42)

        assertTrue(
            session.lastPreparedCql?.contains("USING TTL 42") == true,
            "Expected the explicit ttlSeconds override, got: ${session.lastPreparedCql}"
        )
    }

    @Test
    fun `versioned updateSuspend applies the entity's @Ttl default`() = runBlocking {
        val schema = SchemaRegistry.register(BewsLease::class)
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Applied(true)))
        val engine = newEngine(session)

        val old = BewsLease(UUID.randomUUID(), "worker-1", version = 1L)
        engine.updateSuspend(schema, old, old.copy(holder = "worker-2"))

        assertTrue(session.lastPreparedCql?.contains("USING TTL 999") == true)
    }

    // ── GH #62: soft-delete must honor the configured write consistency ─────

    @Test
    fun `soft delete resolves the configured write consistency`() {
        val schema = SchemaRegistry.register(BewsSession::class)
        val session = ScriptedCqlSession(listOf(ExecuteOutcome.Applied(true), ExecuteOutcome.Applied(true)))
        val config = ConsistencyConfig().apply { defaultWrite = KandraConsistency.LOCAL_QUORUM }
        val engine = newEngine(session, config)

        engine.delete(schema, BewsSession(UUID.randomUUID(), deleted = false))

        assertEquals(DefaultConsistencyLevel.LOCAL_QUORUM, session.lastBoundConsistencyLevel)
    }

    // ── #70 item 5: retry backoff jitter ─────────────────────────────────────

    private fun jitteredBackoffMethod(): Method =
        BatchEngine::class.java.getDeclaredMethod("jitteredBackoff", Int::class.java).apply { isAccessible = true }

    @Test
    fun `jittered backoff stays within half-to-full of the computed linear backoff`() {
        val session = ScriptedCqlSession()
        val engine = newEngine(session)
        val retryConfig = RetryConfig().apply { backoffMillis = 100; maxBackoffMillis = 2000; jitter = true }
        val jitteredEngine = BatchEngine(session, StatementBuilder(session), scope, retryConfig = retryConfig)
        val method = jitteredBackoffMethod()

        val computed = 100L * (0 + 1) // attempt 0
        repeat(50) {
            val backoff = method.invoke(jitteredEngine, 0) as Long
            assertTrue(backoff in (computed / 2)..computed, "backoff $backoff out of [${computed / 2}, $computed]")
        }
        Unit
    }

    @Test
    fun `jitter disabled returns exactly the computed linear backoff`() {
        val session = ScriptedCqlSession()
        val retryConfig = RetryConfig().apply { backoffMillis = 100; maxBackoffMillis = 2000; jitter = false }
        val engine = BatchEngine(session, StatementBuilder(session), scope, retryConfig = retryConfig)
        val method = jitteredBackoffMethod()

        assertEquals(200L, method.invoke(engine, 1) as Long) // attempt 1 -> 100 * 2
    }
}
