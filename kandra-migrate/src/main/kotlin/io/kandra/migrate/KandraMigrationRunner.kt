package io.kandra.migrate

import com.datastax.oss.driver.api.core.CqlSession
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kandra.core.exception.KandraMigrationException
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Lock key guarding [KandraMigrationRunner]'s own `kandra_migrations` bookkeeping-table bootstrap
 * (the `CREATE TABLE IF NOT EXISTS` + the legacy-column `ALTER TABLE`s run from `init`). See
 * [claimAndRunDdlBootstrap]'s KDoc, further down this file (GH #79/ISS-071).
 */
private const val DDL_BOOTSTRAP_MIGRATIONS_TABLE_LOCK = "kandra-migrations-bootstrap"

/**
 * How long the `kandra_migrations` bootstrap claim (see [DDL_BOOTSTRAP_MIGRATIONS_TABLE_LOCK]) is
 * given the benefit of the doubt before a waiting instance reclaims it. Distinct from
 * [KandraMigrationRunner]'s own `staleClaimThreshold` constructor parameter, which governs
 * per-*migration* claims (default 10 minutes, sized for arbitrary user `up()` bodies) -- this one
 * guards a fixed, tiny bootstrap (one `CREATE TABLE IF NOT EXISTS` plus at most two `ALTER TABLE
 * ADD`s), so a much shorter threshold is appropriate. Mirrors `kandra-ktor`'s identical constant
 * for its own schema-DDL bootstrap guard (`Kandra.kt`'s `DDL_CLAIM_STALE_THRESHOLD`) -- kept as a
 * separate copy here since `kandra-migrate` does not depend on `kandra-ktor` (and vice versa).
 */
private val DDL_BOOTSTRAP_STALE_THRESHOLD: Duration = Duration.ofMinutes(2)

