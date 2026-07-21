---
name: kandra-core
description: Exhaustive API reference for the kandra-core module — annotations, schema model, DDL generation, exceptions, auth/consistency/metrics/validator/timestamp types. Load when writing or reviewing code that touches io.kandra.core.*.
---

# kandra-core

`kandra-core` is the annotation/schema/exception layer Kandra is built on — no Ktor, no CQL session, no
network I/O. It defines the `@ScyllaTable` annotation family, reflects entity classes into an immutable
`TableSchema`, turns that schema into CQL DDL strings, and declares every exception type and pluggable
interface (`KandraAuthProvider`, `KandraMetrics`, `KandraEventListener`, `KandraValidator`) the rest of
Kandra consumes. Everything here is plain Kotlin reflection (`kotlin.reflect`) — no KSP, no codegen; that
lives in other modules.

Package map: `io.kandra.core` (top-level types), `io.kandra.core.annotations`, `io.kandra.core.exception`,
`io.kandra.core.schema`.

## Entity annotations — `io.kandra.core.annotations`

All are `RUNTIME` retention, read via `kotlin.reflect.full.findAnnotation` in `SchemaRegistry`.

| Annotation | Target | Signature | Notes |
|---|---|---|---|
| `@ScyllaTable` | Class | `(name: String, gcGraceSeconds: Int = -1)` | Required — `SchemaRegistry.register` throws `KandraSchemaException` if absent. `gcGraceSeconds = -1` (the default) means "omit `WITH gc_grace_seconds`, use ScyllaDB's default"; any value `>= 0` is emitted verbatim in DDL. |
| `@PartitionKey` | Property | `(index: Int = 0)` | At least one required per class. Use distinct `index` values for composite partition keys — columns are sorted by `index` ascending. Two properties sharing the same `index` throws `KandraSchemaException("Duplicate @PartitionKey index $idx on ...")` at registration time. |
| `@ClusteringKey` | Property | `(order: ClusteringOrder = ASC, index: Int = 0)` | Sorted by `index` ascending. Each key's `order` is emitted per-column in `CLUSTERING ORDER BY (col1 ASC, col2 DESC, ...)`. No duplicate-index validation exists for clustering keys (unlike partition keys) — collisions silently produce whatever `sortedBy` yields. |
| `@LookupIndex` | Property | `(tableSuffix: String, consistency: LookupConsistency = BATCH)` | Generates schema for a `{tableName}_{tableSuffix}` denormalized lookup table. Two properties on the same class with the same `tableSuffix` throws `KandraSchemaException` mentioning "duplicate" at registration. |
| `@Column` | Property | `(name: String)` | Overrides the CQL column name. Without it, the property name is converted via `SchemaRegistry.camelToSnake`. |
| `@Transient` | Property | no args | Excluded from `columns`, `partitionKeys`/`clusteringKeys` filtering (a transient property annotated `@PartitionKey` is still collected into `partitionKeys` for key-index validation purposes, but is filtered out of `clusteringKeys`/`lookupTables`/`secondaryIndexes` via explicit `&& !it.isTransient` checks — see Gotchas). |
| `@Ttl` | Class | `(seconds: Int)` | Populates `TableSchema.defaultTtl`; emitted as `WITH default_time_to_live = N`. Lookup tables never inherit it — `DdlGenerator.lookupTable` has no TTL clause at all. |
| `@Counter` | Property | no args | Marks a ScyllaDB `COUNTER` column. If **any** non-key column in a class is `@Counter`, **all** non-key columns must be — `SchemaRegistry` throws `KandraSchemaException` on a mix ("mixes @Counter and non-@Counter columns"). Partition keys, clustering keys, `@Transient`, `@CreatedAt`, `@UpdatedAt` columns are excluded from this check regardless of `@Counter`. |
| `@CreatedAt` | Property (`Instant`) | no args | Must be on an `Instant`-typed property or registration throws `KandraSchemaException("...must be an Instant field.")`. At most one per class (`KandraSchemaException` on a second one). |
| `@UpdatedAt` | Property (`Instant`) | no args | Same `Instant`-only and at-most-one rules as `@CreatedAt`, validated identically and independently. |
| `@SecondaryIndex` | Property | no args | Native CQL `CREATE INDEX`. Collected into `TableSchema.secondaryIndexes` (excluding `@Transient` properties). |
| `@ReadConsistency` / `@WriteConsistency` | Class | `(level: KandraConsistency)` | Not read by `SchemaRegistry` or `DdlGenerator` at all — these two files only declare the annotations. Consumed elsewhere (the Ktor/repository layer) for consistency resolution. |
| `@Version` | Property (`Long` or `Instant`) | no args | Must be `Long` or `Instant` — any other type throws `KandraSchemaException` with the actual `KType` in the message. At most one per class. |
| `@SoftDelete` | Class | `(ttlSeconds: Int = 86400)` | Sets `TableSchema.isSoftDelete = true` and `softDeleteTtlSeconds`. `kandra-core` only records this flag; the actual "TTL instead of DELETE" behavior is implemented by the repository layer in another module. |
| `@Sensitive` | Property | no args | Sets `ColumnSchema.isSensitive = true`. `kandra-core` does not do any masking itself — that's a consumer (logger) concern. |
| `@CacheResult` | Class | `(ttlSeconds: Int = 60, maxSize: Long = 1000)` | Populates `TableSchema.cacheConfig` as a `CacheResultConfig(ttlSeconds, maxSize)`. No Caffeine dependency here — just the config record. |

