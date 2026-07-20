---
name: kandra
description: Reference for writing Kotlin code against Kandra, the ScyllaDB/Cassandra ORM shipped as a Ktor plugin. Load this whenever defining @ScyllaTable entities, calling Kandra repositories, configuring install(Kandra) { ... }, or debugging Kandra exceptions (KandraOptimisticLockException, KandraQueryException, KandraValidationException, KandraMigrationException). Covers exact method signatures, annotation reference, and common pitfalls that don't compile-check.
---

# Kandra

Kandra is a Kotlin-first ORM for ScyllaDB/Cassandra, installed as a Ktor `ApplicationPlugin`. It generates
type-safe `*Table` objects via KSP, auto-binds repositories into Kodein or Koin, and fails fast on schema
errors at startup rather than at query time. Full docs: `README.md` in the Kandra repo, or wherever this
skill was copied from — check for a vendored `README.md` alongside this skill first.

## Entity annotations

| Annotation | Target | Notes |
|---|---|---|
| `@ScyllaTable(name, gcGraceSeconds = -1)` | Class | Maps to a CQL table. `gcGraceSeconds` controls tombstone survival time. |
| `@PartitionKey(index = 0)` | Property | Use `index` for composite partition keys — multiple fields share the same tuple. |
| `@ClusteringKey(order = ASC, index = 0)` | Property | Clustering column. |
| `@LookupIndex(tableSuffix, consistency = BATCH)` | Property | Generates a denormalized `{table}_{suffix}` lookup table. `BATCH` = atomic with the primary write; `EVENTUAL` = fire-and-forget after commit. High-cardinality query fields (email, phone) should use this, not `@SecondaryIndex`. |
| `@Column(name)` | Property | Overrides the CQL column name. |
| `@Transient` | Property | Excluded from all CQL operations. |
| `@Ttl(seconds)` | Class | Default TTL for the primary table (not inherited by lookup tables). |
| `@Counter` | Property | ScyllaDB `COUNTER` column. **Every** non-key column in a counter table must be `@Counter` — mix with regular columns and DDL fails. |
| `@CreatedAt` / `@UpdatedAt` | Property (`Instant`) | Auto-filled via `copy()` reflection. `@CreatedAt` only fires on INSERT. |
| `@Version` | Property (`Long` or `Instant`) | Optimistic lock. `update()` emits `IF version = ?`; throws `KandraOptimisticLockException` on mismatch. `save()` always sets the initial version — only `update()` enforces the check. |
| `@SoftDelete(ttlSeconds = 86400)` | Class | `delete()` sets a TTL on non-key columns instead of `DELETE`. There is no built-in `findActive()` — you must add your own `isDeleted`/`deletedAt` marker column and filter on it if you need to query out soft-deleted rows. |
| `@Sensitive` | Property | Masked as `***` by `KandraEntityLogger` in all log output (DEBUG query logs, errors, `toString()`). |
| `@CacheResult(ttlSeconds = 60, maxSize = 1000)` | Class | Caffeine-backed `findById` cache; invalidated on save/update/delete. No-ops silently if `com.github.ben-manes.caffeine:caffeine` isn't on the classpath. |
| `@SecondaryIndex` | Property | Native CQL secondary index — scatter-gather across **all** nodes. Only for low-cardinality fields on non-hot-path queries; logs WARN on every query. Prefer `@LookupIndex`. |
| `@ReadConsistency(level)` / `@WriteConsistency(level)` | Class | `KandraConsistency` override. Resolution order: per-call arg > class annotation > `ConsistencyConfig` default. |

## Repository API — exact signatures

Both `KandraRepository<T>` (blocking) and `KandraSuspendRepository<T>` (suspend, same signatures with `suspend fun`) expose:

```kotlin
fun save(entity: T, ttlSeconds: Int? = null, timestampMicros: Long? = null, consistency: KandraConsistency? = null)
fun saveWithNulls(entity: T, ttlSeconds: Int? = null)     // writes explicit NULL, not UNSET — causes tombstones
fun saveIfNotExists(entity: T, serialConsistency: KandraConsistency = LOCAL_SERIAL): Boolean   // LWT
fun saveAll(entities: List<T>, useBatch: Boolean = true)   // auto-chunks per batchMaxChunkSize/batchAutoChunk

fun update(old: T, new: T)     // ⚠️ TWO args — not update(new). LWT IF version=? when @Version present
fun updateForce(entity: T)     // bypasses the @Version check entirely

fun delete(entity: T)          // TTL-only if @SoftDelete, else DELETE
fun deleteAll(entities: List<T>)
fun deleteById(vararg keyValues: Any)
fun deleteBy(block: QueryContext.() -> Unit)   // resolves via lookup, fetches entity, cleans all lookup tables too

fun raw(cql: String, vararg params: Any?): List<Row>       // WARNs if string literals present with no params
fun rawQuery(query: KandraRawQuery): List<Row>              // always parameterised — prefer this over raw()
```

