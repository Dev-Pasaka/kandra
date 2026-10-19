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
 * ISS-110: `@ScyllaTable(keyspace = ...)` and codegen.
 *
 * The generated `*Table` object carries only column refs (the table and keyspace are resolved at
 * runtime from the entity's schema), so a keyspaced entity must generate byte-identical code to the
 * same entity without a keyspace. The processor additionally validates the keyspace identifier.
 */
@OptIn(ExperimentalCompilerApi::class)
class KandraProcessorKeyspaceTest {

    private val classpath: List<File> =
        System.getProperty("java.class.path")!!
            .split(File.pathSeparator).map { File(it) }.filter { it.exists() }
            .filterNot { it.name.contains("koin", true) || it.name.contains("kodein", true) }

    private fun entity(annotationArgs: String) = SourceFile.kotlin(
        "Invoice.kt",
        """
        package sample

        import io.kandra.core.annotations.PartitionKey
        import io.kandra.core.annotations.ScyllaTable

        @ScyllaTable($annotationArgs)
        data class Invoice(@PartitionKey val id: String, val total: Int)
        """.trimIndent()
    )

    private fun compile(source: SourceFile): JvmCompilationResult =
        KotlinCompilation().apply {
            useKsp2()
            sources = listOf(source)
            symbolProcessorProviders = mutableListOf(KandraProcessorProvider())
            inheritClassPath = false
            classpaths = classpath
            jvmTarget = "17"
            verbose = false
        }.compile()

    private fun JvmCompilationResult.tableText(): String {
        val f = sourcesGeneratedBySymbolProcessor.firstOrNull { it.name == "InvoiceTable.kt" }
        assertNotNull(f, "Expected InvoiceTable.kt to be generated")
        return f!!.readText()
    }

    @Test
    fun `keyspaced entity compiles and generates the table object`() {
        val r = compile(entity("\"invoices\", keyspace = \"billing\""))
        assertEquals(KotlinCompilation.ExitCode.OK, r.exitCode, r.messages)
        val text = r.tableText()
        assertTrue(text.contains("object InvoiceTable : io.kandra.runtime.dsl.KandraTable<Invoice>"), text)
        assertTrue(text.contains("val total = io.kandra.runtime.dsl.KandraColumnRef<kotlin.Int>(\"total\")"), text)
    }

    @Test
    fun `keyspaced and non-keyspaced entities generate byte-identical output`() {
        val plain = compile(entity("\"invoices\"")).tableText()
        val blank = compile(entity("\"invoices\", keyspace = \"\"")).tableText()
        val keyspaced = compile(entity("\"invoices\", keyspace = \"billing\"")).tableText()
        assertEquals(plain, blank)
        assertEquals(plain, keyspaced)
        assertTrue(!keyspaced.contains("billing"), "Generated code must not embed the keyspace:\n$keyspaced")
    }

    @Test
    fun `invalid keyspace identifier fails the build with a Kandra diagnostic`() {
        val r = compile(entity("\"invoices\", keyspace = \"bad-name; DROP\""))
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, r.exitCode, r.messages)
        assertTrue(r.messages.contains("invalid @ScyllaTable keyspace 'bad-name; DROP'"), r.messages)
        assertTrue(r.sourcesGeneratedBySymbolProcessor.none { it.name == "InvoiceTable.kt" })
    }
}