### Supporting enums

```kotlin
enum class ClusteringOrder { ASC, DESC }              // @ClusteringKey.order
enum class LookupConsistency { BATCH, EVENTUAL }       // @LookupIndex.consistency
```

Both map to `TEXT` when used as an entity column type themselves (any `Enum` subclass does — see
"Kotlin → CQL type mapping" below).

## Schema model — `io.kandra.core.schema`

All immutable `data class`es. Nothing here does validation — `SchemaRegistry` builds and validates them.

```kotlin
data class ColumnSchema(
    val propertyName: String,
    val cqlName: String,
    val type: KType,
    val isPartitionKey: Boolean = false,
    val clusteringKey: ClusteringKeySchema? = null,
    val lookupIndex: LookupIndexSchema? = null,
    val isTransient: Boolean = false,
    val isCounter: Boolean = false,
    val isCreatedAt: Boolean = false,
    val isUpdatedAt: Boolean = false,
    val isSecondaryIndex: Boolean = false,
    val isSensitive: Boolean = false,
    val isVersion: Boolean = false
)

data class ClusteringKeySchema(val order: ClusteringOrder, val index: Int)

data class LookupIndexSchema(val tableName: String, val consistency: LookupConsistency)
// NOTE: `tableName` here is already the *composed* name ("{table}_{suffix}"),
// not the raw `tableSuffix` from the annotation.

data class TableSchema(
    val entityClass: KClass<*>,
    val tableName: String,
    val partitionKeys: List<ColumnSchema>,      // ordered by @PartitionKey(index)
    val clusteringKeys: List<ColumnSchema>,     // ordered by @ClusteringKey(index)
    val columns: List<ColumnSchema>,            // regular (non-key, non-transient) columns
    val lookupTables: List<LookupTableSchema>,
    val defaultTtl: Int? = null,
    val isCounterTable: Boolean = false,
    val createdAtColumn: ColumnSchema? = null,
    val updatedAtColumn: ColumnSchema? = null,
    val secondaryIndexes: List<ColumnSchema> = emptyList(),
    val versionColumn: ColumnSchema? = null,
    val isSoftDelete: Boolean = false,
    val softDeleteTtlSeconds: Int? = null,
    val gcGraceSeconds: Int? = null,             // null unless @ScyllaTable.gcGraceSeconds >= 0
    val cacheConfig: CacheResultConfig? = null
)

data class LookupTableSchema(
    val tableName: String,
    val indexColumn: ColumnSchema,                    // the @LookupIndex-annotated column itself
    val partitionKeyColumns: List<ColumnSchema>,       // primary table's partition keys, for reconstructing the PK
    val consistency: LookupConsistency
)

data class CacheResultConfig(val ttlSeconds: Int, val maxSize: Long)
```

Note `TableSchema.columns` (the "regular columns" list) does **not** include the `@LookupIndex` column
itself — `SchemaRegistry.buildSchema` derives `regularColumns` by filtering out anything that's a partition
key, clustering key, or transient, but the lookup-annotated column ends up in both `columns` (if not
otherwise excluded) *and* as `LookupTableSchema.indexColumn`. In `User` from the tests, `email`/`phone` are
`@LookupIndex` columns that also appear in `schema.columns` — `DdlGenerator.primaryTable` de-dupes them via
`.distinctBy { it.cqlName }` when assembling the primary table's column list.

