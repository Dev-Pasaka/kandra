# Kandra 0.4.0-SNAPSHOT — Feature Reference

## Core Annotations (`kandra-core`)

### `@ScyllaTable`
Maps a Kotlin data class to a ScyllaDB/Cassandra table.

```kotlin
@ScyllaTable(
    tableName = "users",
    gcGraceSeconds = 86400   // optional; sets gc_grace_seconds in DDL
)
data class User(...)
```

### `@PartitionKey` / `@ClusteringKey`
Defines primary key components. Multiple `@PartitionKey` fields form a composite partition key.

```kotlin
@PartitionKey(order = 0)
val userId: UUID,
@ClusteringKey(order = 0, descending = true)
val createdAt: Instant
```

### `@Column`
Optional rename: `@Column(name = "cql_name")`.

### `@Version`
Enables optimistic locking via Lightweight Transactions (LWT).

- Field must be `Long` or `Instant`.
- `save()` initialises to `1L` / `Instant.now()`.
- `update()` / `updateSuspend()` emit `IF version = ?` and throw `KandraOptimisticLockException` on conflict.
- `updateForce()` / `updateForceSuspend()` skip the version check.

```kotlin
@Version val version: Long = 0L
```

### `@SoftDelete`
Replaces hard DELETE with `UPDATE … USING TTL`. Lookup rows are hard-deleted.

```kotlin
@SoftDelete(ttlSeconds = 86400)
```

### `@Sensitive`
Masks field values with `***` in all Kandra log output via `KandraEntityLogger`.

```kotlin
@Sensitive val password: String
```

### `@CacheResult`
Attaches a Caffeine in-process cache to `findById`. Requires `com.github.ben-manes.caffeine:caffeine` on the runtime classpath; gracefully disables itself if absent.

```kotlin
@CacheResult(ttlSeconds = 60, maxSize = 1000)
```

### `@Ttl` / `@CreatedAt` / `@UpdatedAt`
- `@Ttl` — row-level TTL forwarded to INSERT.
- `@CreatedAt` — auto-populated `Instant` on first save.
- `@UpdatedAt` — auto-populated `Instant` on every save/update.

### `@Transient`
Excludes a field from CQL entirely.

### `@Counter`
Marks a counter column for `increment()` / `decrement()` operations.

### `@LookupTable`
Declares a denormalised lookup table on a secondary field with configurable consistency (`BATCH` or `EVENTUAL`).

```kotlin
@LookupTable(
    tableName = "users_by_email",
    indexField = "email",
    consistency = LookupConsistency.BATCH
)
```

---

## Schema Modes (`kandra-ktor`)

| Mode | Behaviour |
|---|---|
| `SchemaMode.AUTO_CREATE` | `CREATE TABLE IF NOT EXISTS` on startup |
| `SchemaMode.AUTO_MIGRATE` | `CREATE TABLE IF NOT EXISTS` + `ALTER TABLE ADD` for new columns; warns on unmapped Scylla columns |
| `SchemaMode.VALIDATE` | Verifies every entity column exists in Scylla; throws on mismatch |
| `SchemaMode.NONE` | No DDL; manage schema externally |

---

## Ktor Plugin (`kandra-ktor`)

### Basic setup

```kotlin
install(Kandra) {
    contactPoints = "localhost:9042"
    keyspace      = "myapp"
    localDatacenter = "datacenter1"
    schemaMode    = SchemaMode.AUTO_CREATE
    register(User::class, Wallet::class)
}
```

### Full config surface

```kotlin
install(Kandra) {
    contactPoints       = "localhost:9042"
    keyspace            = "myapp"
    localDatacenter     = "datacenter1"
    autoCreateKeyspace  = true
    schemaMode          = SchemaMode.AUTO_MIGRATE
    register(User::class)

    pool {
        requestTimeoutMillis    = 10_000
        connectionTimeoutMillis = 5_000
    }

    auth {
        provider            = KandraAuth.plainText("user", "pass")
        refreshIntervalSeconds = 3600
    }

    consistency {
        read  = KandraConsistency.LOCAL_QUORUM
        write = KandraConsistency.LOCAL_QUORUM
    }

    retry {
        maxAttempts    = 5
        backoffMillis  = 200
        maxBackoffMillis = 2000
    }

    debug {
        logQueries       = true
        logSlowQueriesMs = 500
        logBatches       = false
    }

    // Graceful shutdown: stops accepting new queries; waits for in-flight to drain
    shutdown {
        graceful        = true
        drainTimeoutMs  = 5000
    }

    // Health check: GET /kandra/health → {"status":"UP"} or {"status":"DOWN"}
    healthCheck = true

    // Tombstone warning: logs WARN when deleteAll() targets more than N rows
    tombstoneWarnThreshold = 1000

    // Batch limits
    batchWarnThresholdKb = 5
    batchMaxChunkSize    = 100
    batchAutoChunk       = true

    // Validation hook
    validate<User> { user ->
        buildList {
            if (user.email.isBlank()) add(KandraValidationError("email", "must not be blank"))
        }
    }

    eventListener = object : KandraEventListener { ... }
}
```

