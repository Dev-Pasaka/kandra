# Kandra 0.2.0-SNAPSHOT — Update Prompt for Claude Code

This is a delta update on top of the existing Kandra codebase you already built.
Do not rebuild from scratch. Apply each change to the existing modules in place.
Bump the version in `gradle.properties` from `0.1.0-SNAPSHOT` to `0.2.0-SNAPSHOT`.

---

## 1. Composite Partition Keys (`kandra-core`)

**Problem:** `@PartitionKey` currently only supports a single field. ScyllaDB supports
composite partition keys where multiple fields together form the partition key.

**Change — update `@PartitionKey` annotation:**

```kotlin
// Before
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class PartitionKey

// After
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class PartitionKey(val index: Int = 0)
```

**Change — update `TableSchema`:**

```kotlin
// Before
data class TableSchema(
    ...
    val partitionKey: ColumnSchema,
    ...
)

// After
data class TableSchema(
    ...
    val partitionKeys: List<ColumnSchema>,   // ordered by @PartitionKey(index)
    ...
)
```

**Change — update `SchemaRegistry`:**
- Collect all `@PartitionKey` fields, sort by `index`, store as `partitionKeys`
- Validate: at least one `@PartitionKey` must exist (unchanged)
- Validate: no duplicate `index` values across `@PartitionKey` fields — throw
  `KandraSchemaException("Duplicate @PartitionKey index $index on ${klass.simpleName}")`
- For backwards compatibility: a single `@PartitionKey` with no index (defaults to 0)
  still works exactly as before

**Change — update `DdlGenerator`:**
- Single partition key: `PRIMARY KEY (user_id, ...clustering...)`
- Composite partition key: `PRIMARY KEY ((user_id, chain), ...clustering...)`

**Change — update `StatementBuilder`:** bind all partition key values in WHERE clauses.

**New test entity for composite PK tests:**

```kotlin
@ScyllaTable("transactions_by_user_chain")
data class Transaction(
    @PartitionKey(index = 0) val userId: UUID,
    @PartitionKey(index = 1) val chain: String,
    @ClusteringKey(order = ClusteringOrder.DESC, index = 0)
    val createdAt: Instant,
    val amount: Double,
    val status: TransactionStatus
)
```

**New tests to add in `kandra-core`:**
- Composite PK DDL wraps partition keys in double parens: `PRIMARY KEY ((user_id, chain), created_at)`
- `SchemaRegistry` sorts composite PK fields by index correctly
- Duplicate `@PartitionKey` index throws `KandraSchemaException`
- Single `@PartitionKey(index = 0)` still works as simple PK (regression test)

---

## 2. TTL Support (`kandra-core` + `kandra-runtime`)

**New annotation in `kandra-core`:**

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Ttl(val seconds: Int)   // table-level default TTL
```

**Change — update `TableSchema`:**

```kotlin
data class TableSchema(
    ...
    val defaultTtl: Int? = null    // null means no TTL
)
```

**Change — `SchemaRegistry`:** read `@Ttl` from class, populate `defaultTtl`.

**Change — `DdlGenerator`:** append `WITH default_time_to_live = N` to primary table DDL
when `defaultTtl` is set. Lookup tables never get TTL (they must survive as long as the
primary row — omit even if entity has `@Ttl`).

**Change — `StatementBuilder`:** add overloads that accept an explicit TTL:

```kotlin
fun insertPrimary(schema: TableSchema, entity: Any, ttlSeconds: Int? = null): BoundStatement
// Use USING TTL ? in the prepared statement when ttlSeconds != null
// Fall back to schema.defaultTtl if ttlSeconds is null and defaultTtl is set
```

**Change — repository API — add ttl parameter to save:**

```kotlin
// KandraRepository
fun save(entity: T, ttlSeconds: Int? = null)

// KandraSuspendRepository
suspend fun save(entity: T, ttlSeconds: Int? = null)
```

**Usage example (OTP codes in CoinX):**

```kotlin
@ScyllaTable("otp_codes")
@Ttl(300)   // 5 minutes
data class OtpCode(
    @PartitionKey val phone: String,
    val code: String,
    val createdAt: Instant
)

