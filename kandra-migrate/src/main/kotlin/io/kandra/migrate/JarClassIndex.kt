package io.kandra.migrate

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarFile

/**
 * Process-wide index of the `.class` entries in each jar that holds migrations, grouped by
 * top-level class (the resource path up to its first `$`). [KandraMigration.checksum] used to open
 * the jar and walk every entry once per migration, on every startup: N migrations in a fat jar
 * with tens of thousands of entries meant N full scans before any CQL ran (GH #194).
 *
 * Keyed by path, size and modification time, so a jar replaced on disk while the JVM runs is
 * indexed again rather than served stale.
 */
internal object JarClassIndex {

    private data class JarKey(val path: String, val length: Long, val lastModified: Long)

    private val indexes = ConcurrentHashMap<JarKey, Map<String, List<String>>>()

    /** Number of jar scans performed so far; lets tests assert the scan happens once. */
    internal val scans = AtomicInteger()

    /** `.class` resource paths (no suffix) in [jar] whose top-level class is [topLevelPath]. */
    fun classesUnder(jar: File, topLevelPath: String): List<String> =
        indexes.computeIfAbsent(JarKey(jar.absolutePath, jar.length(), jar.lastModified())) { index(jar) }[topLevelPath]
            .orEmpty()

    private fun index(jar: File): Map<String, List<String>> {
        scans.incrementAndGet()
        return JarFile(jar).use { file ->
            file.entries().asSequence()
                .map { it.name }
                .filter { it.endsWith(".class") }
                .map { it.removeSuffix(".class") }
                .groupBy { it.substringBefore('$') }
        }
    }
}
