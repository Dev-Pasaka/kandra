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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Compile-testing coverage for [KandraProcessor]'s conditional Koin/Kodein DI accessor
 * generation (GH-17). Each test spins up a real, isolated Kotlin+KSP compilation (via
 * kotlin-compile-testing) with an explicitly controlled classpath — never the full test
 * module's own classpath — so that "Koin present"/"Kodein present"/"neither"/"both" can be
 * simulated precisely, exactly as a real consuming module would look with or without those
 * DI libraries as dependencies.
 *
 * `kspWithCompilation = true` runs KSP *and* compiles whatever it generates in the same pass,
 * so a green [KotlinCompilation.ExitCode.OK] here proves more than "the generated text looks
 * right" — it proves the generated `KoinComponent`/`DIAware` extension functions actually
 * compile against the real Koin/Kodein artifacts.
 */
@OptIn(ExperimentalCompilerApi::class)
class KandraProcessorDiAccessorsTest {

    private val userEntitySource = SourceFile.kotlin(
        "User.kt",
        """
        package sample

        import io.kandra.core.annotations.PartitionKey
        import io.kandra.core.annotations.ScyllaTable

        @ScyllaTable("users")
        data class User(
            @PartitionKey val userId: String,
            val email: String
        )
        """.trimIndent()
    )

    /** The full classpath of *this* test module (kandra-codegen's own test compilation). */
    private val fullTestClasspath: List<File> =
        System.getProperty("java.class.path")!!
            .split(File.pathSeparator)
            .map { File(it) }
            .filter { it.exists() }

    private val koinJars = fullTestClasspath.filter { it.name.contains("koin", ignoreCase = true) }
    private val kodeinJars = fullTestClasspath.filter { it.name.contains("kodein", ignoreCase = true) }

    /** Everything the test module needs (kandra-core, kandra-runtime, kotlin-stdlib, ...) minus Koin/Kodein. */
    private val baseClasspath: List<File> =
        fullTestClasspath - koinJars.toSet() - kodeinJars.toSet()

    init {
        check(koinJars.isNotEmpty()) { "Expected koin-core on the test classpath, found none in: $fullTestClasspath" }
        check(kodeinJars.isNotEmpty()) { "Expected kodein-di on the test classpath, found none in: $fullTestClasspath" }
    }

    private fun compileWith(classpath: List<File>): JvmCompilationResult {
        val compilation = KotlinCompilation().apply {
            useKsp2()
            sources = listOf(userEntitySource)
            symbolProcessorProviders = mutableListOf(KandraProcessorProvider())
            inheritClassPath = false
            classpaths = classpath
            // kodein-di's inline `instance()`/`direct` are compiled targeting JVM 11; inlining
            // that bytecode into a default (JVM 1.8) compile target fails, so bump the target.
            jvmTarget = "17"
            verbose = false
        }
        return compilation.compile()
    }

    private fun JvmCompilationResult.generatedFileNamed(name: String): File? =
        sourcesGeneratedBySymbolProcessor.firstOrNull { it.name == name }

    @Test
    fun `neither Koin nor Kodein on classpath - only Table object is generated, build stays green`() {
        val result = compileWith(baseClasspath)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

        assertNotNull(result.generatedFileNamed("UserTable.kt"))
        assertNull(result.generatedFileNamed("UserKoinDi.kt"))
        assertNull(result.generatedFileNamed("UserKodeinDi.kt"))
    }

    @Test
    fun `Koin on classpath - typed Koin accessors are generated and compile`() {
        val result = compileWith(baseClasspath + koinJars)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

        assertNotNull(result.generatedFileNamed("UserTable.kt"))
        assertNull(result.generatedFileNamed("UserKodeinDi.kt"))

        val koinFile = result.generatedFileNamed("UserKoinDi.kt")
        assertNotNull(koinFile, "Expected UserKoinDi.kt to be generated")
        val content = koinFile!!.readText()

        assertTrue(content.contains("import org.koin.core.component.KoinComponent"))
        assertTrue(content.contains("fun KoinComponent.userRepo(): io.kandra.runtime.repository.KandraRepository<User>"))
        assertTrue(content.contains("named(\"UserRepo\")"))
        assertTrue(content.contains("fun KoinComponent.userSuspendRepo(): io.kandra.runtime.repository.KandraSuspendRepository<User>"))
        assertTrue(content.contains("named(\"UserSuspendRepo\")"))
    }

    @Test
    fun `Kodein on classpath - typed Kodein accessors are generated and compile`() {
        val result = compileWith(baseClasspath + kodeinJars)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

        assertNotNull(result.generatedFileNamed("UserTable.kt"))
        assertNull(result.generatedFileNamed("UserKoinDi.kt"))

        val kodeinFile = result.generatedFileNamed("UserKodeinDi.kt")
        assertNotNull(kodeinFile, "Expected UserKodeinDi.kt to be generated")
        val content = kodeinFile!!.readText()

        assertTrue(content.contains("import org.kodein.di.DIAware"))
        assertTrue(content.contains("fun DIAware.userRepo(): io.kandra.runtime.repository.KandraRepository<User>"))
        assertTrue(content.contains("tag = \"User\""))
        assertTrue(content.contains("fun DIAware.userSuspendRepo(): io.kandra.runtime.repository.KandraSuspendRepository<User>"))
        assertTrue(content.contains("tag = \"UserSuspend\""))
    }

    @Test
    fun `both Koin and Kodein on classpath - both accessor files are generated`() {
        val result = compileWith(baseClasspath + koinJars + kodeinJars)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

        assertNotNull(result.generatedFileNamed("UserTable.kt"))
        assertNotNull(result.generatedFileNamed("UserKoinDi.kt"))
        assertNotNull(result.generatedFileNamed("UserKodeinDi.kt"))
    }
}
