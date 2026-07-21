# Kandra 0.4.0-SNAPSHOT — Update Prompt for Claude Code

This is a delta update on top of the existing Kandra 0.3.0-SNAPSHOT codebase.
Do not rebuild from scratch. Apply each change to the existing modules in place.
Bump the version in `gradle.properties` from `0.3.0-SNAPSHOT` to `0.4.0-SNAPSHOT`.

---

## PART A — Critical Correctness Fixes

---

## 1. Async Driver Usage (`kandra-runtime`)

**Problem:** `KandraSuspendRepository` wraps synchronous `session.execute()` in
`withContext(Dispatchers.IO)` — this still blocks a thread per query. Under CoinX load
(hundreds of concurrent wallet reads) this exhausts the IO thread pool. The DataStax Java
driver has a fully async API that must be used instead.

**Change — replace all `session.execute()` in suspend paths with `session.executeAsync()`:**

```kotlin
// Add to kandra-runtime build.gradle.kts
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.8.x")

// Extension in io.kandra.runtime.driver
suspend fun CqlSession.executeSuspend(statement: Statement<*>): AsyncResultSet =
    executeAsync(statement).toCompletableFuture().await()

suspend fun CqlSession.executeSuspendAll(statement: Statement<*>): List<Row> {
    val rows = mutableListOf<Row>()
    var resultSet = executeAsync(statement).toCompletableFuture().await()
    rows.addAll(resultSet.currentPage())
    while (!resultSet.isFullyFetched) {
        resultSet = resultSet.fetchNextPage().toCompletableFuture().await()
        rows.addAll(resultSet.currentPage())
    }
    return rows
}
```

**Change — `KandraSuspendRepository`:** replace every `withContext(Dispatchers.IO) { session.execute(...) }`
with `session.executeSuspend(...)`. Remove all `Dispatchers.IO` wrappers from suspend paths.

**Change — `BatchEngine` suspend methods:** same replacement — use `executeAsync` bridged
via `await()`.

**Change — `KandraRepository` (blocking):** blocking paths keep `session.execute()` —
no change needed. Document clearly in KDoc that `KandraRepository` is for blocking
contexts only and `KandraSuspendRepository` is the correct choice for Ktor routes.

**Change — retire `Dispatchers.IO` from `BatchEngine` coroutine scope:** the scope passed
to `BatchEngine` for `EVENTUAL` fire-and-forget writes should use `Dispatchers.Default`
(CPU-bound coroutine scheduler) not `Dispatchers.IO` since the underlying call is now
truly async and non-blocking.

**New tests:**
- `KandraSuspendRepository.save()` does not block any thread (verify via
  `Dispatchers.Unconfined` test dispatcher — no thread switch should occur)
- `KandraSuspendRepository.findAll()` pages through all results without blocking
- `EVENTUAL` writes use `Dispatchers.Default` scope not `Dispatchers.IO`
- Blocking `KandraRepository` still uses synchronous `session.execute()`

---

## 2. `UNSET` vs `NULL` Column Binding (`kandra-runtime`)

**Problem:** `save()` currently generates `INSERT INTO users (col1, col2, col3) VALUES (?, ?, ?)`
binding ALL columns. If a nullable field is `null` in Kotlin, the driver binds `null` —
which writes a tombstone in Scylla. In a high-write system tombstone accumulation causes
read amplification and compaction pressure. `null` in Kotlin should mean "leave this
column alone" not "write a tombstone".

**The distinction:**
- `NULL` bind → writes a tombstone (deletes the cell)
- `UNSET` bind (`com.datastax.oss.driver.api.core.CqlIdentifier.UNSET`) → leaves existing
  value unchanged

**Change — `KandraCodec.encode`:** return a sentinel `Unset` value for `null` Kotlin fields:

```kotlin
object KandraUnset   // sentinel

fun encode(value: Any?, type: KType): Any? {
    if (value == null) {
        return if (type.isMarkedNullable) KandraUnset   // leave column alone
        else throw KandraQueryException("Non-nullable field received null value")
    }
    // ... existing type mapping
}
```

**Change — `StatementBuilder.insertPrimary`:** when binding values, check for `KandraUnset`
and use the driver's `UNSET` sentinel:

```kotlin
columns.forEachIndexed { index, column ->
    val encoded = KandraCodec.encode(value, column.type)
    if (encoded === KandraUnset) {
        boundStatement.unset(index)   // driver API: leaves column unchanged
    } else {
        boundStatement.set(index, encoded, ...)
    }
}
```

**Important — primary key columns are never UNSET:** partition keys and clustering keys
must always be bound. Throw `KandraQueryException` if a key column resolves to `KandraUnset`.

**New `saveWithNulls()` method** for cases where writing an explicit `NULL` / tombstone
is intentional (e.g. clearing a field):

```kotlin
// KandraRepository + KandraSuspendRepository
fun saveWithNulls(entity: T)           // writes NULL for null fields (old behaviour)
suspend fun saveWithNulls(entity: T)
```

**New tests:**
- `save(user)` with `user.phone = null` does NOT write a tombstone for `phone`
- `saveWithNulls(user)` with `user.phone = null` DOES write a tombstone for `phone`
- Partition key column with `null` value throws `KandraQueryException`
- Non-nullable column with `null` value throws `KandraQueryException`
- `UNSET` binding verified via `FakeKandraSession.capturedStatements()` inspection

