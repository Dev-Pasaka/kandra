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
 * Content-level coverage for [KandraProcessor]'s generated `*Table.kt` output (GH-37).
 *
 * [KandraProcessorDiAccessorsTest] already exercises the conditional Koin/Kodein accessor
 * generation with real content assertions; this file fills the remaining gap the issue called
 * out — nothing previously asserted on the actual `KandraColumnRef<...>(...)` lines a `*Table`
 * object is built from. Every test here compiles a small fixture entity through the real KSP
 * processor (no mocking of KSP internals) and reads back the generated file's text, so a
 * regression in `resolveTypeName`, `resolveCqlName`, or the `isLookup` threading shows up as a
 * failing substring/line assertion rather than merely "a file with this name exists."
 *
 * Uses the same kotlin-compile-testing harness and classpath-trimming approach as
 * [KandraProcessorDiAccessorsTest] (KSP2, `symbolProcessorProviders`, `inheritClassPath = false`
 * with an explicit classpath) — neither Koin nor Kodein is on the classpath here since this file
 * is only concerned with the `*Table` object, not the DI accessor files.
 */
@OptIn(ExperimentalCompilerApi::class)
class KandraProcessorTableContentTest {

    /**
     * One entity covering every case the issue asks for: a plain non-nullable simple type, a
     * nullable simple type (see the `nullableName` test below for why it currently generates
     * identically to the non-nullable case), three flavors of generic collection, an explicit
     * `@Column` rename, a blank `@Column("")` (the GH-30 regression case — must resolve via
     * `CqlNaming.resolveColumnName`, not an independently-drifting fallback), and a
     * `@LookupIndex` column to verify `isLookup = true` threading.
     */
    private val widgetEntitySource = SourceFile.kotlin(
        "Widget.kt",
        """
        package sample

        import io.kandra.core.annotations.Column
        import io.kandra.core.annotations.LookupIndex
        import io.kandra.core.annotations.PartitionKey
        import io.kandra.core.annotations.ScyllaTable

        @ScyllaTable("widgets")
        data class Widget(
            @PartitionKey val id: String,
            val nullableName: String?,
            val tags: List<String>,
            val scores: Set<Int>,
            val attributes: Map<String, Int>,
            @Column("custom_col") val renamed: String,
            @Column("") val blankColumn: String,
            @LookupIndex(tableSuffix = "by_email") val email: String
        )
        """.trimIndent()
    )

    /** The full classpath of *this* test module (kandra-codegen's own test compilation). */
    private val fullTestClasspath: List<File> =
        System.getProperty("java.class.path")!!
            .split(File.pathSeparator)
            .map { File(it) }
            .filter { it.exists() }

    /**
     * Neither Koin nor Kodein need to be present for these tests — they're purely about the
     * `*Table` object, which is generated unconditionally. Excluding both keeps the compilation
     * minimal and avoids incidentally depending on those DI accessor files existing.
     */
    private val koinJars = fullTestClasspath.filter { it.name.contains("koin", ignoreCase = true) }
    private val kodeinJars = fullTestClasspath.filter { it.name.contains("kodein", ignoreCase = true) }
    private val baseClasspath: List<File> =
        fullTestClasspath - koinJars.toSet() - kodeinJars.toSet()

    private fun compile(): JvmCompilationResult {
        val compilation = KotlinCompilation().apply {
            useKsp2()
            sources = listOf(widgetEntitySource)
            symbolProcessorProviders = mutableListOf(KandraProcessorProvider())
            inheritClassPath = false
            classpaths = baseClasspath
            jvmTarget = "17"
            verbose = false
        }
        return compilation.compile()
    }

    private fun JvmCompilationResult.generatedFileNamed(name: String): File? =
        sourcesGeneratedBySymbolProcessor.firstOrNull { it.name == name }

    private val result: JvmCompilationResult by lazy { compile() }
    private val tableContent: String by lazy {
        val file = result.generatedFileNamed("WidgetTable.kt")
        assertNotNull(file, "Expected WidgetTable.kt to be generated")
        file!!.readText()
    }

