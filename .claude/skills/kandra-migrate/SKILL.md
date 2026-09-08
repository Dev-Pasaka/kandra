---
name: kandra-migrate
description: Exhaustive API reference for kandra-migrate — KandraMigration, KandraMigrationRunner, versioned checksum-validated schema migrations. Load when writing or reviewing schema migrations, or debugging KandraMigrationException.
---

# kandra-migrate

`kandra-migrate` is the separate module for versioned, checksummed CQL schema migrations — the tool to reach
for when `SchemaMode.AUTO_MIGRATE` isn't enough (renames, backfills, index changes, drops). It's three files,
all in `io.kandra.migrate`: `KandraMigration.kt`, `KandraMigrationRunner.kt`, `BytecodeNormalizer.kt`.

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
| `checksum()` | `internal fun checksum(): String` | Module-internal. SHA-256, hex-encoded lowercase, of `"${version}:${name}:${this::class.qualifiedName}"` **followed by** the migration class's own compiled bytecode, normalized by `BytecodeNormalizer` (see below) before hashing. |

**The checksum DOES detect an edited `up()` body** — this is the current behavior after GH #17 (ISS-017) and
GH #63 (ISS-062); an earlier version of this class hashed only `version:name:qualifiedClassName`, but that is
no longer accurate. `checksum()`:

1. Hashes `"${version}:${name}:${this::class.qualifiedName}"`.
2. Loads the migration class's own `.class` resource bytes and hashes `BytecodeNormalizer.normalize(bytes)`
   on top — so a genuine change to `up()`'s compiled instructions (or any lambda/anonymous class it captures)
   changes the checksum, catching exactly the "silently edited an already-applied migration" mistake.

`BytecodeNormalizer` exists specifically so *cosmetic* recompilation (a JDK/Kotlin patch bump, a changed
`-jvmTarget`, a toggled debug-info flag) doesn't produce a false-positive checksum mismatch: it strips the
classfile's `minor_version`/`major_version` field and `LineNumberTable`/`LocalVariableTable`/
`LocalVariableTypeTable`/`SourceFile`/`SourceDebugExtension` attributes before hashing, while leaving the
actual bytecode instructions, constant pool, and annotations sensitive to change. Any parse failure falls back
to hashing the raw bytes unmodified — normalization can only *reduce* false positives, never mask a real edit.
See `docs/issues/ISS-062-migration-checksum-false-positive-risk.md`.

