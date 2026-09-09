package io.kandra.migrate

import com.datastax.oss.driver.api.core.CqlSession
import java.io.File
import java.util.jar.JarFile

/**
 * Base class for a single versioned CQL migration.
 *
 * Define migrations as Kotlin `object` subclasses and register them with [KandraMigrationRunner.run].
 * Migrations are applied in ascending [version] order and skipped if already applied.
 *
 * **Never modify a migration after it has been applied to any environment.**
 * Kandra validates checksums on startup and throws [io.kandra.core.exception.KandraMigrationException]
 * if a previously-applied migration's body has changed.
 *
 * ### If a checksum mismatch is a false positive (GH #63 / ISS-062)
 *
 * [checksum] normalizes away the most common sources of toolchain noise (see its KDoc), but it
 * is an approximation of "did the behavior change", not a semantic diff -- a large enough
 * compiler upgrade can still, rarely, flip a checksum for a migration whose source is byte-for-
 * byte unchanged. If you've manually confirmed (by diffing source, not just re-reading it) that
 * a reported mismatch is exactly this kind of false positive, the documented recovery path is to
 * acknowledge it explicitly rather than edit the migration or its row by hand-guessing: inspect
 * [KandraMigrationRunner.history] for the stored checksum, confirm the only difference is
 * toolchain noise, and update that one row's `checksum` column to the new value it reports
 * (`UPDATE kandra_migrations SET checksum = ? WHERE version = ?`). Never do this without
 * confirming the migration's actual behavior is unchanged.
 */
abstract class KandraMigration(
    val version: Int,
    val name: String
) {
    abstract fun up(session: CqlSession)

    /**
     * Checksum of this migration, used to detect edits to an already-applied migration.
     *
     * Hashes [version]/[name]/qualified class name plus a *normalized* copy of every compiled
     * `.class` file belonging to this migration -- its own top-level file, and every sibling
     * synthetic file Kotlin compiled alongside it (see [compiledClassFiles]) -- so a change to
     * the body of [up], or any lambda/anonymous class it creates anywhere in that call tree,
     * changes the checksum, while unrelated recompilation of the rest of the project does not.
     *
     * Each file's bytecode is normalized by [BytecodeNormalizer] before hashing: debug/line-
     * number attributes and the classfile's target-version field are stripped first, so a
     * JDK/Kotlin compiler patch bump, a changed `-jvmTarget`, or a toggled debug-info compiler
     * flag no longer flips this checksum for a migration whose source didn't change (GH #63 /
     * ISS-062). It remains an approximation — see [BytecodeNormalizer]'s KDoc, [compiledClassFiles]'s
     * KDoc, and the class-level note above for what it still can't see through, and the
     * documented operator recovery path for when a mismatch is a false positive anyway.
     */
    internal fun checksum(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update("${version}:${name}:${this::class.qualifiedName}".toByteArray())
        for (bytes in compiledClassFiles()) {
            digest.update(BytecodeNormalizer.normalize(bytes))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * This migration's own compiled `.class` bytes, plus every sibling synthetic class file
     * Kotlin compiled alongside it -- e.g. `V1_CreateUsers$up$1.class` for an anonymous object
     * expression (`object : Comparator<T> { ... }`), a local/nested class, or any other
     * construct inside [up] that Kotlin can't reduce to an `invokedynamic` call site in the
     * outer method itself (GH #102 / ISS-089). (A plain SAM-literal or `{ }` lambda -- e.g.
     * `Runnable { ... }` -- no longer produces one of these on Kotlin 2.x: such lambdas compile
     * via `invokedynamic`/`LambdaMetafactory` by default, with the lambda body itself living as
     * a synthetic method *inside* the outer class, so editing one of those already changes the
     * outer class's own bytes and was never actually missed. The gap this closes is everything
     * still shaped like a genuine separate class file.) Those siblings live in their own
     * top-level `.class` file that the outer class's bytecode references only by a
     * `new V1_CreateUsers$up$1()` call site, never by content -- editing only such a class's body
     * leaves the outer class byte-identical, so hashing just [this]'s own file (the pre-fix
     * behavior) missed it entirely.
     *
     * Resolved from this class's own `ProtectionDomain.codeSource`: a plain classes directory
     * (the normal shape of a Gradle/Maven test run, or an exploded/dev deployment) and a jar
     * (the normal shape of a packaged app) are both handled, matching every `.class` resource
     * whose path -- with the `.class` suffix stripped -- either *is* this class's own binary name
     * or *starts with* it followed by `$` (so `V1_CreateUsers$up$1` matches but an unrelated
     * `V1_CreateUsersLegacy` does not). Results are sorted before hashing so the checksum doesn't
     * depend on directory-listing or jar-entry order.
     *
     * If the code source can't be resolved this way (an unusual classloading setup this method
     * doesn't recognize -- not expected for a normal Gradle/Maven/packaged-jar deployment), this
     * falls back to just this class's own file, the same as the pre-fix behavior, rather than
     * throwing out of [checksum]: see [BytecodeNormalizer]'s KDoc for the same "degrade
     * gracefully instead of crashing" philosophy applied to a different gap in this same method.
     */
    private fun compiledClassFiles(): List<ByteArray> {
        val clazz = this::class.java
        val binaryName = clazz.name
        val packagePath = binaryName.substringBeforeLast('.', missingDelimiterValue = "").replace('.', '/')
        val simpleBinaryName = binaryName.substringAfterLast('.')
        val ownResourcePath = if (packagePath.isEmpty()) simpleBinaryName else "$packagePath/$simpleBinaryName"

        val resourcePaths = runCatching { siblingResourcePaths(clazz, packagePath, ownResourcePath) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: setOf(ownResourcePath) // fallback: just this class's own file, as before this fix

        return resourcePaths.sorted().mapNotNull { path ->
            clazz.getResourceAsStream("/$path.class")?.use { it.readBytes() }
        }
    }

    /**
     * Every `.class` resource path (relative to the classpath root, no `.class` suffix, `/`-
     * separated) under [clazz]'s code source that is either [ownResourcePath] itself or one of
     * its synthetic siblings (`$ownResourcePath$...`). Empty if [clazz]'s code source can't be
     * resolved as a plain directory or jar -- [compiledClassFiles] treats that as "fall back to
     * just the migration's own file", not an error.
     */
    private fun siblingResourcePaths(clazz: Class<*>, packagePath: String, ownResourcePath: String): Set<String> {
        val location = clazz.protectionDomain?.codeSource?.location ?: return emptySet()
        val root = runCatching { File(location.toURI()) }.getOrNull() ?: return emptySet()

        fun matches(resourcePath: String) =
            resourcePath == ownResourcePath || resourcePath.startsWith("$ownResourcePath$")

        return when {
            root.isDirectory -> {
                val packageDir = if (packagePath.isEmpty()) root else File(root, packagePath)
                (packageDir.listFiles { file -> file.isFile && file.name.endsWith(".class") } ?: emptyArray())
                    .map { file -> (if (packagePath.isEmpty()) file.name else "$packagePath/${file.name}").removeSuffix(".class") }
                    .filterTo(mutableSetOf(), ::matches)
            }
            root.isFile && root.name.endsWith(".jar") -> {
                JarFile(root).use { jar ->
                    jar.entries().asSequence()
                        .map { entry -> entry.name }
                        .filter { entryName -> entryName.endsWith(".class") }
                        .map { entryName -> entryName.removeSuffix(".class") }
                        .filter(::matches)
                        .toSet()
                }
            }
            else -> emptySet()
        }
    }
}
