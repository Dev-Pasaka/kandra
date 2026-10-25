package io.kandra.migrate

import io.kandra.core.exception.KandraMigrationException
import io.kandra.test.KandraTestBackend
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Path

/**
 * Regression coverage for GH #191: a migration whose `up()` calls an inline function (`forEach`)
 * gets Kotlin's inline source map (SMAP, with line numbers) as a constant-pool Utf8 entry, not
 * only in the stripped `SourceDebugExtension` attribute. Moving the migration's source lines (a
 * reformat, a KDoc added above it) changed that constant and with it the checksum, so
 * [KandraMigrationRunner] refused to start against an already-applied migration.
 *
 * Unlike [BytecodeNormalizerTest], which mutates bytes of one already-compiled class, this test
 * really compiles the same source twice with the Kotlin compiler, the second time with lines
 * added above the `object`, and compares [KandraMigration.checksum] of the two results.
 */
class KandraMigrationChecksumLayoutTest {

    private fun source(padding: String, cql: String = "CREATE TABLE IF NOT EXISTS users (id UUID PRIMARY KEY)", trailer: String = "") = """
        package gh191
        $padding
        import com.datastax.oss.driver.api.core.CqlSession
        import io.kandra.migrate.KandraMigration
        $padding
        object V001_CreateUsersTables : KandraMigration(version = 1, name = "create users tables") {
            override fun up(session: CqlSession) {
                listOf(
                    "$cql",
                    "CREATE TABLE IF NOT EXISTS users_by_email (email TEXT PRIMARY KEY, id UUID)",
                ).forEach { session.execute(it) }
            }
        }
        $trailer
    """.trimIndent()

    /** Compiles [source] into [workDir] and returns the migration object's [KandraMigration.checksum]. */
    private fun compileAndChecksum(source: String, workDir: File): String = compileAndLoad(source, workDir) { it.checksum() }

    /** Compiles [source] into [workDir], loads the migration object and passes it to [use]. */
    private fun <R> compileAndLoad(source: String, workDir: File, use: (KandraMigration) -> R): R {
        val srcFile = File(workDir, "src/V001.kt").apply { parentFile.mkdirs(); writeText(source) }
        val outDir = File(workDir, "classes").apply { mkdirs() }
        val log = ByteArrayOutputStream()
        val exit = K2JVMCompiler().exec(
            PrintStream(log),
            srcFile.path,
            "-d", outDir.path,
            "-classpath", System.getProperty("java.class.path"),
            "-no-stdlib", "-no-reflect",
            "-jvm-target", "21",
        )
        assertEquals(ExitCode.OK, exit, "compilation failed:\n$log")
        URLClassLoader(arrayOf(outDir.toURI().toURL()), javaClass.classLoader).use { loader ->
            val migration = loader.loadClass("gh191.V001_CreateUsersTables").getField("INSTANCE").get(null) as KandraMigration
            return use(migration)
        }
    }

    private fun classFileOf(workDir: File) = File(workDir, "classes/gh191/V001_CreateUsersTables.class").readBytes()

    @Test
    fun `moving the source lines of a migration that calls an inline function keeps its checksum`(@TempDir tmp: Path) {
        val a = tmp.resolve("a").toFile()
        val b = tmp.resolve("b").toFile()
        val before = compileAndChecksum(source(padding = ""), a)
        val after = compileAndChecksum(source(padding = "\n".repeat(9) + "/** A KDoc line added above the migration. */"), b)

        // Sanity: the raw class files really differ (the SMAP constant carries the moved line
        // numbers), otherwise this test would prove nothing.
        val rawA = classFileOf(a)
        val rawB = classFileOf(b)
        assertTrue(String(rawA, Charsets.ISO_8859_1).contains("SMAP\n"), "expected an SMAP constant in the compiled class")
        assertNotEquals(rawA.toList(), rawB.toList(), "the raw class files should differ after moving the lines")

        assertEquals(before, after, "a layout-only change must not change the migration checksum")
    }

    @Test
    fun `adding lines below the migration keeps its checksum`(@TempDir tmp: Path) {
        // The SMAP also records the file's total line count, so lines added *after* the
        // migration changed the checksum too (follow-up comment on GH #191).
        val a = tmp.resolve("a").toFile()
        val b = tmp.resolve("b").toFile()
        val before = compileAndChecksum(source(padding = ""), a)
        val after = compileAndChecksum(source(padding = "", trailer = "\n".repeat(20) + "// trailing comment\nprivate val unrelated = 42"), b)
        assertNotEquals(classFileOf(a).toList(), classFileOf(b).toList(), "the raw class files should differ")
        assertEquals(before, after)
    }