## `SchemaRegistry` — `io.kandra.core.SchemaRegistry` (`@InternalKandraApi`)

```kotlin
object SchemaRegistry {
    fun <T : Any> register(klass: KClass<T>): TableSchema
    fun get(klass: KClass<*>): TableSchema
    fun getOrNull(klass: KClass<*>): TableSchema?
    fun all(): List<TableSchema>
    fun clear()
    internal fun camelToSnake(name: String): String
}
```

Thread-safe, backed by a `ConcurrentHashMap<KClass<*>, TableSchema>`. This is process-global state — tests
must call `SchemaRegistry.clear()` in `@AfterEach`, or schemas leak across test classes.

- **`register(klass)`** — `registry.getOrPut(klass) { buildSchema(klass) }`. Idempotent: calling twice on
  the same class returns the cached schema without re-running validation. All validation (below) happens
  synchronously, inline, the first time — no lazy/deferred errors.
- **`get(klass)`** — throws `KandraSchemaException("Class '...' is not registered. Call register(...) first.")`
  if never registered. Use this when you require the schema to already exist.
- **`getOrNull(klass)`** — same lookup, returns `null` instead of throwing.
- **`all()`** — snapshot list (`.toList()`) of every registered schema, useful for bulk DDL generation at
  startup.
- **`clear()`** — wipes the entire registry. Intended for tests only.
- **`camelToSnake(name)`** — `internal`, reachable from other modules in the same source set. Regex-based:
  `Regex("([A-Z])")` replaces every uppercase letter with `_` + lowercase, then trims a leading `_`.
  `"userId"` → `"user_id"`, `"id"` → `"id"`, `"someLongPropertyName"` → `"some_long_property_name"`.
  No handling for existing underscores or consecutive capitals (e.g. `"HTTPStatus"` → `"h_t_t_p_status"`).

### `buildSchema` validation order (private, but this *is* the contract of `register`)

Reading top to bottom of the actual implementation, in order:

1. `@ScyllaTable` missing → `KandraSchemaException("Class '...' is missing @ScyllaTable annotation.")`
2. For each property: if `@CreatedAt` or `@UpdatedAt` present, its `returnType` classifier must be exactly
   `Instant::class` → else `KandraSchemaException("@CreatedAt/@UpdatedAt on '...' must be an Instant field.")`
3. No `@PartitionKey` anywhere → `KandraSchemaException("... has no @PartitionKey property. Exactly one is required.")`
4. Duplicate `@PartitionKey(index)` values → `KandraSchemaException("Duplicate @PartitionKey index $idx on ...")`
5. Two or more `@LookupIndex` properties resolving to the same composed table name → `KandraSchemaException("... has duplicate @LookupIndex table name '...' on properties: ...")`
6. Mixed `@Counter`/non-`@Counter` non-key columns → `KandraSchemaException("... mixes @Counter and non-@Counter columns. All non-key columns must be @Counter in a counter table.")`
7. More than one `@CreatedAt` → `KandraSchemaException("At most one @CreatedAt per entity (...)")`; same for `@UpdatedAt`
8. More than one `@Version` → `KandraSchemaException("At most one @Version column per entity (...)")`
9. `@Version` column type is neither `Long` nor `Instant` → `KandraSchemaException("@Version column '...' must be Long or Instant, got: ...")`

All of these are `KandraSchemaException` — there is no other exception type thrown from this file. Order
matters if a test entity violates multiple rules at once: the first check to fail wins.

```kotlin
@ScyllaTable("users")
data class User(
    @PartitionKey val userId: UUID,
    @ClusteringKey(order = ClusteringOrder.ASC, index = 0) val createdAt: Instant,
    @LookupIndex(tableSuffix = "by_email") val email: String,
    @Column("full_name") val name: String,
    @Transient val cachedToken: String? = null
)

val schema = SchemaRegistry.register(User::class)
schema.tableName            // "users"
schema.partitionKeys[0].cqlName  // "user_id"
schema.lookupTables[0].tableName // "users_by_email"
```

## `DdlGenerator` — `io.kandra.core.DdlGenerator` (`@InternalKandraApi`)