// Override TTL per-save
otpRepo.save(otp, ttlSeconds = 120)   // 2 minutes for this one
```

**New tests:**
- `@Ttl` DDL appends `WITH default_time_to_live = 300`
- Lookup table DDL does NOT include TTL even when entity has `@Ttl`
- `save(entity, ttlSeconds = 120)` produces `INSERT ... USING TTL 120`
- `save(entity)` with `@Ttl(300)` on class produces `INSERT ... USING TTL 300`
- `save(entity)` with no TTL produces standard INSERT with no USING clause

---

## 3. Keyspace Auto-Creation (`kandra-ktor`)

**Problem:** The plugin assumes the keyspace already exists. In dev and CI it won't.

**Change — add to `KandraConfig`:**

```kotlin
class KandraConfig {
    ...
    var autoCreateKeyspace: Boolean = false
    var replicationStrategy: ReplicationStrategy = SimpleStrategy(replicationFactor = 1)
}

sealed class ReplicationStrategy {
    data class SimpleStrategy(val replicationFactor: Int) : ReplicationStrategy()
    data class NetworkTopologyStrategy(val dcReplicationMap: Map<String, Int>) : ReplicationStrategy()
}
```

**Change — plugin startup sequence in `kandra-ktor`:**

When `autoCreateKeyspace = true`:
1. Build `CqlSession` WITHOUT a keyspace (remove `.withKeyspace()` from builder)
2. Execute keyspace DDL:
   ```sql
   -- SimpleStrategy
   CREATE KEYSPACE IF NOT EXISTS coinx
   WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

   -- NetworkTopologyStrategy
   CREATE KEYSPACE IF NOT EXISTS coinx
   WITH replication = {'class': 'NetworkTopologyStrategy', 'datacenter1': 3};
   ```
3. Execute `USE coinx;`
4. Then proceed with table DDL as before

**Change — add `localDatacenter` to `KandraConfig` (REQUIRED FIX):**

```kotlin
class KandraConfig {
    ...
    var localDatacenter: String = "datacenter1"   // Scylla default; must be configurable
}
```

Wire into `buildCqlSession`:
```kotlin
CqlSession.builder()
    ...
    .withLocalDatacenter(config.localDatacenter)   // ADD THIS — driver 4.x requires it
    ...
```

**New tests:**
- Keyspace is created when `autoCreateKeyspace = true`
- `SimpleStrategy` DDL is correct CQL
- `NetworkTopologyStrategy` DDL maps dc names correctly
- Session connects without error when keyspace pre-exists and `autoCreateKeyspace = true`
  (IF NOT EXISTS guard)

---

## 4. Lightweight Transactions — IF NOT EXISTS (`kandra-runtime`)

**Problem:** No way to do idempotent inserts (prevent duplicate users, duplicate wallets).

**Change — `StatementBuilder`:**

```kotlin
fun insertPrimary(
    schema: TableSchema,
    entity: Any,
    ttlSeconds: Int? = null,
    ifNotExists: Boolean = false    // ADD THIS
): BoundStatement
// Generates: INSERT INTO ... VALUES ... IF NOT EXISTS
```

**Change — repository API:**

```kotlin
// Returns true if inserted, false if row already existed (LWT applied condition failed)
fun saveIfNotExists(entity: T): Boolean
suspend fun saveIfNotExists(entity: T): Boolean
```

**Implementation note:** LWT `IF NOT EXISTS` returns a result set with a `[applied]` boolean
column. Read it: `resultSet.one()?.getBoolean("[applied]") ?: false`.

**Important constraint:** LWT inserts cannot be part of a `LOGGED BATCH` with other
non-LWT statements. `saveIfNotExists` must execute the primary insert alone (no batch),
then fire lookup inserts separately after `[applied] == true`. Throw
`KandraQueryException("saveIfNotExists: primary insert was not applied — duplicate key")` 
if `[applied] == false` and skip all lookup writes.

**Usage:**

```kotlin
val inserted = userRepo.saveIfNotExists(user)
if (!inserted) throw ConflictException("Email already registered")
```

**New tests:**
- `saveIfNotExists` returns `true` on first insert
- `saveIfNotExists` returns `false` on duplicate partition key
- Lookup tables are NOT written when `[applied] == false`
- Lookup tables ARE written when `[applied] == true`

---

## 5. Pagination (`kandra-runtime`)

**Problem:** `findAll()` loads everything — unusable for transaction history at scale.

**New type in `kandra-runtime`:**

```kotlin
data class KandraPage<T>(
    val items: List<T>,
    val nextPageToken: String?,   // base64-encoded PagingState; null means last page
    val hasMore: Boolean
)
```

**Change — add paged variants to both repositories:**

```kotlin
// KandraRepository
fun findPage(
    pageSize: Int,
    pageToken: String? = null,
    block: QueryContext.() -> Unit
): KandraPage<T>

