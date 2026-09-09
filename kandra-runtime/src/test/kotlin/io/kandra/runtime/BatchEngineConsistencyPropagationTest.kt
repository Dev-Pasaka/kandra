package io.kandra.runtime

import com.datastax.oss.driver.api.core.DefaultConsistencyLevel
import com.datastax.oss.driver.api.core.cql.BatchStatement
import io.kandra.core.InternalKandraApi
import io.kandra.core.KandraConsistency
import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.LookupIndex
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.annotations.Version
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Regression coverage for GH #54 (ISS-053) — `LOGGED BATCH` writes and `@Version` LWT updates
 * silently discarded the configured consistency level, always executing at the driver's built-in
 * `LOCAL_ONE` default instead of `ConsistencyConfig.defaultWrite` / `@WriteConsistency` / a per-call
 * override. These tests assert on the *actual* consistency level attached to what [BatchEngine] hands
 * `session.execute(...)`, not just that the code compiles/runs.
 */
@OptIn(InternalKandraApi::class)
class BatchEngineConsistencyPropagationTest {

    @ScyllaTable("widgets")
    data class Widget(@PartitionKey val id: UUID, val name: String)

    @ScyllaTable("balances")
    data class VersionedBalance(
        @PartitionKey val accountId: UUID,
        val amountCents: Long,
        @Version val version: Long = 1L
    )

