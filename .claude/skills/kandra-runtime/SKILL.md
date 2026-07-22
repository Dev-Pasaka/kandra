---
name: kandra-runtime
description: Exhaustive API reference for kandra-runtime — KandraRepository/KandraSuspendRepository methods, BatchEngine, QueryExecutor, StatementBuilder, query DSL, codec, caching, retry/consistency/debug config. Load when writing or reviewing code that calls repository methods or configures Kandra's runtime behavior.
---

# kandra-runtime

`kandra-runtime` is the module that turns a `@ScyllaTable`-annotated entity plus a `TableSchema`
(built by `kandra-core`/KSP) into actual DataStax driver calls. Everything here operates on the
already-resolved `TableSchema` — no annotation reading happens in this module. All source
references below are exact (file, method, line-level behavior) from the module as of this read;
nothing here is inferred from names alone.

## Package map

| Package | Contents |
|---|---|
| `io.kandra.runtime` | `KandraRuntime`, `BatchEngine`, `QueryExecutor`, `StatementBuilder`, `KandraBatchScope`, `ConsistencyConfig`, `DebugConfig`, `RetryConfig`, `KandraEntityLogger` |
| `io.kandra.runtime.repository` | `KandraRepository<T>` (blocking), `KandraSuspendRepository<T>` (suspend) |
| `io.kandra.runtime.dsl` | `QueryContext`, `KandraPredicate`, `KandraColumnRef<T>`, `KandraPage<T>`, `KandraTable<T>`, `KandraRawQuery`, `KandraRawQueryBuilder` |
| `io.kandra.runtime.codec` | `KandraCodec`, `KandraUnset` |
| `io.kandra.runtime.cache` | `KandraCache<K, V>` |
| `io.kandra.runtime.driver` | `CqlSession.prepareSuspend`, `executeSuspend`, `executeSuspendAll` extensions |

## KandraRuntime — entry point

```kotlin
class KandraRuntime(val session: CqlSession, val batchEngine: BatchEngine, val codec: KandraCodec) {
    val isShuttingDown: AtomicBoolean   // delegates to batchEngine.isShuttingDown
    val inFlightCount: AtomicInteger    // delegates to batchEngine.inFlightCount

    @ExperimentalKandraApi suspend fun batch(block: suspend KandraBatchScope.() -> Unit)
    @ExperimentalKandraApi fun batchBlocking(block: KandraBatchScope.() -> Unit)

    fun <T : Any> repository(entityClass: KClass<T>): KandraRepository<T>
    fun <T : Any> suspendRepository(entityClass: KClass<T>): KandraSuspendRepository<T>
    inline fun <reified T : Any> repository(): KandraRepository<T>
    inline fun <reified T : Any> suspendRepository(): KandraSuspendRepository<T>

    suspend fun isHealthy(): Boolean   // SELECT release_version FROM system.local; false on any exception (logged WARN, not thrown)
}
```

`repository()`/`suspendRepository()` call `SchemaRegistry.get(entityClass)` and construct a new
`KandraRepository`/`KandraSuspendRepository` wrapping the shared `session` and `batchEngine`.
**Each call builds a brand-new repository instance** — see "Repository instances are not free" in
Gotchas below before calling this in a hot path instead of once at startup/DI-registration time.

`isHealthy()` never throws; it's safe to wire directly into a `/health` route.

## Repository API

Both repositories expose the same surface — `KandraRepository<T>` is blocking (calls into
`BatchEngine`'s blocking methods), `KandraSuspendRepository<T>` is `suspend` (calls into
`BatchEngine`'s `*Suspend` methods, which use `CqlSession.executeSuspend`/`prepareSuspend` for
truly non-blocking prepare/execute).

Both are constructed with `(session, schema, entityClass, batchEngine)` and internally build their
**own** `StatementBuilder(session)`, `QueryExecutor(session, schema, statementBuilder)`, and
`KandraCache<Any, T>(schema.cacheConfig)` — see Gotchas for why this matters.

### Save family

```kotlin
fun save(entity: T, ttlSeconds: Int? = null, timestampMicros: Long? = null, consistency: KandraConsistency? = null)
fun saveIfNotExists(entity: T, serialConsistency: KandraConsistency = KandraConsistency.LOCAL_SERIAL): Boolean
fun saveWithNulls(entity: T, ttlSeconds: Int? = null)
fun saveAll(entities: List<T>, useBatch: Boolean = true)
```
(suspend repo: identical signatures, `suspend fun`)

- **`save`** — runs entity validators (`registerValidator`), stamps `@CreatedAt`/`@UpdatedAt` via
  reflective `copy()`, sets the initial `@Version` value (`1L` for `Long`, `Instant.now()` for
  `Instant`) if present, then executes a single `LOGGED BATCH`: `INSERT INTO <table> (...) VALUES
  (...) USING TTL x AND TIMESTAMP y` for the primary row plus one `INSERT` per `@LookupIndex(
  consistency = BATCH)` table. `EVENTUAL` lookups are fired via `scope.launch { session.execute(...)
  }` **after** the batch commits — failures there are caught, logged at ERROR, and forwarded to
  `KandraEventListener.onEventualWriteFailed` if one is registered.
  Throws `KandraQueryException` immediately if `schema.isCounterTable` — counter tables can only use
  `increment()`/`decrement()`.
  A nullable field set to `null` encodes to `KandraUnset` → the driver `BoundStatement.unset(idx)` is
  called, **not** a bound `null`, so no tombstone is written and any existing value is left alone.

- **`saveIfNotExists`** — LWT: `INSERT ... IF NOT EXISTS` with `setSerialConsistencyLevel`. Throws
  `KandraQueryException` up front if `serialConsistency` isn't `LOCAL_SERIAL`/`SERIAL`
  (`KandraConsistency.isSerial`), and if the table is a counter table. Reads the driver's synthetic
  `[applied]` boolean column from the single result row to determine success; if `false`, returns
  `false` immediately **without touching lookup tables at all** (no partial writes). If applied, `BATCH`
  lookups are inserted in a second, separate `LOGGED BATCH` (not atomic with the primary INSERT — a
  crash between the two leaves the primary row without its lookup rows), and `EVENTUAL` lookups fire
  async as usual.

