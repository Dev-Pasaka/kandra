---
name: kandra-migrate
description: Exhaustive API reference for kandra-migrate — KandraMigration, KandraMigrationRunner, versioned checksum-validated schema migrations. Load when writing or reviewing schema migrations, or debugging KandraMigrationException.
---

# kandra-migrate

`kandra-migrate` is the separate module for versioned, checksummed CQL schema migrations — the tool to reach
for when `SchemaMode.AUTO_MIGRATE` isn't enough (renames, backfills, index changes, drops). It's three files,
all in `io.kandra.migrate`: `KandraMigration.kt`, `KandraMigrationRunner.kt`, `MigrationStatus.kt`.

Pair it with `install(Kandra) { schemaMode = SchemaMode.NONE }` (`kandra-ktor`'s `KandraConfig.kt`) — `NONE`
means "skip all DDL, you manage schema yourself," which is exactly what a migration-managed schema needs so
Kandra's plugin-install DDL doesn't race or conflict with the runner.

## `KandraMigration` — abstract class

```kotlin
abstract class KandraMigration(
    val version: Int,
    val name: String
) {
    abstract fun up(session: CqlSession)
    internal fun checksum(): String
}
```

| Member | Signature | Notes |
|---|---|---|
| `version` | `val version: Int` | Public, set via constructor. Determines apply order (ascending) and is the **partition key** in the tracking table — must be unique across all migrations ever defined. |
| `name` | `val name: String` | Public, set via constructor. Free-text label, stored alongside `version` in `kandra_migrations` and echoed in log lines / exception messages. |
| `up(session)` | `abstract fun up(session: CqlSession)` | **Blocking, not `suspend`.** Takes the raw driver `CqlSession` (`com.datastax.oss.driver.api.core.CqlSession`) — call `session.execute(...)` directly with plain CQL, not through a `KandraRepository`. |
| `checksum()` | `internal fun checksum(): String` | Module-internal (visible anywhere inside `kandra-migrate`, not callable from application code in another module). Computed as SHA-256 of the string `"${version}:${name}:${this::class.qualifiedName}"`, hex-encoded lowercase. **Verified from source** — it hashes `version`, `name`, and the migration class's fully-qualified name; it does **not** hash the body of `up()`. |

Because the checksum is derived from `version:name:qualifiedClassName` and not from `up()`'s contents, two
migrations with the same version/name/class but different SQL inside `up()` will produce an **identical**
checksum — the runner cannot detect that kind of edit. What it *does* detect: changing `version`, `name`, or
renaming/moving the `object` (which changes `qualifiedName`) after it has already been recorded as applied.

Documented invariant (KDoc on the class, enforced by `KandraMigrationRunner.run`):

> Never modify a migration after it has been applied to any environment. Kandra validates checksums on
> startup and throws `KandraMigrationException` if a previously-applied migration's identity has changed.

## `KandraMigrationRunner`

```kotlin
class KandraMigrationRunner(private val session: CqlSession) {
    fun run(vararg migrations: KandraMigration)
    fun history(): List<MigrationHistory>
    // private: loadApplied(), recordApplied(migration)
}
```

### Constructor / `init` block

`KandraMigrationRunner(session)` immediately (in `init`) runs:

```sql
CREATE TABLE IF NOT EXISTS kandra_migrations (
    version     INT,
    name        TEXT,
    applied_at  TIMESTAMP,
    checksum    TEXT,
    PRIMARY KEY (version)
)
```

`version` is the sole primary-key column (partition key, no clustering columns) — one row per migration
version, no history of re-applications. Table creation happens **every time** a `KandraMigrationRunner` is
constructed, not just once; `IF NOT EXISTS` makes this a no-op on subsequent app starts.

### `run(vararg migrations: KandraMigration)`

Traced control flow, exactly as implemented:

1. `loadApplied()` — one `SELECT` over `kandra_migrations`, snapshotted **once** at the start of this `run()`
   call, before any `up()` executes. This snapshot is not refreshed mid-loop.
2. `migrations.sortedBy { it.version }` — the vararg array is sorted ascending by version regardless of the
   order you passed them in.
3. For each migration, in ascending version order:
   - If a row for that `version` exists in the snapshot (`existing != null`):
     - If `existing.checksum != migration.checksum()` → **throws `KandraMigrationException`** immediately,
       aborting the rest of the loop. Message (verified verbatim):
       ```
       Migration v${version} ('${name}') checksum mismatch — the migration was modified after being
       applied. Expected: ${existing.checksum}, got: ${migration.checksum()}. Never modify a migration
       after it has been applied.
       ```
     - Else → logs at `DEBUG`: `"Migration v${version} ('${name}') already applied — skipping."` and moves
       to the next migration (`return@forEach`, i.e. a `continue`).
   - If no row exists (never applied): logs `INFO` `"Applying migration v${version}: ${name}"`, calls
     `migration.up(session)` synchronously, then `recordApplied(migration)` (INSERT), then logs `INFO`
     `"Migration v${version} applied successfully."`.

**Fail-fast, not all-or-nothing, no transaction.** This is the critical non-obvious behavior:

- `up()` is called with no try/catch around it in `run()`. If it throws, the exception propagates straight
  out of `run()` — every migration after the failing one in that call is **never attempted**.
- `recordApplied()` only runs *after* `up()` returns successfully, so a migration whose `up()` throws
  partway through (e.g. after executing 2 of 3 CQL statements) is **not** marked applied — but whatever DDL
  it already ran against Scylla stays applied to the keyspace, since ScyllaDB DDL isn't transactional either.
  On the next `run()` call, that migration will be retried from the top of `up()` — write your `up()` bodies
  idempotently (`IF NOT EXISTS` / `IF EXISTS`) so a partial-then-retried run doesn't blow up on the second
  attempt.
- Migrations that succeeded earlier **in the same `run()` call** are already committed and recorded — a
  later failure does not roll them back. There is no cross-migration transaction of any kind.
- Because the "applied" snapshot is loaded once per `run()` call (not once per process), calling `run()`
  again later (e.g. a re-deploy) re-reads `kandra_migrations` fresh — already-recorded versions are skipped,
  the previously-failed version and anything after it are attempted again.

### `history(): List<MigrationHistory>`

```kotlin
session.execute("SELECT version, name, applied_at, checksum FROM kandra_migrations")
    .all()
    .map { row -> MigrationHistory(version, name ?: "", appliedAt ?: Instant.EPOCH, checksum ?: "") }
    .sortedBy { it.version }
```

Public, safe to call any time (e.g. from a health/admin endpoint) — reads and re-sorts client-side by
`version` ascending (CQL gives no ordering guarantee here since `version` is the partition key). Null column
reads default to `""` for `name`/`checksum` and `Instant.EPOCH` for `appliedAt` — in practice these only turn
up if the table is queried at a wider consistency where a replica hasn't caught up.

### Private helpers

- `loadApplied(): Map<Int, MigrationHistory>` — `history().associateBy { it.version }`. Called once per
  `run()` invocation, not cached across calls or across `KandraMigrationRunner` instances.
- `recordApplied(migration: KandraMigration)` — prepares
  `INSERT INTO kandra_migrations (version, name, applied_at, checksum) VALUES (?, ?, ?, ?)`, binds
  `migration.version, migration.name, Instant.now(), migration.checksum()`, executes it. `applied_at` is
  wall-clock time of the *insert*, i.e. right after `up()` finished, not when `run()` started.

## `MigrationHistory` — data class

```kotlin
data class MigrationHistory(
    val version: Int,
    val name: String,
    val appliedAt: Instant,
    val checksum: String
)
```

One instance per row in `kandra_migrations`, returned by `history()`. All four fields map 1:1 to the table's
columns.

## `MigrationStatus` — enum

```kotlin
enum class MigrationStatus { PENDING, APPLIED, CHECKSUM_MISMATCH }
```

**Verified dead code as of this reading**: grepped the whole repo — `MigrationStatus` is declared and never
referenced anywhere else, including inside `KandraMigrationRunner`. There is no `status(migration)` method
that returns one. `run()` and `history()` never construct or expose a `MigrationStatus` value. Don't assume a
per-migration status lookup exists — if you need to classify a `KandraMigration` as pending/applied/mismatched
before calling `run()`, you have to compute it yourself by comparing `runner.history()` against your migration
list's `version` and `checksum()`.

## `KandraMigrationException`

From `kandra-core`'s `Exceptions.kt`:

```kotlin
class KandraMigrationException(message: String, cause: Throwable? = null) : KandraException(message, cause)
```

Extends `KandraException(message, cause) : RuntimeException`. In this module it is thrown from exactly one
place — the checksum-mismatch branch of `run()` described above — always with `cause = null` (the two-arg
constructor exists but `run()` never populates `cause`). It is **not** thrown for a failing `up()`; a failing
`up()` propagates whatever exception the CQL driver or your code raised, unwrapped.

## Full example

```kotlin
import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.migrate.KandraMigration
import io.kandra.migrate.KandraMigrationRunner

// Applied in a previous deploy — version, name, and class identity must never change now.
object V1_CreateUsers : KandraMigration(version = 1, name = "create users table") {
    override fun up(session: CqlSession) {
        session.execute(
            """
            CREATE TABLE IF NOT EXISTS users (
                id    UUID PRIMARY KEY,
                email TEXT
            )
            """.trimIndent()
        )
    }
}

// Also already applied.
object V2_AddPhoneToUsers : KandraMigration(version = 2, name = "add phone column") {
    override fun up(session: CqlSession) {
        session.execute("ALTER TABLE users ADD phone TEXT")
    }
}

// New — not yet in kandra_migrations, will run on next startup.
object V3_AddPhoneIndex : KandraMigration(version = 3, name = "add phone lookup index") {
    override fun up(session: CqlSession) {
        session.execute("CREATE INDEX IF NOT EXISTS users_phone_idx ON users (phone)")
    }
}

fun Application.configureMigrations(session: CqlSession) {
    // Run BEFORE install(Kandra) so schemaMode = NONE has a schema to see.
    KandraMigrationRunner(session).run(V1_CreateUsers, V2_AddPhoneToUsers, V3_AddPhoneIndex)

    install(Kandra) {
        contactPoints = listOf("127.0.0.1:9042")
        keyspace = "myapp"
        localDatacenter = "dc1"
        schemaMode = SchemaMode.NONE   // migrations own the schema, plugin does no DDL
        register(User::class)
    }
}
```

On this startup: `V1` and `V2` are skipped (checksum matches their recorded rows, logged at `DEBUG`), `V3`
runs and gets recorded (logged at `INFO` before/after). If someone had edited `V1`'s `name` or moved it to a
different package after it was first applied, `run()` would throw `KandraMigrationException` on `V1` before
ever reaching `V3` — the "already applied, checksum matches" check happens strictly in ascending version
order, so a broken low-numbered migration blocks every migration after it, applied or not.

## Gotchas worth double-checking in review

- Migrations must be Kotlin `object`s, not `class`es — `checksum()` includes `this::class.qualifiedName`,
  and the runner needs one stable instance per version to compare against `kandra_migrations`. A `class` you
  instantiate fresh each call still works mechanically (same qualified name each time), but there's no reason
  to fight the intended pattern.
- `checksum()` hashes `version`, `name`, and the class's qualified name — **not** the CQL inside `up()`.
  Editing a bug in an already-applied `up()` body silently passes the checksum check; it does not protect you
  against that class of mistake, only against renumbering/renaming/moving.
- `run()` is fail-fast per call, not transactional: an exception from one migration's `up()` aborts every
  later migration in that same `run()` call, while everything before it (and whatever the failing migration
  already executed before throwing) stays applied. Write `up()` idempotently so a retried run is safe.
- Call the runner **before** `install(Kandra)`, and pair it with `schemaMode = SchemaMode.NONE` — running it
  after `install(Kandra)` with `AUTO_CREATE`/`AUTO_MIGRATE` risks the plugin's own DDL racing the runner's.
- `MigrationStatus` (`PENDING`/`APPLIED`/`CHECKSUM_MISMATCH`) is declared but unused anywhere in the module —
  don't expect a helper that classifies a migration's status; derive it yourself from `history()`.
- `KandraMigrationException` only fires on a checksum mismatch for an already-applied version. A failing
  `up()` throws its own (unwrapped) exception, not `KandraMigrationException`.