---

## 3. Optimistic Locking / Compare-and-Set (`kandra-runtime`)

**Problem:** Concurrent updates to the same entity (e.g. two CoinX services updating wallet
balance simultaneously) silently overwrite each other. No mechanism exists for detecting
or preventing lost updates.

**New annotation in `kandra-core`:**

```kotlin
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Version
// Marks a field as the optimistic lock version column.
// Must be Long or Instant.
// KDoc: ScyllaDB implements this via LWT IF col = ? — carries Paxos cost.
// Use LOCAL_SERIAL consistency for single-DC, SERIAL for multi-DC.
```

**Change — `TableSchema`:**

```kotlin
data class TableSchema(
    ...
    val versionColumn: ColumnSchema? = null
)
```

**Change — `SchemaRegistry`:** detect `@Version` field. Validate:
- At most one `@Version` per entity
- Must be `Long` or `Instant` type
- Throw `KandraSchemaException` if violated

**Change — `BatchEngine`:** when entity has a `@Version` column:

- On `save()` (first insert): set version to `1L` (Long) or `Instant.now()` (Instant)
- On `update(old, new)`: generate `UPDATE ... SET ... WHERE pk = ? IF version = ?`
  instead of a plain UPDATE. Read `[applied]` from result:
  - `true` → update succeeded, return normally
  - `false` → throw `KandraOptimisticLockException`

**New exception:**

```kotlin
class KandraOptimisticLockException(
    message: String,
    val entityClass: KClass<*>,
    val partitionKey: Any
) : KandraException(message)
```

**New repository method:**

```kotlin
// update() with @Version entity throws KandraOptimisticLockException on conflict
// updateForce() bypasses version check — for admin/migration use only
fun updateForce(entity: T)
suspend fun updateForce(entity: T)
```

**Auto-increment behaviour:**
- `Long` version: increment by 1 on each successful update
- `Instant` version: set to `Instant.now()` on each successful update

**Usage:**

```kotlin
@ScyllaTable("wallets")
data class Wallet(
    @PartitionKey val walletId: UUID,
    val balance: BigDecimal,
    @Version val version: Long = 0L    // starts at 0, auto-incremented
)

// Service layer
try {
    walletRepo.update(oldWallet, newWallet.copy(balance = newBalance))
} catch (e: KandraOptimisticLockException) {
    // Re-read and retry
    val fresh = walletRepo.findById(walletId)
    walletRepo.update(fresh, fresh.copy(balance = newBalance))
}
```

**New tests:**
- `save()` on `@Version` entity sets version to `1L`
- `update()` succeeds when version matches — version incremented to `2L`
- `update()` throws `KandraOptimisticLockException` when version mismatches
- `updateForce()` bypasses version check
- Two concurrent `update()` calls — only one succeeds, other throws `KandraOptimisticLockException`
- `@Version` on non-`Long`/non-`Instant` field throws `KandraSchemaException`

---

## 4. Tombstone Awareness (`kandra-core` + `kandra-runtime`)

**Problem:** ScyllaDB `DELETE` writes tombstones that persist for `gc_grace_seconds`
(default 10 days). A table with frequent deletes accumulates tombstones causing severe
read amplification. Kandra currently has no awareness of this at all.