- **`saveWithNulls`** — same as `save` except it uses `StatementBuilder.insertPrimaryWithNulls`,
  which binds `null` for null fields via `stmt.setBytesUnsafe(idx, null)` instead of `unset()` —
  this **does** write a tombstone. Does not inject an initial `@Version` value the way `save` does
  (no call to `injectInitialVersion`). Throws `KandraQueryException` on counter tables.

- **`saveAll`** — no-ops on an empty list. With `useBatch = false`, calls `save()` per entity (each
  gets its own atomic batch, timestamps stamped, but **no validator call** — `saveAll`'s per-entity
  path skips `validateEntity`, unlike single `save()`). With `useBatch = true` (default): builds one
  flat list of `BatchableStatement` (primary INSERT + `BATCH`-consistency lookup INSERTs for every
  entity), estimates size as `statements.size * 512` bytes (a fixed heuristic, not actual
  serialized size) and logs a WARN if it exceeds `batchWarnThresholdKb`. If
  `batchAutoChunk` is true and the statement count exceeds `batchMaxChunkSize`, the list is split via
  `.chunked(batchMaxChunkSize)` into **multiple separate `LOGGED BATCH` statements**, each executed
  independently — **this breaks cross-chunk atomicity**: a failure partway through leaves some chunks
  committed and others not. `EVENTUAL` lookups fire per-entity after all chunks/the single batch commits.

```kotlin
userRepo.save(user, ttlSeconds = 3600)
val created = userRepo.saveIfNotExists(user)              // false if partition key already exists
userRepo.saveWithNulls(user.copy(nickname = null))         // tombstones `nickname`
userRepo.saveAll(users, useBatch = true)                   // chunks at 100 by default
```

### Update family

```kotlin
fun update(old: T, new: T)
fun updateForce(entity: T)
```

- **`update(old, new)`** — validates `new`. **If `schema.versionColumn` is present** (`@Version`
  field): reads `old`'s current version value via reflection (throws `KandraQueryException` if it's
  `null`), computes the next version (`+1` for `Long`, `Instant.now()` for `Instant` — anything else
  throws `KandraQueryException("@Version field must be Long or Instant")`), and issues
  `UPDATE <table> SET col=?, ... WHERE pk=? [AND pk2=?...] IF <versionCol> = ?` with
  `setSerialConsistencyLevel(LOCAL_SERIAL)` (hardcoded, not configurable via `RetryConfig`/consistency
  params). If `[applied]` is `false`, throws `KandraOptimisticLockException(message, entityClass,
  partitionKey)`. On success, lookup tables are diffed against the caller-supplied `old` (see below)
  and updated in a follow-up batch/eventual write.
  **If no `@Version` column exists**, `update()` does **not** issue a CQL `UPDATE` at all — it
  performs a full-row `INSERT` (`statementBuilder.insertPrimary`) that overwrites every column, batched
  with lookup-table changes, and there is **no concurrency check whatsoever** — the `old` argument is
  used purely to diff lookup-index column values, never compared against what's actually in the
  database. Passing a stale `old` here silently succeeds and can leave lookup tables pointing at the
  wrong partition key (see Gotchas).
  Lookup diffing logic (`buildUpdateStatements`): for each lookup table, if the index column's value
  changed (`oldVal != newVal && oldVal != null`) the old lookup row is deleted; if the new value is
  non-null, a lookup row is (re)inserted. `BATCH`-consistency lookup changes are folded into the same
  `LOGGED BATCH` as the primary write; `EVENTUAL` changes fire via `scope.launch` afterward.

- **`updateForce(entity)`** — bypasses the `@Version` check entirely, even if the entity has a
  `@Version` column: always does a full-row `INSERT` overwrite in a batch. Because there's no separate
  "old" entity to diff against, it internally calls `buildUpdateStatements(schema, entity, stamped)`
  where `entity` (pre-timestamp-injection) stands in for "old" — since `entity` and `stamped` have
  identical lookup-index column values (only `@CreatedAt`/`@UpdatedAt` differ), the diff **never**
  detects a lookup-index-column change, so **stale lookup rows from a previous value are never
  cleaned up** by `updateForce`. New/unchanged lookup values are still (re)inserted every call.

```kotlin
val loaded = userRepo.findById(userId)!!
val changed = loaded.copy(email = "new@example.com")
userRepo.update(loaded, changed)     // LWT IF version=? if @Version present, else blind overwrite

userRepo.updateForce(changed)        // ⚠ if `email` also drives a @LookupIndex, the OLD lookup
                                      //   row for the previous email is never deleted — see Gotchas
```

### Delete family

```kotlin
fun delete(entity: T)
fun deleteAll(entities: List<T>)
fun deleteById(vararg keyValues: Any)
fun deleteBy(block: QueryContext.() -> Unit)
```

- **`delete(entity)`** — extracts partition key values from `entity` via reflection (throws
  `KandraQueryException` if any is `null`). If `schema.isSoftDelete && schema.softDeleteTtlSeconds !=
  null`: instead of `DELETE`, issues `UPDATE <table> USING TTL <ttl> SET col1=?, col2=?, ... WHERE
  pk=?` covering every non-transient, non-counter column (so all values are rewritten with a fresh
  TTL — the whole row, not just a marker column), then separately deletes any lookup-table rows for
  this entity (outside the batch, via plain `session.execute`/`executeSuspend`, one statement per
  lookup — **not retried**, since it bypasses `executeWithRetry`). Otherwise: builds a `LOGGED BATCH`
  of `DELETE FROM <table> WHERE pk=...` plus one `DELETE` per lookup table (using each lookup's index
  value read off the entity; if that value is `null` on the entity, that lookup's delete is silently
  skipped).