// KandraSuspendRepository
suspend fun findPage(
    pageSize: Int,
    pageToken: String? = null,
    block: QueryContext.() -> Unit
): KandraPage<T>
```

**Implementation:**
- Set `pageSize` via `statement.setPageSize(pageSize)` on the Java driver statement
- Decode `pageToken` from base64 → `PagingState` via `PagingState.fromBytes(bytes)`
- Attach via `statement.setPagingState(pagingState)`
- After execution: encode `resultSet.getExecutionInfo().getPagingState()` → base64 →
  `nextPageToken` (null if no next page)

**Usage:**

```kotlin
// First page
val page1 = txnRepo.findPage(pageSize = 20) {
    +TransactionTable.userId.eq(userId)
}

// Next page
val page2 = txnRepo.findPage(pageSize = 20, pageToken = page1.nextPageToken) {
    +TransactionTable.userId.eq(userId)
}
```

**New tests:**
- `findPage` returns correct `pageSize` items
- `nextPageToken` is non-null when more results exist
- `nextPageToken` is null on last page
- Passing `nextPageToken` from page N returns page N+1 correctly
- `hasMore` is false on last page

---

## 6. Eventual Write Failure Handling (`kandra-runtime`)

**Problem:** `EVENTUAL` lookup writes are fire-and-forget with only a log line on failure.
A failed `users_by_phone` insert silently breaks login-by-phone with no recovery path.

**New interface in `kandra-core`:**

```kotlin
fun interface KandraEventListener {
    fun onEventualWriteFailed(
        tableName: String,
        entity: Any,
        error: Throwable
    )
}
```

**Change — `KandraConfig`:**

```kotlin
class KandraConfig {
    ...
    var eventListener: KandraEventListener? = null
}
```

**Change — `BatchEngine`:** when an `EVENTUAL` write fails in `scope.launch`, call
`eventListener?.onEventualWriteFailed(tableName, entity, error)` after logging.

**Change — store listener on `Application` attributes** so `BatchEngine` can access it
via the same `AttributeKey` pattern as the session.

**Usage — CoinX can plug in alerting:**

```kotlin
install(Kandra) {
    ...
    eventListener = KandraEventListener { tableName, entity, error ->
        // send to dead letter queue, alert, metric increment etc.
        logger.error("Eventual write failed on $tableName for $entity", error)
        deadLetterQueue.publish(FailedLookupWrite(tableName, entity))
    }
}
```

**New tests:**
- `eventListener.onEventualWriteFailed` is called when an EVENTUAL write throws
- `eventListener` is NOT called for BATCH write failures (those propagate as exceptions)
- Null `eventListener` (default) does not throw — falls back to log only

---

## 7. Schema Validation Mode (`kandra-ktor`)

**Problem:** `autoCreate = true` silently ignores schema drift (new field added to entity
but column missing in Scylla).

**Change — add `validateSchema` mode to `KandraConfig`:**

```kotlin
class KandraConfig {
    ...
    var schemaMode: SchemaMode = SchemaMode.AUTO_CREATE
}