### 4.1 `@SoftDelete` Annotation

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class SoftDelete(
    val ttlSeconds: Int = 86400   // default 1 day; entity expires via TTL not tombstone
)
// When applied, delete() sets a TTL on the row instead of issuing a DELETE statement.
// The row expires naturally — no tombstone written.
// KDoc: requires @Ttl or explicit ttlSeconds. Row remains readable until TTL expires.
```

**Change — `BatchEngine.delete()`:** when entity class has `@SoftDelete`:
- Do NOT execute `DELETE FROM table WHERE pk = ?`
- Instead execute `UPDATE table USING TTL ? SET ... WHERE pk = ?` setting all non-key
  columns to their current values with the soft-delete TTL
- Also update lookup tables with the same TTL so they expire together

**New `findActive()` query method** for `@SoftDelete` entities that filters out
logically-deleted rows (rows near TTL expiry have `writetime()` detectable via
`SELECT TTL(col) FROM table`):

```kotlin
// KandraRepository / KandraSuspendRepository — only available on @SoftDelete entities
fun findActive(block: QueryContext.() -> Unit): List<T>
suspend fun findActive(block: QueryContext.() -> Unit): List<T>
// Filters rows where TTL(any_column) IS NOT NULL (i.e. soft-deleted rows near expiry)
// Note: this requires ALLOW FILTERING on non-key columns — log WARN
```

### 4.2 Tombstone Warning on Bulk Delete

When `deleteAll()` or `deleteBy()` would delete more than a configurable threshold of rows,
log a `WARN`:

```kotlin
class KandraConfig {
    ...
    var tombstoneWarnThreshold: Int = 1000
}
```

```
WARN: deleteBy() will delete approximately N rows on table 'users', generating N tombstones.
Consider using @SoftDelete or a TTL-based expiry strategy instead.
ScyllaDB tombstones persist for gc_grace_seconds (default 864000s / 10 days).
```

### 4.3 `gc_grace_seconds` in DDL

Expose `gcGraceSeconds` on `@ScyllaTable`:

```kotlin
@ScyllaTable("transactions", gcGraceSeconds = 86400)  // 1 day for high-delete tables
data class Transaction(...)
```

`DdlGenerator` appends `WITH gc_grace_seconds = N` to the `CREATE TABLE` statement.

**New tests:**
- `delete()` on `@SoftDelete` entity sets TTL instead of executing DELETE
- Lookup tables get matching TTL on soft delete
- `deleteBy()` deleting > `tombstoneWarnThreshold` rows logs WARN
- `@ScyllaTable(gcGraceSeconds = 86400)` DDL includes `WITH gc_grace_seconds = 86400`
- `findActive()` excludes soft-deleted rows

---

## 5. Batch Size Guard (`kandra-runtime`)

**Problem:** ScyllaDB warns at 5KB batch size and hard-limits at 50KB (configurable on
the cluster). `saveAll()` with a large list will hit this limit and fail or degrade
cluster performance silently.

**Change — `BatchEngine`:** before executing any `BatchStatement`, estimate its size and
split if needed:

```kotlin
class KandraConfig {
    ...
    var batchWarnThresholdKb: Int = 5       // log WARN above this
    var batchMaxChunkSize: Int = 100         // max statements per batch chunk
    var batchAutoChunk: Boolean = true       // auto-split large batches
}
```

**Change — `BatchEngine.executeWithSizeCheck`:**

```kotlin
private fun executeWithSizeCheck(batch: BatchStatement) {
    val estimatedSize = batch.statements.sumOf { estimateStatementSize(it) }

    if (estimatedSize > config.batchWarnThresholdKb * 1024) {
        logger.warn {
            "Batch size ~${estimatedSize / 1024}KB exceeds warn threshold " +
            "(${config.batchWarnThresholdKb}KB). Consider reducing batch size."
        }
    }

    if (config.batchAutoChunk && batch.statements.size > config.batchMaxChunkSize) {
        batch.statements
            .chunked(config.batchMaxChunkSize)
            .forEach { chunk ->
                val chunkBatch = BatchStatement.newInstance(batch.batchType)
                chunk.forEach { chunkBatch.add(it) }
                session.execute(chunkBatch)
            }
        return
    }

    session.execute(batch)
}

private fun estimateStatementSize(statement: Statement<*>): Int {
    // Rough estimate: sum of bound value sizes
    // Use driver's built-in size estimation where available
    return 512   // conservative default per statement if size unknown
}
```

**Change — `saveAll()`:** always routes through `executeWithSizeCheck`.

**New tests:**
- `saveAll()` with 200 entities auto-chunks into 2 batches of 100
- Batch over `batchWarnThresholdKb` logs WARN
- `batchAutoChunk = false` executes single batch regardless of size
- All entities are saved correctly when auto-chunked
- Lookup tables for all entities are written across chunks

---

## 6. `@Sensitive` Field Log Masking (`kandra-core` + `kandra-runtime`)

**Problem:** `data class` `toString()` includes all fields. When Kandra logs a failed
write it exposes `passwordHash`, `privateKey`, `seedPhrase` in plaintext in logs —
a critical security vulnerability.

**New annotation in `kandra-core`:**

```kotlin
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Sensitive
// Marks a field as sensitive — never logged, never included in toString output.
// KDoc: Kandra will replace this field's value with "***" in all internal logging.
// Does NOT affect what is stored in ScyllaDB — encryption is a separate concern.
```

**Change — `SchemaRegistry`:** track `@Sensitive` fields in `ColumnSchema`:

```kotlin
data class ColumnSchema(
    ...
    val isSensitive: Boolean = false
)
```

**New `KandraEntityLogger` utility in `kandra-runtime`:**

```kotlin
internal object KandraEntityLogger {
    fun safeToString(entity: Any, schema: TableSchema): String {
        val klass = entity::class
        return klass.memberProperties.joinToString(", ", "${klass.simpleName}(", ")") { prop ->
            val column = schema.columns.find { it.propertyName == prop.name }
            val value = if (column?.isSensitive == true) "***" else prop.call(entity)
            "${prop.name}=$value"
        }
    }
}
```

**Change — all Kandra internal logging that references entity content** must use
`KandraEntityLogger.safeToString(entity, schema)` instead of `entity.toString()`.
This includes:
- `BatchEngine` error logging on failed writes
- `KandraEventListener.onEventualWriteFailed` — entity parameter must be pre-masked
- Debug query logging when `logQueries = true`

**Usage:**

```kotlin
@ScyllaTable("users")
data class User(
    @PartitionKey val userId: UUID,
    val email: String,
    @Sensitive val passwordHash: String,
    @Sensitive val twoFactorSecret: String?,
    val name: String
)

