package io.kandra.codegen

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.sourcesGeneratedBySymbolProcessor
import com.tschuchort.compiletesting.symbolProcessorProviders
import com.tschuchort.compiletesting.useKsp2
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Regression coverage for GH #156 (ISS-099): a `@ScyllaTable` entity declared in the default
 * package (no `package` line at all -- common in quick experiments/copy-pasted snippets) used to
 * make [KandraProcessor] emit a generated file starting with a bare, nameless `package` line,
 * which is a Kotlin syntax error ("Package name must be a '.'-separated identifier list placed on
 * a single line") -- failing compilation of every entity declared this way, no matter how trivial.
 *
 * Same real-KSP-compilation harness as [KandraProcessorTableContentTest] -- no mocking of KSP
 * internals, so a regression here shows up as an actual failed compilation, not a passing test
 * that merely checked the wrong thing.
 */
@OptIn(ExperimentalCompilerApi::class)
class KandraProcessorDefaultPackageTest {

    /** Deliberately no `package` line -- the exact shape GH #156 is about. */
    private val defaultPackageEntitySource = SourceFile.kotlin(
        "User.kt",
        """
        import io.kandra.core.annotations.PartitionKey
        import io.kandra.core.annotations.ScyllaTable

        @ScyllaTable("users")
        data class User(
            @PartitionKey val userId: String,
            val email: String
        )
        """.trimIndent()
    )

    private val fullTestClasspath: List<File> =
        System.getProperty("java.class.path")!!
            .split(File.pathSeparator)
            .map { File(it) }
            .filter { it.exists() }

    private val koinJars = fullTestClasspath.filter { it.name.contains("koin", ignoreCase = true) }
    private val kodeinJars = fullTestClasspath.filter { it.name.contains("kodein", ignoreCase = true) }
    private val baseClasspath: List<File> =
        fullTestClasspath - koinJars.toSet() - kodeinJars.toSet()

    private fun compile(): JvmCompilationResult {
        val compilation = KotlinCompilation().apply {
            useKsp2()
            sources = listOf(defaultPackageEntitySource)
            symbolProcessorProviders = mutableListOf(KandraProcessorProvider())
            inheritClassPath = false
            classpaths = baseClasspath
            jvmTarget = "17"
            verbose = false
        }
        return compilation.compile()
    }

    private val result: JvmCompilationResult by lazy { compile() }

    @Test
    fun `entity with no package line compiles successfully - GH-156 regression`() {
        // Before the fix, this failed with a syntax error from the generated UserTable.kt's bare
        // `package` line, before ever reaching the actual KandraColumnRef content below.
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertNotNull(result.sourcesGeneratedBySymbolProcessor.firstOrNull { it.name == "UserTable.kt" })
    }

    @Test
    fun `generated UserTable has no bare package line and still has the expected column refs`() {
        val file = result.sourcesGeneratedBySymbolProcessor.first { it.name == "UserTable.kt" }
        val content = file.readText()
        assertTrue(
            content.lines().none { it.trim() == "package" },
            "Generated file should never contain a bare, nameless 'package' line:\n$content"
        )
        assertTrue(
            content.contains("val userId = io.kandra.runtime.dsl.KandraColumnRef<kotlin.String>(\"user_id\")"),
            "Expected the partition key's column ref in:\n$content"
        )
        assertTrue(
            content.contains("val email = io.kandra.runtime.dsl.KandraColumnRef<kotlin.String>(\"email\")"),
            "Expected the email column ref in:\n$content"
        )
    }
}
