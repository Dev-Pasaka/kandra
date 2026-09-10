package io.kandra.runtime

import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.core.InternalKandraApi
import io.kandra.core.KandraConsistency
import io.kandra.core.exception.KandraQueryException
import io.kandra.core.schema.EntityReflection
import io.kandra.core.schema.TableSchema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/** Placeholder entity solely so [TableSchema.entityClass] has something real to point at. */
private data class StrictModeEntity(val id: Int)

/**
 * Unit coverage for GH #5 — consistency Strict Mode.
 *
 * Exercises [StatementBuilder]'s private `resolveWriteConsistency`/`resolveReadConsistency` directly via
 * reflection, since the strict-mode WARN is emitted from inside them (see `warnIfStrictModeViolation`).
 * A `CqlSession` is never actually invoked on this path (only `prepare()`-calling methods touch the
 * session), so a no-op dynamic proxy stands in for it — no driver connection or Testcontainers cluster
 * needed for this suite (see `KandraIntegrationTest`/`KandraPluginTest` for the real-cluster wiring proof).
 */
@OptIn(InternalKandraApi::class)
class ConsistencyStrictModeTest {

    private fun fakeSession(): CqlSession {
        val handler = java.lang.reflect.InvocationHandler { _, _, _ -> null }
        return java.lang.reflect.Proxy.newProxyInstance(
            CqlSession::class.java.classLoader,
            arrayOf(CqlSession::class.java),
            handler
        ) as CqlSession
    }

    private fun statementBuilder(config: ConsistencyConfig): StatementBuilder =
        StatementBuilder(session = fakeSession(), consistencyConfig = config)

    private fun schema(): TableSchema = TableSchema(
        entityClass = StrictModeEntity::class,
        tableName = "strict_mode_entities",
        partitionKeys = emptyList(),
        clusteringKeys = emptyList(),
        columns = emptyList(),
        lookupTables = emptyList(),
        reflection = EntityReflection(
            copyFunction = StrictModeEntity::class.memberFunctions.find { it.name == "copy" },
            copyParameters = StrictModeEntity::class.memberFunctions.find { it.name == "copy" }?.parameters ?: emptyList(),
            propertiesByName = StrictModeEntity::class.memberProperties.associateBy { it.name },
            primaryConstructor = StrictModeEntity::class.primaryConstructor,
            constructorParameters = StrictModeEntity::class.primaryConstructor?.parameters ?: emptyList(),
            columnsByProperty = emptyMap()
        )
    )

    /**
     * `resolveWriteConsistency`/`resolveReadConsistency` are now `internal` (see ISS-053/GH #54 and
     * ISS-054/GH #55, needed by [BatchEngine]/[QueryExecutor] respectively), so Kotlin mangles their
     * compiled names with a module-name suffix (e.g. `resolveWriteConsistency$kandra_runtime`) to
     * avoid cross-module clashes. Match by prefix instead of hardcoding the mangled suffix, which is
     * an implementation detail.
     */
    private fun resolveWriteMethod(): Method =
        StatementBuilder::class.java.declaredMethods
            .single { it.name.startsWith("resolveWriteConsistency") }
            .apply { isAccessible = true }

    private fun resolveReadMethod(): Method =
        StatementBuilder::class.java.declaredMethods
            .single { it.name.startsWith("resolveReadConsistency") }
            .apply { isAccessible = true }

    /** Captures WARN (and everything else) written to stderr by slf4j-simple during [block]. */
    private fun captureStderr(block: () -> Unit): String {
        val original = System.err
        val buffer = ByteArrayOutputStream()
        System.setErr(PrintStream(buffer))
        try {
            block()
        } finally {
            System.setErr(original)
        }
        return buffer.toString()
    }

    // ── (a) strictMode = false, regardless of topology → never warns ────────────────────────

    @Test
    fun `no warning when strictMode is false, single-DC topology`() {
        val config = ConsistencyConfig().apply {
            strictMode = false
            multiDcTopology = false
        }
        val builder = statementBuilder(config)
        val output = captureStderr {
            resolveWriteMethod().invoke(builder, schema(), KandraConsistency.LOCAL_ONE)
        }
        assertFalse(output.contains("strictMode"), "Expected no strict-mode WARN, got: $output")
    }