// Internal Kandra log output:
// "User(userId=abc-123, email=pasaka@coinx.io, passwordHash=***, twoFactorSecret=***, name=Pasaka)"
```

**New tests:**
- `KandraEntityLogger.safeToString()` masks `@Sensitive` fields with `***`
- Non-sensitive fields appear normally in log output
- `onEventualWriteFailed` entity parameter has sensitive fields masked
- Partition key fields are NEVER masked (needed to identify the failing row)

---

## PART B — Should-Have Items

---

## 7. Health Check Integration (`kandra-ktor`)

**Problem:** No way to check ScyllaDB connectivity from Ktor's health check system.
CoinX load balancers need a `/health` endpoint that reflects DB state.

**Change — add health check support:**

```kotlin
class KandraConfig {
    ...
    var healthCheck: Boolean = true   // enabled by default
}
```

**Change — expose health check function on `KandraRuntime`:**

```kotlin
suspend fun KandraRuntime.isHealthy(): Boolean {
    return try {
        session.executeSuspend(
            SimpleStatement.newInstance("SELECT release_version FROM system.local")
        )
        true
    } catch (e: Exception) {
        logger.warn("ScyllaDB health check failed", e)
        false
    }
}
```

**Integration with Ktor `ktor-server-health-checks`:**

```kotlin
// Auto-registered when healthCheck = true in KandraConfig
install(HealthCheck) {
    check("scylladb") {
        if (application.kandra.isHealthy()) HealthCheckResult.healthy("ScyllaDB connected")
        else HealthCheckResult.unhealthy("ScyllaDB unreachable")
    }
}
```

**Expose `GET /health/scylladb` directly** when `ktor-server-health-checks` is not on
the classpath — fall back to a simple route:

```kotlin
// Registered automatically when healthCheck = true
get("/kandra/health") {
    if (application.kandra.isHealthy()) call.respond(HttpStatusCode.OK, mapOf("status" to "UP"))
    else call.respond(HttpStatusCode.ServiceUnavailable, mapOf("status" to "DOWN"))
}
```

**New tests:**
- `isHealthy()` returns `true` when session is connected
- `isHealthy()` returns `false` when session throws
- `GET /kandra/health` returns `200 OK` when healthy
- `GET /kandra/health` returns `503` when unhealthy
- `healthCheck = false` registers no routes

---

## 8. Graceful Shutdown Drain (`kandra-ktor`)

**Problem:** `session.close()` is called immediately on `ApplicationStopped` — in-flight
queries fail with `IllegalStateException: Session is closed`. Under load this means request
errors at every deployment.

**Change — add `shutdown` block to `KandraConfig`:**

```kotlin
class KandraConfig {
    ...
    val shutdown: ShutdownConfig = ShutdownConfig()
}

class ShutdownConfig {
    var drainTimeoutMs: Long = 5000    // wait up to 5s for in-flight queries
    var graceful: Boolean = true
}
```

**Change — plugin shutdown sequence:**

```kotlin
environment.monitor.subscribe(ApplicationStopping) {
    if (config.shutdown.graceful) {
        // 1. Signal Kandra to stop accepting new queries
        kandraRuntime.isShuttingDown.set(true)

        // 2. Wait for in-flight query count to reach zero
        val deadline = System.currentTimeMillis() + config.shutdown.drainTimeoutMs
        while (kandraRuntime.inFlightCount.get() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }

        if (kandraRuntime.inFlightCount.get() > 0) {
            logger.warn(
                "${kandraRuntime.inFlightCount.get()} queries still in-flight after " +
                "${config.shutdown.drainTimeoutMs}ms drain timeout — forcing close"
            )
        }
    }
}

environment.monitor.subscribe(ApplicationStopped) {
    session.close()
}
```

**Change — `KandraRuntime`:** add `AtomicBoolean isShuttingDown` and `AtomicInteger inFlightCount`.
Every query execution increments `inFlightCount` before and decrements after (in `finally`).
When `isShuttingDown = true`, new queries throw `KandraException("Kandra is shutting down")`.

**New tests:**
- In-flight queries complete before session closes when `graceful = true`
- Session closes after `drainTimeoutMs` even if queries are still in-flight
- New queries throw `KandraException` after shutdown signal
- `graceful = false` closes session immediately

---

## 9. Entity Pre-Save Validation Hook (`kandra-runtime` + `kandra-ktor`)

**Problem:** No field-level validation before writes. Invalid entities (blank email,
negative balance) are written to Scylla silently.

**New interface in `kandra-core`:**

```kotlin
fun interface KandraValidator<T : Any> {
    fun validate(entity: T): List<KandraValidationError>
}

data class KandraValidationError(
    val field: String,
    val message: String
)

class KandraValidationException(
    val errors: List<KandraValidationError>
) : KandraException(
    "Validation failed: ${errors.joinToString("; ") { "${it.field}: ${it.message}" }}"
)
```

**Change — `KandraConfig`:** support registering validators per entity type:

```kotlin
class KandraConfig {
    ...
    internal val validators = mutableMapOf<KClass<*>, KandraValidator<*>>()

    fun <T : Any> validate(klass: KClass<T>, validator: KandraValidator<T>) {
        validators[klass] = validator
    }

