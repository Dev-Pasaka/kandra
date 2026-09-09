# ISS-071: Schema DDL bootstrap has no coordination guard across concurrently-starting instances

**Status:** Fixed

## Problem

Filed as GH #79.

Filed from a critical library-wide review (security/performance/consistency/scalability/developer
experience) done ahead of experimental multi-cluster DC testing.

`SchemaMode.AUTO_CREATE`/`AUTO_MIGRATE` (default: `AUTO_CREATE`) run `CREATE TABLE IF NOT EXISTS`
(and, for `AUTO_MIGRATE`, `ALTER TABLE ADD`) for every registered entity from inside `Kandra.kt`'s
plugin install path, unconditionally, on every application startup. `KandraMigrationRunner`'s own
`init` block does the same for its `kandra_migrations` bookkeeping table (`CREATE TABLE IF NOT
EXISTS` + a legacy-column `ALTER TABLE`).

None of this is guarded by any leader-election or claim mechanism. In a real multi-instance
deployment — which is the normal topology for anything being tested against a multi-DC cluster — a
rolling deploy of N replicas means N instances race to run DDL against the same keyspace at roughly
the same time.

Concurrent DDL from multiple coordinators is a known schema-disagreement risk in Cassandra/
Scylla-family clusters. This is exactly the class of problem `KandraMigrationRunner`'s own
*migration* path already solves correctly via an `IF NOT EXISTS` LWT claim (`ISS-043`/GH #26) — but
that solution doesn't cover the `kandra_migrations` bootstrap table's own `CREATE TABLE`, nor the
`SchemaMode.AUTO_CREATE`/`AUTO_MIGRATE` DDL path at all.

**Impact:** low risk at RF=1 / single-instance dev setups (where this has presumably always been
tested). Real risk the first time this is exercised with several replicas starting concurrently
against a real multi-DC cluster — which is precisely the upcoming test scenario.

## Suggested fix direction

Options, roughly in order of effort:
1. Document loudly (README + `SchemaMode` KDoc) that `AUTO_CREATE`/`AUTO_MIGRATE` should only run
   from a single instance (init container / job) in a multi-replica deployment, and that `NONE` +
   externally-run migrations is the recommended production posture — as a documentation-only
   interim fix.
2. Reuse the LWT-claim pattern from `KandraMigrationRunner` to guard the DDL bootstrap path itself,
   so concurrent instances serialize rather than race.

**Files:** `kandra-ktor/src/main/kotlin/io/kandra/ktor/Kandra.kt`,
`kandra-migrate/src/main/kotlin/io/kandra/migrate/KandraMigrationRunner.kt`.

## Fix

Went with option 2 (reuse the LWT-claim pattern), not the documentation-only interim option — a
real distributed lock is what actually makes `AUTO_CREATE`/`AUTO_MIGRATE` and
`KandraMigrationRunner`'s own bootstrap safe under a genuine rolling multi-replica deploy, which is
exactly the upcoming test scenario this issue was filed ahead of.

Added a small coordination table, `kandra_ddl_locks` (`lock_name TEXT PRIMARY KEY, holder TEXT,
status TEXT, claimed_at TIMESTAMP`), and a `claimAndRunDdlBootstrap(session, lockName, action)`
helper that wraps a block of DDL in a claim:

- **Claim**: `INSERT ... IF NOT EXISTS` -- exactly one racing instance wins per `lockName`.
- **Losers wait**: poll the row every 200ms until it reads `DONE` (the winner finished -- skip
  running the DDL here at all) or the claim goes stale.
- **Staleness**: measured entirely by the cluster's own clock (`toTimestamp(now())`, the same
  clock-skew-proof approach `KandraMigrationRunner` already uses for its per-migration claims, see
  ISS-063/GH #64) against a fixed 2-minute threshold. A claim older than that is presumed abandoned
  by a crashed claimant (OOM, `SIGKILL`, an uncaught `Error` mid-DDL); one waiter reclaims it via a
  compare-and-set `UPDATE ... IF claimed_at = ?` (so only one of several simultaneous waiters wins
  the reclaim) and runs the DDL itself.

Unlike `KandraMigrationRunner`'s own *migration* claims -- which deliberately halt or throw rather
than let a caller run ahead of an unresolved claim, because later migrations may depend on earlier
DDL -- a waiting instance here always converges on running: startup should not hang indefinitely,
and `CREATE TABLE IF NOT EXISTS`/`ALTER TABLE ADD` are idempotent, so there's no ordering hazard in
retrying. The 2-minute threshold (vs. the migration runner's 10-minute default) reflects that: this
guards a fixed, fast DDL pass, not arbitrary user `up()` bodies. It isn't exposed as a Kandra config
option today -- raise it if a deployment has enough registered entities that `AUTO_MIGRATE`'s
column-diff pass genuinely takes longer than that on a slow cluster.

Wired in two places:
- `Kandra.kt`: `SchemaMode.AUTO_CREATE`'s and `SchemaMode.AUTO_MIGRATE`'s entire per-entity DDL loop
  (across every registered entity) now runs under **one** claim, keyed by a fixed lock name
  (`"schema-bootstrap"`) -- a single claim for the whole bootstrap pass, not one per table. This is
  simpler to reason about, avoids partial-completion states where some tables are guarded and
  others aren't, and matches the deployment shape this guards against (N replicas each wanting to
  run "my whole schema DDL," not per-table races). `VALIDATE` and `NONE` are unaffected -- they do
  no mutating DDL.
- `KandraMigrationRunner`'s `init` block: the `CREATE TABLE IF NOT EXISTS kandra_migrations` +
  legacy-column `ALTER TABLE`s now run under a claim keyed by `"kandra-migrations-bootstrap"`,
  guarding this one level earlier than the per-migration claims `run()` already used.

**Residual race, explicitly accepted:** creating `kandra_ddl_locks` itself, the first time it
doesn't exist, is still an unguarded `CREATE TABLE IF NOT EXISTS` -- there's nothing to claim
against before the claim table exists. This is a single statement against one small, schema-stable
table (no column is ever added to it after creation), which is a far narrower and lower-impact race
than the one this fix removes (N instances concurrently running `CREATE TABLE`/`ALTER TABLE` across
every registered entity, or against `kandra_migrations`). `KandraMigrationRunner`'s pre-existing
`kandra_migrations` bootstrap already carried this identical shape of residual race before this fix
-- this trades a wide, high-blast-radius race for a narrow, low-blast-radius one, rather than
eliminating every last race, which isn't achievable without an external coordinator.

`kandra-ktor` and `kandra-migrate` do not depend on each other, so each module carries its own
self-contained copy of this mechanism (identical `kandra_ddl_locks` schema, so both can coexist
safely against the same keyspace if a deployment somehow uses both).

## Tests

Added `kandra-ktor/src/test/kotlin/io/kandra/ktor/DdlBootstrapClaimTest.kt` and
`kandra-migrate/src/test/kotlin/io/kandra/migrate/DdlBootstrapClaimTest.kt`, both run against a real
Cassandra instance via `KandraTestcontainers` (Docker available in this environment), with genuinely
concurrent callers (a real thread pool, not mocks):

- Several (6-10) concurrent instances racing the same DDL bootstrap claim: exactly one runs the
  guarded action (proven directly via an `AtomicInteger` counter, not inferred from timing or logs),
  none throw / no schema-disagreement-shaped error surfaces, and the guarded DDL is applied exactly
  once in effect.
- `kandra-migrate` additionally covers constructing several `KandraMigrationRunner`s concurrently
  end-to-end (the real production entry point, not just the internal helper) against a keyspace with
  no `kandra_migrations` table yet, confirming the table ends up with its full expected schema and
  is fully usable (a real migration runs successfully) afterward.
- `kandra-ktor` additionally covers several concurrent `AUTO_CREATE`-equivalent DDL bootstraps
  leaving a fully usable table behind.
- Both modules cover the staleness/timeout path: a claim hand-inserted with an old `claimed_at`
  (simulating a crashed claimant, the same technique used for `KandraMigrationRunner`'s own
  per-migration staleness tests) is reclaimed and completed by a later caller rather than blocking
  forever.

```
./gradlew :kandra-migrate:test --no-daemon
BUILD SUCCESSFUL

./gradlew :kandra-ktor:test --no-daemon
BUILD SUCCESSFUL

./gradlew test --no-daemon
BUILD SUCCESSFUL
```

## Files

`kandra-ktor/src/main/kotlin/io/kandra/ktor/Kandra.kt`,
`kandra-migrate/src/main/kotlin/io/kandra/migrate/KandraMigrationRunner.kt`,
`kandra-ktor/src/test/kotlin/io/kandra/ktor/DdlBootstrapClaimTest.kt`,
`kandra-migrate/src/test/kotlin/io/kandra/migrate/DdlBootstrapClaimTest.kt`.