- **`deleteAll(entities)`** — no-ops on empty. If `entities.size > tombstoneWarnThreshold` (default
  1000, configurable via `BatchEngine.configureBatchLimits`), logs a WARN about tombstone accumulation
  and `gc_grace_seconds`. Then calls `delete()` **once per entity, sequentially** — this is `N`
  separate round trips/batches, not one combined batch.

- **`deleteById(vararg keyValues)`** — `keyValues` must cover the **full primary key**: partition
  key column(s) first, then clustering key column(s), in schema order (`@PartitionKey(index)` /
  `@ClusteringKey(index)`). Passing fewer values than the full key throws `KandraSchemaException`
  naming exactly which columns are required — it does not silently scope to a partial key. On a
  partition-key-only entity this is unchanged (`deleteById(id)`); on a clustering-keyed entity you
  must pass both, e.g. `deleteById(userId, bucketedAt)`.
  **Behaves differently between the two repositories**:
  - Blocking `KandraRepository`: always issues `executor.findById(entityClass, *keyValues)` first (a
    real query, bypassing the entity cache), then builds and executes a `LOGGED BATCH` of
    `DELETE FROM <table> WHERE pk=...` plus lookup deletes (if the entity was found) directly via
    `session.execute` — **it does not go through `BatchEngine` at all**, so it gets no retry-on-timeout
    behavior, no shutdown rejection, and (per `@SoftDelete`) **ignores soft-delete** — it always issues
    a hard `DELETE`, even on a `@SoftDelete`-annotated entity.
  - Suspend `KandraSuspendRepository`: also does `executor.findById(...)` first, but if found, delegates
    to `batchEngine.deleteSuspend(schema, entity)` — which **does** honor `@SoftDelete`, retry, and
    shutdown rejection. If not found, it directly issues `session.executeSuspend(deleteById(...))`.
  - **Neither** blocking nor suspend `deleteById` invalidates the `KandraCache` — see Gotchas.

- **`deleteBy(block)`** — runs `QueryContext` predicates via `QueryExecutor.find` to fetch one
  matching entity (`firstOrNull` of `findAll`'s results — see Read family for predicate resolution
  order), then calls `batchEngine.delete(schema, entity)`/`deleteSuspend`. No-ops if nothing matches.
  This one **does** go through `BatchEngine` (soft-delete/retry apply), but like `deleteById`, it
  **does not invalidate the cache**.

```kotlin
userRepo.delete(user)                 // hard delete unless @SoftDelete
userRepo.deleteAll(inactiveUsers)     // N sequential deletes, warns above 1000 rows
userRepo.deleteById(userId)           // ⚠ blocking repo: ignores @SoftDelete, no retry, no cache invalidation
userRepo.deleteBy { +UserTable.email.eq("x@example.com") }
```

### Read family

```kotlin
fun findById(vararg idValues: Any, consistency: KandraConsistency? = null): T?
fun find(block: QueryContext.() -> Unit): T?
fun findAll(limit: Int? = null, block: QueryContext.() -> Unit): List<T>
fun findPage(pageSize: Int, pageToken: String? = null, block: QueryContext.() -> Unit = {}): KandraPage<T>
fun exists(block: QueryContext.() -> Unit): Boolean
fun raw(cql: String, vararg params: Any?): List<Row>
fun rawQuery(query: KandraRawQuery): List<Row>
```

All of these first call `checkNotShuttingDown()` (throws `KandraQueryException` if
`batchEngine.isShuttingDown` is set) — this is a repository-level check, separate from the one
inside `BatchEngine.executeWithRetry`.

- **`findById`** — checks `KandraCache` first (key = the single id value if one PK, else
  `idValues.toList()`); on miss, runs `SELECT * FROM <table> WHERE pk1=? [AND pk2=?...] [AND ck1=?...]`
  via `QueryExecutor.findById` and populates the cache with the decoded entity on a hit. Cache is
  no-op if the entity's class has no `@CacheResult` or Caffeine isn't on the classpath
  (`KandraCache.isEnabled == false`; `getIfPresent` always returns `null`, `put`/`invalidate` are no-ops).
  Like `deleteById`, `idValues` must cover the full primary key (partition then clustering columns,
  in schema order) — `KandraSchemaException` if the count doesn't match. On a clustering-keyed entity,
  `findById(userId)` alone now fails loudly rather than silently returning an arbitrary row from the
  partition; use `findById(userId, bucketedAt)`.

- **`find(block)`** — `findAll(block).firstOrNull()` under the hood in `QueryExecutor` (i.e. it still
  fetches and decodes **all** matching rows, then takes the first — not a `LIMIT 1` unless you add
  `limit(1)` yourself inside the block).

- **`findAll(limit, block)`** — wraps the caller's `block` to also call `limit(limit)` if the `limit`
  parameter is non-null (in addition to anything the block itself calls). Predicate resolution
  (`QueryExecutor.resolveRows`), in order:
  1. Throws `KandraQueryException("Query must have at least one predicate.")` if the block adds none.
  2. If any predicate is `KandraPredicate.In`: the column must be a partition key or `@SecondaryIndex`
     column, else throws `KandraQueryException` — the message says `"Add allowFiltering() to the
     query..."` but **`QueryContext` has no `allowFiltering()` method**; the actual fix is to add
     `@SecondaryIndex` to the column or restrict the `IN` to a partition key. Empty `values` list
     returns `emptyList()` without querying. `IN` on a single-column partition key logs DEBUG and runs
     `SELECT * FROM <table> WHERE pk IN ?`; `IN` on a composite partition key throws
     `KandraSchemaException` (thrown by `StatementBuilder.selectByPartitionKeyIn`, not
     `KandraQueryException`) — this only surfaces when the `IN` column actually *is* the (single)
     partition key column; the "must be a PK or secondary index" check above happens first.
  3. Else if a predicate targets a `@LookupIndex` column: only `Eq` is supported (anything else throws
     `KandraQueryException`); queries the lookup table for the primary table's partition key value(s),
     then queries the primary table by that key. A miss on the lookup table returns `emptyList()`
     without querying the primary table.
  4. Else if a predicate targets a `@SecondaryIndex` column: logs a WARN (every call, in production),
     then falls through to the direct-CQL branch.
  5. Direct CQL: builds `WHERE col op ? [AND ...]` from all predicates in order (`Eq`/`Gt`/`Gte`/`Lt`
     /`Lte`/`In`), appends `LIMIT n` if `ctx.limit` was set, and executes. Kandra never appends
     `ALLOW FILTERING` anywhere in this module.