    // Reified convenience
    inline fun <reified T : Any> validate(noinline validator: (T) -> List<KandraValidationError>) {
        validate(T::class, KandraValidator { validator(it) })
    }
}
```

**Change — `BatchEngine`:** before every `save()` and `update()`, look up the validator
for the entity class and run it. If errors are returned, throw `KandraValidationException`
before any CQL is executed — nothing is written.

**Usage:**

```kotlin
install(Kandra) {
    validate<User> { user ->
        buildList {
            if (user.email.isBlank()) add(KandraValidationError("email", "cannot be blank"))
            if (!user.email.contains("@")) add(KandraValidationError("email", "invalid format"))
        }
    }
    validate<Wallet> { wallet ->
        buildList {
            if (wallet.balance < BigDecimal.ZERO)
                add(KandraValidationError("balance", "cannot be negative"))
        }
    }
}
```

**Jakarta Validation integration (optional):** if `jakarta.validation:jakarta.validation-api`
is on the classpath, auto-detect `@NotNull`, `@NotBlank`, `@Min`, `@Max` annotations and
run the Bean Validation provider before the custom validator:

```kotlin
// Auto-detected — no config needed if jakarta.validation is on classpath
@ScyllaTable("users")
data class User(
    @PartitionKey val userId: UUID,
    @field:NotBlank val email: String,
    @field:Size(min = 8) val passwordHash: String
)
```

**New tests:**
- Validator returning errors throws `KandraValidationException` — nothing written to Scylla
- Validator returning empty list allows save to proceed
- Multiple validators can be registered for different entity types
- Jakarta `@NotBlank` violation caught automatically when API on classpath
- `update()` also runs validator on the new entity

---

## 10. `ALTER TABLE` for New Columns (`kandra-ktor`)

**Problem:** `AUTO_CREATE` uses `CREATE TABLE IF NOT EXISTS` — when a table already exists,
new fields added to the entity are silently ignored. The column never appears in Scylla.
This is silent data loss.

**Change — new `SchemaMode` value:**

```kotlin
enum class SchemaMode {
    AUTO_CREATE,      // CREATE TABLE IF NOT EXISTS (original)
    AUTO_MIGRATE,     // CREATE TABLE IF NOT EXISTS + ALTER TABLE ADD for new columns
    VALIDATE,         // check existing schema matches entity, throw on missing columns
    NONE              // do nothing
}
```

**Change — `AUTO_MIGRATE` startup behaviour:**

1. Run `CREATE TABLE IF NOT EXISTS` for all tables (existing `AUTO_CREATE` behaviour)
2. For each table that already exists, query `system_schema.columns` for current columns
3. Diff entity columns against Scylla columns
4. For columns in entity but not in Scylla → run `ALTER TABLE table ADD column TYPE`
5. For columns in Scylla but not in entity → log `WARN` only (extra columns are non-breaking)
6. For columns with type mismatch → throw `KandraSchemaException` (type changes require
   manual migration)

**Change — `DdlGenerator`:** add `alterTableAddColumn` method:

```kotlin
fun alterTableAddColumn(schema: TableSchema, column: ColumnSchema): String =
    "ALTER TABLE ${schema.tableName} ADD ${column.cqlName} ${cqlType(column.type)};"
```

**Drop safety guard:** if a field is removed from an entity and `VALIDATE` mode detects
it as an extra column in Scylla, log:

```
WARN: Column 'old_field' exists in Scylla table 'users' but is not mapped in User entity.
The data is still stored in ScyllaDB but will not be readable via Kandra.
To remove it permanently, run: ALTER TABLE users DROP old_field;
Never run DROP COLUMN on a column with active data without a migration plan.
```

**New tests:**
- `AUTO_MIGRATE` runs `ALTER TABLE ADD` for new entity field not in Scylla
- `AUTO_MIGRATE` does NOT drop columns missing from entity
- Type mismatch between entity and Scylla throws `KandraSchemaException`
- Removed entity field logs `WARN` with `ALTER TABLE DROP` guidance

---

## 11. Micrometer Metrics Integration (`kandra-ktor` + `kandra-runtime`)

**Problem:** No observability into Kandra's performance. No way to know query latency,
cache hit rates, batch sizes, or failure rates from CoinX dashboards.

**Add optional dependency** (only required if metrics enabled):

```kotlin
// kandra-ktor/build.gradle.kts
compileOnly("io.ktor:ktor-server-metrics-micrometer:2.3.x")
compileOnly("io.micrometer:micrometer-core:1.x")
```

**Change — add `metrics` block to `KandraConfig`:**

```kotlin
class KandraConfig {
    ...
    val metrics: MetricsConfig = MetricsConfig()
}

class MetricsConfig {
    var enabled: Boolean = false
    var meterRegistry: MeterRegistry? = null   // set to app's MeterRegistry
    var tagTableName: Boolean = true           // tag metrics with table name
    var tagOperationType: Boolean = true       // tag with operation (find/save/delete)
}
```

**Metrics to expose (all prefixed with `kandra.`):**

| Metric | Type | Tags | Description |
|---|---|---|---|
| `kandra.query.duration` | Timer | table, operation | Query execution time |
| `kandra.batch.size` | DistributionSummary | table | Number of statements per batch |
| `kandra.batch.duration` | Timer | table | Batch execution time |
| `kandra.eventual.write.failed` | Counter | table | Failed eventual writes |
| `kandra.optimistic.lock.failed` | Counter | table | Optimistic lock conflicts |
| `kandra.validation.failed` | Counter | table | Pre-save validation failures |
| `kandra.session.inflight` | Gauge | — | Current in-flight query count |
| `kandra.credential.refresh` | Counter | — | Credential rotation count |
| `kandra.prepared.cache.hit` | Counter | — | PreparedStatement cache hits |
| `kandra.prepared.cache.miss` | Counter | — | PreparedStatement cache misses |

**Change — `BatchEngine`:** wrap every execution in a `Timer.record()` block when metrics
enabled. Increment counters on errors.

**Change — `KandraRuntime`:** register the `inFlightCount` atomic as a `Gauge`.

**Auto-detect `MeterRegistry`** from Ktor's Micrometer plugin if `meterRegistry` is not
explicitly set:

```kotlin
val registry = config.metrics.meterRegistry
    ?: application.attributes.getOrNull(MicrometerMetricsConfig.REGISTRY_KEY)
    ?: run {
        logger.warn("Kandra metrics enabled but no MeterRegistry found. Metrics disabled.")
        null
    }