```kotlin
object DdlGenerator {
    fun primaryTable(schema: TableSchema): String
    fun lookupTable(lookup: LookupTableSchema): String
    fun secondaryIndex(schema: TableSchema, column: ColumnSchema): String
    fun alterTableAddColumn(schema: TableSchema, column: ColumnSchema): String
    fun cqlTypeString(column: ColumnSchema): String
    fun allStatements(schema: TableSchema): List<String>
    internal fun kotlinTypeToCql(type: KType): String
}
```

- **`primaryTable(schema)`** — builds the full `CREATE TABLE IF NOT EXISTS` statement.
  - Column list = `partitionKeys + clusteringKeys + columns + lookupTables.map { it.indexColumn }`,
    de-duplicated by `cqlName` (`.distinctBy { it.cqlName }`) — this is what prevents a `@LookupIndex`
    column from appearing twice even though it lives in both `columns` and `lookupTables`.
  - Each column renders as `COUNTER` if `isCounter`, else via `kotlinTypeToCql`.
  - Single partition key → `PRIMARY KEY (id)`. Composite (`partitionKeys.size > 1`) → double-paren wrapped:
    `PRIMARY KEY ((user_id, chain), created_at)`.
  - `CLUSTERING ORDER BY (...)` clause emitted only if `clusteringKeys` is non-empty; one `col ORDER`
    pair per clustering key, in schema order.
  - `WITH` clause assembled from, in order: `CLUSTERING ORDER BY`, `default_time_to_live = N` (if
    `defaultTtl != null`), `gc_grace_seconds = N` (if `gcGraceSeconds != null`), joined with `\nAND `.
    Omitted entirely if there are no options.
- **`lookupTable(lookup)`** — `CREATE TABLE IF NOT EXISTS {name} (indexColumn, partitionKeyColumns..., PRIMARY KEY (indexColumn))`.
  No TTL, no clustering, no gc_grace — lookup tables are intentionally minimal. Always exactly
  `1 + partitionKeyColumns.size` columns, single-column primary key on the index column itself.
- **`secondaryIndex(schema, column)`** — `CREATE INDEX IF NOT EXISTS {table}_{col}_idx ON {table} ({col});`
- **`alterTableAddColumn(schema, column)`** — `ALTER TABLE {table} ADD {col} {type};` — used by migration/
  auto-migrate logic elsewhere to add newly-discovered columns; never drops or renames.
- **`cqlTypeString(column)`** — returns `"COUNTER"` if `column.isCounter`, else delegates to
  `kotlinTypeToCql(column.type)`. Convenience for callers that only have a `ColumnSchema`, not a raw `KType`.
- **`allStatements(schema)`** — `[primaryTable(schema)] + lookupTable() for each lookupTables + secondaryIndex() for each secondaryIndexes`, in that order. For a schema with 2 lookup tables and 0 secondary indexes, this returns exactly 3 strings.
- **`kotlinTypeToCql(type)`** — `internal`. Throws `KandraSchemaException("Unsupported type: $type")` if the
  type's classifier isn't a `KClass` (e.g. a type variable/wildcard).

### Kotlin → CQL type mapping (exhaustive, from `mapType`)

| Kotlin type | CQL type |
|---|---|
| `UUID` | `UUID` |
| `String` | `TEXT` |
| `Int` / `Integer` | `INT` |
| `Long` | `BIGINT` |
| `Boolean` | `BOOLEAN` |
| `Double` | `DOUBLE` |
| `Float` | `FLOAT` |
| `Instant` | `TIMESTAMP` |
| `LocalDate` | `DATE` |
| `ByteArray` | `BLOB` |
| `BigDecimal` | `DECIMAL` |
| `List<X>` | `LIST<cql(X)>` — recurses on the type argument |
| `Set<X>` | `SET<cql(X)>` |
| `Map<K, V>` | `MAP<cql(K), cql(V)>` |
| any `Enum` subclass | `TEXT` |
| anything else | throws `KandraSchemaException("Unsupported type: $klass — use @Column or register a custom encoder/decoder via KandraCodec.")` |

`List`/`Set`/`Map` throw `KandraSchemaException` if their type argument is missing (raw generic use without
reified argument info) — `"List type argument missing in: $type"` etc. Nested generics recurse arbitrarily
deep (e.g. `List<Set<String>>` → `LIST<SET<TEXT>>`), though nothing else in this module tests that path.