    @Test
    fun `no warning when strictMode is false, multi-DC topology`() {
        val config = ConsistencyConfig().apply {
            strictMode = false
            multiDcTopology = true
        }
        val builder = statementBuilder(config)
        val output = captureStderr {
            resolveWriteMethod().invoke(builder, schema(), KandraConsistency.LOCAL_ONE)
        }
        assertFalse(output.contains("strictMode"), "Expected no strict-mode WARN, got: $output")
    }

    // ── (b) strictMode = true but single-DC (multiDcTopology = false) → never warns ─────────

    @Test
    fun `no warning when strictMode is true but topology is single-DC`() {
        val config = ConsistencyConfig().apply {
            strictMode = true
            multiDcTopology = false
        }
        val builder = statementBuilder(config)
        val output = captureStderr {
            resolveWriteMethod().invoke(builder, schema(), KandraConsistency.LOCAL_ONE)
        }
        assertFalse(output.contains("strictMode"), "Expected no strict-mode WARN in single-DC, got: $output")
    }

    // ── (c) strictMode = true, multi-DC, resolved = LOCAL_ONE or ONE → WARN ──────────────────

    @Test
    fun `warns on write resolving to LOCAL_ONE in strict multi-DC mode`() {
        val config = ConsistencyConfig().apply {
            strictMode = true
            multiDcTopology = true
        }
        val builder = statementBuilder(config)
        val output = captureStderr {
            val resolved = resolveWriteMethod().invoke(builder, schema(), KandraConsistency.LOCAL_ONE)
            assertEquals(KandraConsistency.LOCAL_ONE, resolved)
        }
        assertTrue(output.contains("strictMode"), "Expected strict-mode WARN, got: $output")
        assertTrue(output.contains("LOCAL_ONE"), "Expected WARN to mention LOCAL_ONE, got: $output")
    }

    @Test
    fun `warns on read resolving to ONE in strict multi-DC mode`() {
        val config = ConsistencyConfig().apply {
            strictMode = true
            multiDcTopology = true
        }
        val builder = statementBuilder(config)
        val output = captureStderr {
            val resolved = resolveReadMethod().invoke(builder, schema(), KandraConsistency.ONE)
            assertEquals(KandraConsistency.ONE, resolved)
        }
        assertTrue(output.contains("strictMode"), "Expected strict-mode WARN, got: $output")
        assertTrue(output.contains("ONE"), "Expected WARN to mention ONE, got: $output")
    }

    // ── (d) strictMode = true, multi-DC, resolved is anything else → never warns ─────────────

    @Test
    fun `no warning when resolved consistency is LOCAL_QUORUM in strict multi-DC mode`() {
        val config = ConsistencyConfig().apply {
            strictMode = true
            multiDcTopology = true
        }
        val builder = statementBuilder(config)
        val output = captureStderr {
            resolveWriteMethod().invoke(builder, schema(), KandraConsistency.LOCAL_QUORUM)
        }
        assertFalse(output.contains("strictMode"), "Expected no strict-mode WARN for LOCAL_QUORUM, got: $output")
    }

    @Test
    fun `no warning for QUORUM, ALL, or SERIAL levels in strict multi-DC mode`() {
        val config = ConsistencyConfig().apply {
            strictMode = true
            multiDcTopology = true
        }
        val builder = statementBuilder(config)
        // EACH_QUORUM removed from this list under GH #140 -- it's invalid for reads and now throws
        // before the strict-mode warn check ever runs (see `read resolution rejects EACH_QUORUM`
        // below), so it no longer belongs in a "no warning" fixture.
        val nonTriggering = listOf(
            KandraConsistency.QUORUM,
            KandraConsistency.ALL,
            KandraConsistency.LOCAL_SERIAL,
            KandraConsistency.SERIAL,
            KandraConsistency.TWO,
            KandraConsistency.THREE
        )
        nonTriggering.forEach { level ->
            val output = captureStderr {
                resolveReadMethod().invoke(builder, schema(), level)
            }
            assertFalse(output.contains("strictMode"), "Expected no strict-mode WARN for $level, got: $output")
        }
    }

    // ── (e) never throws, under every combination — except the two invalid-consistency shapes ──

