# Schema Modes (`kandra-ktor`)

| Mode | Behaviour |
|---|---|
| `SchemaMode.AUTO_CREATE` | `CREATE TABLE IF NOT EXISTS` on startup |
| `SchemaMode.AUTO_MIGRATE` | `CREATE TABLE IF NOT EXISTS` + `ALTER TABLE ADD` for new columns; warns on unmapped Scylla columns |
| `SchemaMode.VALIDATE` | Verifies every entity column exists in Scylla; throws on mismatch |
| `SchemaMode.NONE` | No DDL; manage schema externally |

## Concurrent-instance coordination (GH #79 / ISS-071)

A rolling deploy of N replicas means N `install(Kandra)` calls can start at roughly the same time
against the same keyspace. `AUTO_CREATE`/`AUTO_MIGRATE`'s DDL is guarded by a cluster-wide LWT claim
so this races safely instead of literally racing:

- One instance wins an `INSERT ... IF NOT EXISTS` claim (in a small coordination table,
  `kandra_ddl_locks`) and runs the DDL for every registered entity as one pass.
- The other instances detect the claim and wait — polling every 200ms — until the winner reports
  done, then skip running the DDL themselves entirely.
- If the claim-holder goes silent for more than 2 minutes (measured by the cluster's own clock, not
  any application instance's — the same clock-skew-proof approach `kandra-migrate`'s migration
  claims use, see [ISS-063](../issues/ISS-063-migration-claim-staleness-clock-skew.md)), a waiting
  instance presumes it crashed mid-DDL and reclaims the lock itself via a compare-and-set update, so
  startup never hangs indefinitely behind a dead claimant.

This is one claim for the *entire* bootstrap pass (all registered entities), not one per table —
simpler to reason about, and it matches the deployment shape being guarded against (each replica
wants to run "my whole schema DDL" once, not race table-by-table). `VALIDATE` and `NONE` do no
mutating DDL and aren't affected.

`KandraMigrationRunner`'s own `kandra_migrations` bookkeeping-table bootstrap uses the identical
mechanism — see [`docs/features/migrations.md`](migrations.md) and
[ISS-071](../issues/ISS-071-concurrent-ddl-bootstrap-race.md) for the full design writeup.

The one race this cannot remove: creating `kandra_ddl_locks` itself, the first time it doesn't yet
exist. That's a single `CREATE TABLE IF NOT EXISTS` for one small, schema-stable table — a far
narrower and lower-impact race than the one this guard removes.
