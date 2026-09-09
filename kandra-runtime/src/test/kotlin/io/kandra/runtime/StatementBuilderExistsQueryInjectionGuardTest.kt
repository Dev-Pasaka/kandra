package io.kandra.runtime

import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.exception.KandraQueryException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.UUID

@ScyllaTable("sb_exists_guard")
private data class ExistsGuardEntity(
    @PartitionKey val id: UUID,
    val email: String
)

/**
 * Regression coverage for GH #107 (finding 2) — [StatementBuilder.existsQuery] spliced its
 * `whereCql` argument directly into the generated CQL with zero identifier/heuristic validation,
 * unlike every other raw-CQL entry point (`QueryExecutor.raw()`/`rawQuery()`, guarded under GH #50 /
 * ISS-050). `existsQuery` has zero call sites anywhere in the codebase today, but [StatementBuilder]
 * is a public class with a public constructor, reachable cross-module (e.g. from `kandra-kodein`) --
 * exactly the "forgotten escape hatch" shape #50 was filed to close everywhere else.
 *
 * Mirrors the pattern in [QueryExecutorRawInjectionGuardTest]: captures stderr for the WARN case,
 * and asserts [DebugConfig.rawQueryStrictMode] makes the guard throw instead, before the statement
 * is ever prepared against the session.
 */
class StatementBuilderExistsQueryInjectionGuardTest {

    @AfterEach
    fun tearDown() {
        SchemaRegistry.clear()
    }

    private fun builder(strictMode: Boolean = false): StatementBuilder =
        StatementBuilder(ControllableFakeSession(), debugConfig = DebugConfig().apply { rawQueryStrictMode = strictMode })

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

    @Test
    fun `existsQuery warns on an embedded literal spliced into whereCql`() {
        val schema = SchemaRegistry.register(ExistsGuardEntity::class)
        val output = captureStderr {
            builder().existsQuery(schema, "email = 'admin@example.com'", emptyList())
        }
        assertTrue(output.contains("injection risk"), "Expected an injection-risk WARN, got: $output")
    }

    @Test
    fun `existsQuery does not warn on a fully parameterized whereCql`() {
        val schema = SchemaRegistry.register(ExistsGuardEntity::class)
        val output = captureStderr {
            builder().existsQuery(schema, "email = ?", listOf("admin@example.com"))
        }
        assertFalse(output.contains("injection risk"), "Expected no WARN, got: $output")
    }

    @Test
    fun `existsQuery throws instead of warning when rawQueryStrictMode is enabled`() {
        val schema = SchemaRegistry.register(ExistsGuardEntity::class)
        val ex = assertThrows(KandraQueryException::class.java) {
            builder(strictMode = true).existsQuery(schema, "email = 'admin@example.com'", emptyList())
        }
        assertTrue(ex.message!!.contains("injection risk"), "Expected exception message to mention injection risk, got: ${ex.message}")
    }

    @Test
    fun `existsQuery strict mode does not throw when whereCql has no embedded literal`() {
        val schema = SchemaRegistry.register(ExistsGuardEntity::class)
        // Must not throw -- proving the guard doesn't false-positive on a legitimate parameterized query.
        builder(strictMode = true).existsQuery(schema, "email = ?", listOf("admin@example.com"))
    }
}
