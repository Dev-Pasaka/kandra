# Core Annotations (`kandra-core`)

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

Optionally add `markerProperty` to enable `findActive()` — see
[repositories.md](repositories.md#findactive-soft-delete-entities-only).

```kotlin
@SoftDelete(ttlSeconds = 86400, markerProperty = "isDeleted")
data class Widget(@PartitionKey val id: UUID, val isDeleted: Boolean = false, ...)
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
