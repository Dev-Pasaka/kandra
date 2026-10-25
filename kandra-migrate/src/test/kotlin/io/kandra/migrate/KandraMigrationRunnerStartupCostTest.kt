package io.kandra.migrate

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.BoundStatement
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import io.kandra.test.KandraTestBackend
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.Collections
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * Regression coverage for GH #194: work [KandraMigrationRunner] repeated on every startup.
 *  - [KandraMigration.checksum] walked the whole application jar once per migration.
 *  - Constructing a runner against an already-bootstrapped keyspace still ran
 *    `CREATE TABLE IF NOT EXISTS` and a losing `INSERT ... IF NOT EXISTS` LWT.
 */
class KandraMigrationRunnerStartupCostTest {

    private val migrationCount = 12

    private fun migrationSource(i: Int) = """
        package gh194
        import com.datastax.oss.driver.api.core.CqlSession
        import io.kandra.migrate.KandraMigration
        object V$i : KandraMigration(version = $i, name = "m$i") {
            override fun up(session: CqlSession) {
                listOf("CREATE TABLE IF NOT EXISTS t$i (id UUID PRIMARY KEY)").forEach { session.execute(it) }
                val sorter = object : Comparator<String> { override fun compare(a: String, b: String) = a.compareTo(b) }
                listOf("b", "a").sortedWith(sorter)
            }
        }
    """.trimIndent()

    private fun compileMigrations(workDir: File): File {
        val src = File(workDir, "src").apply { mkdirs() }
        (1..migrationCount).forEach { File(src, "V$it.kt").writeText(migrationSource(it)) }
        val out = File(workDir, "classes").apply { mkdirs() }
        val log = ByteArrayOutputStream()
        val exit = K2JVMCompiler().exec(
            PrintStream(log), *src.listFiles()!!.map { it.path }.toTypedArray(),
            "-d", out.path, "-classpath", System.getProperty("java.class.path"),
            "-no-stdlib", "-no-reflect", "-jvm-target", "21",
        )
        assertEquals(ExitCode.OK, exit, "compilation failed:\n$log")
        return out
    }

    private fun jar(classes: File, target: File): File {
        JarOutputStream(target.outputStream()).use { jar ->
            classes.walkTopDown().filter { it.isFile }.forEach { f ->
                jar.putNextEntry(JarEntry(f.relativeTo(classes).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(jar) }
                jar.closeEntry()
            }
            // Bulk, like a fat jar: plenty of unrelated entries the old code walked per migration.
            repeat(2000) { n -> jar.putNextEntry(JarEntry("filler/C$n.class")); jar.write(byteArrayOf(0)); jar.closeEntry() }
        }
        return target
    }

    private fun checksumsFrom(location: File): List<String> =
        URLClassLoader(arrayOf(location.toURI().toURL()), javaClass.classLoader).use { loader ->
            (1..migrationCount).map { i ->
                (loader.loadClass("gh194.V$i").getField("INSTANCE").get(null) as KandraMigration).checksum()
            }
        }

    @Test
    fun `checksums of many migrations in one jar scan the jar once and match the directory build`(@TempDir tmp: Path) {
        val classes = compileMigrations(tmp.toFile())
        val jarFile = jar(classes, File(tmp.toFile(), "app.jar"))

        val before = JarClassIndex.scans.get()
        val fromJar = checksumsFrom(jarFile)
        assertEquals(1, JarClassIndex.scans.get() - before, "the jar must be indexed once for all $migrationCount migrations")

        // Same bytes hashed whether the migrations come from a jar or a classes directory, so the
        // index changes no stored checksum. (Each anonymous Comparator is a sibling class file.)
        assertEquals(checksumsFrom(classes), fromJar)
        assertTrue(fromJar.toSet().size == migrationCount)
    }

    /** A [CqlSession] that forwards to [delegate] and records the CQL text of every execute/prepare. */
    private fun recording(delegate: CqlSession, log: MutableList<String>): CqlSession =
        Proxy.newProxyInstance(CqlSession::class.java.classLoader, arrayOf(CqlSession::class.java), InvocationHandler { _, method, args ->
            when (val first = args?.firstOrNull()) {
                is String -> log += "${method.name}: $first"
                is SimpleStatement -> log += "${method.name}: ${first.query}"
                is BoundStatement -> log += "${method.name}: ${first.preparedStatement.query}"
            }
            try { method.invoke(delegate, *(args ?: emptyArray())) } catch (e: InvocationTargetException) { throw e.targetException }
        }) as CqlSession

    @Test
    fun `a runner against an already-bootstrapped keyspace issues no DDL and no LWT`() {
        val keyspace = KandraTestBackend.keyspaceName("gh194")
        KandraTestBackend.createKeyspace(keyspace)
        try {
            KandraTestBackend.newSession(keyspace).use { session ->
                KandraMigrationRunner(session) // first construction bootstraps

                val log = Collections.synchronizedList(mutableListOf<String>())
                KandraMigrationRunner(recording(session, log)).run()

                assertTrue(log.none { "CREATE TABLE" in it || "ALTER TABLE" in it }, "no DDL expected, got: $log")
                assertTrue(log.none { "IF NOT EXISTS" in it || " IF " in it }, "no LWT expected, got: $log")
            }
        } finally {
            KandraTestBackend.dropKeyspace(keyspace)
        }
    }
}