**Residual limitation:** this remains an approximation, not a semantic diff — a large enough compiler/toolchain
change can still, rarely, flip a checksum for source that didn't actually change. The documented recovery path
(on `KandraMigration.checksum()`'s own KDoc) is to confirm via source diff that it's a genuine false positive,
then manually `UPDATE kandra_migrations SET checksum = ? WHERE version = ?` to the newly-reported value —
never edit the migration itself to "fix" a mismatch.

Documented invariant (KDoc on the class, enforced by `KandraMigrationRunner.run`):

> Never modify a migration after it has been applied to any environment. Kandra validates checksums on
> startup and throws `KandraMigrationException` if a previously-applied migration's body has changed.

## `KandraMigrationRunner`

```kotlin
class KandraMigrationRunner(
    private val session: CqlSession,
    private val staleClaimThreshold: Duration = Duration.ofMinutes(10)
) {
    fun run(vararg migrations: KandraMigration)
    fun history(): List<MigrationHistory>
    // private: migrateLegacySchema(), handleUnresolvedClaim(), serverNow(), loadApplied(), claim(), markApplied()
}
```

`staleClaimThreshold` (default 10 minutes) controls the crash-safety behavior described below — see
"Crash safety".

### Constructor / `init` block

`KandraMigrationRunner(session)` immediately (in `init`) runs:

```sql
CREATE TABLE IF NOT EXISTS kandra_migrations (
    version     INT,
    name        TEXT,
    status      TEXT,
    claimed_at  TIMESTAMP,
    applied_at  TIMESTAMP,
    checksum    TEXT,
    PRIMARY KEY (version)
)
```

then `migrateLegacySchema()` — `ALTER TABLE kandra_migrations ADD status TEXT` / `ADD claimed_at TIMESTAMP` if
either column is missing (upgrading a `kandra_migrations` table created before GH #26 added crash safety).
Existing rows in an upgraded table read back with `status = NULL`, which `history()`/`claim()` treat as
legacy-applied. `version` is the sole primary-key column (partition key, no clustering columns) — one row per
migration version. This DDL runs **every time** a `KandraMigrationRunner` is constructed, not just once;
`IF NOT EXISTS`/column-presence checks make it a no-op on subsequent app starts against an up-to-date table —
but see the "concurrent instances" caveat below.

**Multi-instance caveat:** this bootstrap DDL (unlike the per-migration claim below) has no LWT guard of its
own — if several application instances construct a `KandraMigrationRunner` concurrently against a keyspace
that doesn't have the table yet, they race on `CREATE TABLE`/`ALTER TABLE`. See
`docs/issues/ISS-071-concurrent-ddl-bootstrap-race.md`.

### Crash safety (GH #26 / ISS-043)

Each row in `kandra_migrations` carries a `status`: `CLAIMED` or `APPLIED` (legacy rows with no status at all
are treated as applied, for backward compatibility with pre-GH-26 tables). A version is claimed —written as
`CLAIMED` — via an `INSERT ... IF NOT EXISTS` LWT *before* `up()` runs, so two runner instances racing the
same never-before-seen version can't both execute it. The row only flips to `APPLIED` after `up()` returns
successfully.

`run()` never guesses about an unresolved `CLAIMED` row — there is no lease/heartbeat, so a `CLAIMED` row from
a still-running instance is indistinguishable from one left behind by a crashed process (OOM, `SIGKILL`, an
uncaught `Error`). Instead:
- **Recently claimed** (age ≤ `staleClaimThreshold`): logs a `WARN` and **halts the rest of this `run()`
  call** — does not skip ahead to later migrations, which may depend on DDL the claimant hasn't finished.
- **Claimed longer ago than `staleClaimThreshold`**: throws `KandraMigrationException` telling the operator to
  inspect `kandra_migrations` and resolve it manually (confirm whether the DDL actually completed, then either
  delete the row for a safe retry, or update its status to `APPLIED`).

"How long ago" is measured **entirely by the ScyllaDB/Cassandra cluster's own clock**, never by comparing two
application instances' wall clocks (GH #64 / ISS-063): `claimed_at` is written via the CQL `toTimestamp(now())`
function (evaluated by the coordinator, not bound as a JVM `Instant`), and the staleness check reads "now" the
same way via a fresh `SELECT toTimestamp(now())`. This avoids clock-skew between application instances making
a crashed migration look perpetually fresh (or a genuinely in-progress one look falsely stale).

### `run(vararg migrations: KandraMigration)`

Traced control flow, exactly as implemented:

1. `loadApplied()` — one `SELECT` over `kandra_migrations` (`history().associateBy { it.version }`), snapshotted
   **once** at the start of this `run()` call, before any `up()` executes.
2. `migrations.sortedBy { it.version }` — the vararg array is sorted ascending by version regardless of the
   order you passed them in.
3. For each migration, in ascending version order:
   - If a row for that `version` exists in the snapshot:
     - `status == CLAIMED` → `handleUnresolvedClaim` (warn+halt, or throw — see "Crash safety" above); either
       way `run()` **returns immediately**, without attempting this version or anything after it.
     - Checksum mismatch → **throws `KandraMigrationException`** immediately, aborting the rest of the loop.
     - Otherwise (checksum matches) → logs at `DEBUG`, moves to the next migration (`continue`).
   - If no row exists (never applied): calls `claim(migration)` (the LWT). If another instance claimed it
     between the snapshot read and now, `handleUnresolvedClaim` runs and `run()` returns. Otherwise: logs
     `INFO`, calls `migration.up(session)` synchronously, then `markApplied(migration)`, then logs `INFO`.

**Fail-fast, not all-or-nothing, no cross-migration transaction:**

- `up()` **is** wrapped in a try/catch in `run()` — but only to distinguish a synchronous, in-process failure
  (a normal `Exception`) from a crashed process. On a caught `Exception`, the `CLAIMED` row is explicitly
  **deleted** (`DELETE FROM kandra_migrations WHERE version = ?`) so a later `run()` can retry cleanly, and a
  `KandraMigrationException` (wrapping the original) is thrown, aborting every later migration in that call.
- An `Error` (not `Exception`) — e.g. from a killed/crashed process — is deliberately **not** caught here. That
  is exactly the case the `CLAIMED`-row/staleness mechanism above exists to surface loudly on a later `run()`,
  rather than silently trusting or silently retrying.
- Migrations that succeeded earlier **in the same `run()` call** are already committed and recorded — a later
  failure does not roll them back. Whatever DDL a failing migration already ran before throwing stays applied
  to the keyspace (ScyllaDB DDL isn't transactional) even though its row was deleted for retry — write `up()`
  idempotently (`IF NOT EXISTS`/`IF EXISTS`) so a retried run is safe.
- Because the "applied" snapshot is loaded once per `run()` call (not once per process), calling `run()` again
  later (e.g. a re-deploy) re-reads `kandra_migrations` fresh.

### `history(): List<MigrationHistory>`

```kotlin
session.execute("SELECT version, name, status, claimed_at, applied_at, checksum FROM kandra_migrations")
    .all()
    .map { row -> MigrationHistory(version, name ?: "", appliedAt ?: Instant.EPOCH, checksum ?: "",
        status ?: APPLIED /* legacy-row fallback */, claimedAt) }
    .sortedBy { it.version }
```

Public, safe to call any time (e.g. from a health/admin endpoint) — reads and re-sorts client-side by
`version` ascending (CQL gives no ordering guarantee here since `version` is the partition key).

### Private helpers

- `loadApplied(): Map<Int, MigrationHistory>` — `history().associateBy { it.version }`. Called once per `run()`
  invocation, not cached across calls or across `KandraMigrationRunner` instances.
- `claim(migration): MigrationHistory?` — `INSERT ... VALUES (?, ?, 'CLAIMED', toTimestamp(now()), ?) IF NOT
  EXISTS`. Returns `null` on success; returns the pre-existing row (from the LWT response, no extra read
  needed) if another instance already claimed/applied this version first.
- `markApplied(migration)` — `UPDATE kandra_migrations SET status = 'APPLIED', applied_at = ? WHERE version = ?`,
  binding `Instant.now()` (this one **is** the app's own wall clock — it's display-only, never compared across
  instances, unlike `claimed_at`).
- `serverNow(version): Instant` — `SELECT toTimestamp(now()) AS server_now FROM kandra_migrations WHERE
  version = ?`; used only for the staleness comparison in `handleUnresolvedClaim`.

## `MigrationHistory` — data class

```kotlin
data class MigrationHistory(
    val version: Int,
    val name: String,
    val appliedAt: Instant,
    val checksum: String,
    val status: MigrationRowStatus,
    val claimedAt: Instant?
)
```

One instance per row in `kandra_migrations`, returned by `history()`.

## `MigrationRowStatus` — enum

```kotlin
enum class MigrationRowStatus { CLAIMED, APPLIED }
```

Actively used throughout `KandraMigrationRunner` — this is **not** dead code. It's the field that
distinguishes "someone started this migration" from "this migration definitely finished," which is the whole
basis for the crash-safety mechanism above. A legacy row with no `status` column value reads back as `APPLIED`
(backward compatibility with pre-GH-26 tables).

## `KandraMigrationException`

From `kandra-core`'s `Exceptions.kt`:

```kotlin
class KandraMigrationException(message: String, cause: Throwable? = null) : KandraException(message, cause)
```

Extends `KandraException(message, cause) : RuntimeException`. Thrown from three places in this module:
1. A checksum mismatch on an already-applied version (`cause = null`).
2. A `CLAIMED` row older than `staleClaimThreshold` (`cause = null`).
3. `up()` throwing a synchronous `Exception` (`cause` = the original exception, message includes
   `${e.message}`).

It is **not** thrown for a crashed process (an `Error`, not caught) — that propagates unwrapped, and is what
leaves behind the `CLAIMED` row the staleness mechanism is designed to catch on a later `run()`.

## Full example

```kotlin
import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.migrate.KandraMigration
import io.kandra.migrate.KandraMigrationRunner

// Applied in a previous deploy — never modify version, name, or up()'s body now.
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
        contactPoints = "127.0.0.1:9042"
        keyspace = "myapp"
        localDatacenter = "dc1"
        schemaMode = SchemaMode.NONE   // migrations own the schema, plugin does no DDL
        register(User::class)
    }
}
```

On this startup: `V1` and `V2` are skipped (checksum matches their recorded `APPLIED` rows, logged at `DEBUG`),
`V3` is claimed, runs, and gets marked `APPLIED` (logged at `INFO` before/after). If someone had edited `V1`'s
`up()` body (even without touching `version`/`name`) after it was first applied, `run()` would throw
`KandraMigrationException` on `V1` before ever reaching `V3` — the check happens strictly in ascending version
order, so a broken low-numbered migration blocks every migration after it, applied or not.

## Gotchas worth double-checking in review

- Migrations must be Kotlin `object`s, not `class`es — `checksum()` includes `this::class.qualifiedName`, and
  the runner needs one stable instance per version to compare against `kandra_migrations`. A `class` you
  instantiate fresh each call still works mechanically (same qualified name each time), but there's no reason
  to fight the intended pattern.
- `checksum()` **does** hash the migration's own compiled bytecode (normalized to strip toolchain noise) on top
  of `version:name:qualifiedClassName` — editing `up()`'s body after it's been applied **is** detected and
  throws `KandraMigrationException` on the next `run()`. This is a change from an earlier version of this
  class; don't assume body edits are silently ignored.
- `run()` is fail-fast per call, not transactional: an exception from one migration's `up()` deletes its
  `CLAIMED` row (safe retry) and aborts every later migration in that same `run()` call, while everything
  before it stays applied. A crashed *process* (not a caught exception) leaves a `CLAIMED` row behind instead —
  see "Crash safety" above. Write `up()` idempotently so a retried run is safe either way.
- Call the runner **before** `install(Kandra)`, and pair it with `schemaMode = SchemaMode.NONE` — running it
  after `install(Kandra)` with `AUTO_CREATE`/`AUTO_MIGRATE` risks the plugin's own DDL racing the runner's.
- `MigrationRowStatus` (`CLAIMED`/`APPLIED`) is actively used, not dead code — it's the core of the crash-safety
  design. There is no `MigrationStatus` enum with `PENDING`/`CHECKSUM_MISMATCH` values; that described an
  earlier, since-replaced version of this module.
- `KandraMigrationException` fires on a checksum mismatch, a stale unresolved `CLAIMED` row, **or** `up()`
  throwing an `Exception` (wrapped, with `cause` set) — not just the checksum case.
- Constructing multiple `KandraMigrationRunner`s concurrently against a keyspace with no `kandra_migrations`
  table yet races on the bootstrap DDL (`init` block) — this has no LWT guard, unlike per-migration claiming.
  See `docs/issues/ISS-071-concurrent-ddl-bootstrap-race.md`.