```

**New tests:**
- `kandra.query.duration` timer is recorded on every `findById`
- `kandra.eventual.write.failed` counter increments on EVENTUAL write failure
- `kandra.optimistic.lock.failed` counter increments on `KandraOptimisticLockException`
- Metrics disabled when `enabled = false` (no registry interactions)
- Table name tag present when `tagTableName = true`

---

## 12. CQL Injection Guard (`kandra-runtime`)

**Problem:** `raw()` takes a CQL string directly. A developer interpolating user input
creates a CQL injection vulnerability. There is no guardrail.

**Change — `raw()` signature enforce parameterised queries:**

```kotlin
// Current (dangerous — allows string interpolation)
fun raw(cql: String, vararg params: Any?): List<Row>

// After — no change to signature but add runtime guard:
fun raw(cql: String, vararg params: Any?): List<Row> {
    // Detect likely string interpolation — warn if CQL contains literals
    // that look like they should be parameters
    if (params.isEmpty() && (cql.contains("'") || cql.contains("\"="))) {
        logger.warn(
            "raw() called with no parameters but CQL contains string literals. " +
            "If any literal came from user input this is a CQL injection risk. " +
            "Always use parameterised queries: raw(\"SELECT * FROM users WHERE email = ?\", email)"
        )
    }
    return executeRaw(cql, params)
}
```

**New `KandraRawQuery` builder** as a safer alternative to string `raw()`:

```kotlin
class KandraRawQuery private constructor(
    val cql: String,
    val params: List<Any?>
) {
    companion object {
        fun cql(template: String): KandraRawQueryBuilder = KandraRawQueryBuilder(template)
    }
}

class KandraRawQueryBuilder(private val template: String) {
    private val params = mutableListOf<Any?>()
    fun bind(vararg values: Any?): KandraRawQueryBuilder { params.addAll(values); return this }
    fun build(): KandraRawQuery = KandraRawQuery(template, params)
}

// Repository
fun rawQuery(query: KandraRawQuery): List<Row>
suspend fun rawQuery(query: KandraRawQuery): List<Row>
```

**Usage:**

```kotlin
// Unsafe — triggers warning
userRepo.raw("SELECT * FROM users WHERE email = '${email}'")

// Safe — parameterised
userRepo.rawQuery(
    KandraRawQuery.cql("SELECT * FROM users WHERE email = ?")
        .bind(email)
        .build()
)
```

**New tests:**
- `raw()` with no params and string literal in CQL logs WARN
- `raw()` with params and `?` placeholders does NOT log WARN
- `KandraRawQuery` binds params correctly
- `rawQuery()` executes parameterised CQL without warning

---

## 13. Schema Migration Runner (`kandra-migrate`)

**Problem:** No versioned migration system. `AUTO_MIGRATE` handles additive column changes
but cannot handle: column renames, type changes, data backfills, index changes, or
breaking schema changes.

**New module `kandra-migrate`:**

```
kandra-migrate/
├── build.gradle.kts
└── src/main/kotlin/io/kandra/migrate/
    ├── KandraMigration.kt
    ├── KandraMigrationRunner.kt
    ├── MigrationHistory.kt
    └── MigrationStatus.kt
```

**Migration table** (auto-created by `kandra-migrate`):

```sql
CREATE TABLE IF NOT EXISTS kandra_migrations (
    version     INT,
    name        TEXT,
    applied_at  TIMESTAMP,
    checksum    TEXT,
    PRIMARY KEY (version)
);
```

**Migration DSL:**

```kotlin
// User defines migrations in their codebase
object V1_CreateUsers : KandraMigration(
    version = 1,
    name = "create_users_table"
) {
    override fun up(session: CqlSession) {
        session.execute("""
            CREATE TABLE IF NOT EXISTS users (
                user_id UUID PRIMARY KEY,
                email TEXT,
                name TEXT
            )
        """.trimIndent())
    }
}

object V2_AddPhoneToUsers : KandraMigration(
    version = 2,
    name = "add_phone_to_users"
) {
    override fun up(session: CqlSession) {
        session.execute("ALTER TABLE users ADD phone TEXT")
        session.execute("""
            CREATE TABLE IF NOT EXISTS users_by_phone (
                phone TEXT,
                user_id UUID,
                PRIMARY KEY (phone)
            )
        """.trimIndent())
    }
}
```

**`KandraMigrationRunner`:**

```kotlin
class KandraMigrationRunner(private val session: CqlSession) {
    fun run(vararg migrations: KandraMigration) {
        val applied = loadApplied()
        migrations
            .sortedBy { it.version }
            .filter { it.version !in applied }
            .forEach { migration ->
                logger.info("Applying migration v${migration.version}: ${migration.name}")
                migration.up(session)
                recordApplied(migration)
                logger.info("Migration v${migration.version} applied successfully")
            }
    }

