package io.kandra.runtime

import io.kandra.core.InternalKandraApi
import io.kandra.core.KandraConsistency
import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ReadConsistency
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.annotations.WriteConsistency
import io.kandra.core.exception.KandraQueryException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

@ScyllaTable("cl_widgets")
data class ClWidget(@PartitionKey val id: UUID, val name: String)

@ReadConsistency(KandraConsistency.EACH_QUORUM)
@ScyllaTable("cl_bad_read_widgets")
data class BadReadWidget(@PartitionKey val id: UUID, val name: String)

@WriteConsistency(KandraConsistency.LOCAL_SERIAL)
@ScyllaTable("cl_bad_write_widgets")
data class BadWriteWidget(@PartitionKey val id: UUID, val name: String)

/**
 * Regression coverage for GH #140 — `defaultRead = EACH_QUORUM` (a write-only CQL consistency
 * level) and `defaultWrite = SERIAL`/`LOCAL_SERIAL` (serial-only levels, never valid as a table's
 * regular write consistency) were previously accepted with no validation anywhere in Kandra, at any
 * of the three places a consistency level can come from — a per-call override, a
 * `@ReadConsistency`/`@WriteConsistency` class annotation, or the plugin's `consistency { }` default.
 * Both reached the real driver unvalidated and failed with an opaque, driver-level exception naming
 * neither Kandra nor the actual misconfiguration.
 *
 * Exercises the real [StatementBuilder.selectById]/[StatementBuilder.insertPrimary] API surface
 * (not reflection) against a [ScriptedCqlSession], proving the fix fires through all three
 * resolution sources for both the read and the write path. [ConsistencyStrictModeTest] separately
 * exercises `resolveReadConsistency`/`resolveWriteConsistency` directly (via reflection) across
 * every `KandraConsistency` value and every `strictMode`/`multiDcTopology` combination; this file
 * proves the same validation through the public methods callers actually invoke, and that it fires
 * *before* the driver is ever touched (`executeCount` stays `0`).
 */
@OptIn(InternalKandraApi::class)
class ConsistencyLevelValidationTest {

    @AfterEach
    fun tearDown() = SchemaRegistry.clear()

    // ── Read: EACH_QUORUM, at each of the three resolution sources ──────────────────────────────

    @Test
    fun `selectById rejects a per-call EACH_QUORUM override before touching the driver`() {
        val schema = SchemaRegistry.register(ClWidget::class)
        val session = ScriptedCqlSession()
        val builder = StatementBuilder(session)

        val ex = assertThrows(KandraQueryException::class.java) {
            builder.selectById(schema, UUID.randomUUID(), consistency = KandraConsistency.EACH_QUORUM)
        }
        assertTrue(ex.message!!.contains("EACH_QUORUM"), "Expected message to mention EACH_QUORUM, got: ${ex.message}")
        assertTrue(ex.message!!.contains("read"), "Expected message to identify this as a read-consistency problem, got: ${ex.message}")
        assertEquals(0, session.executeCount.get(), "Must throw before the driver is ever invoked")
    }

    @Test
    fun `selectById rejects EACH_QUORUM coming from a @ReadConsistency class annotation`() {
        val schema = SchemaRegistry.register(BadReadWidget::class)
        val session = ScriptedCqlSession()
        val builder = StatementBuilder(session)

        val ex = assertThrows(KandraQueryException::class.java) {
            builder.selectById(schema, UUID.randomUUID())
        }
        assertTrue(ex.message!!.contains("EACH_QUORUM"), "Expected message to mention EACH_QUORUM, got: ${ex.message}")
        assertEquals(0, session.executeCount.get())
    }

    @Test
    fun `selectById rejects EACH_QUORUM coming from consistency block defaultRead`() {
        val schema = SchemaRegistry.register(ClWidget::class)
        val session = ScriptedCqlSession()
        val config = ConsistencyConfig().apply { defaultRead = KandraConsistency.EACH_QUORUM }
        val builder = StatementBuilder(session, consistencyConfig = config)

        val ex = assertThrows(KandraQueryException::class.java) {
            builder.selectById(schema, UUID.randomUUID())
        }
        assertTrue(ex.message!!.contains("EACH_QUORUM"), "Expected message to mention EACH_QUORUM, got: ${ex.message}")
        assertEquals(0, session.executeCount.get())
    }

    @Test
    fun `selectById with a valid read consistency is unaffected`() {
        val schema = SchemaRegistry.register(ClWidget::class)
        val builder = StatementBuilder(ScriptedCqlSession())
        // Must not throw.
        builder.selectById(schema, UUID.randomUUID(), consistency = KandraConsistency.LOCAL_QUORUM)
    }

    // ── Write: SERIAL / LOCAL_SERIAL, at each of the three resolution sources ───────────────────

    @Test
    fun `insertPrimary rejects a per-call SERIAL override before touching the driver`() {
        val schema = SchemaRegistry.register(ClWidget::class)
        val session = ScriptedCqlSession()
        val builder = StatementBuilder(session)

        val ex = assertThrows(KandraQueryException::class.java) {
            builder.insertPrimary(schema, ClWidget(UUID.randomUUID(), "widget"), consistency = KandraConsistency.SERIAL)
        }
        assertTrue(ex.message!!.contains("SERIAL"), "Expected message to mention SERIAL, got: ${ex.message}")
        assertTrue(ex.message!!.contains("write"), "Expected message to identify this as a write-consistency problem, got: ${ex.message}")
        assertEquals(0, session.executeCount.get(), "Must throw before the driver is ever invoked")
    }

    @Test
    fun `insertPrimary rejects LOCAL_SERIAL coming from a @WriteConsistency class annotation`() {
        val schema = SchemaRegistry.register(BadWriteWidget::class)
        val session = ScriptedCqlSession()
        val builder = StatementBuilder(session)

        val ex = assertThrows(KandraQueryException::class.java) {
            builder.insertPrimary(schema, BadWriteWidget(UUID.randomUUID(), "widget"))
        }
        assertTrue(ex.message!!.contains("LOCAL_SERIAL"), "Expected message to mention LOCAL_SERIAL, got: ${ex.message}")
        assertEquals(0, session.executeCount.get())
    }

    @Test
    fun `insertPrimary rejects LOCAL_SERIAL coming from consistency block defaultWrite`() {
        val schema = SchemaRegistry.register(ClWidget::class)
        val session = ScriptedCqlSession()
        val config = ConsistencyConfig().apply { defaultWrite = KandraConsistency.LOCAL_SERIAL }
        val builder = StatementBuilder(session, consistencyConfig = config)

        val ex = assertThrows(KandraQueryException::class.java) {
            builder.insertPrimary(schema, ClWidget(UUID.randomUUID(), "widget"))
        }
        assertTrue(ex.message!!.contains("LOCAL_SERIAL"), "Expected message to mention LOCAL_SERIAL, got: ${ex.message}")
        assertEquals(0, session.executeCount.get())
    }

    @Test
    fun `insertPrimary with EACH_QUORUM (valid for writes) is unaffected`() {
        val schema = SchemaRegistry.register(ClWidget::class)
        val builder = StatementBuilder(ScriptedCqlSession())
        // EACH_QUORUM is write-only, so it's perfectly valid here even though it's rejected for reads
        // above — must not throw.
        builder.insertPrimary(schema, ClWidget(UUID.randomUUID(), "widget"), consistency = KandraConsistency.EACH_QUORUM)
    }
}
