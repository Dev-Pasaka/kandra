# Kandra User Guide

Kandra is a Kotlin ORM for ScyllaDB/Cassandra, shipped as a Ktor `ApplicationPlugin`. It maps Kotlin data classes to ScyllaDB tables and provides type-safe repositories, automatic schema management, optimistic locking, soft delete, query caching, and more.

> **Version:** 0.4.6 · **Kotlin:** 2.1.21 · **Driver:** DataStax Java Driver 4.17.0

---

## Feature Index

| Feature | Category | Description |
|---|---|---|
| [`@ScyllaTable`](#scyllatable) | Annotation | Maps a data class to a table |
| [`@PartitionKey`](#partitionkey--clusteringkey) | Annotation | Defines partition key column(s) |
| [`@ClusteringKey`](#partitionkey--clusteringkey) | Annotation | Defines clustering column(s) with ordering |
| [`@Column`](#column) | Annotation | Renames a field in CQL |
| [`@Version`](#version--optimistic-locking) | Annotation | Optimistic locking via LWT |
| [`@SoftDelete`](#softdelete) | Annotation | TTL-based soft delete instead of hard DELETE |
| [`@Sensitive`](#sensitive) | Annotation | Masks field in all log output |
| [`@CacheResult`](#cacheresult) | Annotation | In-process Caffeine cache on `findById` |
| [`@Ttl`](#ttl) | Annotation | Row-level TTL on insert |
| [`@CreatedAt` / `@UpdatedAt`](#createdat--updatedat) | Annotation | Auto-populated timestamps |
| [`@Transient`](#transient) | Annotation | Excludes a field from CQL |
| [`@Counter`](#counter) | Annotation | Declares a counter column |
| [`@GeneratedUuid`](#generateduuid) | Annotation | Auto-populates a `UUID` field with a collision-resistant value on insert |
| [`@LookupIndex`](#lookupindex) | Annotation | Denormalised secondary table |
| [`@SecondaryIndex`](#secondaryindex) | Annotation | CQL `CREATE INDEX` on a column |
| [`@ReadConsistency` / `@WriteConsistency`](#readconsistency--writeconsistency) | Annotation | Per-entity default consistency override |
| [Plugin installation](#plugin-installation) | Config | Connects to ScyllaDB via the Ktor plugin |
| [Schema modes](#schema-modes) | Config | `AUTO_CREATE`, `AUTO_MIGRATE`, `VALIDATE`, `NONE` |
| [Connection pool](#connection-pool) | Config | Timeouts, heartbeat, requests per connection |
| [Authentication](#authentication) | Config | Plain text, env vars, credential rotation |
| [SSL / TLS](#ssl--tls) | Config | One-way and mutual TLS |
| [Consistency levels](#consistency-levels) | Config | Per-operation read/write consistency |
| [Retry policy](#retry-policy) | Config | Automatic retry with linear backoff |
| [Debug & slow query logging](#debug--slow-query-logging) | Config | Query and batch logging |
| [Health check](#health-check) | Config | `GET /kandra/health` route |
| [Graceful shutdown](#graceful-shutdown) | Config | In-flight drain before session close |
| [Batch size guard](#batch-size-guard) | Config | Warn and auto-chunk large batches |
| [Tombstone warning](#tombstone-warning) | Config | WARN when `deleteAll` generates many tombstones |
| [Metrics](#metrics) | Config | Pluggable metrics recorder |
| [Validation hook](#validation-hook) | Config | Per-entity validation before save/update |
| [Event listener](#event-listener) | Config | Hooks for connection, credential, write events |
| [`KandraRepository`](#kandrarepository) | Repository | Blocking CRUD repository |
| [`KandraSuspendRepository`](#kandrasuspendrepository) | Repository | Coroutine-based CRUD repository |
| [Pagination](#pagination) | Repository | Cursor-based page queries |
| [Collection operations](#collection-operations) | Repository | Append/remove from List/Set, put into Map |
| [Counter operations](#counter-operations-1) | Repository | Increment/decrement counter columns |
| [Raw queries](#raw-queries) | Repository | Escape hatch for arbitrary CQL |
| [Batch scope](#batch-scope) | Advanced | Collect multiple writes into one LOGGED batch |
| [UNSET vs NULL](#unset-vs-null) | Advanced | Avoid tombstones on nullable fields |
| [`saveWithNulls`](#savingwith-explicit-nulls) | Advanced | Intentionally write NULL to a column |
| [`updateForce`](#updateforce) | Advanced | Bypass `@Version` check |
| [`saveIfNotExists`](#saveifnotexists) | Advanced | Idempotent LWT insert |
| [CQL injection guard](#cql-injection-guard) | Advanced | Safe parameterised raw queries |
| [Schema migration runner](#schema-migration-runner) | Migration | Versioned, checksum-validated migrations |
| [Kodein DI integration](#kodein-di-integration) | DI | Auto-bind repos into Kodein container |
| [Koin DI integration](#koin-di-integration) | DI | Auto-bind repos into Koin container |
| [Testing utilities](#testing-utilities) | Testing | In-memory fake session and Testcontainers helper |
| [Permission validation](#permission-validation) | Operations | Startup check for SELECT/MODIFY/ALTER grants |
| [Multi-DC support](#multi-dc-support) | Advanced | DC-aware load balancing and failover |

---

## Installation

Add the BOM and the modules you need to your Gradle build:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") { from(files("gradle/libs.versions.toml")) }
    }
}
```

```kotlin
// build.gradle.kts
dependencies {
    implementation(platform("ke.co.coinx.kandra:kandra-bom:0.4.6"))
    implementation("ke.co.coinx.kandra:kandra-ktor")       // Ktor plugin (required)
    implementation("ke.co.coinx.kandra:kandra-runtime")    // repositories and batch engine
    implementation("ke.co.coinx.kandra:kandra-core")       // annotations and schema model

    // Optional modules
    implementation("ke.co.coinx.kandra:kandra-kodein")     // Kodein DI
    implementation("ke.co.coinx.kandra:kandra-koin")       // Koin DI
    implementation("ke.co.coinx.kandra:kandra-migrate")    // schema migration runner
    testImplementation("ke.co.coinx.kandra:kandra-test")   // testing utilities
}
```

---

## Plugin Installation

```kotlin
import io.kandra.ktor.Kandra
import io.kandra.ktor.SchemaMode

fun Application.configureDatabase() {
    install(Kandra) {
        contactPoints   = "localhost:9042"      // comma-separated for multiple nodes
        keyspace        = "myapp"
        localDatacenter = "datacenter1"
        schemaMode      = SchemaMode.AUTO_CREATE
        register(User::class, Wallet::class)
    }
}
```

Access the runtime anywhere in your application:

```kotlin
val kandra = application.kandra           // KandraRuntime
val session = application.kandraSession  // raw CqlSession (escape hatch)
```

---

## Annotations

### `@ScyllaTable`

Maps a Kotlin data class to a ScyllaDB table. All Kandra entities must carry this annotation.

```kotlin
@ScyllaTable(
    name           = "users",          // required — no default; Kandra never infers a table name
    gcGraceSeconds = 864000            // optional: sets gc_grace_seconds in WITH clause
)
data class User(
    @PartitionKey val id: UUID,
    val name: String,
    val email: String
)
```

Generated DDL:
```sql
CREATE TABLE IF NOT EXISTS users (
    id UUID,
    name TEXT,
    email TEXT,
    PRIMARY KEY (id)
) WITH gc_grace_seconds = 864000;
```

| Parameter | Type | Default | Description |
|---|---|---|---|
| `name` | `String` | **required** | CQL table name |
| `gcGraceSeconds` | `Int` | `-1` (use ScyllaDB's own default) | Grace period before tombstones are purged |

---

### `@PartitionKey` / `@ClusteringKey`

Define the primary key. Use `index` to order composite keys.

```kotlin
@ScyllaTable("events")
data class Event(
    @PartitionKey(index = 0) val userId: UUID,
    @PartitionKey(index = 1) val type: String,         // composite partition key
    @ClusteringKey(order = ClusteringOrder.DESC, index = 0) val createdAt: Instant,
    val payload: String
)
```

Generated DDL:
```sql
CREATE TABLE IF NOT EXISTS events (
    user_id UUID,
    type TEXT,
    created_at TIMESTAMP,
    payload TEXT,
    PRIMARY KEY ((user_id, type), created_at)
) WITH CLUSTERING ORDER BY (created_at DESC);
```

| Annotation | Parameter | Type | Default | Description |
|---|---|---|---|---|
| `@PartitionKey` | `index` | `Int` | `0` | Position among multiple `@PartitionKey` fields (composite partition key) |
| `@ClusteringKey` | `index` | `Int` | `0` | Position among multiple `@ClusteringKey` fields |
| `@ClusteringKey` | `order` | `ClusteringOrder` (`ASC`/`DESC`) | `ASC` | Sort direction for this clustering column — **not** a `Boolean` `descending` flag |

> **Rule:** Every entity must have at least one `@PartitionKey`. Clustering keys are optional and determine the within-partition ordering.

---

### `@Column`

Renames a Kotlin property to a different CQL column name.

```kotlin
data class User(
    @PartitionKey val id: UUID,
    @Column("full_name") val name: String,   // Kotlin: name → CQL: full_name
    val email: String
)
```

---

### `@Transient`

Excludes a field from being written to or read from ScyllaDB. Useful for computed or in-memory-only properties.

```kotlin
data class User(
    @PartitionKey val id: UUID,
    val firstName: String,
    val lastName: String,
    @Transient val displayName: String = "$firstName $lastName"  // never touches ScyllaDB
)
```

---

### `@Ttl`

A **class-level** annotation — not a field annotation — that sets a fixed default TTL (in seconds)
for every row inserted into the primary table. It is a static constant baked into the schema, not
read from a field on the entity, and lookup tables generated by `@LookupIndex` never inherit it.
Override per-write with the `ttlSeconds` parameter most repository write methods accept.

```kotlin
@Ttl(seconds = 3600)          // every row in this table defaults to a 1-hour TTL
@ScyllaTable("sessions")
data class Session(
    @PartitionKey val token: String,
    val userId: UUID
)
```

---

### `@CreatedAt` / `@UpdatedAt`

Auto-populated `Instant` fields. Kandra injects `Instant.now()` automatically — you never set these yourself.

```kotlin
data class User(
    @PartitionKey val id: UUID,
    val name: String,
    @CreatedAt val createdAt: Instant? = null,    // set on first save; never overwritten
    @UpdatedAt val updatedAt: Instant? = null     // overwritten on every save/update
)
```

---

### `@Counter`

Marks a `Long` field as a ScyllaDB counter column. Counter tables have restrictions:
- Every non-key column must be `@Counter`.
- Counter tables cannot use `save()` or `saveAll()` — use `increment()`/`decrement()` instead.

```kotlin
@ScyllaTable("post_stats")
data class PostStats(
    @PartitionKey val postId: UUID,
    @Counter val views: Long = 0,
    @Counter val likes: Long = 0
)
```

See [Counter operations](#counter-operations-1) for usage.

---

### `@GeneratedUuid`

Auto-populates a `UUID` field on every insert (`save`, `saveIfNotExists`, `saveAll`, and their
suspend equivalents) — the same way `@CreatedAt` is auto-populated. Any caller-supplied value is
overwritten. Works on partition keys, clustering keys, or plain columns; never touched by
`update()`/`updateForce()`, since primary-key components are immutable in Cassandra.

**Why it exists:** a Cassandra/ScyllaDB write is an upsert on partition key + clustering key — there
is no separate insert-vs-overwrite path. A clustering key derived from `Instant.now()` (or anything
no finer than millisecond resolution) can silently collide: two rows written to the same partition
in the same millisecond mean the second `save()` overwrites the first, with no exception and no
warning. `@GeneratedUuid` removes that failure mode by having Kandra generate a collision-resistant
key itself instead of relying on caller-supplied timestamps.

```kotlin
@ScyllaTable("events")
data class Event(
    @PartitionKey val streamId: UUID,
    @GeneratedUuid @ClusteringKey val eventId: UUID,   // sorts chronologically, collision-resistant
    val payload: String
)
```

| Parameter | Type | Default | Description |
|---|---|---|---|
| `strategy` | `UuidStrategy` (`TIME_ORDERED`/`RANDOM`) | `TIME_ORDERED` | `TIME_ORDERED` = UUIDv7 (sorts chronologically as a plain `uuid` column); `RANDOM` = UUIDv4, for ids that must not leak their creation time |

Validated at registration the same way `@Version` is: applying it to a non-`UUID` field throws
`KandraSchemaException` immediately, not at first insert. `KandraUuid.timeOrdered()`/`.random()` are
also usable directly outside the annotation lifecycle wherever application code needs one of these
without going through an entity's insert path.

---

### `@LookupIndex`

Declares a denormalised secondary table keyed by the annotated **property** — unlike every other
annotation on this page except `@Column`/`@Transient`, `@LookupIndex` goes on the field itself, not
the class. Kandra writes both the primary table and lookup table atomically (or eventually,
depending on `consistency`).

```kotlin
@ScyllaTable("users")
data class User(
    @PartitionKey val id: UUID,
    @LookupIndex(tableSuffix = "by_email", consistency = LookupConsistency.BATCH) val email: String,      // written in the same LOGGED batch
    @LookupIndex(tableSuffix = "by_phone", consistency = LookupConsistency.EVENTUAL) val phone: String?,  // written asynchronously after primary commit
    val name: String
)
```

`tableSuffix` is appended to the primary table's name — the example above generates
`users_by_email` and `users_by_phone`.

| Parameter | Type | Default | Description |
|---|---|---|---|
| `tableSuffix` | `String` | required | Appended to the primary table's name to form the lookup table's name |
| `consistency` | `LookupConsistency` | `BATCH` | `BATCH` = atomic with primary; `EVENTUAL` = async |

> **When to use `EVENTUAL`:** For high-write-throughput tables where strict atomicity between primary and lookup is not required. Failed eventual writes are forwarded to `KandraEventListener.onEventualWriteFailed`.
>
> `EVENTUAL` only relaxes *atomicity* with the primary write, not durability guarantees: the write still
> retries on transient errors per `retry { }`'s `retryOn` set, counts toward `inFlightCount`, and is
> rejected once graceful shutdown begins — the same protections every other write gets. See
> [Graceful shutdown](#graceful-shutdown).

---

### `@SecondaryIndex`

Creates a CQL `CREATE INDEX` on a column. Use sparingly on ScyllaDB — secondary indexes have significant read amplification costs.

```kotlin
data class User(
    @PartitionKey val id: UUID,
    @SecondaryIndex val status: String,   // CREATE INDEX IF NOT EXISTS on users(status)
    val name: String
)
```

---

### `@Version` — Optimistic Locking

Enables compare-and-swap (LWT) on every update. The field must be `Long` or `Instant`.

```kotlin
@ScyllaTable("products")
data class Product(
    @PartitionKey val id: UUID,
    val name: String,
    val price: BigDecimal,
    @Version val version: Long = 0L    // Kandra sets to 1L on first save
)
```

**How it works:**

| Operation | Behaviour |
|---|---|
| `save(product)` | Sets `version = 1L` automatically |
| `update(old, new)` | Emits `UPDATE … SET … WHERE id = ? IF version = ?`; throws `KandraOptimisticLockException` if another writer changed the version first |
| `updateForce(product)` | Skips the version check entirely |

```kotlin
try {
    repo.update(fetchedProduct, fetchedProduct.copy(price = 9.99.toBigDecimal()))
} catch (e: KandraOptimisticLockException) {
    // entity was modified concurrently — re-fetch and retry
}
```

> **Note:** LWT cannot be included in a LOGGED batch. Kandra automatically issues the LWT as a standalone statement, then writes lookup table changes in a separate batch.

> **Note — no automatic retry on transient errors.** Every other write path in Kandra retries on
> `WriteTimeoutException`/`ReadTimeoutException`/`NoNodeAvailableException` per `RetryConfig` (default:
> up to 3 attempts). `update()`/`updateSuspend()` on a `@Version` entity deliberately **do not** —
> the `IF version = ?` statement is executed exactly once. Reasoning: on a transient error, the write
> may have already been applied server-side (advancing the version) even though the client never saw
> the success response; retrying the identical conditional statement would then see `[applied] = false`
> and raise a **spurious** `KandraOptimisticLockException` — reporting "someone else changed this row"
> when actually "your own prior attempt already succeeded." Only the caller can tell those two
> situations apart. If you want retry-on-timeout semantics for a versioned update, catch the transient
> exception yourself, re-fetch the entity (to learn its true current version), and reissue `update()`
> with that freshly-read `old` — do not retry the same `(old, new)` pair blindly:
>
> ```kotlin
> try {
>     repo.update(old, new)
> } catch (e: WriteTimeoutException) {
>     val current = repo.findById(old.id)!!
>     repo.update(current, new.copy(version = current.version))
> }
> ```

---

### `@ReadConsistency` / `@WriteConsistency`

Class-level annotations that override the plugin's default read/write consistency level for every
operation against that entity's table.

```kotlin
@ReadConsistency(KandraConsistency.LOCAL_QUORUM)
@WriteConsistency(KandraConsistency.EACH_QUORUM)
@ScyllaTable("critical_balances")
data class Balance(@PartitionKey val id: UUID, val amountCents: Long)
```

| Parameter | Type | Default | Description |
|---|---|---|---|
| `level` | `KandraConsistency` | required | The consistency level to use for every read (`@ReadConsistency`) or write (`@WriteConsistency`) against this table |

**Resolution order** (highest priority first): per-call `consistency` parameter →
`@ReadConsistency`/`@WriteConsistency` → the plugin's `consistency { defaultRead / defaultWrite }`
config. See [Consistency levels](#consistency-levels).

---

### `@SoftDelete`

Replaces hard `DELETE` with `UPDATE … USING TTL`, setting a TTL on all non-key columns. The row key remains visible (as a ScyllaDB tombstone) until `gc_grace_seconds` passes.

```kotlin
@ScyllaTable("orders")
@SoftDelete(ttlSeconds = 604800)   // columns expire after 7 days
data class Order(
    @PartitionKey val id: UUID,
    val customerId: UUID,
    val total: BigDecimal,
    val status: String
)
```

After calling `repo.delete(order)`:
- Non-key columns (`customerId`, `total`, `status`) are set with TTL = 604800s.
- `findById(order.id)` still returns the row until the TTL fires and ScyllaDB removes the data.
- `@LookupIndex` rows are deliberately **kept alive**, not hard-deleted — a soft-deleted row still
  "exists" until its own TTL expires, so it must remain resolvable via its lookup index too, same as
  `findById`. On high-churn tables combining `@LookupIndex` + `@SoftDelete`, this means the lookup
  table holds more live rows than the primary table at any given time — expected, not a leak (see
  [ISS-030](issues/ISS-030-soft-delete-removes-lookup-rows.md) and
  [ISS-035](issues/ISS-035-lookupindex-softdelete-storage-growth.md)).

To distinguish live vs soft-deleted rows, add `@SoftDelete(ttlSeconds = ..., markerProperty = "isDeleted")`
with a `Boolean` field and call `repo.findActive()` — see
[`docs/features/repositories.md`](features/repositories.md#findactive-soft-delete-entities-only) and
[ISS-007](issues/ISS-007-find-active-soft-delete.md). `findActive()` queries the marker column directly
if it has `@SecondaryIndex`; otherwise it throws unless you pass `allowFullScan = true`, since answering
without an index requires `ALLOW FILTERING` — an explicit opt-in, not a silent default (see
[ISS-036](issues/ISS-036-findactive-allow-filtering-scope.md)).

---

### `@Sensitive`

Masks the field value with `***` in all Kandra log output. The actual data stored in ScyllaDB is unaffected.

```kotlin
data class User(
    @PartitionKey val id: UUID,
    val email: String,
    @Sensitive val passwordHash: String,   // logged as "***"
    @Sensitive val phoneNumber: String?
)
```

---

### `@CacheResult`

Attaches a [Caffeine](https://github.com/ben-manes/caffeine) in-process cache to `findById`. Cache entries are invalidated automatically on `save`, `update`, and `delete`.

```kotlin
@ScyllaTable("users")
@CacheResult(
    ttlSeconds = 120,    // entries expire 120 seconds after write
    maxSize    = 5000    // LRU eviction after 5000 entries
)
data class User(
    @PartitionKey val id: UUID,
    val name: String,
    val email: String
)
```

**Requirements:** Add Caffeine to your runtime classpath. Kandra uses a `compileOnly` dependency so it doesn't force Caffeine on users who don't need caching:

```kotlin
implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
```

If Caffeine is absent at runtime, Kandra logs a `WARN` and disables the cache transparently — all calls fall through to ScyllaDB.

**Per-process only.** This cache lives entirely in the JVM heap of the instance that built it —
there is no distributed invalidation channel. In a horizontally-scaled or multi-DC deployment
(typically one or more app instances per DC), a write on instance A never invalidates instance
B/C's cached copy of the same row; those instances keep serving their own stale entries until
their own `ttlSeconds` expires, independent of any consistency level configured for the write.
For tables where cross-instance freshness matters, avoid `@CacheResult`, or pair it with a short
`ttlSeconds` to bound how stale a read from another instance can be.

---

## Schema Modes

Configure how Kandra manages schema on startup via `schemaMode`:

```kotlin
install(Kandra) {
    schemaMode = SchemaMode.AUTO_CREATE   // default
}
```

| Mode | Behaviour | Use case |
|---|---|---|
| `AUTO_CREATE` | `CREATE TABLE IF NOT EXISTS` for all registered entities | Development, CI |
| `AUTO_MIGRATE` | `CREATE TABLE IF NOT EXISTS` + `ALTER TABLE ADD` for new columns; logs `ERROR` on type mismatches | Staging with evolving schemas |
| `VALIDATE` | Verifies every entity column exists in ScyllaDB; throws `KandraSchemaException` on missing columns | Production safety check |
| `NONE` | No DDL at all | Production with external migration tooling |

### `AUTO_MIGRATE` details

When `AUTO_MIGRATE` is set, Kandra:

1. Runs `CREATE TABLE IF NOT EXISTS` (idempotent).
2. Queries `system_schema.columns` to find existing columns.
3. For every entity column **not** in ScyllaDB: runs `ALTER TABLE ADD`.
4. For every entity column **in** ScyllaDB with a different type: logs `ERROR` with a fix suggestion.
5. For every ScyllaDB column **not** in the entity: logs `WARN` (data preserved, not mapped).

> **`AUTO_MIGRATE` does not support:** dropping columns, renaming columns, or changing types. Use the [schema migration runner](#schema-migration-runner) for those operations.

---

## Plugin Configuration Reference

### Connection Pool

```kotlin
install(Kandra) {
    pool {
        requestTimeoutMillis          = 5000   // per-query timeout (ms)
        connectionTimeoutMillis       = 3000   // initial connect timeout (ms)
        heartbeatIntervalSeconds      = 30     // keep-alive interval
        maxRequestsPerConnection      = 32768
    }
}
```

---

### Authentication

Kandra defaults to reading credentials from environment variables — no credentials in source code.

```kotlin
// Default: reads SCYLLA_USERNAME and SCYLLA_PASSWORD from environment
install(Kandra) { }

// Hardcoded — local dev/tests only, never production
install(Kandra) {
    auth {
        provider = KandraAuth.static("admin", "secret")
    }
}

// From files (Kubernetes/Docker secrets) — two plain-text files, not one JSON file
install(Kandra) {
    auth {
        provider = KandraAuth.fromFile("/run/secrets/scylla-username", "/run/secrets/scylla-password")
    }
}

// Custom — Vault, AWS Secrets Manager, etc.
install(Kandra) {
    auth {
        provider = KandraAuth.custom { KandraCredentials(vault.read("username"), vault.read("password")) }
    }
}

// Credential rotation (re-fetches credentials without restarting the session)
install(Kandra) {
    auth {
        provider               = KandraAuth.fromEnv()
        refreshIntervalSeconds = 3600   // refresh every hour
    }
}
```

`KandraAuth` factory functions: `fromEnv(usernameVar, passwordVar)`, `fromFile(usernamePath, passwordPath)`,
`static(username, password)`, `custom { KandraCredentials(...) }`.

---

### SSL / TLS

```kotlin
install(Kandra) {
    ssl {
        enabled              = true
        requireEncryption    = true   // refuse to start if `enabled` is ever flipped back to false
        hostnameVerification = true
        minimumTlsVersion    = "TLSv1.3"
        cipherSuites         = listOf("TLS_AES_256_GCM_SHA384")
        trustStorePath       = "/etc/kandra/truststore.jks"
        trustStorePassword   = System.getenv("TRUST_STORE_PASSWORD")

        // For mutual TLS (client certificate):
        keyStorePath     = "/etc/kandra/keystore.jks"
        keyStorePassword = System.getenv("KEY_STORE_PASSWORD")
    }
}
```

`requireEncryption` defaults to `false` (SSL itself is opt-in via `enabled`, so defaulting this to
`true` would fail startup for every non-SSL deployment) — set it explicitly when TLS must never
accidentally be left off. `minimumTlsVersion` must be one of `TLSv1`/`TLSv1.1`/`TLSv1.2`/`TLSv1.3`
(anything else throws `KandraSchemaException` at startup). All fields shown above are live and
enforced ([ISS-070](issues/ISS-070-ssl-config-dead-fields.md)).

---

### Consistency Levels

```kotlin
install(Kandra) {
    consistency {
        defaultRead  = KandraConsistency.LOCAL_QUORUM   // default: LOCAL_ONE
        defaultWrite = KandraConsistency.LOCAL_QUORUM   // default: LOCAL_QUORUM (already this)
    }
}
```

Available levels: `ANY`, `ONE`, `TWO`, `THREE`, `QUORUM`, `ALL`, `LOCAL_QUORUM`, `EACH_QUORUM`, `LOCAL_ONE`, `LOCAL_SERIAL`, `SERIAL`.

> **Read-your-writes above RF 3:** the defaults (`LOCAL_ONE` read / `LOCAL_QUORUM` write) only
> guarantee read-your-writes when `R + W ≥ RF`, which holds for `RF ≤ 3` but not higher. If your
> keyspace's replication factor is greater than 3 (common in larger multi-DC deployments), raise
> `defaultRead` — e.g. to `LOCAL_QUORUM` — accordingly. Strict Mode below does not currently check
> this for you ([ISS-075](issues/ISS-075-strict-mode-rf-consistency-math.md)).

#### Strict Mode (multi-DC `LOCAL_ONE`/`ONE` warning)

Opt-in, default `false`, **WARN-only — never throws**:

```kotlin
install(Kandra) {
    consistency {
        strictMode = true
    }
    loadBalancing {
        allowedRemoteDcs = listOf("eu-west") // marks this deployment as multi-DC
    }
}
```

When `strictMode = true` *and* `loadBalancing.allowedRemoteDcs` is non-empty, Kandra logs a WARN every
time a query resolves (after per-call override → `@ReadConsistency`/`@WriteConsistency` → these
defaults) to `LOCAL_ONE` or `ONE`. In a multi-DC deployment those levels are satisfied by a single
replica in a single datacenter, which is usually not what's intended — `LOCAL_QUORUM` is the normal
default precisely so reads/writes are acknowledged across datacenters. This never blocks or fails the
query; it only warns, so enabling it cannot break an existing deployment. The multi-DC signal
(`allowedRemoteDcs` non-empty) is picked up automatically — there's nothing else to configure beyond
`strictMode` and whatever `loadBalancing.allowedRemoteDcs` you'd already set for multi-DC failover.

---

### Retry Policy

```kotlin
install(Kandra) {
    retry {
        maxAttempts      = 5          // total attempts (including first) — default: 3
        backoffMillis    = 200        // linear backoff unit — default: 100
        maxBackoffMillis = 2000       // cap on backoff — default: 2000
        jitter           = true       // "equal jitter" randomization — default: true
        retryOn          = setOf(     // exception types that trigger a retry — REPLACES the default set below
            com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException::class,
            com.datastax.oss.driver.api.core.servererrors.ReadTimeoutException::class,
            com.datastax.oss.driver.api.core.NoNodeAvailableException::class
        )
    }
}
```

Backoff is **linear**, not exponential: `backoffMillis * (attempt + 1)`, capped at `maxBackoffMillis`
(so with the defaults: 100ms, 200ms, 300ms, ... up to 2000ms) — it does not double each attempt.
When `jitter = true` (the default), half of that computed delay is randomized ("equal jitter") so
many concurrent callers retrying the same transient failure don't retry in lockstep.

`retryOn` **replaces** the default set shown above rather than adding to it — the three types shown
(`WriteTimeoutException`, `ReadTimeoutException`, `NoNodeAvailableException`) are Kandra's actual
defaults; if you set `retryOn` yourself, include them explicitly unless you deliberately want to
retry a different set. Only statements the driver/`StatementBuilder` mark idempotent are ever
retried, regardless of `retryOn` — a plain `INSERT`, collection mutation, or counter update is never
retried even if its exception type matches, to avoid double-applying it. The `@Version` LWT update
path is never retried by this policy at all, regardless of configuration — see
[`@Version`](#version--optimistic-locking).

---

### Debug & Slow Query Logging

```kotlin
install(Kandra) {
    debug {
        logQueries       = true    // logs every CQL statement at DEBUG level
        logBatches       = true    // logs batch statement counts
        logSlowQueriesMs = 500     // logs WARN for queries > 500ms
    }
}
```

---

### Health Check

When enabled, Kandra registers `GET /kandra/health`. The handler runs `SELECT release_version FROM system.local` to confirm connectivity.

```kotlin
install(Kandra) {
    healthCheck = true   // default: true
}
```

Response:
```json
// 200 OK
{"status":"UP"}

// 503 Service Unavailable
{"status":"DOWN"}
```

---

### Graceful Shutdown

When Ktor's `ApplicationStopping` event fires, Kandra:

1. Sets `isShuttingDown = true` — all new queries throw `KandraQueryException` immediately.
2. Waits up to `drainTimeoutMs` for in-flight queries to complete.
3. Logs `WARN` if queries are still running after the timeout.
4. Closes the `CqlSession` on `ApplicationStopped`.

```kotlin
install(Kandra) {
    shutdown {
        graceful       = true   // default: true
        drainTimeoutMs = 5000   // default: 5000ms
    }
}
```

`LookupConsistency.EVENTUAL` lookup writes fired from `save()`/`update()` are drained by this same
mechanism — they're routed through the same retry/`inFlightCount`/shutdown-gate path as every
synchronous write, so they no longer risk running against an already-closed session during shutdown.
A new `EVENTUAL` write attempted after step 1 above throws the same `KandraQueryException` a
synchronous query would, surfaced via `KandraEventListener.onEventualWriteFailed` (since the write
happens on a background coroutine, not the caller's call stack).

---

### Batch Size Guard

ScyllaDB recommends keeping batches under 100KB. Kandra warns and optionally auto-chunks large `saveAll` calls.

```kotlin
install(Kandra) {
    batchWarnThresholdKb = 5      // log WARN when estimated batch > 5KB
    batchMaxChunkSize    = 100    // max statements per batch chunk
    batchAutoChunk       = true   // split automatically when limit exceeded
}
```

---

### Tombstone Warning

ScyllaDB accumulates tombstones on every hard DELETE. Kandra logs a `WARN` when `deleteAll()` targets more rows than the threshold.

```kotlin
install(Kandra) {
    tombstoneWarnThreshold = 1000   // log WARN when deleting > 1000 rows at once
}
```

> **Best practice:** Use `@SoftDelete` for high-deletion tables, and set `gcGraceSeconds` appropriately to control tombstone accumulation.

---

### Metrics

Kandra provides a `KandraMetrics` callback interface so you can bridge into any metrics backend without a mandatory Micrometer dependency.

```kotlin
install(Kandra) {
    metrics {
        enabled  = true
        recorder = KandraMetrics { table, operation, durationMs ->
            // bridge to Micrometer, Dropwizard, Prometheus, etc.
            meterRegistry
                .timer("kandra.query", "table", table, "operation", operation)
                .record(durationMs, TimeUnit.MILLISECONDS)
        }
    }
}
```

`operation` values: `save`, `saveIfNotExists`, `update`, `delete`, `saveAll`, `query`.

---

### Validation Hook

Register per-entity validators that run before every `save()` and `update()`. Throw `KandraValidationException` (collected) if validation fails.

```kotlin
install(Kandra) {
    validate<User> { user ->
        buildList {
            if (user.email.isBlank())
                add(KandraValidationError("email", "must not be blank"))
            if (!user.email.contains("@"))
                add(KandraValidationError("email", "must be a valid email address"))
            if (user.name.length < 2)
                add(KandraValidationError("name", "must be at least 2 characters"))
        }
    }
}
```

Catch in your route:
```kotlin
try {
    userRepo.save(user)
} catch (e: KandraValidationException) {
    e.errors.forEach { err -> println("${err.field}: ${err.message}") }
}
```

---

### Event Listener

Hook into Kandra's internal events for observability, alerting, or custom retry logic.

```kotlin
install(Kandra) {
    eventListener = object : KandraEventListener {
        override fun onConnectionEstablished(contactPoints: String) {
            logger.info { "Connected to $contactPoints" }
        }
        override fun onEventualWriteFailed(table: String, entity: Any, cause: Throwable) {
            alerting.send("EVENTUAL write failed on $table: ${cause.message}")
        }
        override fun onCredentialRefreshed() {
            logger.info { "Credentials rotated successfully" }
        }
        override fun onAuthFailed(contactPoints: String, cause: Throwable) {
            alerting.pagerDuty("ScyllaDB auth failure: ${cause.message}")
        }
    }
}
```

---

## Repositories

Obtain a repository from the `KandraRuntime` after plugin installation:

```kotlin
// Coroutine-safe (preferred in Ktor routes)
val users: KandraSuspendRepository<User> = application.kandra.suspendRepository<User>()

// Blocking (for background jobs or non-suspend contexts)
val users: KandraRepository<User> = application.kandra.repository<User>()
```

---

### `KandraRepository`

Blocking repository. All methods call the driver synchronously — use on background threads or dedicated thread-pool dispatchers, not on Ktor's coroutine dispatcher.

```kotlin
val repo = kandra.repository<User>()

// ── Write ──────────────────────────────────────────────────────────
repo.save(user)
repo.save(user, ttlSeconds = 3600)                        // row-level TTL override
repo.saveAll(listOf(u1, u2, u3))
repo.saveAll(listOf(u1, u2), useBatch = false)            // individual statements, no batch
repo.saveIfNotExists(user)                                // IF NOT EXISTS (LWT)
repo.saveWithNulls(user)                                  // write actual NULL (tombstone)
repo.update(oldUser, newUser)                             // respects @Version if present
repo.updateForce(user)                                    // skip @Version check
repo.delete(user)                                         // respects @SoftDelete if present
repo.deleteAll(listOf(u1, u2, u3))                       // warns if > tombstoneWarnThreshold
repo.deleteById(uuid)
repo.deleteBy { UserTable.email eq "x@example.com" }

// ── Read ───────────────────────────────────────────────────────────
val user: User? = repo.findById(uuid)
val user: User? = repo.findById(uuid, consistency = KandraConsistency.LOCAL_QUORUM)
val user: User? = repo.find { UserTable.email eq "x@example.com" }
val users: List<User> = repo.findAll { UserTable.status eq "active" }
val users: List<User> = repo.findAll(limit = 100) { UserTable.status eq "active" }
val exists: Boolean = repo.exists { UserTable.email eq "x@example.com" }
```

`UserTable` here is the `kandra-codegen`-generated type-safe column-reference object for `User` (see
the root [`README.md`](../README.md#type-safe-queries-kandra-codegen)'s "Type-Safe Queries" section)
— there is no `where { }` wrapper or raw string column names; the comparison itself
(`eq`/`gt`/`gte`/`lt`/`lte`/`isIn`) registers the predicate.

---

### `KandraSuspendRepository`

Suspend (coroutine-friendly) repository. All methods are `suspend` and use the async driver API — safe to call directly from Ktor route handlers.

```kotlin
val repo = kandra.suspendRepository<User>()

routing {
    post("/users") {
        val user = call.receive<User>()
        repo.save(user)
        call.respond(HttpStatusCode.Created)
    }
    get("/users/{id}") {
        val id = UUID.fromString(call.parameters["id"])
        val user = repo.findById(id) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(user)
    }
    put("/users/{id}") {
        val id = UUID.fromString(call.parameters["id"])
        val old = repo.findById(id) ?: return@put call.respond(HttpStatusCode.NotFound)
        val new = call.receive<User>().copy(id = id)
        repo.update(old, new)
        call.respond(HttpStatusCode.OK)
    }
    delete("/users/{id}") {
        val id = UUID.fromString(call.parameters["id"])
        val user = repo.findById(id) ?: return@delete call.respond(HttpStatusCode.NotFound)
        repo.delete(user)
        call.respond(HttpStatusCode.NoContent)
    }
}
```

All write methods and read methods on `KandraSuspendRepository` mirror `KandraRepository` exactly — prefix each call with `suspend`.

---

### Pagination

Use `findPage` for cursor-based pagination. Kandra uses the DataStax driver's paging state as an opaque token.

```kotlin
// First page
val page1: KandraPage<User> = repo.findPage(pageSize = 20) {
    UserTable.status eq "active"
}
val users: List<User>  = page1.items
val token: String?     = page1.nextPageToken   // null means no more pages
val more: Boolean      = page1.hasMore

// Subsequent pages
val page2 = repo.findPage(pageSize = 20, pageToken = token) {
    UserTable.status eq "active"
}
```

```kotlin
data class KandraPage<T>(
    val items: List<T>,
    val nextPageToken: String?,   // null when on the last page
    val hasMore: Boolean
)
```

---

### Collection Operations

Append or remove elements from `List`, `Set`, or `Map` columns without rewriting the full value.

```kotlin
// List / Set — append values
repo.append(user, User::tags, setOf("kotlin", "scylladb"))

// List / Set — remove values
repo.remove(user, User::tags, setOf("outdated-tag"))

// Map — add or update entries
repo.put(user, User::metadata, mapOf("plan" to "premium", "region" to "eu"))
```

> **Note:** These operations use `UPDATE SET col = col + ?` — they do not read the current value first.

---

### Counter Operations

Counter columns must be incremented or decremented; they cannot be set directly.

```kotlin
@ScyllaTable("post_stats")
data class PostStats(
    @PartitionKey val postId: UUID,
    @Counter val views: Long = 0,
    @Counter val likes: Long = 0
)

val statsRepo = kandra.suspendRepository<PostStats>()

statsRepo.increment(PostStats::views, mapOf("postId" to postId), by = 1)
statsRepo.increment(PostStats::likes, mapOf("postId" to postId), by = 1)
statsRepo.decrement(PostStats::likes, mapOf("postId" to postId), by = 1)
```

---

### Raw Queries

For queries that Kandra's DSL cannot express. Use `rawQuery` with the builder to prevent CQL injection:

```kotlin
// Safe: parameters are bound positionally
val rows: List<Row> = repo.rawQuery(
    KandraRawQuery.cql("SELECT * FROM users WHERE status = ? AND plan = ? ALLOW FILTERING")
        .bind("active", "premium")
        .build()
)

// Map Row → entity manually
val users = rows.map { row ->
    User(
        id    = row.getUuid("id")!!,
        name  = row.getString("name")!!,
        email = row.getString("email")!!
    )
}
```

The raw shorthand (no builder) is available but logs a `WARN` when called without parameters and the CQL string contains literals — a reminder to check for injection risk:

```kotlin
val rows = repo.raw("SELECT * FROM users LIMIT 10")   // WARN logged
val rows = repo.raw("SELECT * FROM users WHERE id = ?", uuid)  // OK
```

---

## Advanced Usage

### Batch Scope

Collect multiple saves and deletes from different repositories into a single atomic LOGGED batch,
using `saveInBatch`/`deleteInBatch` — **not** `save()`/`delete()`. Reads are not allowed inside a
batch scope and will throw.

```kotlin
application.kandra.batch {
    userRepo.saveInBatch(newUser)
    walletRepo.saveInBatch(newWallet)
    auditRepo.saveInBatch(auditEntry)
}
```

> **Why `saveInBatch`/`deleteInBatch` and not `save`/`delete`:** Kotlin always resolves a
> repository's own real `save`/`delete` member over an extension of the same name — even one
> declared inside the batch scope itself — with no compiler warning. `repo.save(entity)` inside a
> `batch { }` block would silently call the repository's normal, immediately-executing `save()`
> instead of collecting it into the batch, defeating the point of the batch with no error at all.
> Distinct names make it a compile error to reach for the wrong one.

All three writes succeed or all fail atomically. The batch is submitted when the lambda returns.

> **Limit:** A LOGGED batch is not a transaction — it guarantees atomicity within a single partition or across partitions, but not isolation from concurrent readers. Keep batches small (< 20 statements, < 5KB) for best performance.

---

### UNSET vs NULL

By default, Kandra uses `UNSET` binding for nullable fields that are `null`. ScyllaDB treats an unset column as "leave unchanged" — **no tombstone is written**.

```kotlin
data class User(
    @PartitionKey val id: UUID,
    val name: String,
    val phone: String? = null    // if null, no tombstone written on save
)

repo.save(user.copy(phone = null))   // phone column untouched in ScyllaDB
```

This is the correct default for most use cases. It avoids tombstone accumulation on partial updates.

---

### Saving with Explicit NULLs

When you intentionally want to overwrite an existing value with `NULL` (writing a tombstone), use `saveWithNulls`:

```kotlin
// Erases phone from ScyllaDB — existing value is tombstoned
repo.saveWithNulls(user.copy(phone = null))
```

> **Use sparingly.** Each `NULL` write creates a tombstone that persists for `gc_grace_seconds`. Excessive tombstones degrade read performance and compaction.

---

### `saveIfNotExists`

LWT insert that succeeds only when the row does not already exist. Returns `true` if the row was inserted, `false` if it already existed.

```kotlin
val inserted: Boolean = repo.saveIfNotExists(user)
if (!inserted) {
    // a user with this ID already exists
}

// Custom serial consistency level
val inserted = repo.saveIfNotExists(user, serialConsistency = KandraConsistency.SERIAL)
```

---

### `updateForce`

Updates without checking `@Version`. Use when you need to force an overwrite regardless of concurrent modifications (e.g. admin operations, data repairs).

```kotlin
// Skips the IF version = ? LWT check
repo.updateForce(updatedProduct)
```

---

### CQL Injection Guard

`KandraRawQuery` enforces parameterised binding. The `cql(template)` builder accepts a CQL string and `.bind(vararg values)` to attach positional parameters:

```kotlin
val query = KandraRawQuery
    .cql("SELECT * FROM users WHERE status = ? AND created_at > ?")
    .bind("active", cutoffInstant)
    .build()

val rows = repo.rawQuery(query)
```

Never build CQL by string interpolation:
```kotlin
// DANGEROUS — do not do this
val rows = repo.raw("SELECT * FROM users WHERE name = '${input}'")
```

---

## Schema Migration Runner

The `kandra-migrate` module provides versioned, checksum-validated migrations similar to Flyway or Liquibase, but written directly in Kotlin with full access to `CqlSession`.

### Defining migrations

`version`/`name` are constructor parameters on `KandraMigration`, not overridable properties, and
`up()` is a plain (non-`suspend`) function — define migrations as `object`s, per `KandraMigration`'s
own recommended pattern:

```kotlin
import io.kandra.migrate.KandraMigration
import com.datastax.oss.driver.api.core.CqlSession

object V1_CreateUsers : KandraMigration(version = 1, name = "create_users_table") {
    override fun up(session: CqlSession) {
        session.execute("""
            CREATE TABLE IF NOT EXISTS users (
                id UUID PRIMARY KEY,
                name TEXT,
                email TEXT
            )
        """.trimIndent())
    }
}

object V2_AddPhone : KandraMigration(version = 2, name = "add_phone_to_users") {
    override fun up(session: CqlSession) {
        session.execute("ALTER TABLE users ADD phone TEXT")
    }
}
```

### Running migrations

```kotlin
val runner = KandraMigrationRunner(application.kandraSession)
runner.run(V1_CreateUsers, V2_AddPhone)
```

Kandra creates a `kandra_migrations` table to track applied migrations. On subsequent runs:
- Already-applied versions are skipped.
- A checksum mismatch (migration script changed after application) throws `KandraMigrationException`.
- Migrations are applied in `version` order regardless of the order passed to `run()`.
- `run()` requires LWT support on the cluster — each migration is claimed via `INSERT ... IF NOT
  EXISTS` before it runs, so two runner instances racing the same keyspace can't both execute the
  same migration concurrently.

### Inspecting history

```kotlin
val history: List<MigrationHistory> = runner.history()
history.forEach { m ->
    println("v${m.version} — ${m.name} applied at ${m.appliedAt} (checksum: ${m.checksum}, status: ${m.status})")
}
```

`MigrationHistory` also carries `claimedAt` (when the LWT claim row was written) alongside `status`
(`CLAIMED` or `APPLIED`) — see [migrations.md](features/migrations.md) for the crash-safety design
behind them.

---

## DI Integrations

### Kodein DI Integration

```kotlin
fun Application.configureDatabase() {
    install(Kandra) {
        contactPoints = "localhost:9042"
        keyspace      = "myapp"
        register(User::class, Wallet::class)
    }
    kandraKodein()   // auto-binds repos for all registered entities
}
```

Repositories are bound by tag:

```kotlin
// In a route or service (using Kodein DI on KTor)
val userRepo by closestDI().instance<KandraSuspendRepository<*>>(tag = "UserSuspend")
val walletRepo by closestDI().instance<KandraRepository<*>>(tag = "Wallet")
```

For type-safe bindings of a specific entity outside of a Ktor context:

```kotlin
val myDiModule = DI.Module("kandra") {
    bindKandraRepository<User>(
        session = mySession,
        schema  = SchemaRegistry.get(User::class),
        scope   = myLifecycleScope
    )
}
```

---

### Koin DI Integration

```kotlin
fun Application.configureDatabase() {
    install(Koin) { modules(appModule) }
    install(Kandra) {
        contactPoints = "localhost:9042"
        keyspace      = "myapp"
        register(User::class, Wallet::class)
    }
    kandraKoin()   // loads repos into Koin container
}
```

Inject in routes:

```kotlin
val userRepo by inject<KandraSuspendRepository<*>>(named("UserSuspendRepo"))
val walletRepo by inject<KandraRepository<*>>(named("WalletRepo"))
```

---

## Testing Utilities

### In-Memory (no cluster required)

`KandraTestUtils.inMemory` provides a `FakeKandraSession` that records statements without hitting ScyllaDB. Useful for unit-testing service logic.

```kotlin
@Test
fun `save and find user`() {
    val runtime = KandraTestUtils.inMemory(User::class)
    runtime.use {                                      // AutoCloseable — cancels scope
        val repo = runtime.repository(User::class)
        val user = User(id = UUID.randomUUID(), name = "Alice", email = "alice@example.com")
        repo.save(user)
        // assert on captured statements via FakeKandraSession
    }
}
```

### Testcontainers (real ScyllaDB)

`KandraTestcontainers` manages a single Cassandra-compatible container lazily, shared across the
whole JVM — there is no `@Testcontainers`/`@Container` JUnit wiring to do yourself. Each call to
`freshKeyspace(...)` creates a new, isolated, randomly-named keyspace against that one shared
container and returns a `KandraRuntimeHandle` for it; each test gets its own keyspace without
paying to start a new container per test.

```kotlin
class UserRepositoryTest {

    private lateinit var handle: KandraRuntimeHandle

    @BeforeEach
    fun setup() {
        handle = KandraTestcontainers.freshKeyspace(User::class)
    }

    @AfterEach
    fun teardown() {
        handle.close()   // drops the isolated test keyspace and cancels the coroutine scope
    }

    @Test
    fun `save and retrieve user`() = runBlocking {
        val repo = handle.suspendRepository<User>()
        val user = User(id = UUID.randomUUID(), name = "Bob", email = "bob@example.com")
        repo.save(user)
        val found = repo.findById(user.id)
        assertEquals(user, found)
    }
}
```

Add the dependency:

```kotlin
testImplementation("ke.co.coinx.kandra:kandra-test")
testImplementation("org.testcontainers:cassandra:1.19.8")
testImplementation("org.testcontainers:junit-jupiter:1.19.8")
```

---

## Permission Validation

At startup (when `validatePermissions = true`, the default), Kandra queries `system_auth.role_permissions` to check the connecting role has the required grants:

| Permission | Required for | Missing at startup |
|---|---|---|
| `SELECT` | All read operations | Throws `KandraAuthException` immediately |
| `MODIFY` | All write operations | Throws `KandraAuthException` immediately |
| `ALTER` | `AUTO_CREATE`, `AUTO_MIGRATE` | Logs a WARN only — startup still succeeds, but the first DDL statement will fail |

```kotlin
install(Kandra) {
    validatePermissions = true   // default
}
```

If the check cannot be completed at all (common on ScyllaDB where `system_auth` is not always
accessible), Kandra logs a `DEBUG` message and continues rather than failing startup. Grant
permissions explicitly:

```cql
GRANT SELECT ON KEYSPACE myapp TO service_role;
GRANT MODIFY ON KEYSPACE myapp TO service_role;
GRANT ALTER  ON KEYSPACE myapp TO service_role;
```

---

## Multi-DC Support

`kandra-multidc` configures DC-aware load balancing and failover for multi-region deployments.

```kotlin
install(Kandra) {
    localDatacenter = "us-east-1"

    loadBalancing {
        tokenAware       = true               // route to token owner (recommended)
        dcAwareFailover  = true               // fall back to remote DCs on outage
        allowedRemoteDcs = listOf("eu-west-1", "ap-southeast-1")
        maxRemoteNodesPerRemoteDc = 2
    }

    failover {
        onLocalDcUnavailable = FailoverPolicy.RETRY_REMOTE_DC
        remoteRetryDelayMs   = 50
    }
}
```

For speculative execution (fire a second request if the first is slow):

```kotlin
install(Kandra) {
    speculativeExecution {
        enabled     = true
        delayMillis = 100     // fire second request after 100ms
        maxAttempts = 2
    }
}
```

---

## Supported Types

| Kotlin type | CQL type |
|---|---|
| `UUID` | `UUID` |
| `String` | `TEXT` |
| `Int` | `INT` |
| `Long` | `BIGINT` |
| `Boolean` | `BOOLEAN` |
| `Double` | `DOUBLE` |
| `Float` | `FLOAT` |
| `Instant` | `TIMESTAMP` |
| `LocalDate` | `DATE` |
| `ByteArray` | `BLOB` |
| `BigDecimal` | `DECIMAL` |
| `List<T>` | `LIST<T>` |
| `Set<T>` | `SET<T>` |
| `Map<K, V>` | `MAP<K, V>` |
| `Enum` subclass | `TEXT` (name) |

Custom types can be registered via `KandraCodec` — as a separate encoder and decoder (plain
functions, not a single codec object), both `@ExperimentalKandraApi`:

```kotlin
install(Kandra) {
    codec.registerEncoder(MyCustomType::class) { value -> value.toStorageForm() }
    codec.registerDecoder(MyCustomType::class) { row, columnName -> MyCustomType.fromStorageForm(row.getString(columnName)) }
}
```

---

## Exception Reference

| Exception | When thrown |
|---|---|
| `KandraOptimisticLockException` | `update()` / `updateSuspend()` on a `@Version` entity when another writer changed the version first |
| `KandraValidationException` | Entity fails a registered `KandraValidator` — contains a list of `KandraValidationError` |
| `KandraSchemaException` | Schema validation fails at startup (`VALIDATE` mode) or an unsupported type is used |
| `KandraQueryException` | Query rejected (shutting down, null partition key, counter table misuse) or all retries exhausted |
| `KandraAuthException` | Connecting role is missing required permissions and `validatePermissions = true` |
| `KandraMigrationException` | Checksum mismatch on a previously-applied migration |

---

## Quick Start Checklist

- [ ] `install(Kandra)` with `contactPoints`, `keyspace`, `localDatacenter`
- [ ] `register(MyEntity::class)` for every entity
- [ ] Pick a `schemaMode` (`AUTO_CREATE` for dev, `VALIDATE` or `NONE` for prod)
- [ ] Use `suspendRepository<T>()` in Ktor routes, `repository<T>()` for background jobs
- [ ] Add Caffeine to runtime classpath if using `@CacheResult`
- [ ] Set `shutdown { graceful = true }` in production
- [ ] Set `healthCheck = true` and wire it into your load balancer
- [ ] Set `validatePermissions = true` and grant `SELECT`, `MODIFY`, `ALTER` to your role
- [ ] In tests, call `handle.close()` / `runtime.close()` in `@AfterEach`