enum class SchemaMode {
    AUTO_CREATE,      // CREATE TABLE IF NOT EXISTS (current behaviour)
    VALIDATE,         // check existing tables match entity, throw on drift
    NONE              // do nothing — user manages DDL themselves
}
```

**`VALIDATE` mode behaviour at startup:**
1. For each registered entity, query `system_schema.columns` for the table
2. Compare columns in Scylla vs columns in `TableSchema`
3. If a column exists in the entity but not in Scylla →
   throw `KandraSchemaException("Column '${col.cqlName}' missing from table '${schema.tableName}' in Scylla. Run migration or set schemaMode = AUTO_CREATE.")`
4. If a column exists in Scylla but not in the entity → log a warning only (extra columns
   are non-breaking)

**Change — remove `autoCreate: Boolean`** from `KandraConfig` (replaced by `schemaMode`).
Keep backwards compatibility by mapping old `autoCreate = true` → `schemaMode = AUTO_CREATE`
with a `@Deprecated` warning.

**New tests:**
- `VALIDATE` mode passes when schema matches
- `VALIDATE` mode throws `KandraSchemaException` on missing column
- `VALIDATE` mode logs warning (not throws) on extra column in Scylla
- `NONE` mode skips all DDL and validation

---

## 8. Project Scaffolding Fixes

Apply these to the root project — they were missing from v1:

**`gradle.properties`** — pin all dependency versions explicitly:
```properties
kandraVersion=0.2.0-SNAPSHOT
kotlinVersion=2.0.0
ktorVersion=2.3.12
datastaxDriverVersion=4.17.0
coroutinesVersion=1.8.1
kodeinVersion=7.21.2
koinVersion=3.5.6
kspVersion=2.0.0-1.0.21
kotlinLoggingVersion=6.0.9
junitVersion=5.10.2
testcontainersVersion=1.19.8
```

**`.gitignore`** — add standard Kotlin/Gradle gitignore.

**`LICENSE`** — Apache 2.0.

**`kandra-bom/`** — add a new Bill of Materials module:
```kotlin
// kandra-bom/build.gradle.kts
plugins { `java-platform` }
javaPlatform { allowDependencies() }
dependencies {
    constraints {
        api("io.kandra:kandra-core:${project.version}")
        api("io.kandra:kandra-runtime:${project.version}")
        api("io.kandra:kandra-ktor:${project.version}")
        api("io.kandra:kandra-kodein:${project.version}")
        api("io.kandra:kandra-koin:${project.version}")
        api("io.kandra:kandra-codegen:${project.version}")
        api("io.kandra:kandra-test:${project.version}")
    }
}
```

Consumers then import:
```kotlin
implementation(platform("io.kandra:kandra-bom:0.2.0-SNAPSHOT"))
implementation("io.kandra:kandra-ktor")    // no version needed
implementation("io.kandra:kandra-koin")
```

**`GitHub Actions`** — add `.github/workflows/ci.yml`:
```yaml
name: CI
on: [push, pull_request]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '17', distribution: 'temurin' }
      - run: ./gradlew test --no-daemon
```

---

## 9. LIMIT and ALLOW FILTERING on Queries (`kandra-runtime`)

**Problem:** `findAll` returns every matching row with no cap. Queries on non-key fields
have no escape hatch for internal/dev tooling use cases.

**Change — `QueryContext`:** add `limit` and `allowFiltering` options:

```kotlin
class QueryContext {
    internal val predicates = mutableListOf<KandraPredicate>()
    internal var limit: Int? = null
    internal var allowFiltering: Boolean = false