`KandraRawQuery` has no public constructor — build it with `KandraRawQuery.cql("...").bind(v1, v2).build()`.

Plain `save()` on a nullable field set to `null` encodes to `UNSET` (no tombstone) — use `saveWithNulls()` only
when you deliberately want to clear/tombstone a column.

## Plugin config — `install(Kandra) { ... }`

Top-level `KandraConfig` properties (not nested under sub-blocks unless noted):

```kotlin
contactPoints, keyspace, localDatacenter
autoCreateKeyspace: Boolean, replicationStrategy: ReplicationStrategy
schemaMode: SchemaMode                    // AUTO_CREATE (default) | AUTO_MIGRATE | VALIDATE | NONE
validatePermissions: Boolean = true       // checks SELECT+MODIFY on system_auth.role_permissions at startup
preparedStatementCacheSize: Int = 1000
tombstoneWarnThreshold: Int = 1000
batchWarnThresholdKb: Int = 5             // top-level, NOT inside a batch { } block
batchMaxChunkSize: Int = 100              // top-level
batchAutoChunk: Boolean = true            // top-level
healthCheck: Boolean = true               // exposes GET /kandra/health
register(vararg classes: KClass<*>)
```

Sub-block DSLs (each is `fun xxx(block: XxxConfig.() -> Unit)`):

```kotlin
pool { requestTimeoutMillis = 10_000; maxRequestsPerConnection = 32768 }
auth { provider = KandraAuth.fromEnv(); refreshIntervalSeconds = 3600 }
retry { maxAttempts = 3; backoffMillis = 100 }
debug { logQueries = false; logSlowQueriesMs = 500 }
consistency { defaultRead = ...; defaultWrite = ... }
ssl { enabled = true; trustStorePath = "..."; hostnameVerification = true }
loadBalancing { tokenAware = true; dcAwareFailover = true; allowedRemoteDcs = listOf("eu-west") }
failover { onLocalDcUnavailable = FailoverPolicy.RETRY_REMOTE_DC }
speculativeExecution { enabled = true; delayMillis = 100; maxAttempts = 2 }
shutdown { graceful = true; drainTimeoutMs = 5_000 }
metrics { enabled = true; recorder = KandraMetrics { table, op, ms -> ... } }   // NOT meterRegistry/tagX props
validate<User> { user -> buildList { if (bad) add(KandraValidationError("field", "message")) } }
```

`SchemaMode.AUTO_MIGRATE` adds columns found on the entity but missing in `system_schema.columns`; it never
drops or renames. For renames/backfills/index changes, use the separate `kandra-migrate` module:

```kotlin
object V2_AddPhoneIndex : KandraMigration(version = 2, name = "add phone index") {
    override fun up(session: CqlSession) { session.execute("CREATE INDEX IF NOT EXISTS ...") }
}
KandraMigrationRunner(session).run(V2_AddPhoneIndex)   // call BEFORE install(Kandra) with schemaMode = NONE
```
Migrations are `object`s (singletons), checksummed from `(version, name, class)` — never edit an applied
migration's body, or `KandraMigrationRunner` throws `KandraMigrationException` on the checksum mismatch.

## Exceptions

All extend `KandraException(message, cause)`:

- `KandraSchemaException` — schema mismatch (VALIDATE mode) or DDL error.
- `KandraQueryException` — bad query construction: non-nullable field got a null column, `IN` on a non-PK
  column without an index, composite-PK `IN` query, invalid serial consistency, etc.
- `KandraAuthException` — auth/SSL handshake failures, missing keyspace permissions.
- `KandraOptimisticLockException(message, entityClass, partitionKey)` — `update()` LWT check failed; re-read
  and retry, don't just swallow it.
- `KandraValidationException(errors: List<KandraValidationError>)` — thrown by the `validate<T> { }` hook.
- `KandraMigrationException` — checksum mismatch on an already-applied migration.

## Testing

- `FakeKandraSession` + `KandraTestUtils.inMemory(User::class)` — pure unit tests, no database. Call
  `SchemaRegistry.clear()` in `@AfterEach` (schema registration is process-global).
- `KandraTestcontainers.freshKeyspace(User::class)` — real Testcontainers Cassandra, isolated
  `kandra_test_{uuid}` keyspace per test class, one shared container per JVM. `db.close()` drops the keyspace.

## Gotchas worth double-checking in review

- `update(old, new)` takes **two** entities, not one — a common transcription mistake from `save(entity)`.
- `saveIfNotExists` (LWT) cannot be mixed with other statements in `application.kandra.batch { }` — it throws immediately if attempted.
- Counter tables can't use `save()` — only `increment()`/`decrement()`.
- `@SecondaryIndex` queries scatter-gather the whole cluster; reach for `@LookupIndex` for anything on a hot path or high-cardinality field.
- `IN` queries only work on single-column partition keys — composite PK tables throw `KandraQueryException`.
- `saveWithNulls()` vs `save()` — using the wrong one either leaves stale data (should've nulled it) or writes unwanted tombstones (should've used UNSET).