    @Test
    fun `every string of the SourceDebugExtension annotation is excluded`(@TempDir tmp: Path) {
        compileAndChecksum(source(padding = ""), tmp.resolve("a").toFile())
        val bytes = classFileOf(tmp.resolve("a").toFile())
        val normalized = String(BytecodeNormalizer.normalize(bytes), Charsets.ISO_8859_1)
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("SMAP\n"))
        assertTrue(!normalized.contains("SMAP\n"), "no SMAP text may survive normalization")
        // The annotation itself (its type descriptor) is kept; only its line-dependent strings go.
        assertTrue(normalized.contains("Lkotlin/jvm/internal/SourceDebugExtension;"))
    }

    @Test
    fun `an annotation string that does not start with SMAP is still excluded`(@TempDir tmp: Path) {
        // Stands in for a long SMAP split across several annotation strings, where only the
        // first chunk starts with "SMAP": rename the prefix so only the annotation path can match.
        compileAndChecksum(source(padding = ""), tmp.resolve("a").toFile())
        val original = classFileOf(tmp.resolve("a").toFile())
        val at = String(original, Charsets.ISO_8859_1).indexOf("SMAP\n")
        val chunk = original.copyOf().also { it[at] = 'X'.code.toByte() }        // "XMAP\n...": no longer prefixed
        val chunkEdited = chunk.copyOf().also { it[at + 10] = (it[at + 10] + 1).toByte() } // line-number-like edit inside it
        assertEquals(
            BytecodeNormalizer.normalize(chunk).toList(), BytecodeNormalizer.normalize(chunkEdited).toList(),
            "a change inside a SourceDebugExtension string must not reach the hash"
        )
    }

    @Test
    fun `changing the CQL of a migration that calls an inline function still changes its checksum`(@TempDir tmp: Path) {
        val before = compileAndChecksum(source(padding = ""), tmp.resolve("a").toFile())
        val after = compileAndChecksum(
            source(padding = "", cql = "CREATE TABLE IF NOT EXISTS users (id UUID PRIMARY KEY, email TEXT)"),
            tmp.resolve("b").toFile()
        )
        assertNotEquals(before, after, "a change to the CQL up() runs must still change the checksum")
    }

    @Test
    fun `a row applied with the pre-fix checksum verifies and is upgraded to the new checksum`(@TempDir tmp: Path) {
        val keyspace = KandraTestBackend.keyspaceName("gh191")
        KandraTestBackend.createKeyspace(keyspace)
        try {
            KandraTestBackend.newSession(keyspace).use { session ->
                compileAndLoad(source(padding = ""), tmp.resolve("a").toFile()) { migration ->
                    val legacy = migration.legacyChecksum()
                    assertNotEquals(legacy, migration.checksum(), "an inline-calling migration's legacy checksum differs")

                    // Simulate a row written by Kandra <= 0.5.1: APPLIED with the legacy checksum.
                    val runner = KandraMigrationRunner(session)
                    session.execute(
                        session.prepare(
                            "INSERT INTO kandra_migrations (version, name, status, applied_at, checksum) " +
                                "VALUES (?, ?, 'APPLIED', toTimestamp(now()), ?)"
                        ).bind(migration.version, migration.name, legacy)
                    )

                    runner.run(migration) // must not throw
                    val stored = runner.history().single { it.version == migration.version }.checksum
                    assertEquals(migration.checksum(), stored, "the stored checksum is rewritten to the new form")
                    runner.run(migration) // and verifies directly from now on
                }

                // A genuinely different migration under the same version is still rejected.
                compileAndLoad(
                    source(padding = "", cql = "CREATE TABLE IF NOT EXISTS users (id UUID PRIMARY KEY, email TEXT)"),
                    tmp.resolve("b").toFile()
                ) { changed ->
                    assertThrows(KandraMigrationException::class.java) { KandraMigrationRunner(session).run(changed) }
                }
            }
        } finally {
            KandraTestBackend.dropKeyspace(keyspace)
        }
    }
}