    private fun loadApplied(): Set<Int> { ... }
    private fun recordApplied(migration: KandraMigration) { ... }
}
```

**Ktor integration:**

```kotlin
fun Application.configureMigrations() {
    val runner = KandraMigrationRunner(kandraSession)
    runner.run(
        V1_CreateUsers,
        V2_AddPhoneToUsers,
        V3_AddWallets
    )
}
// Call BEFORE install(Kandra) with schemaMode = NONE for migration-managed schemas
```

**Checksum validation:** each migration's `up()` body is checksummed. If a previously
applied migration's checksum changes, throw `KandraMigrationException` — the migration
was modified after being applied, which is dangerous.

**New tests:**
- Unapplied migrations run in version order
- Applied migrations are skipped on re-run
- Modified migration checksum throws `KandraMigrationException`
- Migration history is readable from `kandra_migrations` table
- Out-of-order migration list is sorted before execution

---

## 14. Query Result Caching (`kandra-runtime` + `kandra-ktor`)

**Problem:** `findById` hits Scylla on every call. CoinX wallet balance and user profile
are read 100x more than written. Without caching, Scylla handles avoidable read load.

**Add optional dependency:**

```kotlin
// kandra-runtime/build.gradle.kts
compileOnly("com.github.ben-manes.caffeine:caffeine:3.x")
```

**New annotation in `kandra-core`:**

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class CacheResult(
    val ttlSeconds: Int = 60,
    val maxSize: Long = 1000
)
```

**Change — `TableSchema`:**

```kotlin
data class TableSchema(
    ...
    val cacheConfig: CacheResultConfig? = null
)

data class CacheResultConfig(val ttlSeconds: Int, val maxSize: Long)
```

**Change — `KandraRepository` + `KandraSuspendRepository`:** when entity has `@CacheResult`,
wrap `findById` with a Caffeine cache:

```kotlin
// Per-table cache, initialised at repository creation
private val cache: Cache<Any, T>? = schema.cacheConfig?.let { cfg ->
    Caffeine.newBuilder()
        .expireAfterWrite(cfg.ttlSeconds.toLong(), TimeUnit.SECONDS)
        .maximumSize(cfg.maxSize)
        .build()
}

fun findById(id: Any): T? {
    return cache?.getIfPresent(id) ?: run {
        val result = executeFind(id)
        result?.also { cache?.put(id, it) }
    }
}
```

**Cache invalidation on write:**

```kotlin
fun save(entity: T, ...) {
    // ... execute write
    cache?.invalidate(partitionKeyOf(entity))
}

fun update(old: T, new: T) {
    // ... execute update
    cache?.invalidate(partitionKeyOf(new))
}

fun delete(entity: T) {
    // ... execute delete
    cache?.invalidate(partitionKeyOf(entity))
}
```

**Cache does NOT apply to `findAll`, `findPage`, or lookup-based queries** — only
`findById` by partition key (deterministic, safe to cache).

**When Caffeine is not on classpath:** `@CacheResult` is silently ignored and a `WARN`
is logged at startup.

**New tests:**
- `findById` returns cached value on second call (no Scylla query fired)
- `save()` invalidates cache for that partition key
- `update()` invalidates cache
- `delete()` invalidates cache
- Cache respects `ttlSeconds` — expired entries trigger new Scylla query
- Cache respects `maxSize` — evicts LRU entries when full
- Missing Caffeine dependency logs WARN and disables caching

---

## PART C — Publishing & Ecosystem

---

## 15. Gradle Version Catalog (`libs.versions.toml`)

**Problem:** `gradle.properties` version management is the old approach. Modern Gradle
projects use a version catalog for IDE support, dependency updates, and clean BOM alignment.

**Change — create `gradle/libs.versions.toml`:**