- **`findPage(pageSize, pageToken, block)`** — driver-native paging, not offset-based.
  If the block contains no predicates: does a full **token-range scan** (`SELECT * FROM <table>`,
  no `WHERE`) — the DataStax driver pages across token ranges automatically; this is the only
  Kandra-sanctioned way to iterate a whole table (no `ALLOW FILTERING` is ever used). Logs an INFO
  message the first time (`pageToken == null`) warning it's a full scan.
  If the block contains a predicate on a `@LookupIndex` column (equality only, else
  `KandraQueryException`): resolves the lookup row once, then paginates the primary table by the
  resolved partition key — if the lookup misses, returns `KandraPage(emptyList(), null, false)`
  immediately. `pageToken`, when supplied, is base64-decoded into the driver's raw paging-state bytes
  and set via `setPagingState`. Only rows already buffered in the current page
  (`rs.getAvailableWithoutFetching()`) are consumed per call — it does not force-fetch beyond one
  page. `nextPageToken` is `null` (and `hasMore == false`) once `rs.isFullyFetched`.

- **`exists(block)`** — routes through the **same** `resolveRows` as `findAll`, with `limitOne = true,
  selectKeys = true` — but those two flags are **only honored by the final direct-CQL branch**
  (step 5 above: `SELECT pk1, pk2 FROM <table> WHERE ... LIMIT 1`). If the query resolves via the
  `IN` branch or the lookup-table branch, `exists()` runs the **full** query (`SELECT *`, no `LIMIT`)
  and just checks `rows.isNotEmpty()` — meaningfully more expensive than the direct-CQL path for the
  same logical check.

- **`raw(cql, vararg params)`** — prepares and binds `cql` directly (`session.prepare(cql).bind(*params)`),
  returns `rs.all()` (materializes every row). Logs a WARN if `params` is empty **and** `cql` contains
  a `'` (single quote) or the literal substring `"="` — a narrow heuristic meant to flag likely string
  literals/injection risk; it does not catch e.g. bare numeric literals or double-quoted identifiers in
  general, so absence of the warning is not proof the query is safe.

- **`rawQuery(query)`** — same execution path as `raw`, but the CQL/params come from a
  `KandraRawQuery` (see DSL section) — always parameterized by construction, no injection-risk warning
  logic needed.

```kotlin
val u = userRepo.findById(userId)                              // cached if @CacheResult
val one = userRepo.find { +UserTable.email.eq("a@b.com") }      // fetches all matches, takes first
val page = userRepo.findPage(pageSize = 50, pageToken = null) { +UserTable.status.eq("ACTIVE") }
val next = userRepo.findPage(pageSize = 50, pageToken = page.nextPageToken) { +UserTable.status.eq("ACTIVE") }
val yes = userRepo.exists { +UserTable.email.eq("a@b.com") }    // full-row fetch if `email` is a @LookupIndex!
val rows = userRepo.rawQuery(KandraRawQuery.cql("SELECT count(*) FROM users WHERE status = ?").bind("ACTIVE").build())
```

### Collection & counter family

```kotlin
fun <V> append(entity: T, field: KProperty1<T, Collection<V>?>, values: Collection<V>)
fun <V> remove(entity: T, field: KProperty1<T, Collection<V>?>, values: Collection<V>)
fun <K, V> put(entity: T, field: KProperty1<T, Map<K, V>?>, entries: Map<K, V>)
fun increment(field: KProperty1<T, Long?>, partitionKeys: Map<String, Any>, by: Long = 1L)
fun decrement(field: KProperty1<T, Long?>, partitionKeys: Map<String, Any>, by: Long = 1L)
```

- **`append`** — `UPDATE <table> SET col = col + ? WHERE pk=... [AND ck=...]`; works for CQL
  `list`/`set` columns (driver-level `+` append). `col` is resolved by matching `field.name` against
  `schema.columns` (throws `KandraSchemaException` if not found). Full key values (partition +
  clustering) are read off `entity` automatically — nothing extra to pass on a clustering-keyed entity.
- **`remove`** — same shape with `col = col - ?`.
- **`put`** — despite the name, internally calls `statementBuilder.appendToCollection` with `entries`
  as the bound value (`col = col + ?`) — for a CQL `map` column, driver-level `+` on a map is a
  merge/overwrite-by-key, which is the correct "put" semantics for map columns; there is no
  map-remove-by-key method (only `remove()` for list/set).
- **`increment`/`decrement`** — only valid when `schema.isCounterTable`; else throws
  `KandraSchemaException`. Builds `UPDATE <table> SET col = col + ? WHERE pk=... [AND ck=...]` (or
  `- ?` — the sign is baked in via `Math.abs(delta)` and a literal `+`/`-` in the CQL depending on the
  sign passed to `counterUpdate`, so `decrement` internally calls `counterUpdate(..., -by)`).
  `partitionKeys` is a `Map<String, Any>` keyed by **property name** (falls back to matching CQL name),
  not an entity instance — you don't need to load the entity to bump a counter. On a clustering-keyed
  counter table, the map must also include an entry for each clustering key column (by property or CQL
  name) — `counterUpdate` looks them up the same way as partition keys and throws `KandraSchemaException`
  naming the missing column if one is absent.