```kotlin
DdlGenerator.kotlinTypeToCql(typeOf<Map<String, Int>>())  // "MAP<TEXT, INT>"
DdlGenerator.allStatements(schema)  // [CREATE TABLE ..., CREATE TABLE ..._by_email (...), ...]
```

## `KandraConsistency` — `io.kandra.core.KandraConsistency`

```kotlin
enum class KandraConsistency {
    ONE, TWO, THREE,
    QUORUM,
    ALL,
    LOCAL_ONE,      // default read — single local-DC replica
    LOCAL_QUORUM,   // default write — majority of local-DC replicas
    EACH_QUORUM,    // majority in EVERY DC — strongest multi-DC write
    LOCAL_SERIAL,   // default LWT serial consistency — Paxos, local DC only
    SERIAL;         // global Paxos across all DCs — for globally-unique constraints

    val isSerial: Boolean get() = this == LOCAL_SERIAL || this == SERIAL
}
```

`isSerial` is the only computed member — a plain `get()` property, no backing field. Use it to check
whether a given level is valid as a *serial* consistency argument (e.g. for `saveIfNotExists`) before
passing it through; `kandra-core` itself does not enforce this anywhere — it's a convenience for callers.

Resolution order for which level actually gets used at query time (per-call param > `@ReadConsistency`/
`@WriteConsistency` annotation > config default) is documented in the KDoc here but implemented in other
modules — `kandra-core` only defines the enum and the two annotations.

## `KandraAuth` — `io.kandra.core.KandraAuth` (`@ExperimentalKandraApi`)

```kotlin
data class KandraCredentials(val username: String, val password: String)

@ExperimentalKandraApi
fun interface KandraAuthProvider {
    fun getCredentials(): KandraCredentials
}

@ExperimentalKandraApi
object KandraAuth {
    fun fromEnv(usernameVar: String = "SCYLLA_USERNAME", passwordVar: String = "SCYLLA_PASSWORD"): KandraAuthProvider
    fun fromFile(usernamePath: String, passwordPath: String): KandraAuthProvider
    fun static(username: String, password: String): KandraAuthProvider
    fun custom(provider: () -> KandraCredentials): KandraAuthProvider
}
```

All four factory functions return a `KandraAuthProvider` — a SAM (`fun interface`), so any of them can be
replaced inline with a lambda if needed. `getCredentials()` implementations must be thread-safe per the
KDoc contract (may be invoked concurrently during credential rotation) — none of the four built-ins hold
mutable state, so they're safe as-is.

- **`fromEnv`** — reads `System.getenv(usernameVar)` / `System.getenv(passwordVar)` **on every call** to
  `getCredentials()` (not cached at provider-construction time), so external credential rotation via env
  var reload is picked up automatically if your process supports it. Missing username throws
  `KandraAuthException("Environment variable '$usernameVar' is not set. Set it or configure a different KandraAuthProvider.")`;
  missing password throws `KandraAuthException("Environment variable '$passwordVar' is not set.")` — note
  the asymmetric wording, only the username message suggests an alternative.
- **`fromFile`** — reads and `.trim()`s both files fresh on every `getCredentials()` call. Wraps
  `java.io.IOException` into `KandraAuthException("Failed to read credentials from file: ${e.message}", e)` —
  other exception types (e.g. `SecurityException`) are **not** caught and propagate raw.
- **`static`** — closes over fixed `username`/`password` values. KDoc explicitly flags dev/test-only use —
  credentials end up in source and logs.
- **`custom`** — thinnest wrapper, just `KandraAuthProvider { provider() }`. Use for Vault/Secrets Manager
  integrations.

```kotlin
auth {
    provider = KandraAuth.custom {
        val secret = awsSecretsClient.getSecretValue("coinx/scylla")
        KandraCredentials(secret.username, secret.password)
    }
}
```

## `KandraEventListener` — `io.kandra.core.KandraEventListener` (`@ExperimentalKandraApi`)

```kotlin
interface KandraEventListener {
    fun onEventualWriteFailed(tableName: String, entity: Any, error: Throwable)   // abstract, no default
    fun onAuthFailed(contactPoint: String, error: Throwable) {}                    // no-op default
    fun onConnectionEstablished(contactPoint: String) {}                           // no-op default
    fun onCredentialRefreshed() {}                                                 // no-op default
    fun onSslHandshakeFailed(contactPoint: String, error: Throwable) {}            // no-op default
}
```

