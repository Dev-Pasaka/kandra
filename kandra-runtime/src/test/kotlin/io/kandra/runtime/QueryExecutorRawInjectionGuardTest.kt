package io.kandra.runtime

import io.kandra.core.exception.KandraQueryException
import io.kandra.core.schema.EntityReflection
import io.kandra.core.schema.TableSchema
import io.kandra.runtime.dsl.KandraRawQuery
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/** Placeholder entity solely so [TableSchema.entityClass] has something real to point at. */
private data class RawGuardEntity(val id: Int)

/**
 * Regression coverage for GH #32 / ISS-050 — `raw()`/`rawSuspend()`/`rawQuery()`/`rawQuerySuspend()`'s
 * CQL-injection guard.
 *
 * Before this fix, the heuristic in `raw()`/`rawSuspend()` only ran when `params.isEmpty()` — binding
 * a single, entirely unrelated parameter anywhere in the call suppressed the warning for the whole CQL
 * string, even though an unparameterized literal was still spliced into it. `rawQuery()`/
 * `rawQuerySuspend()` had no check at all. These tests prove:
 *  (a) the warning now fires regardless of whether other params are bound, for all four entry points;
 *  (b) it still doesn't false-positive on a fully-parameterized query;
 *  (c) [DebugConfig.rawQueryStrictMode] makes the guard throw instead of warn, and prevents execution.
 *
 * `raw()`/`rawQuery()` never touch the schema, so a bare placeholder [TableSchema] (same pattern as
 * `ConsistencyStrictModeTest`) is enough — no `SchemaRegistry` registration needed.
 */
class QueryExecutorRawInjectionGuardTest {

    private fun schema(): TableSchema = TableSchema(
        entityClass = RawGuardEntity::class,
        tableName = "raw_guard_entities",
        partitionKeys = emptyList(),
        clusteringKeys = emptyList(),
        columns = emptyList(),
        lookupTables = emptyList(),
        reflection = EntityReflection(
            copyFunction = RawGuardEntity::class.memberFunctions.find { it.name == "copy" },
            copyParameters = RawGuardEntity::class.memberFunctions.find { it.name == "copy" }?.parameters ?: emptyList(),
            propertiesByName = RawGuardEntity::class.memberProperties.associateBy { it.name },
            primaryConstructor = RawGuardEntity::class.primaryConstructor,
            constructorParameters = RawGuardEntity::class.primaryConstructor?.parameters ?: emptyList()
        )
    )

    private fun executor(strictMode: Boolean = false, session: ScriptedCqlSession = ScriptedCqlSession()): Pair<QueryExecutor, ScriptedCqlSession> {
        val debugConfig = DebugConfig().apply { rawQueryStrictMode = strictMode }
        val executor = QueryExecutor(session, schema(), StatementBuilder(session), debugConfig = debugConfig)
        return executor to session
    }

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

    // ── (a) warning fires even when other, unrelated params are bound ───────────────────────

    @Test
    fun `raw() warns on an embedded literal even when another unrelated param is bound`() {
        val (executor, _) = executor()
        val output = captureStderr {
            executor.raw("SELECT * FROM t WHERE x = ? AND y = 'admin'", 42)
        }
        assertTrue(output.contains("injection risk"), "Expected an injection-risk WARN, got: $output")
    }

    @Test
    fun `raw() with zero params and a quoted literal still warns (pre-existing behavior preserved)`() {
        val (executor, _) = executor()
        val output = captureStderr {
            executor.raw("SELECT * FROM t WHERE y = 'admin'")
        }
        assertTrue(output.contains("injection risk"), "Expected an injection-risk WARN, got: $output")
    }

    @Test
    fun `raw() does not warn on a fully parameterized query`() {
        val (executor, _) = executor()
        val output = captureStderr {
            executor.raw("SELECT * FROM t WHERE x = ? AND y = ?", 42, "admin")
        }
        assertFalse(output.contains("injection risk"), "Expected no WARN, got: $output")
    }

