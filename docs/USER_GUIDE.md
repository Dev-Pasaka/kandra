# Kandra User Guide

Kandra is a Kotlin ORM for ScyllaDB/Cassandra, shipped as a Ktor `ApplicationPlugin`. It maps Kotlin data classes to ScyllaDB tables and provides type-safe repositories, automatic schema management, optimistic locking, soft delete, query caching, and more.

> **Version:** 0.4.4 · **Kotlin:** 2.1.21 · **Driver:** DataStax Java Driver 4.17.0

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
| [`@LookupTable`](#lookuptable) | Annotation | Denormalised secondary table |
| [`@SecondaryIndex`](#secondaryindex) | Annotation | CQL `CREATE INDEX` on a column |
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
    implementation(platform("ke.co.coinx.kandra:kandra-bom:0.4.4"))
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
    tableName      = "users",          // defaults to snake_case class name if omitted
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
| `tableName` | `String` | snake_case class name | CQL table name |
| `gcGraceSeconds` | `Int` | `-1` (driver default) | Grace period before tombstones are purged |

---

### `@PartitionKey` / `@ClusteringKey`

Define the primary key. Use `order` to handle composite keys.

```kotlin
@ScyllaTable("events")
data class Event(
    @PartitionKey(order = 0) val userId: UUID,
    @PartitionKey(order = 1) val type: String,         // composite partition key
    @ClusteringKey(order = 0, descending = true) val createdAt: Instant,
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

| Parameter | Type | Default | Description |
|---|---|---|---|
| `order` | `Int` | `0` | Position when there are multiple keys |
| `descending` | `Boolean` | `false` | (`@ClusteringKey` only) Reverse sort order |

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

Sets a per-row time-to-live in seconds. The value is read from the annotated field at insert time.

```kotlin
data class Session(
    @PartitionKey val token: String,
    val userId: UUID,
    @Ttl val ttlSeconds: Int = 3600    // row expires after 1 hour
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

### `@LookupTable`

Declares a denormalised secondary table keyed by a different field. Kandra writes both the primary table and lookup table atomically (or eventually, depending on `consistency`).

```kotlin
@ScyllaTable("users")
@LookupTable(
    tableName   = "users_by_email",
    indexField  = "email",
    consistency = LookupConsistency.BATCH      // written in the same LOGGED batch
)
@LookupTable(
    tableName   = "users_by_phone",
    indexField  = "phone",
    consistency = LookupConsistency.EVENTUAL   // written asynchronously after primary commit
)
data class User(
    @PartitionKey val id: UUID,
    val email: String,
    val phone: String?,
    val name: String
)
```

| Parameter | Type | Default | Description |
|---|---|---|---|
| `tableName` | `String` | required | Name of the lookup table |
| `indexField` | `String` | required | Property name to use as the lookup key |
| `consistency` | `LookupConsistency` | `BATCH` | `BATCH` = atomic with primary; `EVENTUAL` = async |

> **When to use `EVENTUAL`:** For high-write-throughput tables where strict atomicity between primary and lookup is not required. Failed eventual writes are forwarded to `KandraEventListener.onEventualWriteFailed`.

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
- Lookup table rows are hard-deleted immediately (they hold no useful data after soft-delete).

> **Limitation:** There is currently no `findActive()` method because ScyllaDB does not expose a predicate for "TTL has not expired". If you need to distinguish live vs soft-deleted rows, add a `deletedAt: Instant?` column and filter on it yourself.

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
        localRequestsPerConnection    = 1024
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

// Explicit plain text (avoid in production)
install(Kandra) {
    auth {
        provider = KandraAuth.plainText("admin", "secret")
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

---

### SSL / TLS

```kotlin
install(Kandra) {
    ssl {
        enabled              = true
        requireEncryption    = true
        hostnameVerification = true
        trustStorePath       = "/etc/kandra/truststore.jks"
        trustStorePassword   = System.getenv("TRUST_STORE_PASSWORD")

        // For mutual TLS (client certificate):
        keyStorePath     = "/etc/kandra/keystore.jks"
        keyStorePassword = System.getenv("KEY_STORE_PASSWORD")

        minimumTlsVersion = "TLSv1.2"
    }
}
```

---

### Consistency Levels

```kotlin
install(Kandra) {
    consistency {
        read  = KandraConsistency.LOCAL_QUORUM   // default: LOCAL_ONE
        write = KandraConsistency.LOCAL_QUORUM   // default: LOCAL_QUORUM
    }
}
```

Available levels: `ANY`, `ONE`, `TWO`, `THREE`, `QUORUM`, `ALL`, `LOCAL_QUORUM`, `EACH_QUORUM`, `LOCAL_ONE`, `LOCAL_SERIAL`, `SERIAL`.

---

### Retry Policy

```kotlin
install(Kandra) {
    retry {
        maxAttempts     = 5          // total attempts (including first)
        backoffMillis   = 200        // delay before attempt 2 (doubles each retry)
        maxBackoffMillis = 2000      // cap on backoff
        retryOn         = setOf(     // exception types that trigger a retry
            com.datastax.oss.driver.api.core.connection.ConnectionInitException::class,
            com.datastax.oss.driver.api.core.DriverTimeoutException::class
        )
    }
}
```

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
repo.deleteBy { where { "email" eq "x@example.com" } }

// ── Read ───────────────────────────────────────────────────────────
val user: User? = repo.findById(uuid)
val user: User? = repo.findById(uuid, consistency = KandraConsistency.LOCAL_QUORUM)
val user: User? = repo.find { where { "email" eq "x@example.com" } }
val users: List<User> = repo.findAll { where { "status" eq "active" } }
val users: List<User> = repo.findAll(limit = 100) { where { "status" eq "active" } }
val exists: Boolean = repo.exists { where { "email" eq "x@example.com" } }
```

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
    where { "status" eq "active" }
}
val users: List<User>  = page1.items
val token: String?     = page1.nextPageToken   // null means no more pages

// Subsequent pages
val page2 = repo.findPage(pageSize = 20, pageToken = token) {
    where { "status" eq "active" }
}
```

```kotlin
data class KandraPage<T>(
    val items: List<T>,
    val nextPageToken: String?   // null when on the last page
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

Collect multiple saves and deletes from different repositories into a single atomic LOGGED batch. Only `save()` and `delete()` are allowed inside a batch scope — reads will throw.

```kotlin
application.kandra.batch {
    userRepo.save(newUser)
    walletRepo.save(newWallet)
    auditRepo.save(auditEntry)
}
```

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

```kotlin
import io.kandra.migrate.KandraMigration
import com.datastax.oss.driver.api.core.CqlSession

class V1_CreateUsers : KandraMigration() {
    override val version = 1
    override val name    = "create_users_table"

    override suspend fun up(session: CqlSession) {
        session.execute("""
            CREATE TABLE IF NOT EXISTS users (
                id UUID PRIMARY KEY,
                name TEXT,
                email TEXT
            )
        """.trimIndent())
    }
}

class V2_AddPhone : KandraMigration() {
    override val version = 2
    override val name    = "add_phone_to_users"

    override suspend fun up(session: CqlSession) {
        session.execute("ALTER TABLE users ADD phone TEXT")
    }
}
```

### Running migrations

```kotlin
val runner = KandraMigrationRunner(application.kandraSession)
runner.run(V1_CreateUsers(), V2_AddPhone())
```

Kandra creates a `kandra_migrations` table to track applied migrations. On subsequent runs:
- Already-applied versions are skipped.
- A checksum mismatch (migration script changed after application) throws `KandraMigrationException`.
- Migrations are applied in `version` order regardless of the order passed to `run()`.

### Inspecting history

```kotlin
val history: List<MigrationHistory> = runner.history()
history.forEach { m ->
    println("v${m.version} — ${m.name} applied at ${m.appliedAt} (checksum: ${m.checksum})")
}
```

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

`KandraTestcontainers` spins up a real ScyllaDB (via Cassandra container) and tears it down after the test. Each test gets an isolated keyspace.

```kotlin
@Testcontainers
class UserRepositoryTest {

    companion object {
        @Container
        val scylla: CassandraContainer<*> = KandraTestcontainers.container()
    }

    private lateinit var handle: KandraRuntimeHandle

    @BeforeEach
    fun setup() {
        handle = KandraTestcontainers.setup(scylla, User::class)
    }

    @AfterEach
    fun teardown() {
        handle.close()   // drops test keyspace and cancels coroutine scope
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

| Permission | Required for |
|---|---|
| `SELECT` | All read operations |
| `MODIFY` | All write operations |
| `ALTER` | `AUTO_CREATE`, `AUTO_MIGRATE` |

```kotlin
install(Kandra) {
    validatePermissions = true   // default
}
```

If the check cannot be completed (common on ScyllaDB where `system.local.role` is not always populated), Kandra logs an `INFO` message and continues. Grant permissions explicitly:

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

Custom types can be registered via `KandraCodec`:

```kotlin
install(Kandra) {
    codec.register(MyCustomType::class, MyCustomTypeCodec())
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