---

## Repositories

### `KandraRepository<T>` (blocking)

```kotlin
val users = application.kandra.repository<User>()

users.save(user)
users.saveAll(listOf(u1, u2))
users.saveWithNulls(user)          // writes actual NULL tombstones
users.saveIfNotExists(user)        // LWT IF NOT EXISTS
users.update(old, new)             // LWT if @Version; otherwise upsert
users.updateForce(user)            // skip @Version check
users.delete(user)                 // respects @SoftDelete
users.deleteAll(entities)          // warns if > tombstoneWarnThreshold
users.deleteById(uuid)
users.deleteBy { where { "email" eq "x@y.com" } }

users.findById(uuid)
users.find { where { "email" eq "x@y.com" } }
users.findAll { where { "status" eq "active" } }
users.findPage(pageSize = 20, pageToken = token) { ... }
users.exists { where { "email" eq "x@y.com" } }

users.raw("SELECT * FROM users WHERE status = ?", "active")
users.rawQuery(KandraRawQuery.cql("SELECT * FROM users WHERE status = ?").bind("active").build())

users.append(user, User::tags, setOf("new-tag"))
users.remove(user, User::tags, setOf("old-tag"))
users.put(user, User::meta, mapOf("k" to "v"))
users.increment(UserStat::views, mapOf("userId" to id), by = 1)
```

### `KandraSuspendRepository<T>` (coroutines — preferred in Ktor routes)

All methods are `suspend` equivalents of the above.

---

## Batch scope

Collect multiple saves and deletes into a single LOGGED batch:

```kotlin
application.kandra.batch {
    userRepo.save(user)
    walletRepo.save(wallet)
}
```

---

## UNSET vs NULL

By default Kandra uses `UNSET` binding for nullable `null` fields — no tombstone is written. Use `saveWithNulls()` to intentionally write `NULL` (e.g. to overwrite a previously set value with empty).

---

## Raw queries (injection-safe)

```kotlin
// Builder enforces parameterisation
val rows = repo.rawQuery(
    KandraRawQuery.cql("SELECT * FROM users WHERE status = ?")
        .bind("active")
        .build()
)
```

Calling `raw(cql)` with no bind parameters and a CQL string containing literals logs a `WARN`.

---

## Schema Migration (`kandra-migrate`)

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
- Checksums (SHA-256) are stored in `kandra_migrations` and validated on re-run; mismatch throws `KandraMigrationException`.
- `runner.history()` returns a list of applied migrations.

---

## Health check

When `healthCheck = true`, a route is registered:

```
GET /kandra/health
→ 200 {"status":"UP"}
→ 503 {"status":"DOWN"}
```

---

## Graceful shutdown

When `shutdown { graceful = true }` is set, the `ApplicationStopping` event:

1. Sets `isShuttingDown = true` on the `BatchEngine` — all new queries throw immediately.
2. Waits up to `drainTimeoutMs` for `inFlightCount` to reach zero.
3. Logs a warning if queries are still in-flight after the timeout.
4. On `ApplicationStopped`, closes the `CqlSession`.

---

## DI Integrations

- **`kandra-kodein`** — `KandraKodeinModule` for Kodein DI
- **`kandra-koin`** — `kandraModule()` for Koin

---

## Multi-DC (`kandra-multidc`)

Configures per-DC consistency levels and local-DC routing policies for multi-region deployments.

---

## Testing (`kandra-test`)

- `FakeKandraSession` — in-memory CQL session for unit tests
- `KandraTestUtils` — schema assertion helpers
- `KandraTestcontainers` — ready-to-use ScyllaDB Testcontainers setup
