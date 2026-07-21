# Schema Migration (`kandra-migrate`)

Versioned, checksum-validated migrations:

```kotlin
class AddPhoneColumn : KandraMigration() {
    override val version = 2
    override val name    = "add_phone_to_users"
    override suspend fun up(session: CqlSession) {
        session.execute("ALTER TABLE users ADD phone text")
    }
}

// On startup:
val runner = KandraMigrationRunner(session)
runner.run(CreateUsersTable(), AddPhoneColumn())
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