**All five of these bypass `BatchEngine` entirely** — they call `session.execute(...)` (note: even in
`KandraSuspendRepository`, these five methods call the **blocking** `session.execute`, not
`session.executeSuspend`, despite being declared `suspend fun` — see Gotchas) directly from the
repository. That means: no retry-on-transient-failure, no shutdown rejection, no metrics recording,
no slow-query logging, and no cache invalidation for any of these five operations.

```kotlin
userRepo.append(user, User::tags, listOf("vip"))
userRepo.remove(user, User::tags, listOf("trial"))
userRepo.put(user, User::metadata, mapOf("theme" to "dark"))
counterRepo.increment(PageViews::count, mapOf("pageId" to id))          // +1
counterRepo.decrement(PageViews::count, mapOf("pageId" to id), by = 5)  // -5
```

## Query DSL (`io.kandra.runtime.dsl`)

```kotlin
sealed class KandraPredicate {
    data class Eq(val column: String, val value: Any?) : KandraPredicate()
    data class Gt(val column: String, val value: Any?) : KandraPredicate()
    data class Gte(val column: String, val value: Any?) : KandraPredicate()
    data class Lt(val column: String, val value: Any?) : KandraPredicate()
    data class Lte(val column: String, val value: Any?) : KandraPredicate()
    data class In(val column: String, val values: List<Any?>) : KandraPredicate()
}

class KandraColumnRef<T>(val cqlName: String, val isLookup: Boolean = false) {
    infix fun eq(value: T): KandraPredicate
    infix fun gt(value: T): KandraPredicate
    infix fun gte(value: T): KandraPredicate
    infix fun lt(value: T): KandraPredicate
    infix fun lte(value: T): KandraPredicate
    infix fun isIn(values: List<T>): KandraPredicate
}

class QueryContext {
    operator fun KandraPredicate.unaryPlus()   // adds the predicate — `+Table.col.eq(x)`
    fun limit(n: Int)                          // appends LIMIT n to the generated CQL
}

data class KandraPage<T>(val items: List<T>, val nextPageToken: String?, val hasMore: Boolean)

interface KandraTable<T>   // marker interface implemented by KSP-generated `*Table` objects

class KandraRawQuery internal constructor(val cql: String, val params: List<Any?>) {
    companion object { fun cql(template: String): KandraRawQueryBuilder }
}
class KandraRawQueryBuilder(template: String) {
    fun bind(vararg values: Any?): KandraRawQueryBuilder
    fun build(): KandraRawQuery
}
```

`KandraRawQuery` has no public constructor — always build via
`KandraRawQuery.cql("SELECT * FROM t WHERE col = ?").bind(value).build()`. `KandraColumnRef` is what
KSP-generated `*Table` objects expose per column (e.g. `UserTable.email`); `isLookup` is metadata only
— it doesn't change how the predicate is built, `QueryExecutor` figures out the lookup-vs-direct
routing from `schema.lookupTables` at execution time regardless of this flag.

```kotlin
repository.findAll {
    +UserTable.status.eq("ACTIVE")
    limit(20)
}
```

`QueryContext` predicates are ANDed in the order added; there is no `or`, and (as noted above) no
`allowFiltering()` — every predicate must resolve through a partition key, a single-column partition
key `IN`, a `@LookupIndex` column (`Eq` only), or a `@SecondaryIndex` column.

## BatchEngine (`@InternalKandraApi`)

Not meant to be constructed directly by consumers — it's what `KandraRepository`/
`KandraSuspendRepository` call into for every write, and what `KandraBatchScope` uses for
caller-controlled batches. Documented here because its exact retry/chunking/eventual-write semantics
determine repository behavior.

```kotlin
class BatchEngine(
    session: CqlSession,
    statementBuilder: StatementBuilder,
    scope: CoroutineScope,
    eventListener: KandraEventListener? = null,   // @ExperimentalKandraApi
    retryConfig: RetryConfig = RetryConfig(),
    debugConfig: DebugConfig = DebugConfig(),
    codec: KandraCodec = KandraCodec.default
) {
    val isShuttingDown: AtomicBoolean
    val inFlightCount: AtomicInteger

    fun setMetrics(recorder: KandraMetrics)
    fun configureBatchLimits(warnThresholdKb: Int, maxChunkSize: Int, autoChunk: Boolean, tombstoneWarnThreshold: Int = 1000)
    fun <T : Any> registerValidator(klass: KClass<T>, validator: KandraValidator<T>)
}
```

- **Retry**: `executeWithRetry`/`executeWithRetrySuspend` wrap every batch/statement execution. On
  any `Throwable` whose class is in `retryConfig.retryOn` (default: `WriteTimeoutException`,
  `ReadTimeoutException`, `NoNodeAvailableException`), retries with linear backoff
  `min(backoffMillis * (attempt + 1), maxBackoffMillis)` up to `maxAttempts` times (default 3), using
  `Thread.sleep` on the blocking path and `kotlinx.coroutines.delay` on the suspend path. Any
  exception **not** in `retryOn` is rethrown immediately (no retry). After exhausting `maxAttempts`,
  throws `KandraQueryException("Query failed after N attempts", lastError)`.
- **Shutdown**: every execute call starts with `checkNotShuttingDown()` — throws
  `KandraQueryException("Kandra is shutting down — new queries are rejected")` if `isShuttingDown` is
  set. `inFlightCount` is incremented before and decremented after (in a `finally`) — used by graceful
  shutdown to drain in-flight requests.
- **Metrics/slow-query logging**: after a successful execute, if `debugConfig.logSlowQueriesMs > 0` and
  elapsed time exceeds it, logs a WARN; then calls `metricsRecorder?.record(tableName, operation, elapsed)`
  if `setMetrics` was called. Both happen only on success, not on a retried-then-failed call.
- **`collectSave`/`collectDelete`** (`internal`) — used by `KandraBatchScope` to gather statements
  without executing them; `collectSave` throws `KandraQueryException` immediately for counter tables.

