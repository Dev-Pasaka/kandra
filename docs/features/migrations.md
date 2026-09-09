# Schema Migration (`kandra-migrate`)

## Prerequisites

The migration runner requires **LWT (lightweight transactions) support** on the cluster.
`KandraMigrationRunner` claims each migration via an `INSERT ... IF NOT EXISTS` before running it, to lock
against concurrent runner instances racing the same keyspace. If LWT is disabled or restricted on the
cluster (some operators do this for performance reasons), the runner fails outright. Confirm LWT is
available before adopting `kandra-migrate`.

Versioned, checksum-validated migrations. `version`/`name` are constructor parameters (not
overridable properties), and `up()` is a plain (non-`suspend`) function — define migrations as
`object`s, as recommended by `KandraMigration`'s own KDoc:

```kotlin
object CreateUsersTable : KandraMigration(version = 1, name = "create_users_table") {
    override fun up(session: CqlSession) {
        session.execute("CREATE TABLE IF NOT EXISTS users (id uuid PRIMARY KEY, email text)")
    }
}

object AddPhoneColumn : KandraMigration(version = 2, name = "add_phone_to_users") {
    override fun up(session: CqlSession) {
        session.execute("ALTER TABLE users ADD phone text")
    }
}

// On startup:
val runner = KandraMigrationRunner(session)
runner.run(CreateUsersTable, AddPhoneColumn)
```

- Migrations are applied in version-number order.
- Checksums (SHA-256, hashing version/name/class name **and the migration class's own compiled
  bytecode**) are stored in `kandra_migrations` and validated on re-run; mismatch throws
  `KandraMigrationException`.
- Each migration is claimed via an LWT (`INSERT ... IF NOT EXISTS`) before it runs, so two
  runner instances racing against the same keyspace can't both execute the same migration
  concurrently. If `up()` throws, the claim is released so a retry can pick it up.
- `runner.history()` returns a list of applied migrations.

See [ISS-017](../issues/ISS-017-migration-checksum-not-body-based.md) and
[ISS-018](../issues/ISS-018-migration-no-locking.md) for the reasoning behind the checksum and
locking design.

## Concurrent-instance bootstrap coordination (GH #79 / ISS-071)

The per-migration LWT claim above only guards *migrations themselves* — it assumes the
`kandra_migrations` bookkeeping table already exists. `KandraMigrationRunner`'s constructor also
creates that table (`CREATE TABLE IF NOT EXISTS` plus a couple of legacy-column `ALTER TABLE`s for
upgrading a pre-GH-26 table), and if several application instances construct a runner concurrently
against a keyspace that doesn't have the table yet — the normal shape of a rolling multi-replica
deploy — that bootstrap step is now guarded by the same kind of LWT claim, one level earlier:

- One instance wins an `INSERT ... IF NOT EXISTS` claim (in a small coordination table,
  `kandra_ddl_locks`, shared in design — though not in code, since `kandra-migrate` and
  `kandra-ktor` don't depend on each other — with `kandra-ktor`'s identical guard for
  `SchemaMode.AUTO_CREATE`/`AUTO_MIGRATE`, see [`docs/features/schema-modes.md`](schema-modes.md))
  and runs the `kandra_migrations` bootstrap.
- The other instances detect the claim and wait until the winner reports done, then skip the
  bootstrap themselves.
- If the claim-holder goes silent for more than 2 minutes (measured by the cluster's own clock, the
  same clock-skew-proof approach used for the per-migration claim staleness check above), a waiting
  instance presumes it crashed mid-bootstrap and reclaims the lock itself.

This is deliberately a much shorter staleness threshold than the per-migration
`staleClaimThreshold` constructor parameter (default 10 minutes, sized for arbitrary user `up()`
bodies) — the bootstrap itself is a fixed, fast operation (one `CREATE TABLE` plus at most two
`ALTER TABLE ADD`s), not user code. It is not currently exposed as a `KandraMigrationRunner`
constructor parameter. See [ISS-071](../issues/ISS-071-concurrent-ddl-bootstrap-race.md) for the
full design writeup, including the one residual race this can't remove (creating `kandra_ddl_locks`
itself, the first time it doesn't exist yet).