    operator fun KandraPredicate.unaryPlus() { predicates.add(this) }
    fun limit(n: Int) { limit = n }
    fun allowFiltering() { allowFiltering = true }
}
```

**Change — `QueryExecutor`:** append `LIMIT ?` and/or `ALLOW FILTERING` to generated CQL
when set. `ALLOW FILTERING` must log a warning via kotlin-logging every time it is used —
it is opt-in and the developer must be aware of the performance cost.

**Change — repository API:** add `limit` shorthand:

```kotlin
fun findAll(limit: Int? = null, block: QueryContext.() -> Unit): List<T>
suspend fun findAll(limit: Int? = null, block: QueryContext.() -> Unit): List<T>
```

**Usage:**

```kotlin
// 5 most recent transactions
val recent = txnRepo.findAll(limit = 5) {
    +TransactionTable.userId.eq(id)
}

// Internal admin query — explicitly opt in to ALLOW FILTERING
val flagged = userRepo.findAll {
    +UserTable.accountStatus.eq(AccountStatus.FLAGGED)
    allowFiltering()
}
```

**New tests:**
- `limit(5)` appends `LIMIT 5` to CQL
- `allowFiltering()` appends `ALLOW FILTERING` to CQL
- `allowFiltering()` triggers a warning log
- Both can be combined in the same query

---

## 10. Delete by ID and Delete by Field (`kandra-runtime`)

**Problem:** `delete(entity)` requires a fully hydrated entity object. Most call sites
only have an ID or a lookup value.

**Change — `StatementBuilder`:** add targeted delete statements:

```kotlin
fun deleteByPartitionKeys(schema: TableSchema, vararg keyValues: Any): BoundStatement
fun deleteByLookup(lookup: LookupTableSchema, indexValue: Any): BoundStatement
```

**Change — repository API:**

```kotlin
// Delete by partition key(s) — no entity needed
fun deleteById(vararg keyValues: Any)
suspend fun deleteById(vararg keyValues: Any)

// Delete via lookup field — resolves to partition key then deletes primary + all lookups
fun deleteBy(block: QueryContext.() -> Unit)
suspend fun deleteBy(block: QueryContext.() -> Unit)
```

**`deleteBy` implementation:**
1. Resolve partition key via lookup (same two-step as `findBy`)
2. Fetch the full entity (needed to know all lookup field values for cleanup)
3. Execute `LOGGED BATCH` deleting primary row + all lookup rows

**Usage:**

```kotlin
userRepo.deleteById(userId)
userRepo.deleteBy { +UserTable.email.eq("pasaka@coinx.io") }
```

**New tests:**
- `deleteById` removes primary row and all lookup rows
- `deleteBy` on a lookup field resolves and deletes correctly
- `deleteById` on non-existent key is a no-op (no exception)

---

## 11. Collection Mutations (`kandra-runtime`)

**Problem:** Updating a `Set`, `List`, or `Map` column requires read-modify-write today,
which is a race condition. ScyllaDB supports atomic collection mutations natively.

**Change — `StatementBuilder`:** add collection mutation statements:

```kotlin
fun appendToCollection(schema: TableSchema, entity: Any, columnName: String, values: Any): BoundStatement
// Generates: UPDATE table SET col = col + ? WHERE pk = ?

fun removeFromCollection(schema: TableSchema, entity: Any, columnName: String, values: Any): BoundStatement
// Generates: UPDATE table SET col = col - ? WHERE pk = ?
```

**Change — repository API:**

```kotlin
// Append to a Set or List column
fun <V> append(entity: T, field: KProperty1<T, Collection<V>>, values: Collection<V>)
suspend fun <V> append(entity: T, field: KProperty1<T, Collection<V>>, values: Collection<V>)

// Remove from a Set or List column
fun <V> remove(entity: T, field: KProperty1<T, Collection<V>>, values: Collection<V>)
suspend fun <V> remove(entity: T, field: KProperty1<T, Collection<V>>, values: Collection<V>)

// Put into a Map column
fun <K, V> put(entity: T, field: KProperty1<T, Map<K, V>>, entries: Map<K, V>)
suspend fun <K, V> put(entity: T, field: KProperty1<T, Map<K, V>>, entries: Map<K, V>)
```

**Usage:**

```kotlin
// Add a role to user
userRepo.append(user, User::roles, setOf("vip", "early_adopter"))

// Remove a tag
userRepo.remove(user, User::tags, setOf("inactive"))