## StatementBuilder (`@InternalKandraApi`)

Builds every `BoundStatement`. Key behaviors:

- **Prepared statement cache**: a `Collections.synchronizedMap` around a `LinkedHashMap` in
  access-order mode (`accessOrder = true`), capped at `cacheSize` (default 1000, matching
  `KandraConfig.preparedStatementCacheSize`). Eviction of the LRU entry logs a WARN suggesting
  raising the cache size. Keyed by the exact CQL string.
- **`insertPrimary`**: builds `INSERT INTO <table> (cols...) VALUES (?...) [IF NOT EXISTS] [USING TTL
  x AND TIMESTAMP y]`. For each column, encodes the value via `KandraCodec`; if the encoded result is
  `KandraUnset`, calls `unset(idx)` — **except** for partition-key/clustering-key columns, where an
  unset (i.e. a `null` value from the entity) throws `KandraSchemaException` immediately, since key
  columns can never be left unbound. Sets `setIdempotent(ifNotExists)` — a plain `INSERT` is treated
  as **not** idempotent (retrying could duplicate side effects at the CQL level in edge cases /
  is simply the conservative default), while `INSERT ... IF NOT EXISTS` is idempotent. Resolves write
  consistency via `resolveWriteConsistency` (override param → `@WriteConsistency` class annotation →
  `consistencyConfig.defaultWrite`).
- **`insertPrimaryWithNulls`**: identical column/CQL construction, but binds actual `null` (via
  `setBytesUnsafe(idx, null)`) instead of calling `unset` for null values — this is what makes
  `saveWithNulls` tombstone instead of skip.
- **`insertLookup`/`deleteLookup`**: simple single-table statements against the lookup table;
  `insertLookup` is `setIdempotent(false)`, `deleteLookup` is `setIdempotent(true)`. Neither applies
  any consistency-level override — they always use the driver/session default consistency, regardless
  of the entity's `@WriteConsistency` or any per-call `consistency` parameter.
- **`selectById`/`selectByPartitionKeyIn`**: apply `resolveReadConsistency`; both `setIdempotent(true)`.
  `selectByPartitionKeyIn` throws `KandraSchemaException` if `schema.partitionKeys.size != 1`.
- **`appendToCollection`/`removeFromCollection`/`counterUpdate`**: all `setIdempotent(false)` (collection
  and counter mutations are never safe to blindly retry); all throw `KandraSchemaException` if the
  named column isn't found in the schema.
- **`existsQuery`**: builds `SELECT pk... FROM <table> WHERE <whereCql> LIMIT 1` — present in
  `StatementBuilder` but **not called** by `QueryExecutor.exists()` (which builds its own
  key-selecting SELECT inline via `resolveRows(limitOne = true, selectKeys = true)` instead); keep
  this in mind if you're tracing `exists()` behavior — the logic lives in `QueryExecutor`, not here.

## Codec (`io.kandra.runtime.codec.KandraCodec`)

```kotlin
object KandraUnset   // sentinel: "leave this column unchanged", never a bound null

class KandraCodec {
    @ExperimentalKandraApi fun <T : Any> registerEncoder(klass: KClass<T>, encoder: (T) -> Any?)
    @ExperimentalKandraApi fun <T : Any> registerDecoder(klass: KClass<T>, decoder: (Row, String) -> T?)
    fun encode(value: Any?, type: KType): Any?
    fun decode(row: Row, column: ColumnSchema): Any?
    companion object { val default: KandraCodec }
}
```

- **`encode(null, type)`**: returns `KandraUnset` if `type.isMarkedNullable`; else throws
  `KandraQueryException("Non-nullable field received null value (type: $type)")`.
- **`encode(value, type)`** (non-null): checks `customEncoders` first (registered per-`KClass`); then,
  if the classifier is an `Enum` subclass, encodes as `(value as Enum<*>).name` (a `String`); otherwise
  returns the value as-is (the driver handles `UUID`, `String`, numeric types, `Instant`,
  `LocalDate`, `BigDecimal`, `ByteArray`, `List`/`Set`/`Map` natively via `setEncoded`, which does
  `set(idx, value, value::class.java)`).
- **`decode(row, column)`**: **`List`/`Set`/`Map` columns are exempt from the NULL check entirely** —
  Cassandra can't store an empty (non-frozen) collection any other way than NULL, so a NULL collection
  column always decodes to an empty collection (via `row.getList`/`getSet`/`getMap`, which return empty
  rather than null for a NULL column), regardless of whether the Kotlin property is declared nullable.
  This is what makes the idiomatic `tags: Set<String> = emptySet()` default readable after a round-trip.
  For every other type: if the column is NULL in Scylla, returns `null` when the Kotlin type is
  nullable, else throws `KandraQueryException` naming the column and property — this is the exception
  you'll see if you widen a column's actual nullability in the DB without updating the entity. For
  non-null columns, checks `customDecoders` first, then dispatches by classifier: `UUID`, `String`,
  `Int`, `Long`, `Boolean`, `Double`, `Float`, `Instant`, `LocalDate`, `BigDecimal`, `ByteArray` (reads
  the raw `ByteBuffer` into a fresh array), `List`/`Set` (reads generic element type from `KType`
  arguments, defaults to `Any` if unresolvable), `Map` (same for key/value types), `Enum` subclasses
  (`java.lang.Enum.valueOf` against the stored `String` — **throws `IllegalArgumentException`, not a
  Kandra exception**, if the stored string doesn't match any enum constant, e.g. after a rename), and
  falls back to `row.getObject(name)` for anything else.
- Custom codecs are **per-`KandraCodec`-instance** (`ConcurrentHashMap`), registered via
  `@ExperimentalKandraApi fun registerEncoder/registerDecoder`. `KandraCodec.default` is a shared
  singleton — if your app registers a custom encoder there without funneling every repository through
  the *same* codec instance, some paths may not see it (repositories accept a `codec` only implicitly
  via `KandraCodec.default`, since `StatementBuilder`/`QueryExecutor` inside `KandraRepository`/
  `KandraSuspendRepository` are constructed with default params, i.e. always `KandraCodec.default`).

## Caching (`io.kandra.runtime.cache.KandraCache`)

```kotlin
internal class KandraCache<K : Any, V : Any>(config: CacheResultConfig?) {
    fun getIfPresent(key: K): V?
    fun put(key: K, value: V)
    fun invalidate(key: K)
    val isEnabled: Boolean
}
```

Reflection-based thin wrapper — resolves `com.github.benmanes.caffeine.cache.Caffeine` by
`Class.forName` at construction. If `config == null` (no `@CacheResult` on the entity), or the class
isn't found (Caffeine not on the classpath — logs one WARN), or reflective `Method` resolution fails
for `getIfPresent`/`put`/`invalidate` (logs one WARN per failed method), the cache silently becomes a
no-op (`isEnabled == false`, all calls are no-ops). `getIfPresent`/`put`/`invalidate` are resolved
against Caffeine's public `Cache` interface (not `inner`'s concrete runtime class, which is
package-private) — resolving via the interface keeps the `Method`'s declaring class public, so
`Method.invoke` doesn't need `setAccessible(true)` to avoid `IllegalAccessException`. Built with
`Caffeine.newBuilder().expireAfterWrite(ttlSeconds, SECONDS).maximumSize(maxSize).build()`. Only
`findById` reads/writes this cache — `find`, `findAll`, `findPage`, and `exists` never touch it.