Only `onEventualWriteFailed` is abstract — every other method has a no-op default body, so existing
implementations that predate a newly-added method keep compiling. Per the KDoc, `onEventualWriteFailed`
**must not throw**: any exception it raises is silently swallowed by the caller (in another module), so
don't rely on propagating errors from inside it — log/enqueue instead.

```kotlin
eventListener = object : KandraEventListener {
    override fun onEventualWriteFailed(tableName: String, entity: Any, error: Throwable) {
        deadLetterQueue.publish(FailedLookupWrite(tableName, entity))
    }
}
```

## `KandraMetrics` — `io.kandra.core.KandraMetrics`

```kotlin
fun interface KandraMetrics {
    fun record(tableName: String, operation: String, durationMs: Long)
}
```

SAM interface, no `@ExperimentalKandraApi` gate (unlike `KandraAuthProvider`/`KandraEventListener`). Called
after every query execution on both blocking and suspend paths. `operation` is a free-form string —
per the KDoc, the values actually emitted are one of: `"save"`, `"update"`, `"delete"`, `"saveAll"`,
`"deleteAll"`, `"batch"` (that's documentation of caller behavior, not an enforced closed set — `kandra-core`
itself places no constraint on the string).

```kotlin
recorder = KandraMetrics { table, op, ms ->
    meterRegistry.timer("kandra.query", "table", table, "operation", op).record(ms, TimeUnit.MILLISECONDS)
}
```

## `KandraTimestamp` — `io.kandra.core.KandraTimestamp`

```kotlin
object KandraTimestamp {
    fun now(): Long                        // System.currentTimeMillis() * 1000L
    fun fromInstant(instant: Instant): Long // instant.toEpochMilli() * 1000L
}
```

Both just scale millisecond values up to microseconds (ScyllaDB's native write-timestamp unit) — neither
adds actual microsecond precision, they only convert units. Use `fromInstant` when replaying/reprocessing
events out of order, so the write timestamp reflects event time rather than server-processing time (last-
event-wins instead of last-server-write-wins).

```kotlin
walletRepo.save(wallet, timestampMicros = KandraTimestamp.fromInstant(event.occurredAt))
```

## `KandraValidator` — `io.kandra.core.KandraValidator`

```kotlin
fun interface KandraValidator<T : Any> {
    fun validate(entity: T): List<KandraValidationError>
}

data class KandraValidationError(val field: String, val message: String)

class KandraValidationException(
    val errors: List<KandraValidationError>
) : KandraException("Validation failed: ${errors.joinToString("; ") { "${it.field}: ${it.message}" }}")
```

`validate()` returning an empty list means "valid" — there's no separate boolean, callers (in another
module) treat a non-empty list as failure and wrap it in `KandraValidationException`.
`KandraValidationException.message` is auto-composed from every error as `"field: message"` pairs joined
by `"; "` — don't rebuild this string yourself, just populate `errors` correctly.

```kotlin
validate<User> { user ->
    buildList {
        if (user.email.isBlank()) add(KandraValidationError("email", "must not be blank"))
    }
}
```

## Exceptions — `io.kandra.core.exception`

```kotlin
open class KandraException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class KandraSchemaException(message: String) : KandraException(message)
class KandraQueryException(message: String, cause: Throwable? = null) : KandraException(message, cause)
class KandraAuthException(message: String, cause: Throwable? = null) : KandraException(message, cause)
class KandraOptimisticLockException(
    message: String,
    val entityClass: KClass<*>,
    val partitionKey: Any
) : KandraException(message)
class KandraMigrationException(message: String, cause: Throwable? = null) : KandraException(message, cause)
```

All are unchecked (`RuntimeException` root). Note asymmetry: `KandraSchemaException` and
`KandraOptimisticLockException` do **not** accept a `cause` parameter — you cannot chain an underlying
exception into either of those two, only a message (plus, for `KandraOptimisticLockException`, the
offending `entityClass`/`partitionKey`, both public `val`s for programmatic inspection in a catch block).
Every other exception here (`KandraQueryException`, `KandraAuthException`, `KandraMigrationException`)
does accept `cause`.

Within `kandra-core` itself, only `KandraSchemaException` and `KandraAuthException` are actually thrown
(by `DdlGenerator`/`SchemaRegistry` and `KandraAuth` respectively) — `KandraQueryException`,
`KandraOptimisticLockException`, and `KandraMigrationException` are declared here but thrown by other
modules; `kandra-core` is just their home package.

