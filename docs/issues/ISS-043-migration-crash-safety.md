# ISS-043: Interrupted or racing migration runs could leave a migration marked applied before it completes

**Status:** Fixed

## Category

Bug (highest blast-radius module: irreversible schema-state corruption) + test coverage.

## Problem

Filed as GH #26, together with the `kandra-migrate` half of GH #37 (zero test coverage in this
module). `KandraMigrationRunner.run()` wrote the "applied" row to `kandra_migrations` via LWT
*before* the migration body executed, and treated that row's mere existence as proof the migration
fully completed. Two consequences:

1. **No crash recovery.** A process kill (OOM, SIGKILL, `Error`, k8s liveness-probe eviction) between
   the claim succeeding and `up()` returning left the row permanently in place with no cleanup path —
   every subsequent boot saw the version present, logged "already applied — skipping", and the
   application started against a schema whose migration DDL may never have run, or ran only partially.
2. **No ordering guarantee across instances.** A concurrently-booting second instance would see the
   same in-flight row and treat it as done, proceeding straight to later migrations that might depend
   on the first one's DDL.

`kandra-migrate` also had zero test files, meaning this exact failure mode — the reason a
"checksum-validated, LWT-locked" migration runner exists — had nothing catching it.

## Fix

Split `kandra_migrations` rows into a two-state lifecycle via a new `status` column
(`MigrationRowStatus`: `CLAIMED`, `APPLIED`):

1. `claim()` inserts the row with `status = CLAIMED` via the existing `IF NOT EXISTS` LWT, before
   `up()` runs, and stamps a new `claimed_at` timestamp.
2. Only after `migration.up(session)` returns successfully does `markApplied()` flip the row to
   `APPLIED` and set `applied_at` (previously stamped at claim time — now it means real completion
   time).
3. `run()`/`loadApplied()` only ever treat `APPLIED` rows (or legacy rows with `status = NULL`, see
   below) as done and eligible for the checksum-match/skip path.
4. Any unresolved `CLAIMED` row — from the initial snapshot or from losing a concurrent claim race
   mid-loop — routes into `handleUnresolvedClaim()`, which compares `Duration.between(claimedAt, now())`
   against a configurable `staleClaimThreshold` (default 10 minutes) **as `Duration` objects directly**
   (not truncated to whole seconds — an earlier version of this fix compared `.seconds` Longs, which
   silently broke the zero-threshold test case since sub-second ages truncate to 0):
   - Within the threshold: logs `WARN` and halts the rest of that `run()` call — presumed to possibly
     be a live in-progress run elsewhere; does not skip ahead to later migrations.
   - Past the threshold: throws `KandraMigrationException` with actionable guidance to inspect
     `kandra_migrations` and resolve manually (delete the row for a safe retry, or flip to `APPLIED` if
     the DDL did finish).
5. A synchronous `Exception` from `up()` still deletes the `CLAIMED` row for a clean retry (unchanged
   behavior — in that case we know for certain nothing else is running it). An `Error` (simulating a
   crash) is deliberately not caught by that block, leaving the row `CLAIMED` — exactly the case the
   new handling exists to catch on a subsequent run.

**Backward compatibility:** a pre-fix `kandra_migrations` table has no `status`/`claimed_at` columns.
A new idempotent schema check inspects the table's actual columns and `ALTER`s in the two missing ones
if absent. Legacy rows read back with `status = NULL`, treated as `APPLIED` (consistent with the old
code's "existence means applied" semantics for rows that predate this change).

Added `kandra-migrate`'s first test suite (`KandraMigrationRunnerTest`, 8 tests, Testcontainers-backed
since `FakeKandraSession` can't be used here — every path goes through `session.prepare().bind()`),
covering: apply+record `APPLIED`, skip-on-second-call, ascending-order application regardless of
vararg order, checksum-mismatch throw, a fresh `CLAIMED` row halting without throwing, a stale
`CLAIMED` row throwing, the `Error`-crash regression (row ends `CLAIMED`, a second `run()` throws
`KandraMigrationException`), and the legacy-`NULL`-status backward-compat case.

## Files

- `kandra-migrate/src/main/kotlin/io/kandra/migrate/KandraMigrationRunner.kt`
- `kandra-migrate/src/main/kotlin/io/kandra/migrate/MigrationHistory.kt`
- `kandra-migrate/src/main/kotlin/io/kandra/migrate/MigrationRowStatus.kt` (new)
- `kandra-migrate/build.gradle.kts`
- `kandra-migrate/src/test/kotlin/io/kandra/migrate/KandraMigrationRunnerTest.kt` (new)