**Invalidation is called from**: `save`, `saveIfNotExists` (only if applied), `saveAll` (per entity),
`update`, `updateForce`, `saveWithNulls`, `delete`, `deleteAll` (per entity). **Not called from**:
`deleteById`, `deleteBy`, `append`, `remove`, `put`, `increment`, `decrement` — see Gotchas.

## Config classes

```kotlin
class ConsistencyConfig {
    var defaultRead: KandraConsistency = KandraConsistency.LOCAL_ONE
    var defaultWrite: KandraConsistency = KandraConsistency.LOCAL_QUORUM
    var defaultSerialConsistency: KandraConsistency = KandraConsistency.LOCAL_SERIAL   // not read anywhere in this module — saveIfNotExists takes serialConsistency as a direct param instead
}

class DebugConfig {
    var logQueries: Boolean = false        // logs CQL template (never bound values) at DEBUG in StatementBuilder.prepare
    var logSlowQueriesMs: Long = 0L        // 0 = disabled; WARN in BatchEngine.executeWithRetry(Suspend)
    var logBatches: Boolean = false        // DEBUG-logs batch statement count; only checked by save()/saveAll() (and their suspend variants), NOT by update()/updateForce()/saveWithNulls()/delete()
}

class RetryConfig {
    var maxAttempts: Int = 3
    var backoffMillis: Long = 100
    var maxBackoffMillis: Long = 2000
    var retryOn: Set<KClass<out Throwable>> = setOf(WriteTimeoutException::class, ReadTimeoutException::class, NoNodeAvailableException::class)
}
```

## KandraBatchScope (`@ExperimentalKandraApi`)

```kotlin
class KandraBatchScope internal constructor(session: CqlSession, batchEngine: BatchEngine) {
    fun <T : Any> KandraRepository<T>.saveInBatch(entity: T, ttlSeconds: Int? = null)
    fun <T : Any> KandraSuspendRepository<T>.saveInBatch(entity: T, ttlSeconds: Int? = null)
    fun <T : Any> KandraRepository<T>.deleteInBatch(entity: T)
    fun <T : Any> KandraSuspendRepository<T>.deleteInBatch(entity: T)
    fun <T : Any> KandraSuspendRepository<T>.saveIfNotExistsInBatch(entity: T): Boolean   // always throws
}
```

