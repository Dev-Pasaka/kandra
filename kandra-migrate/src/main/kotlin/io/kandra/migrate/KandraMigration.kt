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
 */
abstract class KandraMigration(
    val version: Int,
    val name: String
) {
    abstract fun up(session: CqlSession)

    /**
     * Checksum of this migration, used to detect edits to an already-applied migration.
     *
     * Hashes [version]/[name]/qualified class name plus the migration class's own compiled
     * bytecode — so a change to the body of [up] (or any anonymous/lambda class it captures
     * as a top-level member) changes the checksum, while unrelated recompilation of the rest
     * of the project does not.
     */
    internal fun checksum(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update("${version}:${name}:${this::class.qualifiedName}".toByteArray())
        val resourceName = "/" + this::class.java.name.replace('.', '/') + ".class"
        this::class.java.getResourceAsStream(resourceName)?.use { digest.update(it.readBytes()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
