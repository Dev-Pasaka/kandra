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
 * Coverage for the illegal/edge entity shapes GH-109 (item 3) flagged as untested: two entities
 * with colliding simple names in the same package (GH-104 part 1's file-name-collision guard),
 * `@ScyllaTable` on an `object`/`interface`/`enum class` (GH-104 part 2 — codegen itself performs
 * no structural validation; that's enforced at runtime by `SchemaRegistry.buildSchema`, so codegen
 * is documented here to still succeed), and an entity with zero `@PartitionKey` properties (also
 * not validated by codegen — `SchemaRegistry` is the one that requires a partition key).
 *
 * Same kotlin-compile-testing / KSP2 harness as [KandraProcessorTableContentTest].
 */
@OptIn(ExperimentalCompilerApi::class)
class KandraProcessorEdgeCaseTest {

    private val fullTestClasspath: List<File> =
        System.getProperty("java.class.path")!!
            .split(File.pathSeparator)
            .map { File(it) }
            .filter { it.exists() }

    private val koinJars = fullTestClasspath.filter { it.name.contains("koin", ignoreCase = true) }
    private val kodeinJars = fullTestClasspath.filter { it.name.contains("kodein", ignoreCase = true) }
    private val baseClasspath: List<File> =
        fullTestClasspath - koinJars.toSet() - kodeinJars.toSet()

    private fun compile(source: SourceFile): JvmCompilationResult {
        val compilation = KotlinCompilation().apply {
            useKsp2()
            sources = listOf(source)
            symbolProcessorProviders = mutableListOf(KandraProcessorProvider())
            inheritClassPath = false
            classpaths = baseClasspath
            jvmTarget = "17"
            verbose = false
        }
        return compilation.compile()
    }

    /**
     * GH-104 part 1: two distinct `@ScyllaTable` entities nested under different outer classes but
     * sharing the simple name `User`, both in package `sample`, both compute `objectName =
     * "UserTable"`. Before the fix this crashed the build with a raw KSP
     * `FileAlreadyExistsException`; now [KandraProcessor] must detect the collision itself and fail
     * with a clear, Kandra-flavored diagnostic naming both classes instead.
     */
    @Test
    fun `colliding simple names under different outer classes fail with a clear diagnostic, not a raw KSP crash`() {
        val source = SourceFile.kotlin(
            "Collision.kt",
            """
            package sample

            import io.kandra.core.annotations.PartitionKey
            import io.kandra.core.annotations.ScyllaTable

            class Ns1 {
                @ScyllaTable("users1")
                data class User(@PartitionKey val id: String)
            }

            class Ns2 {
                @ScyllaTable("users2")
                data class User(@PartitionKey val id: String)
            }
            """.trimIndent()
        )
        val result = compile(source)

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            result.messages.contains("already claimed") && result.messages.contains("UserTable"),
            "Expected a clear Kandra collision diagnostic naming 'UserTable' in:\n${result.messages}"
        )
        // The raw KSP-internal crash this replaces threw java.nio.file.FileAlreadyExistsException /
        // com.google.devtools.ksp.processing.CodeGenerator's own exception type — make sure that's
        // not what leaked through instead of our diagnostic.
        assertTrue(
            !result.messages.contains("FileAlreadyExistsException"),
            "Expected the clear Kandra diagnostic to replace the raw KSP crash, but found it in:\n${result.messages}"
        )
    }

    /**
     * GH-104 part 2: codegen performs no `classKind` check — `@ScyllaTable` on an `object` is
     * accepted and a `*Table` object is generated referencing it, exactly like a normal entity.
     * The corresponding fail-fast diagnostic lives in `SchemaRegistry.buildSchema` (runtime side,
     * covered in kandra-core's own test suite), not here — this test documents/pins codegen's
     * current (intentionally permissive) behavior so a future change to that scope is a deliberate
     * decision, not an accidental regression.
     */
    @Test
    fun `@ScyllaTable on an object compiles fine at the codegen level`() {
        val source = SourceFile.kotlin(
            "ObjectEntity.kt",
            """
            package sample

            import io.kandra.core.annotations.PartitionKey
            import io.kandra.core.annotations.ScyllaTable

            @ScyllaTable("singletons")
            object SingletonThing {
                @PartitionKey val id: String = "fixed"
            }
            """.trimIndent()
        )
        val result = compile(source)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertNotNull(
            result.sourcesGeneratedBySymbolProcessor.firstOrNull { it.name == "SingletonThingTable.kt" },
            "Expected SingletonThingTable.kt to still be generated for an object entity"
        )
    }

    /** Same as above, for `interface`. */
    @Test
    fun `@ScyllaTable on an interface compiles fine at the codegen level`() {
        val source = SourceFile.kotlin(
            "InterfaceEntity.kt",
            """
            package sample

            import io.kandra.core.annotations.PartitionKey
            import io.kandra.core.annotations.ScyllaTable

            @ScyllaTable("things")
            interface ThingLike {
                @PartitionKey val id: String
            }
            """.trimIndent()
        )
        val result = compile(source)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertNotNull(
            result.sourcesGeneratedBySymbolProcessor.firstOrNull { it.name == "ThingLikeTable.kt" },
            "Expected ThingLikeTable.kt to still be generated for an interface entity"
        )
    }

    /** Same as above, for `enum class`. */
    @Test
    fun `@ScyllaTable on an enum class compiles fine at the codegen level`() {
        val source = SourceFile.kotlin(
            "EnumEntity.kt",
            """
            package sample

            import io.kandra.core.annotations.ScyllaTable

            @ScyllaTable("colors")
            enum class Color { RED, GREEN, BLUE }
            """.trimIndent()
        )
        val result = compile(source)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertNotNull(
            result.sourcesGeneratedBySymbolProcessor.firstOrNull { it.name == "ColorTable.kt" },
            "Expected ColorTable.kt to still be generated for an enum entity"
        )
    }

    /**
     * GH-109 item 3: codegen has no opinion on primary-key correctness — a `@ScyllaTable` class
     * with zero `@PartitionKey` properties generates a `*Table` object exactly like a valid one.
     * `SchemaRegistry` is what requires a partition key, at runtime registration, not codegen.
     */
    @Test
    fun `@ScyllaTable entity with zero @PartitionKey properties compiles fine at the codegen level`() {
        val source = SourceFile.kotlin(
            "NoKeyEntity.kt",
            """
            package sample

            import io.kandra.core.annotations.ScyllaTable

            @ScyllaTable("no_key_things")
            data class NoKeyThing(val name: String, val note: String)
            """.trimIndent()
        )
        val result = compile(source)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val file = result.sourcesGeneratedBySymbolProcessor.firstOrNull { it.name == "NoKeyThingTable.kt" }
        assertNotNull(file, "Expected NoKeyThingTable.kt to still be generated for a keyless entity")
        val content = file!!.readText()
        assertTrue(content.contains("val name ="), "Expected 'name' column line not found in:\n$content")
        assertTrue(content.contains("val note ="), "Expected 'note' column line not found in:\n$content")
    }
}
