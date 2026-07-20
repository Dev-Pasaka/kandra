package io.kandra.migrate

import com.datastax.oss.driver.api.core.CqlSession
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kandra.core.exception.KandraMigrationException
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Applies versioned [KandraMigration]s to a ScyllaDB keyspace.
 *
 * Maintains a `kandra_migrations` table to track which migrations have been applied.
 * Migrations are executed in ascending version order. Already-applied migrations are skipped.
 * If a previously-applied migration's checksum no longer matches, [KandraMigrationException] is thrown.
 *
 * ```kotlin
 * fun Application.configureMigrations() {
 *     val runner = KandraMigrationRunner(kandraSession)
 *     runner.run(V1_CreateUsers, V2_AddPhoneToUsers)
 * }
 * // Call BEFORE install(Kandra) with schemaMode = NONE for migration-managed schemas
 * ```
 */
class KandraMigrationRunner(private val session: CqlSession) {

    init {
        session.execute("""
            CREATE TABLE IF NOT EXISTS kandra_migrations (
                version     INT,
                name        TEXT,
                applied_at  TIMESTAMP,
                checksum    TEXT,
                PRIMARY KEY (version)
            )
        """.trimIndent())
    }

    fun run(vararg migrations: KandraMigration) {
        val applied = loadApplied()
        migrations.sortedBy { it.version }.forEach { migration ->
            val existing = applied[migration.version]
            if (existing != null) {
                if (existing.checksum != migration.checksum()) {
                    throw KandraMigrationException(
                        "Migration v${migration.version} ('${migration.name}') checksum mismatch — " +
                        "the migration was modified after being applied. " +
                        "Expected: ${existing.checksum}, got: ${migration.checksum()}. " +
                        "Never modify a migration after it has been applied."
                    )
                }
                logger.debug { "Migration v${migration.version} ('${migration.name}') already applied — skipping." }
                return@forEach
            }

            logger.info { "Applying migration v${migration.version}: ${migration.name}" }
            migration.up(session)
            recordApplied(migration)
            logger.info { "Migration v${migration.version} applied successfully." }
        }
    }

    /** Returns the full history of applied migrations. */
    fun history(): List<MigrationHistory> {
        return session.execute("SELECT version, name, applied_at, checksum FROM kandra_migrations")
            .all()
            .map { row ->
                MigrationHistory(
                    version = row.getInt("version"),
                    name = row.getString("name") ?: "",
                    appliedAt = row.getInstant("applied_at") ?: Instant.EPOCH,
                    checksum = row.getString("checksum") ?: ""
                )
            }
            .sortedBy { it.version }
    }

    private fun loadApplied(): Map<Int, MigrationHistory> =
        history().associateBy { it.version }

    private fun recordApplied(migration: KandraMigration) {
        val prepared = session.prepare(
            "INSERT INTO kandra_migrations (version, name, applied_at, checksum) VALUES (?, ?, ?, ?)"
        )
        session.execute(
            prepared.bind(migration.version, migration.name, Instant.now(), migration.checksum())
        )
    }
}
