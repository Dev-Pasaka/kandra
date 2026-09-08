package io.kandra.migrate

import com.datastax.oss.driver.api.core.CqlSession

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
     * Hashes [version]/[name]/qualified class name plus a *normalized* copy of the migration
     * class's own compiled bytecode — so a change to the body of [up] (or any anonymous/lambda
     * class it captures as a top-level member) changes the checksum, while unrelated
     * recompilation of the rest of the project does not.
     *
     * The bytecode is normalized by [BytecodeNormalizer] before hashing: debug/line-number
     * attributes and the classfile's target-version field are stripped first, so a JDK/Kotlin
     * compiler patch bump, a changed `-jvmTarget`, or a toggled debug-info compiler flag no
     * longer flips this checksum for a migration whose source didn't change (GH #63 / ISS-062).
     * It remains an approximation — see [BytecodeNormalizer]'s KDoc and the class-level note
     * above for what it still can't see through, and the documented operator recovery path for
     * when a mismatch is a false positive anyway.
     */
    internal fun checksum(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update("${version}:${name}:${this::class.qualifiedName}".toByteArray())
        val resourceName = "/" + this::class.java.name.replace('.', '/') + ".class"
        this::class.java.getResourceAsStream(resourceName)?.use { stream ->
            digest.update(BytecodeNormalizer.normalize(stream.readBytes()))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
