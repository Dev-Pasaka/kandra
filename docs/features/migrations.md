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
