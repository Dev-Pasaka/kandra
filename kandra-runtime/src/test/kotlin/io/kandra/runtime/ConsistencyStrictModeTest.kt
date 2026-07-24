package io.kandra.runtime

import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.core.InternalKandraApi
import io.kandra.core.KandraConsistency
import io.kandra.core.schema.TableSchema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.Method

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
        lookupTables = emptyList()
    )

    private fun resolveWriteMethod(): Method =
        StatementBuilder::class.java.getDeclaredMethod(
            "resolveWriteConsistency", TableSchema::class.java, KandraConsistency::class.java
        ).apply { isAccessible = true }

    private fun resolveReadMethod(): Method =
        StatementBuilder::class.java.getDeclaredMethod(
            "resolveReadConsistency", TableSchema::class.java, KandraConsistency::class.java
        ).apply { isAccessible = true }

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
    fun `no warning for QUORUM, EACH_QUORUM, ALL, or SERIAL levels in strict multi-DC mode`() {
        val config = ConsistencyConfig().apply {
            strictMode = true
            multiDcTopology = true
        }
        val builder = statementBuilder(config)
        val nonTriggering = listOf(
            KandraConsistency.QUORUM,
            KandraConsistency.EACH_QUORUM,
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

    // ── (e) never throws, under every combination ────────────────────────────────────────────

    @Test
    fun `strict mode resolution never throws under any combination`() {
        val schema = schema()
        for (strict in listOf(true, false)) {
            for (multiDc in listOf(true, false)) {
                val config = ConsistencyConfig().apply {
                    strictMode = strict
                    multiDcTopology = multiDc
                }
                val builder = statementBuilder(config)
                KandraConsistency.entries.forEach { level ->
                    resolveWriteMethod().invoke(builder, schema, level)
                    resolveReadMethod().invoke(builder, schema, level)
                }
                // Also exercise the "no override, fall through to config defaults" path.
                resolveWriteMethod().invoke(builder, schema, null)
                resolveReadMethod().invoke(builder, schema, null)
            }
        }
    }
}
