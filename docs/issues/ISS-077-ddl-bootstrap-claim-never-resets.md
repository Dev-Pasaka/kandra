# ISS-077: Schema DDL bootstrap claim never resets, so AUTO_CREATE/AUTO_MIGRATE runs at most once ever per keyspace

**Status:** Fixed

## Problem

Filed as GH #90.

The LWT-claim guard added for #79 (`claimAndRunDdlBootstrap` in `kandra-ktor/src/main/kotlin/io/kandra/ktor/Kandra.kt`, mirrored in `kandra-migrate`'s own bootstrap guard) keys its lock by a single fixed constant (`DDL_BOOTSTRAP_SCHEMA_LOCK = "schema-bootstrap"`) and never deletes or resets the row once `markDdlLockDone` sets `status = 'DONE'`.

`tryClaimDdlLock`'s `INSERT ... IF NOT EXISTS` fails once *any* row exists for that lock name — claimed or done, doesn't matter. A waiting/later caller that observes `status == "DONE"` returns immediately without ever calling `action()` (see the `if (row.getString("status") == "DONE") { ...; return }` branch).

Concrete sequence:
1. App deploys with `SchemaMode.AUTO_CREATE`, entities `{A, B}` registered. First startup wins the claim, creates tables for A and B, marks `schema-bootstrap` `DONE`.
2. Weeks later, entity `C` is added and the app is redeployed (still `AUTO_CREATE`/`AUTO_MIGRATE`). `SchemaRegistry.register(C::class)` runs fine (in-memory, unclaimed) — but the DDL loop that would `CREATE TABLE C` (or `ALTER TABLE` an existing one under `AUTO_MIGRATE`) is skipped entirely, because the shared lock is already `DONE`.
3. The app starts "successfully" with no error or warning. The first query against `C` throws `InvalidQueryException: table C does not exist`. Under `AUTO_MIGRATE`, a newly-added column on an existing entity is silently never `ALTER TABLE ADD`ed after the very first successful bootstrap, ever.

This is a straight regression from the pre-#79 behavior, which ran unconditionally (racily, but correctly) on every startup, and defeats the entire documented purpose of `AUTO_CREATE`/`AUTO_MIGRATE` — picking up schema changes on every deploy.

No test in `DdlBootstrapClaimTest.kt` (either copy) exercises "run again later, after DONE, with new schema pending" — every assertion is "ran exactly once across one concurrent wave," which is necessary but not sufficient.

## Impact

Critical. Silently breaks the core `AUTO_CREATE`/`AUTO_MIGRATE` feature for any deployment that adds an entity or column after its first successful startup — which is the normal lifecycle of any real application. Discovered during a critical post-fix audit ahead of experimental multi-DC cluster testing.

## Suggested fix

The claim needs to model "one run per deploy/schema-generation," not "run exactly once for the keyspace's lifetime." Options:
- Delete/reset the lock row at the end of a successful run instead of leaving it `DONE` forever (reintroduces the original #79 race for the next wave only, which the mechanism is designed to handle).
- Key the lock by a hash of the registered schema set (table + column names) so a changed schema gets a fresh claim.
- Scope "done forever" semantics only to genuinely one-time bootstraps (the `kandra_ddl_locks`/`kandra_migrations` table creation itself is fine to run once) and give `AUTO_CREATE`/`AUTO_MIGRATE` a different mechanism — e.g. per-table locks with per-table `DONE` tracking, reset whenever the registered schema changes.

## Files

`kandra-ktor/src/main/kotlin/io/kandra/ktor/Kandra.kt`, `kandra-migrate/src/main/kotlin/io/kandra/migrate/KandraMigrationRunner.kt`, `kandra-ktor/src/test/kotlin/io/kandra/ktor/DdlBootstrapClaimTest.kt`, `kandra-migrate/src/test/kotlin/io/kandra/migrate/DdlBootstrapClaimTest.kt`

Filed from a critical post-fix audit (2026-09-09) done ahead of experimental multi-DC cluster testing, specifically re-reviewing the brand-new #79 fix.

## Fix

Went with the second option from "Suggested fix" above: key the `AUTO_CREATE`/`AUTO_MIGRATE` DDL
bootstrap claim by a fingerprint of the currently-registered schema, rather than the fixed
`"schema-bootstrap"` constant.

`schemaFingerprint(schemas: List<TableSchema>)` (`Kandra.kt`) hashes (SHA-256) the exact DDL each
table would emit via `DdlGenerator.allStatements` — sorted by table name first, so registration
order never affects the result. `ddlBootstrapLockName(schemas)` combines this with the
`DDL_BOOTSTRAP_SCHEMA_LOCK` prefix (`"schema-bootstrap-<hash>"`), and both `SchemaMode.AUTO_CREATE`
and `SchemaMode.AUTO_MIGRATE` now call `claimAndRunDdlBootstrap` with this fingerprinted name
instead of the bare constant.

Effect: a claim marked `DONE` for one schema generation (one specific set of registered entities
and their columns) is a completely different row from a claim for a later generation with a new
entity or a new column — the later deploy lands on a fresh, never-claimed lock and runs its own
`CREATE TABLE`/`ALTER TABLE ADD` pass. Replicas within the *same* deploy wave (identical registered
schema, identical fingerprint) still correctly serialize against each other via the existing
`claimAndRunDdlBootstrap` LWT mechanism — only the keying changed, not the concurrency guarantee
#79 added.

`kandra-migrate`'s own `DDL_BOOTSTRAP_MIGRATIONS_TABLE_LOCK` (guarding the one-time creation of the
`kandra_migrations` bookkeeping table) was deliberately left as a fixed constant — that table's
shape never depends on the application's registered entities, so "done forever" is correct there,
and fingerprinting it would only add churn with no benefit. This is the third option from
"Suggested fix" above, applied narrowly to the one lock that actually needed it.

Verified with:
- `SchemaFingerprintTest` (new, `kandra-ktor`) — pure in-memory assertions that the fingerprint is
  stable for an unchanged schema, order-independent, and changes when an entity is added or when a
  column (including a new key column) is added to an existing entity.
- `AutoCreateRedeployTest` (new, `kandra-ktor`) — end-to-end against a real Testcontainers cluster:
  installs the plugin once with one entity registered (winning and completing the DDL bootstrap
  claim), then installs again against the same keyspace with a second entity added, and asserts the
  second entity's table now exists — the exact regression this issue described.
- Full `./gradlew :kandra-ktor:test :kandra-migrate:test` run, green.
