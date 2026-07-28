package io.kandra.migrate

/**
 * The lifecycle state of a single row in the `kandra_migrations` bookkeeping table.
 *
 * Introduced to fix a crash-safety gap (GH-26): the runner used to write a migration's row
 * *before* calling [KandraMigration.up], and [KandraMigrationRunner.loadApplied] treated the
 * mere existence of a row as proof the migration had fully completed. A process killed between
 * the claim and the migration finishing left a row that looked identical to a real completion.
 *
 * - [CLAIMED] — a runner has reserved this version (via a `IF NOT EXISTS` LWT) and is either
 *   currently running its `up()`, or crashed before finishing it. These two situations are
 *   indistinguishable without a lease/heartbeat mechanism, which this module does not implement.
 * - [APPLIED] — `up()` returned successfully and the row was updated to reflect that. Only rows
 *   in this state (or legacy rows with no `status` at all — see below) are treated as "done".
 *
 * Backward compatibility: rows written by a Kandra version older than this fix have no `status`
 * column value (`NULL`). [KandraMigrationRunner] treats a `NULL` status as legacy-applied — those
 * rows were only ever written *after* `up()` completed under the old code, so trusting them as
 * [APPLIED] is safe.
 */
enum class MigrationRowStatus {
    CLAIMED,
    APPLIED
}
