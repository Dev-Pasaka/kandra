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

    /** Stable checksum of this migration — computed from [version] and [name] for simplicity. */
    internal fun checksum(): String {
        val body = "${version}:${name}:${this::class.qualifiedName}"
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(body.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
