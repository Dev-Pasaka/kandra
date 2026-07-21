# ISS-018: No locking in `KandraMigrationRunner` — concurrent runners could double-apply

**Status:** Fixed — pending live-cluster verification (Testcontainers, see [ISS-013](ISS-013-no-integration-tests.md))

## Problem

Found during a pre-real-database-testing audit (2026-07-21). `KandraMigrationRunner.run()` read
`kandra_migrations`, then unconditionally called `migration.up(session)` followed by a plain
(non-conditional) `INSERT` to record it. Two app instances starting simultaneously against the same
keyspace could both see a migration as "not applied" and both execute `up()` concurrently — no
`IF NOT EXISTS` LWT, no lock table. Harmless for idempotent DDL like `ALTER TABLE ADD` (worst case,
errors twice), but risky for any migration containing data-mutating CQL.

## Fix

Reworked the runner into a claim-first pattern: before running a migration, it now issues
`INSERT INTO kandra_migrations (...) VALUES (...) IF NOT EXISTS` and checks `wasApplied()`. Only the
runner instance that wins the LWT proceeds to call `up()`. If `up()` throws, the runner deletes the
claim row so a subsequent run can retry the migration, then rethrows as `KandraMigrationException`
with the original cause attached. A runner that loses the race logs and skips, since another
instance is (or already has) applying that version.

**File:** `kandra-migrate/.../KandraMigrationRunner.kt`.
