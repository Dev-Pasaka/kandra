package io.kandra.migrate

import com.datastax.oss.driver.api.core.CqlSession
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kandra.core.exception.KandraMigrationException
import java.time.Duration
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
 *
 * ### Crash safety (GH-26)
 *
 * Each row in `kandra_migrations` carries a [MigrationRowStatus]. A version is written as
 * [MigrationRowStatus.CLAIMED] *before* [KandraMigration.up] runs (via an `IF NOT EXISTS` LWT,
 * so two instances racing on the same never-before-seen version can't both run it), and is only
 * flipped to [MigrationRowStatus.APPLIED] after `up()` returns successfully. Only `APPLIED` rows
 * (and legacy rows with no status at all, treated as applied for backward compatibility) count
 * as "done" -- a `CLAIMED` row proves nothing except that *someone* started the migration.
 *
 * `run()` never guesses about an unresolved `CLAIMED` row: there is no lease or heartbeat here,
 * so a `CLAIMED` row belonging to a live, still-running instance is indistinguishable from one
 * left behind by a process that crashed (OOM, `SIGKILL`, an uncaught `Error`, a Kubernetes
 * eviction) between claiming a version and finishing it. Instead:
 * - A **recently** claimed row (within [staleClaimThreshold]) logs a `WARN` and halts the rest
 *   of this `run()` call -- it does not skip ahead to later migrations, which may depend on DDL
 *   the claimant hasn't finished writing yet.
 * - A row claimed **longer ago** than [staleClaimThreshold] throws [KandraMigrationException],
 *   telling the operator to inspect `kandra_migrations` and resolve it manually.
 *
 * @param staleClaimThreshold how long a [MigrationRowStatus.CLAIMED] row is given the benefit of
 *   the doubt (treated as possibly still in progress elsewhere) before `run()` refuses to
 *   proceed and throws instead. Defaults to 10 minutes.
 */
class KandraMigrationRunner(
    private val session: CqlSession,
    private val staleClaimThreshold: Duration = Duration.ofMinutes(10)
) {

    init {
        session.execute("""
            CREATE TABLE IF NOT EXISTS kandra_migrations (
                version     INT,
                name        TEXT,
                status      TEXT,
                claimed_at  TIMESTAMP,
                applied_at  TIMESTAMP,
                checksum    TEXT,
                PRIMARY KEY (version)
            )
        """.trimIndent())
        migrateLegacySchema()
    }

    /**
     * A `kandra_migrations` table created by a Kandra version prior to GH-26 has no `status`/
     * `claimed_at` columns -- `CREATE TABLE IF NOT EXISTS` above is a no-op against it, so add
     * the two new columns here if they're missing. Existing rows in an upgraded table read back
     * with `status = NULL`, which [history] and [claim] both treat as legacy-applied.
     */
    private fun migrateLegacySchema() {
        val existingColumns = session.execute("SELECT * FROM kandra_migrations LIMIT 1")
            .columnDefinitions
            .map { it.name.toString() }
            .toSet()
        if ("status" !in existingColumns) {
            session.execute("ALTER TABLE kandra_migrations ADD status TEXT")
        }
        if ("claimed_at" !in existingColumns) {
            session.execute("ALTER TABLE kandra_migrations ADD claimed_at TIMESTAMP")
        }
    }

    fun run(vararg migrations: KandraMigration) {
        val applied = loadApplied()
        for (migration in migrations.sortedBy { it.version }) {
            val existing = applied[migration.version]
            if (existing != null) {
                if (existing.status == MigrationRowStatus.CLAIMED) {
                    // Halts (via WARN) or throws -- either way, we never fall through to treat
                    // this version (or anything after it) as safely skippable.
                    handleUnresolvedClaim(existing, migration)
                    return
                }

                if (existing.checksum != migration.checksum()) {
                    throw KandraMigrationException(
                        "Migration v${migration.version} ('${migration.name}') checksum mismatch — " +
                        "the migration was modified after being applied. " +
                        "Expected: ${existing.checksum}, got: ${migration.checksum()}. " +
                        "Never modify a migration after it has been applied."
                    )
                }
                logger.debug { "Migration v${migration.version} ('${migration.name}') already applied — skipping." }
                continue
            }

            // Claim the version via LWT before running it, so two runner instances racing
            // against the same keyspace can't both execute the same migration concurrently.
            val lostRace = claim(migration)
            if (lostRace != null) {
                // Another instance claimed this version between our snapshot read and now.
                handleUnresolvedClaim(lostRace, migration)
                return
            }

            logger.info { "Applying migration v${migration.version}: ${migration.name}" }
            try {
                migration.up(session)
            } catch (e: Exception) {
                // A synchronous, in-process failure -- we know for certain nothing else is
                // running this migration, so it's safe to release the claim for a later retry.
                // (An Error, e.g. from a crashed/killed process, is NOT caught here on purpose --
                // that's exactly the case handleUnresolvedClaim exists to surface loudly instead
                // of silently trusting or silently retrying.)
                session.execute(
                    session.prepare("DELETE FROM kandra_migrations WHERE version = ?").bind(migration.version)
                )
                throw KandraMigrationException("Migration v${migration.version} ('${migration.name}') failed: ${e.message}", e)
            }
            markApplied(migration)
            logger.info { "Migration v${migration.version} applied successfully." }
        }
    }

    /**
     * A [MigrationRowStatus.CLAIMED]-but-not-[MigrationRowStatus.APPLIED] row means we cannot
     * prove the migration finished. It may be genuinely in progress on another instance right
     * now, or it may be what's left of a process that crashed between claiming the version and
     * completing it. Without a lease/heartbeat mechanism these two cases are indistinguishable,
     * so this never guesses:
     * - Within [staleClaimThreshold]: log a `WARN` and return, halting the rest of this `run()`
     *   call so we don't run later migrations against a schema this one may not have finished.
     * - Past [staleClaimThreshold]: throw [KandraMigrationException] with actionable guidance.
     */
    private fun handleUnresolvedClaim(row: MigrationHistory, migration: KandraMigration) {
        val claimedAt = row.claimedAt ?: row.appliedAt
        val age = Duration.between(claimedAt, Instant.now())
        // Only for display in the log/exception text below -- truncates to whole seconds, so it
        // must never be used for the actual staleness comparison (a sub-second age would round
        // down to 0 and compare equal to a Duration.ZERO threshold, silently forgiving anything).
        val ageSeconds = age.seconds
        val description = "Migration v${migration.version} ('${migration.name}') is marked CLAIMED in " +
            "kandra_migrations but not yet APPLIED (claimed at $claimedAt, ${ageSeconds}s ago). This means " +
            "either another instance is actively applying it right now, or a previous instance crashed " +
            "(process kill, OOM, uncaught Error) after claiming it but before finishing. Kandra has no " +
            "lease/heartbeat mechanism to tell these two cases apart, so it refuses to guess."

        // Compare the Duration objects directly, not truncated .seconds Longs -- Duration is
        // Comparable<Duration>, so this correctly treats any non-zero age (even sub-second) as
        // exceeding a Duration.ZERO threshold, while still behaving correctly for the real
        // multi-minute default case.
        if (age > staleClaimThreshold) {
            throw KandraMigrationException(
                "$description This exceeds the staleness threshold of ${staleClaimThreshold.seconds}s. " +
                "Inspect the kandra_migrations table for version ${migration.version} and resolve it " +
                "manually: confirm whether this migration's DDL actually completed, then either delete its " +
                "row to allow a safe retry on the next run(), or update its status to 'APPLIED' if it did " +
                "finish."
            )
        }

        logger.warn {
            "$description Still within the staleness threshold of ${staleClaimThreshold.seconds}s, so this " +
            "is presumed to be a live in-progress run elsewhere for now -- halting this run() call before " +
            "applying any later migration rather than risk running ahead of it. Call run() again later; " +
            "if this persists past the staleness threshold it will be surfaced as an error instead."
        }
    }

    /** Returns the full history of applied migrations. */
    fun history(): List<MigrationHistory> {
        return session.execute("SELECT version, name, status, claimed_at, applied_at, checksum FROM kandra_migrations")
            .all()
            .map { row ->
                MigrationHistory(
                    version = row.getInt("version"),
                    name = row.getString("name") ?: "",
                    appliedAt = row.getInstant("applied_at") ?: Instant.EPOCH,
                    checksum = row.getString("checksum") ?: "",
                    status = row.getString("status")?.let { runCatching { MigrationRowStatus.valueOf(it) }.getOrNull() }
                        ?: MigrationRowStatus.APPLIED,
                    claimedAt = row.getInstant("claimed_at")
                )
            }
            .sortedBy { it.version }
    }

    private fun loadApplied(): Map<Int, MigrationHistory> =
        history().associateBy { it.version }

    /**
     * Claims a migration version via LWT, writing it as [MigrationRowStatus.CLAIMED].
     *
     * Returns `null` on success. Returns the pre-existing row if another instance already
     * claimed (or applied) this version first -- Cassandra/Scylla's `IF NOT EXISTS` LWT response
     * includes the current values of that row on failure, so no extra read is needed.
     */
    private fun claim(migration: KandraMigration): MigrationHistory? {
        val prepared = session.prepare(
            "INSERT INTO kandra_migrations (version, name, status, claimed_at, checksum) " +
            "VALUES (?, ?, ?, ?, ?) IF NOT EXISTS"
        )
        val rs = session.execute(
            prepared.bind(
                migration.version,
                migration.name,
                MigrationRowStatus.CLAIMED.name,
                Instant.now(),
                migration.checksum()
            )
        )
        if (rs.wasApplied()) return null

        val row = rs.one() ?: return null
        return MigrationHistory(
            version = row.getInt("version"),
            name = row.getString("name") ?: migration.name,
            appliedAt = row.getInstant("applied_at") ?: Instant.EPOCH,
            checksum = row.getString("checksum") ?: "",
            status = row.getString("status")?.let { runCatching { MigrationRowStatus.valueOf(it) }.getOrNull() }
                ?: MigrationRowStatus.APPLIED,
            claimedAt = row.getInstant("claimed_at")
        )
    }

    /** Marks a claimed migration as confirmed complete. Only called after `up()` returns successfully. */
    private fun markApplied(migration: KandraMigration) {
        session.execute(
            session.prepare("UPDATE kandra_migrations SET status = ?, applied_at = ? WHERE version = ?")
                .bind(MigrationRowStatus.APPLIED.name, Instant.now(), migration.version)
        )
    }
}