// Add wallet metadata
walletRepo.put(wallet, Wallet::metadata, mapOf("kyc_level" to "1"))
```

**New tests:**
- `append` on a `Set` column generates `SET col = col + ?`
- `remove` on a `Set` column generates `SET col = col - ?`
- `put` on a `Map` column generates `SET col = col + ?` with map literal
- Calling `append` on a non-collection field throws `KandraSchemaException`

---

## 12. Counter Table Support (`kandra-core` + `kandra-runtime`)

**Problem:** ScyllaDB `COUNTER` columns require `UPDATE ... SET col = col + ?` — not
`INSERT`. The current codec and batch engine generate wrong CQL for counter tables.

**New annotation in `kandra-core`:**

```kotlin
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Counter
```

**Validation rules (enforce in `SchemaRegistry`):**
- A table with any `@Counter` column must have ALL non-key columns annotated `@Counter`
  (ScyllaDB rule — mixed counter/non-counter tables are illegal)
- Throw `KandraSchemaException` if violated
- Counter columns map to CQL type `COUNTER` in `DdlGenerator`

**Change — `TableSchema`:**

```kotlin
data class TableSchema(
    ...
    val isCounterTable: Boolean = false
)
```

**New repository method (counter tables only):**

```kotlin
// Available on all repositories but throws KandraSchemaException if called on non-counter table
fun increment(field: KProperty1<T, Long>, partitionKeys: Map<String, Any>, by: Long = 1L)
suspend fun increment(field: KProperty1<T, Long>, partitionKeys: Map<String, Any>, by: Long = 1L)

fun decrement(field: KProperty1<T, Long>, partitionKeys: Map<String, Any>, by: Long = 1L)
suspend fun decrement(field: KProperty1<T, Long>, partitionKeys: Map<String, Any>, by: Long = 1L)
```

**`save()` on a counter table throws `KandraQueryException`** — counter tables cannot use
INSERT. The developer must use `increment`/`decrement` only.

**Usage:**

```kotlin
@ScyllaTable("chain_stats")
data class ChainStats(
    @PartitionKey val chain: String,
    @Counter val totalTransactions: Long,
    @Counter val totalVolumeUsd: Long
)

statsRepo.increment(ChainStats::totalTransactions, mapOf("chain" to "BASE"), by = 1L)
statsRepo.increment(ChainStats::totalVolumeUsd, mapOf("chain" to "BASE"), by = amountUsd)
```

**New tests:**
- Counter table DDL uses `COUNTER` CQL type
- `increment` generates `UPDATE ... SET col = col + ?`
- `decrement` generates `UPDATE ... SET col = col - ?`
- Mixed counter/non-counter table throws `KandraSchemaException` at registration
- `save()` on a counter table throws `KandraQueryException`

---

## 13. Extensible Codec + BigDecimal Support (`kandra-runtime`)

**Problem:** `KandraCodec` is an `object` — users cannot register custom type mappings.
`BigDecimal` (essential for crypto amounts in CoinX) is missing from the default map.

**Change — refactor `KandraCodec` from `object` to `class`:**

```kotlin
class KandraCodec {
    private val customEncoders = ConcurrentHashMap<KClass<*>, (Any) -> Any?>()
    private val customDecoders = ConcurrentHashMap<KClass<*>, (Row, String) -> Any?>()

    fun <T : Any> registerEncoder(klass: KClass<T>, encoder: (T) -> Any?) {
        customEncoders[klass] = encoder as (Any) -> Any?
    }

    fun <T : Any> registerDecoder(klass: KClass<T>, decoder: (Row, String) -> T?) {
        customDecoders[klass] = decoder as (Row, String) -> Any?
    }

