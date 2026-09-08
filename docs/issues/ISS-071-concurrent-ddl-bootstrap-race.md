# ISS-071: Schema DDL bootstrap has no coordination guard across concurrently-starting instances

**Status:** Open

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