    @Test
    fun `compilation succeeds and WidgetTable is generated`() {
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertNotNull(result.generatedFileNamed("WidgetTable.kt"))
    }

    @Test
    fun `non-nullable simple type property generates KandraColumnRef with the plain type and snake_cased cqlName`() {
        assertTrue(
            tableContent.contains("val id = io.kandra.runtime.dsl.KandraColumnRef<kotlin.String>(\"id\")"),
            "Expected non-nullable String column line not found in:\n$tableContent"
        )
    }

    @Test
    fun `nullable property currently generates the SAME type as non-nullable - GH-36 nullability threading is not yet implemented`() {
        // resolveTypeName only splices `type.declaration.qualifiedName` (plus recursed generic
        // arguments); it never consults `type.isMarkedNullable`, so a `String?` property currently
        // renders identically to a non-nullable `String` property: `KandraColumnRef<kotlin.String>`,
        // not `KandraColumnRef<kotlin.String?>`. This is the CURRENT behavior, not the desired one —
        // GH-36 (still open) covers adding nullability threading. Once that lands, this assertion
        // must change to expect `kotlin.String?` and stop asserting the erasure.
        assertTrue(
            tableContent.contains("val nullableName = io.kandra.runtime.dsl.KandraColumnRef<kotlin.String>(\"nullable_name\")"),
            "Expected nullable property to currently generate the same non-nullable-looking type in:\n$tableContent"
        )
    }

    @Test
    fun `generic collection types get their type arguments spliced in recursively`() {
        assertTrue(
            tableContent.contains("val tags = io.kandra.runtime.dsl.KandraColumnRef<kotlin.collections.List<kotlin.String>>(\"tags\")"),
            "Expected List<String> column line not found in:\n$tableContent"
        )
        assertTrue(
            tableContent.contains("val scores = io.kandra.runtime.dsl.KandraColumnRef<kotlin.collections.Set<kotlin.Int>>(\"scores\")"),
            "Expected Set<Int> column line not found in:\n$tableContent"
        )
        assertTrue(
            tableContent.contains(
                "val attributes = io.kandra.runtime.dsl.KandraColumnRef<kotlin.collections.Map<kotlin.String, kotlin.Int>>(\"attributes\")"
            ),
            "Expected Map<String, Int> column line not found in:\n$tableContent"
        )
    }

    @Test
    fun `explicit @Column name is used verbatim as the cqlName`() {
        assertTrue(
            tableContent.contains("val renamed = io.kandra.runtime.dsl.KandraColumnRef<kotlin.String>(\"custom_col\")"),
            "Expected @Column-renamed column line not found in:\n$tableContent"
        )
    }

    @Test
    fun `blank @Column name falls back to camelToSnake of the property name via CqlNaming - GH-30 regression`() {
        // Regression test for GH-30: resolveCqlName now delegates to
        // io.kandra.core.CqlNaming.resolveColumnName, which treats a blank @Column("") identically
        // to "no @Column at all" and falls back to camelToSnake(propertyName). Before that fix, a
        // separately-maintained inline fallback in KandraProcessor could in principle drift from
        // SchemaRegistry's runtime resolution for the same entity.
        assertTrue(
            tableContent.contains("val blankColumn = io.kandra.runtime.dsl.KandraColumnRef<kotlin.String>(\"blank_column\")"),
            "Expected blank @Column(\"\") to fall back to 'blank_column' in:\n$tableContent"
        )
    }

    @Test
    fun `@LookupIndex column threads isLookup = true into the generated KandraColumnRef`() {
        assertTrue(
            tableContent.contains(
                "val email = io.kandra.runtime.dsl.KandraColumnRef<kotlin.String>(\"email\", isLookup = true)"
            ),
            "Expected @LookupIndex column to carry isLookup = true in:\n$tableContent"
        )
    }

    @Test
    fun `non-lookup columns do not carry isLookup = true`() {
        assertTrue(
            !tableContent.contains("val id = io.kandra.runtime.dsl.KandraColumnRef<kotlin.String>(\"id\", isLookup = true)"),
            "Did not expect the partition-key column to be marked isLookup in:\n$tableContent"
        )
    }
}