Only reachable via `KandraRuntime.batch { }` (suspend) / `batchBlocking { }`. Collects statements from
`saveInBatch`/`deleteInBatch` calls (via `BatchEngine.collectSave`/`collectDelete` — same primary-row +
`BATCH`-lookup statement building as the standalone methods, `EVENTUAL` lookups are **not** included)
into one list, then on scope exit executes them as a single `LOGGED BATCH` via a plain **blocking**
`session.execute(batch)` — even inside the `suspend fun batch { }` variant, the final commit is not
`executeSuspend`, so it blocks the calling coroutine's thread. `findAll`/`findById`/any read method is
not exposed by this scope (reads can't be batched in CQL) — calling them on a captured repo still
works, but executes immediately and is not part of the batch. Calling `saveIfNotExistsInBatch` inside
the block always throws `KandraQueryException` — LWT can't share a `LOGGED BATCH` with regular
statements. No cache invalidation happens for entities saved/deleted through a batch scope (batch
collection bypasses `KandraRepository`'s own `save`/`delete`, which are what call `cache.invalidate`).

**Naming is deliberate, not stylistic**: these are named `saveInBatch`/`deleteInBatch`, not `save`/
`delete`, because Kotlin always resolves a same-named member of the extension receiver over an
extension function — even a member-extension of an implicit outer receiver, which is exactly what
these are (declared inside `KandraBatchScope`, extending `KandraRepository`/`KandraSuspendRepository`).
Since every repository already has its own real `save`/`delete`, a same-named version here would be
structurally unreachable through any normal call syntax (`repo.save(x)` or `with(repo) { save(x) }`
both resolve to the repository's own immediately-executing method) — silently defeating the batch with
no compiler warning. Distinct names route correctly and make the wrong call a compile error instead.

```kotlin
runtime.batchBlocking {
    userRepo.saveInBatch(user)
    walletRepo.saveInBatch(wallet)
    auditRepo.deleteInBatch(oldAudit)
}
```

## CqlSession extensions (`io.kandra.runtime.driver`)

```kotlin
suspend fun CqlSession.prepareSuspend(cql: String): PreparedStatement       // prepareAsync().toCompletableFuture().await()
suspend fun CqlSession.executeSuspend(statement: Statement<*>): AsyncResultSet
suspend fun CqlSession.executeSuspendAll(statement: Statement<*>): List<Row> // drains every page via fetchNextPage()
```

`executeSuspendAll` is the only helper here that fully materializes a multi-page result — nothing in
`QueryExecutor` calls it (`findAll`/`raw`/`rawQuery` use blocking `rs.all()` even from the suspend
repository, since `QueryExecutor` itself has no suspend variant — see Gotchas).

## KandraEntityLogger (`internal`, `@InternalKandraApi`)

```kotlin
internal object KandraEntityLogger {
    fun safeToString(entity: Any, schema: TableSchema): String
}
```

Reflectively renders `ClassName(prop=value, ...)` for every member property, substituting `***` for
any property whose matching `ColumnSchema.isSensitive` is `true` (matched against `schema.columns`,
`schema.partitionKeys`, or `schema.clusteringKeys` by property name — a property with no schema match
at all is logged unmasked). Not wired into any exception message or logger call within
`kandra-runtime` itself as read here — it's a utility for consumers (e.g. `kandra-ktor`) that want
Kandra-aware safe logging of entities.

## Gotchas worth double-checking in review

- **Repository instances are not free.** `KandraRuntime.repository<T>()`/`suspendRepository<T>()`
  build a brand-new `StatementBuilder` (own 1000-entry prepared-statement cache) and a brand-new
  `KandraCache` **every call**. Create repositories once (DI singleton) and reuse them — calling
  `runtime.repository<User>()` per-request throws away the statement cache and the `@CacheResult`
  cache every time.
- **Read/misc paths may not see your `install(Kandra) { consistency {} / debug {} }` config.**
  `KandraRepository`/`KandraSuspendRepository` construct their internal `StatementBuilder(session)`
  with **default-constructed** `ConsistencyConfig()`/`DebugConfig()` — not whatever instance the
  plugin wired into `BatchEngine`. Per-call `consistency = ...` parameters and `@ReadConsistency`/
  `@WriteConsistency` class annotations still work everywhere (they're resolved independent of which
  config instance is default), but the plugin-level `defaultRead`/`defaultWrite`/`logQueries` defaults
  only reliably apply to writes routed through `BatchEngine` (`save`, `saveIfNotExists`,
  `saveWithNulls`, `update`, `updateForce`, `delete`, `deleteAll`, `saveAll`, `deleteBy`) — not to
  `findById`/`find`/`findAll`/`findPage`/`exists`/`raw`/`rawQuery`/`deleteById`(blocking)/
  `append`/`remove`/`put`/`increment`/`decrement`, all of which use the repository's own default-config
  `StatementBuilder`.
- **`deleteById` cache/soft-delete/retry gaps.** Blocking `KandraRepository.deleteById()` bypasses
  `BatchEngine` completely: no retry, no shutdown-rejection, and it **ignores `@SoftDelete`** (always
  hard-deletes). The suspend version respects soft-delete/retry (it delegates to
  `batchEngine.deleteSuspend`), but **neither** version invalidates the `KandraCache` — a cached
  `findById` result will keep returning the deleted row until TTL expiry.
- **`deleteBy` also skips cache invalidation** even though it does route through `BatchEngine`
  (so soft-delete/retry work there) — same stale-cache risk as `deleteById`.
- **`append`/`remove`/`put`/`increment`/`decrement` bypass `BatchEngine` entirely** — no retry on
  transient failures, no shutdown rejection, no metrics, and no cache invalidation. In
  `KandraSuspendRepository` these five are declared `suspend fun` but internally call the **blocking**
  `session.execute(...)`, not `session.executeSuspend(...)` — they will block the calling coroutine's
  thread on the driver call.
- **`updateForce` doesn't clean up lookup tables when the indexed field changes.** It diffs the
  pre-timestamp entity against the post-timestamp entity — both are the *same* value you passed in, so
  the lookup-index-column-changed check never fires. Only two-argument `update(old, new)` correctly
  deletes the stale lookup row, because it diffs the caller-supplied `old` against `new`.
- **`update(old, new)` without a `@Version` column performs no concurrency check at all** — it's a
  blind full-row overwrite. The `old` argument is used *only* to compute which lookup-table rows to
  delete/insert; a stale or fabricated `old` is silently accepted.
- **`saveAll(useBatch = true)` auto-chunking breaks cross-chunk atomicity.** Once entity/statement
  count exceeds `batchMaxChunkSize` (default 100), Kandra issues multiple independent `LOGGED BATCH`
  statements — a failure partway through a large `saveAll` leaves earlier chunks committed.
- **`saveAll`'s per-entity fallback path (`useBatch = false`) skips validators** — only single `save()`
  calls `validateEntity`.
- **The `IN` error message references a nonexistent `allowFiltering()`.** `QueryContext` has no such
  method; the real remedies are restricting `IN` to a (single-column) partition key or adding
  `@SecondaryIndex` to the column.
- **`exists()` is only cheap on a direct-predicate query.** Via a `@LookupIndex` or `IN` predicate it
  runs the full `SELECT *` query (same cost as `find()`), not the `SELECT pk... LIMIT 1` you'd expect
  from the name.
- **`raw()`'s injection-risk warning is a narrow heuristic** (`cql.contains("'")` or the literal
  substring `"="`) — its absence doesn't mean the query is safe; prefer `rawQuery(KandraRawQuery...)`,
  which is always parameterized.
- **Composite partition-key `IN` throws `KandraSchemaException`**, not `KandraQueryException` — catch
  the right type if you're handling this specifically.
- **Counter tables**: `save`/`saveIfNotExists`/`saveWithNulls`/`collectSave` (batch scope) all throw
  `KandraQueryException` on a counter table — only `increment`/`decrement` work, and they require
  `schema.isCounterTable` (else `KandraSchemaException`).