```toml
[versions]
kandra = "0.4.0-SNAPSHOT"
kotlin = "2.0.0"
ktor = "2.3.12"
datastax = "4.17.0"
coroutines = "1.8.1"
kodein = "7.21.2"
koin = "3.5.6"
ksp = "2.0.0-1.0.21"
kotlinLogging = "6.0.9"
junit = "5.10.2"
testcontainers = "1.19.8"
caffeine = "3.1.8"
micrometer = "1.12.5"
dokka = "1.9.20"
guava = "33.2.0-jre"

[libraries]
datastax-driver = { module = "com.datastax.oss:java-driver-core", version.ref = "datastax" }
ktor-server-core = { module = "io.ktor:ktor-server-core", version.ref = "ktor" }
ktor-server-test = { module = "io.ktor:ktor-server-test-host", version.ref = "ktor" }
ktor-metrics-micrometer = { module = "io.ktor:ktor-server-metrics-micrometer", version.ref = "ktor" }
ktor-health = { module = "io.ktor:ktor-server-health-checks", version.ref = "ktor" }
coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
coroutines-jdk8 = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-jdk8", version.ref = "coroutines" }
kotlin-reflect = { module = "org.jetbrains.kotlin:kotlin-reflect", version.ref = "kotlin" }
kotlin-logging = { module = "io.github.oshai:kotlin-logging-jvm", version.ref = "kotlinLogging" }
kodein-di = { module = "org.kodein.di:kodein-di", version.ref = "kodein" }
kodein-ktor = { module = "org.kodein.di:kodein-di-framework-ktor-server-jvm", version.ref = "kodein" }
koin-ktor = { module = "io.insert-koin:koin-ktor", version.ref = "koin" }
koin-core = { module = "io.insert-koin:koin-core", version.ref = "koin" }
caffeine = { module = "com.github.ben-manes.caffeine:caffeine", version.ref = "caffeine" }
micrometer-core = { module = "io.micrometer:micrometer-core", version.ref = "micrometer" }
guava = { module = "com.google.guava:guava", version.ref = "guava" }
junit = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
testcontainers-cassandra = { module = "org.testcontainers:cassandra", version.ref = "testcontainers" }
ksp-api = { module = "com.google.devtools.ksp:symbol-processing-api", version.ref = "ksp" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
dokka = { id = "org.jetbrains.dokka", version.ref = "dokka" }
```

**Change — update all `build.gradle.kts` files** to reference `libs.*` aliases instead
of string coordinates. Remove version strings from `gradle.properties` (keep only
`kandraVersion` for the BOM).

---

## 16. Dokka Docs Site + GitHub Pages (`root` + `.github/`)

**Problem:** Dokka is configured but generates HTML locally only. No published docs,
no versioned API reference, no search. This blocks library adoption.

**Change — update `.github/workflows/ci.yml`** to publish Dokka output to GitHub Pages
on tag push:

```yaml
  docs:
    needs: test
    if: startsWith(github.ref, 'refs/tags/v')
    runs-on: ubuntu-latest
    permissions:
      contents: write
      pages: write
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '17', distribution: 'temurin' }
      - run: ./gradlew dokkaHtmlMultiModule --no-daemon
      - name: Deploy to GitHub Pages
        uses: peaceiris/actions-gh-pages@v3
        with:
          github_token: ${{ secrets.GITHUB_TOKEN }}
          publish_dir: ./build/dokka/htmlMultiModule
          destination_dir: ${{ github.ref_name }}   # versioned: /v0.4.0/
      - name: Update latest symlink
        run: |
          echo "${{ github.ref_name }}" > latest_version.txt
```

**Change — root `build.gradle.kts`:** configure `dokkaHtmlMultiModule`:

```kotlin
tasks.dokkaHtmlMultiModule {
    outputDirectory.set(buildDir.resolve("dokka/htmlMultiModule"))
    moduleName.set("Kandra")
}
```

**Add `docs/` folder to root** with:
- `index.md` — landing page with quickstart
- `guides/getting-started.md`
- `guides/multi-dc.md`
- `guides/auth-and-security.md`
- `guides/migration.md`
- `guides/coinx-example.md` — a full CoinX-style example with User, Wallet, Transaction

---

## Updated Build Order for 0.4.0

Apply in this order — run `./gradlew test` after each step before proceeding:

**Part A — Critical correctness:**
1. Version bump to `0.4.0-SNAPSHOT`
2. Async driver — `kandra-runtime` (replace all `Dispatchers.IO` + `session.execute` in suspend paths)
3. `UNSET` vs `NULL` — `kandra-runtime` + `kandra-core`
4. Optimistic locking (`@Version`) — `kandra-core` + `kandra-runtime`
5. Tombstone awareness (`@SoftDelete`, `gcGraceSeconds`, bulk delete warning) — `kandra-core` + `kandra-runtime`
6. Batch size guard + auto-chunking — `kandra-runtime`
7. `@Sensitive` log masking — `kandra-core` + `kandra-runtime`

**Part B — Should-have:**
8. Health check integration — `kandra-ktor`
9. Graceful shutdown drain — `kandra-ktor`
10. Entity pre-save validation hook — `kandra-core` + `kandra-runtime` + `kandra-ktor`
11. `AUTO_MIGRATE` + `ALTER TABLE ADD` for new columns — `kandra-ktor` + `kandra-core`
12. Micrometer metrics — `kandra-ktor` + `kandra-runtime`
13. CQL injection guard + `KandraRawQuery` builder — `kandra-runtime`
14. Schema migration runner — scaffold `kandra-migrate` module
15. Query result caching (`@CacheResult` + Caffeine) — `kandra-core` + `kandra-runtime`

**Part C — Publishing & ecosystem:**
16. Gradle version catalog (`libs.versions.toml`) — root + all modules
17. Dokka docs site + GitHub Pages CI step

**Final:**
18. Add `kandra-migrate` to `kandra-bom` + `settings.gradle.kts`
19. Update `README.md`:
    - Async usage guidance (suspend vs blocking)
    - `UNSET` vs `NULL` explanation
    - `@Version` optimistic locking example
    - `@SoftDelete` tombstone avoidance guide
    - `@CacheResult` caching example
    - Migration runner quickstart
    - Metrics dashboard example (Grafana query suggestions)
20. Update `CHANGELOG.md` with all 0.4.0 entries