    @Test
    fun `strict mode resolution never throws under any combination, aside from the invalid read or write levels`() {
        val schema = schema()
        // GH #140: resolveReadConsistency(EACH_QUORUM) and resolveWriteConsistency(SERIAL /
        // LOCAL_SERIAL) now throw KandraQueryException regardless of strictMode/multiDcTopology --
        // that validation is unconditional, not part of Strict Mode. Every other level must still
        // resolve cleanly under every strict/topology combination, which this test continues to prove.
        val invalidForRead = setOf(KandraConsistency.EACH_QUORUM)
        val invalidForWrite = setOf(KandraConsistency.SERIAL, KandraConsistency.LOCAL_SERIAL)
        for (strict in listOf(true, false)) {
            for (multiDc in listOf(true, false)) {
                val config = ConsistencyConfig().apply {
                    strictMode = strict
                    multiDcTopology = multiDc
                }
                val builder = statementBuilder(config)
                KandraConsistency.entries.forEach { level ->
                    if (level !in invalidForWrite) resolveWriteMethod().invoke(builder, schema, level)
                    if (level !in invalidForRead) resolveReadMethod().invoke(builder, schema, level)
                }
                // Also exercise the "no override, fall through to config defaults" path — the
                // library's own defaults (LOCAL_QUORUM write / LOCAL_ONE read) are always valid.
                resolveWriteMethod().invoke(builder, schema, null)
                resolveReadMethod().invoke(builder, schema, null)
            }
        }
    }

    // ── (f) GH #140 — EACH_QUORUM (read) / SERIAL|LOCAL_SERIAL (write) always throw ──────────
    // Unconditional validation, independent of Strict Mode: exercised here at both strictMode
    // settings to prove it isn't accidentally gated behind the opt-in strictMode flag.

    @Test
    fun `read resolution rejects EACH_QUORUM regardless of strictMode`() {
        for (strict in listOf(true, false)) {
            val config = ConsistencyConfig().apply { strictMode = strict; multiDcTopology = true }
            val builder = statementBuilder(config)
            val ex = assertThrows(InvocationTargetException::class.java) {
                resolveReadMethod().invoke(builder, schema(), KandraConsistency.EACH_QUORUM)
            }
            val cause = ex.targetException
            assertTrue(cause is KandraQueryException, "Expected KandraQueryException, got: $cause")
            assertTrue(cause.message!!.contains("EACH_QUORUM"), "Expected message to mention EACH_QUORUM, got: ${cause.message}")
        }
    }

    @Test
    fun `write resolution rejects SERIAL and LOCAL_SERIAL regardless of strictMode`() {
        for (strict in listOf(true, false)) {
            for (level in listOf(KandraConsistency.SERIAL, KandraConsistency.LOCAL_SERIAL)) {
                val config = ConsistencyConfig().apply { strictMode = strict; multiDcTopology = true }
                val builder = statementBuilder(config)
                val ex = assertThrows(InvocationTargetException::class.java) {
                    resolveWriteMethod().invoke(builder, schema(), level)
                }
                val cause = ex.targetException
                assertTrue(cause is KandraQueryException, "Expected KandraQueryException, got: $cause")
                assertTrue(cause.message!!.contains("$level"), "Expected message to mention $level, got: ${cause.message}")
            }
        }
    }

    @Test
    fun `read resolution still accepts EACH_QUORUM's write-side counterpart validation independently`() {
        // Sanity check that the two validations are genuinely independent: EACH_QUORUM is invalid
        // for reads but perfectly valid for writes, and SERIAL/LOCAL_SERIAL are invalid for writes
        // but valid for reads (a linearizable read) -- neither check over-reaches into the other's
        // operation kind.
        val config = ConsistencyConfig()
        val builder = statementBuilder(config)
        assertEquals(KandraConsistency.EACH_QUORUM, resolveWriteMethod().invoke(builder, schema(), KandraConsistency.EACH_QUORUM))
        assertEquals(KandraConsistency.SERIAL, resolveReadMethod().invoke(builder, schema(), KandraConsistency.SERIAL))
        assertEquals(KandraConsistency.LOCAL_SERIAL, resolveReadMethod().invoke(builder, schema(), KandraConsistency.LOCAL_SERIAL))
    }
}
