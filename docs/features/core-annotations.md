# Core Annotations (`kandra-core`)

### `@ScyllaTable`
Maps a Kotlin data class to a ScyllaDB/Cassandra table.

```kotlin
@ScyllaTable(
    name = "users",
    gcGraceSeconds = 86400   // optional; -1 (default) means "use ScyllaDB's own default"
)
data class User(...)
```

### `@PartitionKey` / `@ClusteringKey`
Defines primary key components. Multiple `@PartitionKey` fields form a composite partition key.

```kotlin
@PartitionKey(index = 0)
val userId: UUID,
@ClusteringKey(order = ClusteringOrder.DESC, index = 0)
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

**Not automatically retried on transient errors.** Unlike every other write path, `update()`/
`updateSuspend()` on a `@Version` entity execute the `IF version = ?` statement exactly once — they
never participate in `RetryConfig`'s retry-on-timeout behavior (the default `retryOn` includes
`WriteTimeoutException`/`ReadTimeoutException`/`NoNodeAvailableException`). This is deliberate: if the
driver reports a transient error, the write may have already been applied server-side (the version may
already have advanced) even though the client never saw the success response. Blindly retrying the same
conditional statement would then observe `[applied] = false` and raise a spurious
`KandraOptimisticLockException` for a write that actually succeeded — a false "someone else modified
this" for what was really "you already modified this, you just didn't hear back." Only the caller can
tell those two cases apart. If you want retry-on-timeout semantics for a versioned update, catch the
transient exception yourself, re-fetch the entity's current version, and reissue `update(old, new)` with
the freshly-read `old` — do not retry the same `(old, new)` pair blindly.

```kotlin
try {
    repo.update(old, new)
} catch (e: WriteTimeoutException) {
    val current = repo.findById(old.id)!!   // re-fetch: is my write already applied, or must I redo it?
    repo.update(current, new.copy(version = current.version))
}
```

### `@SoftDelete`
Replaces hard DELETE with `UPDATE … USING TTL`. `@LookupIndex` rows are left alone — a soft-deleted
row still "exists" (queryable until its TTL expires), so it stays resolvable via its lookup index the
same way `findById` still finds it. See
[docs/issues/ISS-030-soft-delete-removes-lookup-rows.md](../issues/ISS-030-soft-delete-removes-lookup-rows.md)
for why this is intentional, not a bug.

```kotlin
@SoftDelete(ttlSeconds = 86400)
```

Optionally add `markerProperty` to enable `findActive()` — see
[repositories.md](repositories.md#findactive-soft-delete-entities-only).

```kotlin
@SoftDelete(ttlSeconds = 86400, markerProperty = "isDeleted")
data class Widget(@PartitionKey val id: UUID, val isDeleted: Boolean = false, ...)
```

**Storage cost with `@LookupIndex`:** combining `@SoftDelete` with `@LookupIndex` on the same entity
means the lookup row outlives the primary table's effectively-deleted data — it isn't removed until
the *entity's* soft-delete TTL expires, not when the primary row's non-key columns TTL out. For
high-churn tables using both, expect the lookup table's live row count to grow faster than the
primary table's at any given time. This is expected storage-cost behavior, not a bug.

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

### `@GeneratedUuid`

**Problem it solves:** a Cassandra/ScyllaDB write is an upsert on partition key + clustering key —
there is no separate insert-vs-overwrite path. If an application generates clustering-key values from
`Instant.now()` (or any other source no finer than millisecond resolution), two rows written by the same
partition in the same millisecond silently collide: the second `save()` overwrites the first, with no
exception and no warning. `@GeneratedUuid` removes that failure mode by having Kandra generate a
collision-resistant key value itself, instead of relying on caller-supplied timestamps.

Marks a `UUID` field to be auto-populated by Kandra on every INSERT (`save`, `saveIfNotExists`,
`saveWithNulls`, `saveAll`, and their suspend equivalents) — the same way `@CreatedAt` is auto-populated.
Any caller-supplied value on that field is overwritten; `@GeneratedUuid` fields are not meant to be
set manually. Works on partition keys, clustering keys, or plain columns; never touched by `update()`/
`updateForce()`, since primary-key components are immutable in Cassandra.

```kotlin
enum class UuidStrategy { TIME_ORDERED, RANDOM }
```

- `TIME_ORDERED` (default) — a UUIDv7, generated by [`KandraUuid.timeOrdered()`][KandraUuid]. UUIDv7
  places its 48-bit millisecond timestamp in the leading bytes, so plain lexicographic comparison —
  which is exactly how Cassandra's `UUIDType` comparator orders a generic `uuid` column — already sorts
  chronologically. This is the annotation's main use case: a `@ClusteringKey UUID` that sorts by
  creation time without the same-millisecond collision risk of `Instant`, and without needing Cassandra's
  separate `timeuuid` CQL type (which Kandra does not map to at all — see the type table in
  `kandra-core`'s DDL generator).
- `RANDOM` — a UUIDv4 (`KandraUuid.random()`, effectively `UUID.randomUUID()`), for cases where the id
  must not leak its creation time (e.g. externally-exposed identifiers).

```kotlin
@ScyllaTable("events")
data class Event(
    @PartitionKey val streamId: UUID,
    @GeneratedUuid @ClusteringKey val eventId: UUID,
    val payload: String
)
```

`@GeneratedUuid` is validated at registration the same way `@Version` is: applying it to a non-`UUID`
field throws `KandraSchemaException` immediately, not at first insert.

`KandraUuid` is also usable directly, outside the annotation lifecycle, wherever application code needs
a UUIDv7/UUIDv4 without going through an entity's insert path:

```kotlin
val id = KandraUuid.timeOrdered()
```

[KandraUuid]: ../../kandra-core/src/main/kotlin/io/kandra/core/KandraUuid.kt

### `@LookupIndex`
Declares a denormalised lookup table keyed by the annotated property. Unlike the other annotations
on this page, it goes on the **property** itself, not the class — the property's own value becomes
the lookup table's key.

```kotlin
@ScyllaTable("users")
data class User(
    @PartitionKey val id: UUID,
    @LookupIndex(tableSuffix = "by_email", consistency = LookupConsistency.BATCH) val email: String,
    @LookupIndex(tableSuffix = "by_phone", consistency = LookupConsistency.EVENTUAL) val phone: String?,
    val name: String
)
```

`tableSuffix` is appended to the primary table's name to form the generated table's name — the
example above generates `users_by_email` and `users_by_phone`, each `PRIMARY KEY` on the annotated
column, storing just enough of the primary key to resolve back to the primary row (see
`repositories.md` for how `findAll { UserTable.email eq "..." }` resolves through it).

| Parameter | Type | Default | Description |
|---|---|---|---|
| `tableSuffix` | `String` | required | Appended to the primary table's name to form the lookup table's name |
| `consistency` | `LookupConsistency` | `BATCH` | `BATCH` = same `LOGGED BATCH` as the primary write (atomic); `EVENTUAL` = written asynchronously after the primary commit |

> **When to use `EVENTUAL`:** for high-write-throughput tables where strict atomicity between primary
> and lookup is not required. Failed eventual writes are forwarded to
> `KandraEventListener.onEventualWriteFailed`.
>
> `EVENTUAL` only relaxes *atomicity* with the primary write, not durability guarantees: the write
> still retries on transient errors per `retry { }`'s `retryOn` set, counts toward `inFlightCount`,
> and is rejected once graceful shutdown begins — the same protections every other write gets.

**With `@SoftDelete`:** a lookup row is not removed when the entity is soft-deleted — it remains
until the entity's own soft-delete TTL expires, matching how `findById` still resolves a soft-deleted
row. On high-churn tables combining both annotations, this means the lookup table's row count grows
faster than the primary table's over time; this is expected, not a leak. See
[`@SoftDelete`](#softdelete) above and
[docs/issues/ISS-030-soft-delete-removes-lookup-rows.md](../issues/ISS-030-soft-delete-removes-lookup-rows.md).
See also [Health check & Graceful shutdown](operations.md) for the `inFlightCount`/shutdown-drain
mechanics referenced above.

### `@SecondaryIndex`
Creates a native ScyllaDB `CREATE INDEX` on the annotated column, answered directly by the index
(no two-step lookup like `@LookupIndex`). Uses scatter-gather across all nodes — only use it for
low-cardinality fields (e.g. `accountStatus`, `isVerified`) and non-hot-path queries. Kandra logs a
WARN every time a query against a `@SecondaryIndex` column executes, as a running reminder of the
cost.

```kotlin
data class User(
    @PartitionKey val id: UUID,
    @SecondaryIndex val accountStatus: String,   // CREATE INDEX IF NOT EXISTS ON users(account_status)
    val email: String
)
```

For a high-cardinality field (e.g. `email`, `userId`), use [`@LookupIndex`](#lookupindex) instead.

### `@ReadConsistency` / `@WriteConsistency`
Overrides the plugin's default read/write consistency level for every operation against this
entity's table. A per-call `consistency` parameter still wins over these; these still win over the
plugin's `consistency { defaultRead / defaultWrite }` config.

```kotlin
@ReadConsistency(KandraConsistency.LOCAL_QUORUM)
@WriteConsistency(KandraConsistency.EACH_QUORUM)
@ScyllaTable("critical_balances")
data class Balance(@PartitionKey val id: UUID, val amountCents: Long)
```

Resolution order (highest priority first): per-call parameter → `@ReadConsistency`/`@WriteConsistency`
→ `ConsistencyConfig` defaults. See [multidc.md](multidc.md) for the full consistency-level guide.