    @ScyllaTable("versioned_lookup_widgets")
    data class VersionedLookupWidget(
        @PartitionKey val id: UUID,
        @LookupIndex(tableSuffix = "by_email") val email: String,
        @Version val version: Long = 1L
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterEach
    fun tearDown() {
        scope.cancel()
        SchemaRegistry.clear()
    }

    private fun newEngine(session: ScriptedCqlSession, consistencyConfig: ConsistencyConfig): BatchEngine {
        val statementBuilder = StatementBuilder(session, consistencyConfig = consistencyConfig)
        return BatchEngine(session, statementBuilder, scope)
    }

    // ── LOGGED BATCH (save/update/saveAll/...) ───────────────────────────────

    @Test
    fun `save() resolves the batch-level consistency to the configured default write level`() {
        val schema = SchemaRegistry.register(Widget::class)
        val session = ScriptedCqlSession()
        val config = ConsistencyConfig().apply { defaultWrite = KandraConsistency.LOCAL_QUORUM }
        val engine = newEngine(session, config)

        engine.save(schema, Widget(UUID.randomUUID(), "widget-1"))

        val batch = session.lastStatement as? BatchStatement
            ?: error("Expected a BatchStatement to be executed, got ${session.lastStatement}")
        assertEquals(
            DefaultConsistencyLevel.LOCAL_QUORUM, batch.consistencyLevel,
            "the LOGGED BATCH must carry the configured write consistency, not the driver's LOCAL_ONE default"
        )
    }

    @Test
    fun `save() honors a per-call consistency override over the configured default`() {
        val schema = SchemaRegistry.register(Widget::class)
        val session = ScriptedCqlSession()
        val config = ConsistencyConfig().apply { defaultWrite = KandraConsistency.LOCAL_QUORUM }
        val engine = newEngine(session, config)

        engine.save(schema, Widget(UUID.randomUUID(), "widget-1"), consistency = KandraConsistency.ALL)

        val batch = session.lastStatement as? BatchStatement ?: error("Expected a BatchStatement")
        assertEquals(DefaultConsistencyLevel.ALL, batch.consistencyLevel)
    }

    @Test
    fun `update() non-versioned branch resolves the batch-level consistency`() {
        val schema = SchemaRegistry.register(Widget::class)
        val session = ScriptedCqlSession()
        val config = ConsistencyConfig().apply { defaultWrite = KandraConsistency.LOCAL_QUORUM }
        val engine = newEngine(session, config)

        val old = Widget(UUID.randomUUID(), "old-name")
        engine.update(schema, old, old.copy(name = "new-name"))

        val batch = session.lastStatement as? BatchStatement ?: error("Expected a BatchStatement")
        assertEquals(DefaultConsistencyLevel.LOCAL_QUORUM, batch.consistencyLevel)
    }

    @Test
    fun `saveAll() resolves the batch-level consistency for a chunked LOGGED batch`() {
        val schema = SchemaRegistry.register(Widget::class)
        val session = ScriptedCqlSession()
        val config = ConsistencyConfig().apply { defaultWrite = KandraConsistency.LOCAL_QUORUM }
        val engine = newEngine(session, config)

        engine.saveAll(schema, listOf(Widget(UUID.randomUUID(), "a"), Widget(UUID.randomUUID(), "b")))

        val batch = session.lastStatement as? BatchStatement ?: error("Expected a BatchStatement")
        assertEquals(DefaultConsistencyLevel.LOCAL_QUORUM, batch.consistencyLevel)
    }

    @Test
    fun `saveSuspend() resolves the batch-level consistency`() = runBlocking {
        val schema = SchemaRegistry.register(Widget::class)
        val session = ScriptedCqlSession()
        val config = ConsistencyConfig().apply { defaultWrite = KandraConsistency.LOCAL_QUORUM }
        val engine = newEngine(session, config)

        engine.saveSuspend(schema, Widget(UUID.randomUUID(), "widget-1"))

        val batch = session.lastStatement as? BatchStatement ?: error("Expected a BatchStatement")
        assertEquals(DefaultConsistencyLevel.LOCAL_QUORUM, batch.consistencyLevel)
    }

    // ── @Version LWT update ──────────────────────────────────────────────────

    @Test
    fun `versioned update sets both the regular and serial consistency on the LWT statement`() {
        val schema = SchemaRegistry.register(VersionedBalance::class)
        val session = ScriptedCqlSession()
        val config = ConsistencyConfig().apply { defaultWrite = KandraConsistency.LOCAL_QUORUM }
        val engine = newEngine(session, config)

        val old = VersionedBalance(UUID.randomUUID(), 100L, version = 1L)
        engine.update(schema, old, old.copy(amountCents = 200L))

        assertEquals(
            DefaultConsistencyLevel.LOCAL_QUORUM, session.lastBoundConsistencyLevel,
            "the versioned UPDATE's regular consistency must resolve to the configured write level, " +
                "not just its serial consistency"
        )
    }

    @Test
    fun `versioned updateSuspend sets the regular consistency on the LWT statement`() = runBlocking {
        val schema = SchemaRegistry.register(VersionedBalance::class)
        val session = ScriptedCqlSession()
        val config = ConsistencyConfig().apply { defaultWrite = KandraConsistency.LOCAL_QUORUM }
        val engine = newEngine(session, config)

        val old = VersionedBalance(UUID.randomUUID(), 100L, version = 1L)
        engine.updateSuspend(schema, old, old.copy(amountCents = 200L))

        assertEquals(DefaultConsistencyLevel.LOCAL_QUORUM, session.lastBoundConsistencyLevel)
    }

    @Test
    fun `versioned update honors a per-call consistency override`() {
        val schema = SchemaRegistry.register(VersionedBalance::class)
        val session = ScriptedCqlSession()
        val config = ConsistencyConfig().apply { defaultWrite = KandraConsistency.LOCAL_QUORUM }
        val engine = newEngine(session, config)

        val old = VersionedBalance(UUID.randomUUID(), 100L, version = 1L)
        engine.update(schema, old, old.copy(amountCents = 200L), consistency = KandraConsistency.ALL)

        assertEquals(DefaultConsistencyLevel.ALL, session.lastBoundConsistencyLevel)
    }

    // ── updateLookups / updateLookupsSuspend (GH #96) ────────────────────────

    @Test
    fun `versioned update's lookup-table batch resolves the configured write consistency`() {
        val schema = SchemaRegistry.register(VersionedLookupWidget::class)
        val session = ScriptedCqlSession()
        val config = ConsistencyConfig().apply { defaultWrite = KandraConsistency.LOCAL_QUORUM }
        val engine = newEngine(session, config)

        val old = VersionedLookupWidget(UUID.randomUUID(), "old@example.com", version = 1L)
        engine.update(schema, old, old.copy(email = "new@example.com"))

        val batch = session.lastStatement as? BatchStatement
            ?: error("Expected the lookup-table LOGGED BATCH to be the last statement executed")
        assertEquals(
            DefaultConsistencyLevel.LOCAL_QUORUM, batch.consistencyLevel,
            "updateLookups must resolve write consistency like every other write batch, not default to LOCAL_ONE"
        )
    }

    @Test
    fun `versioned updateSuspend's lookup-table batch resolves the configured write consistency`() = runBlocking {
        val schema = SchemaRegistry.register(VersionedLookupWidget::class)
        val session = ScriptedCqlSession()
        val config = ConsistencyConfig().apply { defaultWrite = KandraConsistency.LOCAL_QUORUM }
        val engine = newEngine(session, config)

        val old = VersionedLookupWidget(UUID.randomUUID(), "old@example.com", version = 1L)
        engine.updateSuspend(schema, old, old.copy(email = "new@example.com"))

        val batch = session.lastStatement as? BatchStatement
            ?: error("Expected the lookup-table LOGGED BATCH to be the last statement executed")
        assertEquals(DefaultConsistencyLevel.LOCAL_QUORUM, batch.consistencyLevel)
    }

    @Test
    fun `versioned update's lookup-table batch honors a per-call consistency override`() {
        val schema = SchemaRegistry.register(VersionedLookupWidget::class)
        val session = ScriptedCqlSession()
        val config = ConsistencyConfig().apply { defaultWrite = KandraConsistency.LOCAL_QUORUM }
        val engine = newEngine(session, config)

        val old = VersionedLookupWidget(UUID.randomUUID(), "old@example.com", version = 1L)
        engine.update(schema, old, old.copy(email = "new@example.com"), consistency = KandraConsistency.ALL)

        val batch = session.lastStatement as? BatchStatement
            ?: error("Expected the lookup-table LOGGED BATCH to be the last statement executed")
        assertEquals(DefaultConsistencyLevel.ALL, batch.consistencyLevel)
    }
}