    fun encode(value: Any?, type: KType): Any?
    fun decode(row: Row, column: ColumnSchema): Any?
}
```

**Change — `KandraConfig`:** expose codec for customisation:

```kotlin
class KandraConfig {
    ...
    val codec: KandraCodec = KandraCodec()
}
```

**Change — add `BigDecimal` to default codec:**

| Kotlin | CQL |
|---|---|
| `BigDecimal` | `DECIMAL` |

**Change — store `KandraCodec` instance on `Application` attributes** alongside the session
so `StatementBuilder` and `QueryExecutor` both use the same configured instance.

**Usage — CoinX custom type:**

```kotlin
install(Kandra) {
    ...
    codec.registerEncoder(WalletAddress::class) { it.value }         // String in Scylla
    codec.registerDecoder(WalletAddress::class) { row, col -> WalletAddress(row.getString(col)!!) }
}
```

**New tests:**
- `BigDecimal` encodes to `DECIMAL` CQL type in DDL
- `BigDecimal` round-trips correctly through encode/decode
- Custom encoder is called for registered type
- Custom decoder is called for registered type
- Unregistered unknown type still throws `KandraSchemaException`

---

## 14. `@CreatedAt` / `@UpdatedAt` Auto-fill (`kandra-core` + `kandra-runtime`)

**Problem:** Timestamps are set manually in service layer — error-prone and repetitive.

**New annotations in `kandra-core`:**

```kotlin
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class CreatedAt   // set to Instant.now() on INSERT only; never updated

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class UpdatedAt   // set to Instant.now() on every INSERT and UPDATE
```

**Validation (SchemaRegistry):**
- `@CreatedAt` / `@UpdatedAt` must be on an `Instant` field — throw `KandraSchemaException` otherwise
- At most one `@CreatedAt` and one `@UpdatedAt` per entity

**Change — `TableSchema`:**

```kotlin
data class TableSchema(
    ...
    val createdAtColumn: ColumnSchema? = null,
    val updatedAtColumn: ColumnSchema? = null
)
```

**Change — `BatchEngine`:** before building insert/update statements, inject `Instant.now()`
into the entity's `@CreatedAt` field (insert only) and `@UpdatedAt` field (insert + update)
via reflection. Since entities are `data class`, use `copy()` via reflection to produce a
new instance with the timestamp set — never mutate the original.

**Usage:**

```kotlin
@ScyllaTable("users")
data class User(
    @PartitionKey val userId: UUID,
    val email: String,
    @CreatedAt val createdAt: Instant = Instant.EPOCH,   // default ignored; auto-set on save
    @UpdatedAt val updatedAt: Instant = Instant.EPOCH
)

userRepo.save(user)   // createdAt and updatedAt set automatically
userRepo.update(old, new)  // updatedAt refreshed; createdAt unchanged
```

**New tests:**
- `save()` sets `@CreatedAt` to approximately `Instant.now()`
- `save()` sets `@UpdatedAt` to approximately `Instant.now()`
- `update()` refreshes `@UpdatedAt`
- `update()` does NOT change `@CreatedAt`
- `@CreatedAt` on non-`Instant` field throws `KandraSchemaException`

---

## 15. Connection Pool Configuration (`kandra-ktor`)

**Problem:** No way to tune driver connection pool settings — critical under CoinX load.

**Change — add `pool` block to `KandraConfig`:**

```kotlin
class KandraConfig {
    ...
    val pool: PoolConfig = PoolConfig()
}

class PoolConfig {
    var localRequestsPerConnection: Int = 1024
    var maxRequestsPerConnection: Int = 32768
    var heartbeatIntervalSeconds: Int = 30
    var requestTimeoutMillis: Long = 5000    // default Java driver is 2000 — too low for cold starts
}
```

**Change — wire into `buildCqlSession`:**

```kotlin
CqlSession.builder()
    ...
    .withConfigLoader(
        DriverConfigLoader.programmaticBuilder()
            .withInt(DefaultDriverOption.CONNECTION_MAX_REQUESTS, config.pool.maxRequestsPerConnection)
            .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofMillis(config.pool.requestTimeoutMillis))
            .withDuration(DefaultDriverOption.HEARTBEAT_INTERVAL, Duration.ofSeconds(config.pool.heartbeatIntervalSeconds))
            .build()
    )
    .build()