    @Test
    fun `rawQuery() warns on an embedded literal even though the KandraRawQuery also has bound params`() {
        val (executor, _) = executor()
        val query = KandraRawQuery.cql("SELECT * FROM t WHERE x = ? AND y = 'admin'").bind(42).build()
        val output = captureStderr {
            executor.rawQuery(query)
        }
        assertTrue(output.contains("injection risk"), "Expected an injection-risk WARN, got: $output")
    }

    @Test
    fun `rawQuery() does not warn on a fully parameterized KandraRawQuery`() {
        val (executor, _) = executor()
        val query = KandraRawQuery.cql("SELECT * FROM t WHERE x = ? AND y = ?").bind(42, "admin").build()
        val output = captureStderr {
            executor.rawQuery(query)
        }
        assertFalse(output.contains("injection risk"), "Expected no WARN, got: $output")
    }

    @Test
    fun `rawSuspend() warns on an embedded literal even when another unrelated param is bound`() = runBlocking {
        val (executor, _) = executor()
        val output = captureStderr {
            runBlocking { executor.rawSuspend("SELECT * FROM t WHERE x = ? AND y = 'admin'", 42) }
        }
        assertTrue(output.contains("injection risk"), "Expected an injection-risk WARN, got: $output")
    }

    @Test
    fun `rawQuerySuspend() warns on an embedded literal even though the KandraRawQuery also has bound params`() = runBlocking {
        val (executor, _) = executor()
        val query = KandraRawQuery.cql("SELECT * FROM t WHERE x = ? AND y = 'admin'").bind(42).build()
        val output = captureStderr {
            runBlocking { executor.rawQuerySuspend(query) }
        }
        assertTrue(output.contains("injection risk"), "Expected an injection-risk WARN, got: $output")
    }

    @Test
    fun `bare double-quoted identifier eq pattern also triggers the guard`() {
        val (executor, _) = executor()
        val output = captureStderr {
            executor.raw("SELECT * FROM t WHERE x = ? AND \"name\"='admin'", 42)
        }
        assertTrue(output.contains("injection risk"), "Expected an injection-risk WARN, got: $output")
    }

    // ── (b) strict mode throws instead of warning, and never reaches the driver ─────────────

    @Test
    fun `raw() throws instead of warning when rawQueryStrictMode is enabled`() {
        val (executor, session) = executor(strictMode = true)
        val ex = assertThrows(KandraQueryException::class.java) {
            executor.raw("SELECT * FROM t WHERE x = ? AND y = 'admin'", 42)
        }
        assertTrue(ex.message!!.contains("injection risk"), "Expected exception message to mention injection risk, got: ${ex.message}")
        assertEquals(0, session.executeCount.get(), "Strict mode must prevent execution, not just log")
    }

    @Test
    fun `rawQuery() throws instead of warning when rawQueryStrictMode is enabled`() {
        val (executor, session) = executor(strictMode = true)
        val query = KandraRawQuery.cql("SELECT * FROM t WHERE y = 'admin'").build()
        assertThrows(KandraQueryException::class.java) {
            executor.rawQuery(query)
        }
        assertEquals(0, session.executeCount.get(), "Strict mode must prevent execution, not just log")
    }

    @Test
    fun `rawSuspend() throws instead of warning when rawQueryStrictMode is enabled`() = runBlocking {
        val (executor, session) = executor(strictMode = true)
        assertThrows(KandraQueryException::class.java) {
            runBlocking { executor.rawSuspend("SELECT * FROM t WHERE y = 'admin'") }
        }
        assertEquals(0, session.executeCount.get(), "Strict mode must prevent execution, not just log")
    }

    @Test
    fun `strict mode does not throw when the CQL has no embedded literal`() {
        val (executor, session) = executor(strictMode = true)
        val rows = executor.raw("SELECT * FROM t WHERE x = ?", 42)
        assertTrue(rows.isNotEmpty() || rows.isEmpty()) // just proving no exception was thrown
        assertEquals(1, session.executeCount.get(), "Query without a suspicious literal must still execute")
    }
}