```kotlin
try {
    repo.update(old, new)
} catch (e: KandraOptimisticLockException) {
    logger.warn("version conflict on ${e.entityClass.simpleName} pk=${e.partitionKey}")
}
```

## `ApiStability` — `io.kandra.core.ApiStability`

```kotlin
@RequiresOptIn(message = "...", level = RequiresOptIn.Level.ERROR)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class InternalKandraApi

@RequiresOptIn(message = "...", level = RequiresOptIn.Level.WARNING)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class ExperimentalKandraApi
```

- `@InternalKandraApi` — `ERROR` level opt-in. Marks `SchemaRegistry` and `DdlGenerator` (both annotated at
  the `object` level in this module). Using either from outside Kandra modules is a compile error without
  `@OptIn(InternalKandraApi::class)` — and per the KDoc, you're not meant to add that opt-in in your own
  code; these are implementation details other modules build on, not public surface.
- `@ExperimentalKandraApi` — `WARNING` level opt-in. Marks `KandraAuthProvider`, `KandraAuth`, and
  `KandraEventListener` — usable with just a compiler warning, or silence it with
  `@OptIn(ExperimentalKandraApi::class)`. Signals these three may still change shape in a future release.

Note the asymmetry in what's gated: `KandraMetrics`, `KandraValidator`, `KandraConsistency`,
`KandraTimestamp`, all annotation types, all exceptions, and the whole `schema` package are **not**
gated by either annotation — they're considered stable public API.

## Gotchas worth double-checking in review

- `@Transient` on a property does **not** universally suppress it from every collection — it's excluded
  from `clusteringKeys`, the lookup-column set, and `secondaryIndexes` via explicit `!it.isTransient`
  filters, but a `@Transient @PartitionKey` property still counts toward `partitionKeys` for index-
  duplication validation (there's no `!isTransient` filter on the partition-key branch in `buildSchema`).
  Don't combine `@Transient` with `@PartitionKey`/`@ClusteringKey` — the schema will look inconsistent.
- `@LookupIndex` columns appear in both `TableSchema.columns` and `LookupTableSchema.indexColumn`.
  `DdlGenerator.primaryTable` relies on `.distinctBy { it.cqlName }` to avoid emitting the column twice —
  if you hand-roll DDL from `schema.columns` alone without also considering `lookupTables`, you'll get a
  correct (non-duplicated) primary table but need the same de-dup discipline anywhere else you combine
  those lists.
- Counter-table validation excludes partition keys, clustering keys, `@Transient`, `@CreatedAt`, and
  `@UpdatedAt` columns from the "all non-key columns must be @Counter" check — but does **not** exclude
  `@LookupIndex` or `@SecondaryIndex` columns, so a `@Counter` entity with a non-counter `@LookupIndex`
  column will fail registration.
- `SchemaRegistry.register` is `getOrPut` — the *first* successful call wins and is cached; if you mutate
  annotations or reload the class in a way the JVM doesn't consider a "different" `KClass`, you won't see
  the updated schema. Call `SchemaRegistry.clear()` between test classes.
- `gcGraceSeconds` on `@ScyllaTable` defaults to `-1`, which is treated as "unset" (`takeIf { it >= 0 }` in
  `SchemaRegistry`) — it is *not* a real gc_grace_seconds value of `-1`; passing `0` explicitly IS honored
  and emitted in DDL (`0 >= 0` is true), even though `0` disables tombstone GC entirely in Cassandra/Scylla.
- `KandraSchemaException` and `KandraOptimisticLockException` have no `cause` parameter — if you're
  wrapping a lower-level exception, you'll lose the stack trace of the original unless you fold its
  message into the string yourself.
- `KandraTimestamp.now()`/`fromInstant()` only have millisecond resolution multiplied by 1000 — they do
  not source real microsecond time, so two calls in rapid succession within the same millisecond produce
  identical timestamps.
- `camelToSnake` is a naive regex — no special-casing for acronyms or existing underscores/digits; verify
  generated column names for entity properties with consecutive capitals or leading underscores.
- `DdlGenerator.kotlinTypeToCql` recognizes exactly the types in the table above; anything else (e.g. a
  data class, `BigInteger`, `URI`) throws `KandraSchemaException` at DDL-generation time, not at
  compile time — a bad field type surfaces only when you first generate/register schema for that entity.