```

**Usage:**

```kotlin
install(Kandra) {
    contactPoints = "scylla:9042"
    keyspace = "coinx"
    pool {
        maxRequestsPerConnection = 32768
        requestTimeoutMillis = 10_000
    }
}
```

**New tests:**
- Default `requestTimeoutMillis` is 5000 (not the driver default of 2000)
- Pool config values are applied to the built session's driver config

---

## 16. `saveAll` Batch Insert (`kandra-runtime`)

**Problem:** Inserting multiple entities one-by-one is slow and generates N round trips.

**Change — repository API:**

```kotlin
// UNLOGGED BATCH for same-partition rows; LOGGED BATCH for cross-partition
fun saveAll(entities: List<T>, useBatch: Boolean = true)
suspend fun saveAll(entities: List<T>, useBatch: Boolean = true)
```

**Implementation:**
- When `useBatch = true`: collect all primary inserts + BATCH lookup inserts into a single
  `UNLOGGED BatchStatement` for same-partition rows. For cross-partition inserts use
  `LOGGED BatchStatement`.
- When `useBatch = false`: execute each `save()` individually (useful when list is large
  and batching would exceed the driver's batch size limit)
- Log a warning when `entities.size > 100` suggesting `useBatch = false`

**Usage:**

```kotlin
walletRepo.saveAll(listOf(wallet1, wallet2, wallet3))
```

**New tests:**
- `saveAll` with 3 entities executes one batch (not 3 separate statements)
- All lookup tables are written for all entities
- `saveAll` with empty list is a no-op

---

## 17. `exists()` Check (`kandra-runtime`)

**Problem:** Checking for existence requires a full `SELECT *` today — wasteful.

**Change — repository API:**

```kotlin
fun exists(block: QueryContext.() -> Unit): Boolean
suspend fun exists(block: QueryContext.() -> Unit): Boolean
```

**Implementation:** generate `SELECT <partition_key> FROM <table> WHERE ... LIMIT 1`.
Returns `true` if `resultSet.one() != null`. Never fetches non-key columns.

**Usage:**

```kotlin
val emailTaken = userRepo.exists { +UserTable.email.eq("pasaka@coinx.io") }
if (emailTaken) throw ConflictException("Email already in use")
```

**New tests:**
- `exists` returns `true` when row is present
- `exists` returns `false` when row is absent
- `exists` generates `SELECT partition_key ... LIMIT 1` (not `SELECT *`)

---

## Updated Build Order for 0.2.0

Apply in this order — each step must pass `./gradlew test` before proceeding:

1. `gradle.properties` version bump to `0.2.0-SNAPSHOT` + dependency pins
2. `@PartitionKey(index)` + composite PK — `kandra-core` + `kandra-runtime`
3. `@Ttl` + per-save TTL — `kandra-core` + `kandra-runtime`
4. `localDatacenter` + keyspace auto-creation + pool config — `kandra-ktor`
5. `saveIfNotExists` LWT — `kandra-runtime`
6. Pagination `findPage` — `kandra-runtime`
7. `KandraEventListener` eventual write failure hook — `kandra-core` + `kandra-runtime` + `kandra-ktor`
8. `SchemaMode` validation — `kandra-ktor`
9. LIMIT + ALLOW FILTERING — `kandra-runtime`
10. `deleteById` + `deleteBy` — `kandra-runtime`
11. Collection mutations (`append`, `remove`, `put`) — `kandra-runtime`
12. Counter table support (`@Counter`) — `kandra-core` + `kandra-runtime`
13. Extensible codec + `BigDecimal` — `kandra-runtime` + `kandra-ktor`
14. `@CreatedAt` / `@UpdatedAt` auto-fill — `kandra-core` + `kandra-runtime`
15. `saveAll` batch insert — `kandra-runtime`
16. `exists()` — `kandra-runtime`
17. `kandra-bom` module scaffold
18. `.gitignore`, `LICENSE`, GitHub Actions CI
19. Update `README.md` — cover all new features with examples

---

Run `./gradlew test` after each step. Do not proceed if tests are red.