/** How long to sleep between polls while waiting for another instance's DDL claim to resolve. */
private const val DDL_BOOTSTRAP_POLL_MS = 200L

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
 * ### Clock source for staleness (GH-64)
 *
 * "How long ago was this claimed?" is deliberately measured entirely by the ScyllaDB/Cassandra
 * cluster's own clock, never by comparing two application instances' wall clocks. [claim] writes
 * `claimed_at` using the CQL `toTimestamp(now())` function -- evaluated by the coordinator that
 * processes the claim, not bound as a JVM-generated [Instant] -- and [handleUnresolvedClaim]
 * reads "now" the same way, via a fresh `SELECT toTimestamp(now())` against the coordinator
 * serving that read. Both sides of the [Duration.between] comparison therefore come from the
 * cluster, not from `Instant.now()` on whichever apps happen to be claiming or checking. This
 * removes the failure mode of an earlier version of this class, where `claimed_at` was an
 * app-generated [Instant] and staleness was `Duration.between(claimedAt, Instant.now())` across
 * two independently-drifting machine clocks: a claiming instance whose clock ran fast (or a
 * checking instance whose clock ran slow) could make a genuinely crashed migration look
 * perpetually fresh, while the reverse skew could make an in-progress migration look falsely
 * stale and abort a deployment. The remaining assumption -- that nodes within one Scylla/
 * Cassandra cluster have reasonably synced clocks with each other -- is far narrower than
 * "every application instance is NTP-synced with every other," and is already an existing
 * operational precondition for correct LWT/Paxos behavior in these systems.
 *
 * @param staleClaimThreshold how long a [MigrationRowStatus.CLAIMED] row is given the benefit of
 *   the doubt (treated as possibly still in progress elsewhere) before `run()` refuses to
 *   proceed and throws instead. Compared against an age measured entirely by the database
 *   cluster's own clock (see "Clock source for staleness" above). Defaults to 10 minutes.
 *
 * ### DDL bootstrap coordination (GH #79/ISS-071)
 *
 * The `CREATE TABLE IF NOT EXISTS kandra_migrations` + legacy-column `ALTER TABLE`s below used to
 * run unconditionally, every time a [KandraMigrationRunner] was constructed -- if several
 * application instances construct one concurrently against a keyspace that doesn't have the table
 * yet (the normal shape of a rolling multi-replica deploy), they raced the same DDL against the
 * cluster. This is now guarded by [claimAndRunDdlBootstrap] the same way `kandra-ktor`'s
 * `SchemaMode.AUTO_CREATE`/`AUTO_MIGRATE` DDL is guarded: one instance wins an `INSERT ... IF NOT
 * EXISTS` LWT claim and runs the bootstrap, the rest poll until it reports done. If the claim
 * holder goes silent for longer than two minutes (measured by the cluster's own clock, not any
 * application instance's), a waiting instance presumes it crashed mid-bootstrap and reclaims it.
 * See [claimAndRunDdlBootstrap]'s own KDoc, further down this file, for the full mechanism -- it's
 * the same design as this class's own per-migration claim above, just applied one level earlier,
 * to the bookkeeping table's own creation.
 */
class KandraMigrationRunner(
    private val session: CqlSession,
    private val staleClaimThreshold: Duration = Duration.ofMinutes(10)
) {

    init {
        claimAndRunDdlBootstrap(session, DDL_BOOTSTRAP_MIGRATIONS_TABLE_LOCK) {
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

                verifyAppliedChecksum(existing, migration)
                continue
            }

            // Claim the version via LWT before running it, so two runner instances racing
            // against the same keyspace can't both execute the same migration concurrently.
            val lostRace = claim(migration)
            if (lostRace != null) {
                // Another instance claimed this version between our snapshot read and now --
                // but "lost the race" doesn't mean "still in progress": the LWT failure just
                // returns whatever row is there now, which may already be APPLIED if the other
                // instance finished (possibly very quickly) before we got here. Only a genuinely
                // unresolved CLAIMED row is a claim to wait on or complain about; an APPLIED row
                // is exactly the pre-existing-row case above, just discovered a moment later than
                // the initial loadApplied() snapshot.
                if (lostRace.status == MigrationRowStatus.APPLIED) {
                    verifyAppliedChecksum(lostRace, migration)
                    continue
                }
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
     * Shared by both places [run] discovers a row already [MigrationRowStatus.APPLIED] for
     * [migration]'s version -- the initial `loadApplied()` snapshot, and a [claim] that lost the
     * race because the other instance had *already finished*, not merely started (GH #101/
     * ISS-088). Either way the meaning is identical: this version is done, so the only thing left
     * to verify is that nobody silently edited the migration since it was applied.
     */
    private fun verifyAppliedChecksum(existing: MigrationHistory, migration: KandraMigration) {
        if (existing.checksum != migration.checksum()) {
            throw KandraMigrationException(
                "Migration v${migration.version} ('${migration.name}') checksum mismatch — " +
                "the migration was modified after being applied. " +
                "Expected: ${existing.checksum}, got: ${migration.checksum()}. " +
                "Never modify a migration after it has been applied."
            )
        }
        logger.debug { "Migration v${migration.version} ('${migration.name}') already applied — skipping." }
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
        // "now" is read from the cluster (see class KDoc, "Clock source for staleness"), not
        // this JVM's Instant.now() -- claimedAt was written the same way by claim(), so this
        // comparison never mixes two different machines' clocks.
        val age = Duration.between(claimedAt, serverNow(migration.version))
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

    /**
     * The current time as seen by the ScyllaDB/Cassandra coordinator serving this query --
     * *not* this JVM's [Instant.now]. Used so that staleness checks in [handleUnresolvedClaim]
     * never depend on the checking application instance's own wall clock (see class KDoc,
     * "Clock source for staleness", GH-64).
     *
     * `version` must name an existing row (true for every caller here -- [handleUnresolvedClaim]
     * is only ever invoked with a row that was just read). Falls back to this JVM's own
     * [Instant.now] only in the practically-unreachable case that the row vanished between that
     * read and this call.
     */
    private fun serverNow(version: Int): Instant {
        val prepared = session.prepare(
            "SELECT toTimestamp(now()) AS server_now FROM kandra_migrations WHERE version = ?"
        )
        val row = session.execute(prepared.bind(version)).one()
        return row?.getInstant("server_now") ?: Instant.now()
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
     * `claimed_at` is assigned via the CQL `toTimestamp(now())` function -- evaluated by the
     * coordinator that processes this INSERT -- rather than bound as a JVM-generated
     * [Instant.now]. See class KDoc, "Clock source for staleness" (GH-64).
     *
     * Returns `null` on success. Returns the pre-existing row if another instance already
     * claimed (or applied) this version first -- Cassandra/Scylla's `IF NOT EXISTS` LWT response
     * includes the current values of that row on failure, so no extra read is needed.
     */
    private fun claim(migration: KandraMigration): MigrationHistory? {
        val prepared = session.prepare(
            "INSERT INTO kandra_migrations (version, name, status, claimed_at, checksum) " +
            "VALUES (?, ?, ?, toTimestamp(now()), ?) IF NOT EXISTS"
        )
        val rs = session.execute(
            prepared.bind(
                migration.version,
                migration.name,
                MigrationRowStatus.CLAIMED.name,
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

/**
 * Runs [action] under a cluster-wide LWT claim keyed by [lockName], so that when several
 * application instances construct a [KandraMigrationRunner] concurrently against the same
 * keyspace -- a rolling deploy of N replicas is the normal topology this guards against (GH
 * #79/ISS-071) -- only one of them actually executes [action] (here, bootstrapping the
 * `kandra_migrations` table itself), while the others detect the claim and wait for it to finish
 * rather than racing the same `CREATE TABLE`/`ALTER TABLE` statements against the cluster
 * concurrently -- a known schema-disagreement risk on Cassandra/Scylla.
 *
 * This is the exact same design as [KandraMigrationRunner.claim]/[KandraMigrationRunner.run]'s own
 * per-migration claim mechanism (GH #26/#64), just applied one level earlier -- to bootstrapping
 * the bookkeeping table those claims live in, via a small dedicated coordination table,
 * `kandra_ddl_locks`, holding one row per [lockName]:
 * - **Claim**: `INSERT ... IF NOT EXISTS` -- exactly one racing instance wins.
 * - **Losers wait**: poll the row every [DDL_BOOTSTRAP_POLL_MS] until it reads `DONE` (the winner
 *   finished -- skip running [action] here at all) or its claim goes stale.
 * - **Staleness**: measured entirely by the cluster's own clock (`toTimestamp(now())`, never this
 *   JVM's `Instant.now()`) against [DDL_BOOTSTRAP_STALE_THRESHOLD] -- the same clock-skew-proof
 *   approach [KandraMigrationRunner] already uses for its own claim staleness (see its class KDoc,
 *   "Clock source for staleness", GH #64). A claim older than the threshold is presumed abandoned
 *   by a crashed claimant (OOM, `SIGKILL`, an uncaught `Error` mid-bootstrap); one waiter reclaims
 *   it via a compare-and-set `UPDATE ... IF claimed_at = ?` (so only one of several simultaneous
 *   waiters wins the reclaim) and runs [action] itself.
 *
 * Unlike [KandraMigrationRunner.run]'s migration claims -- which deliberately halt (or throw)
 * rather than let a caller barrel ahead of an unresolved claim, because later migrations may
 * depend on earlier DDL -- a waiting instance here always converges on running (constructing a
 * runner should not hang indefinitely, and `CREATE TABLE IF NOT EXISTS`/`ALTER TABLE ADD` are
 * idempotent, so there's no ordering hazard in retrying).
 *
 * The one race this cannot remove: creating `kandra_ddl_locks` itself, the first time it doesn't
 * yet exist. That's a single `CREATE TABLE IF NOT EXISTS` for one small, schema-stable table (no
 * column is ever added to it after creation) -- a far narrower and lower-impact race than the one
 * this guards against, which is N instances concurrently running `CREATE TABLE`/`ALTER TABLE`
 * against `kandra_migrations`. `kandra-ktor`'s `Kandra.kt` plugin uses an identical
 * `kandra_ddl_locks` table (same schema, different lock keys) to guard its own
 * `SchemaMode.AUTO_CREATE`/`AUTO_MIGRATE` DDL bootstrap -- the two modules don't depend on each
 * other, so this is a deliberately duplicated, self-contained copy rather than a shared one, but
 * the table shape is identical so both can coexist safely against the same keyspace if a
 * deployment somehow uses both.
 */
internal fun claimAndRunDdlBootstrap(session: CqlSession, lockName: String, action: () -> Unit) {
    ensureDdlLockTable(session)
    val holder = UUID.randomUUID().toString()

    if (tryClaimDdlLock(session, lockName, holder)) {
        logger.info { "Kandra: claimed DDL bootstrap lock '$lockName' -- running schema DDL." }
        runClaimedDdlAction(session, lockName, action)
        return
    }

    logger.info {
        "Kandra: another instance holds the DDL bootstrap lock '$lockName' -- waiting for it to " +
        "finish rather than racing the same DDL concurrently."
    }
    while (true) {
        val row = session.execute(
            session.prepare("SELECT holder, status, claimed_at FROM kandra_ddl_locks WHERE lock_name = ?")
                .bind(lockName)
        ).one()

        if (row == null) {
            // The row vanished between our failed claim and this read (shouldn't normally happen
            // outside of a manual operator intervention) -- treat it like nobody has claimed it.
            if (tryClaimDdlLock(session, lockName, holder)) {
                runClaimedDdlAction(session, lockName, action)
                return
            }
            continue
        }

        if (row.getString("status") == "DONE") {
            logger.info { "Kandra: DDL bootstrap lock '$lockName' was completed by another instance -- skipping DDL here." }
            return
        }

        val claimedAt = row.getInstant("claimed_at") ?: Instant.EPOCH
        val age = Duration.between(claimedAt, ddlLockServerNow(session, lockName))
        if (age > DDL_BOOTSTRAP_STALE_THRESHOLD) {
            logger.warn {
                "Kandra: DDL bootstrap lock '$lockName' has been CLAIMED for ${age.seconds}s, " +
                "exceeding the staleness threshold of ${DDL_BOOTSTRAP_STALE_THRESHOLD.seconds}s -- " +
                "the previous claimant is presumed crashed. Reclaiming and running the DDL here."
            }
            if (reclaimStaleDdlLock(session, lockName, holder, claimedAt)) {
                runClaimedDdlAction(session, lockName, action)
                return
            }
            // Someone else reclaimed (or finished) it between our staleness check and the reclaim
            // attempt -- loop and re-check rather than assume anything about the outcome.
            continue
        }

        Thread.sleep(DDL_BOOTSTRAP_POLL_MS)
    }
}

/** Runs [action] for the instance that just won (or reclaimed) [lockName], releasing the claim on failure so a later attempt can retry, or marking it DONE on success. */
private fun runClaimedDdlAction(session: CqlSession, lockName: String, action: () -> Unit) {
    try {
        action()
    } catch (e: Exception) {
        releaseDdlLock(session, lockName)
        throw e
    }
    markDdlLockDone(session, lockName)
    logger.info { "Kandra: DDL bootstrap lock '$lockName' released (done)." }
}

/**
 * Coordination table backing [claimAndRunDdlBootstrap]. Deliberately minimal and schema-stable
 * (no column is ever added after creation) to keep its own bootstrap -- the one race this
 * mechanism cannot itself guard, see [claimAndRunDdlBootstrap]'s KDoc -- as narrow as possible.
 */
private fun ensureDdlLockTable(session: CqlSession) {
    session.execute(
        """
        CREATE TABLE IF NOT EXISTS kandra_ddl_locks (
            lock_name   TEXT PRIMARY KEY,
            holder      TEXT,
            status      TEXT,
            claimed_at  TIMESTAMP
        )
        """.trimIndent()
    )
}

/** Returns `true` if this call won the claim on [lockName]. */
private fun tryClaimDdlLock(session: CqlSession, lockName: String, holder: String): Boolean {
    val prepared = session.prepare(
        "INSERT INTO kandra_ddl_locks (lock_name, holder, status, claimed_at) " +
        "VALUES (?, ?, 'CLAIMED', toTimestamp(now())) IF NOT EXISTS"
    )
    return session.execute(prepared.bind(lockName, holder)).wasApplied()
}

/**
 * Reclaims a stale claim via compare-and-set on its previously-observed `claimed_at`, so that if
 * several waiters independently decide the same claim is stale, only one of them wins.
 */
private fun reclaimStaleDdlLock(session: CqlSession, lockName: String, holder: String, previousClaimedAt: Instant): Boolean {
    val prepared = session.prepare(
        "UPDATE kandra_ddl_locks SET holder = ?, status = 'CLAIMED', claimed_at = toTimestamp(now()) " +
        "WHERE lock_name = ? IF claimed_at = ?"
    )
    return session.execute(prepared.bind(holder, lockName, previousClaimedAt)).wasApplied()
}

private fun markDdlLockDone(session: CqlSession, lockName: String) {
    session.execute(
        session.prepare("UPDATE kandra_ddl_locks SET status = 'DONE' WHERE lock_name = ?").bind(lockName)
    )
}

/** Releases a claim (deletes its row) so a later attempt can retry cleanly after [action] fails. */
private fun releaseDdlLock(session: CqlSession, lockName: String) {
    session.execute(session.prepare("DELETE FROM kandra_ddl_locks WHERE lock_name = ?").bind(lockName))
}

/**
 * The current time as seen by the ScyllaDB/Cassandra coordinator serving this query -- *not* this
 * JVM's [Instant.now]. See [claimAndRunDdlBootstrap]'s KDoc (GH #64's approach reused here for the
 * same reason: never compare timestamps across independently-clocked app instances).
 */
private fun ddlLockServerNow(session: CqlSession, lockName: String): Instant {
    val row = session.execute(
        session.prepare("SELECT toTimestamp(now()) AS server_now FROM kandra_ddl_locks WHERE lock_name = ?")
            .bind(lockName)
    ).one()
    return row?.getInstant("server_now") ?: Instant.now()
}
