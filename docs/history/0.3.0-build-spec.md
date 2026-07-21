# Kandra 0.3.0-SNAPSHOT — Update Prompt for Claude Code

This is a delta update on top of the existing Kandra 0.2.0-SNAPSHOT codebase.
Do not rebuild from scratch. Apply each change to the existing modules in place.
Bump the version in `gradle.properties` from `0.2.0-SNAPSHOT` to `0.3.0-SNAPSHOT`.

---

## PART A — Missing Items from 0.2.0 Gap Analysis

---

## 1. Retry Policy (`kandra-ktor` + `kandra-runtime`)

**Problem:** A transient Scylla node failure during a `LOGGED BATCH` throws straight to the
caller with no retry. For CoinX a failed user registration batch means a lost signup.

**Change — add `retry` block to `KandraConfig`:**

```kotlin
class KandraConfig {
    ...
    val retry: RetryConfig = RetryConfig()
}

class RetryConfig {
    var maxAttempts: Int = 3
    var backoffMillis: Long = 100
    var maxBackoffMillis: Long = 2000
    var retryOn: Set<KClass<out Throwable>> = setOf(
        WriteTimeoutException::class,
        ReadTimeoutException::class,
        NoNodeAvailableException::class
    )
}
```

**Change — `BatchEngine`:** wrap every `session.execute()` call in a retry loop:

```kotlin
private fun executeWithRetry(statement: Statement<*>, config: RetryConfig): ResultSet {
    var lastError: Throwable? = null
    repeat(config.maxAttempts) { attempt ->
        try {
            return session.execute(statement)
        } catch (e: Throwable) {
            if (config.retryOn.none { it.isInstance(e) }) throw e
            lastError = e
            val backoff = minOf(config.backoffMillis * (attempt + 1), config.maxBackoffMillis)
            Thread.sleep(backoff)
        }
    }
    throw KandraQueryException("Query failed after ${config.maxAttempts} attempts", lastError)
}
```

Suspend version uses `delay()` instead of `Thread.sleep()`.

**New tests:**
- Retries up to `maxAttempts` on `WriteTimeoutException`
- Does NOT retry on exceptions not in `retryOn`
- Throws `KandraQueryException` after exhausting all attempts
- Backoff increases between attempts
- Suspend version uses `delay` not `Thread.sleep`

---

## 2. Idempotency Tokens on Statements (`kandra-runtime`)

**Problem:** Without idempotency flags the driver's retry policy silently skips retries
on writes even when configured, because it can't safely retry a non-idempotent statement.

**Change — `StatementBuilder`:** mark statements with correct idempotency:

```kotlin
// Mark as idempotent (driver can safely retry)
fun selectById(...): BoundStatement  // always idempotent
fun selectByLookup(...): BoundStatement  // always idempotent
fun insertPrimary(..., ifNotExists: Boolean): BoundStatement
    // ifNotExists = true → idempotent (LWT is safe to retry)
    // ifNotExists = false → NOT idempotent (retry = duplicate risk)

// Mark as NOT idempotent
fun insertLookup(...): BoundStatement   // not idempotent
fun deleteById(...): BoundStatement     // idempotent (delete twice = same result)
fun deleteLookup(...): BoundStatement   // idempotent
```

Apply via Java driver: `statement.setIdempotent(true/false)`

**Rule summary:**
- All `SELECT` → idempotent
- `DELETE` → idempotent
- `INSERT IF NOT EXISTS` (LWT) → idempotent
- Plain `INSERT` → NOT idempotent
- `UPDATE col = col + ?` (collection mutation, counter) → NOT idempotent

**New tests:**
- `selectById` statement has `isIdempotent = true`
- `insertPrimary(ifNotExists = true)` has `isIdempotent = true`
- `insertPrimary(ifNotExists = false)` has `isIdempotent = false`
- `deleteById` has `isIdempotent = true`
- Counter increment has `isIdempotent = false`

---

## 3. `IN` Clause on Partition Keys (`kandra-runtime`)

**Problem:** `isIn` exists on `KandraColumnRef` but `QueryExecutor` doesn't handle it on
partition keys — it generates invalid CQL or throws. Batch-fetching users by ID is a core
CoinX pattern.

**Change — `QueryExecutor`:** detect `KandraPredicate.In` on a partition key column and
generate `SELECT * FROM table WHERE partition_key IN ?` using the driver's list binding.

**Important ScyllaDB constraint:** `IN` on partition keys is valid CQL but causes
scatter-gather across multiple partitions. Log a `DEBUG` message when `IN` is used so
developers are aware. Do not warn (it is a legitimate access pattern).

**Change — `StatementBuilder`:**

```kotlin
fun selectByPartitionKeyIn(schema: TableSchema, ids: List<Any>): BoundStatement
// SELECT * FROM table WHERE partition_key IN ?
```

**Usage:**

```kotlin
val users = userRepo.findAll {
    +UserTable.userId.isIn(listOf(id1, id2, id3))
}
```

**New tests:**
- `isIn` on partition key generates `WHERE partition_key IN ?`
- Result contains all matching rows
- Empty list returns empty result (no query fired)
- `isIn` on a non-partition, non-lookup field throws `KandraQueryException` with message
  explaining that `IN` on regular columns requires `ALLOW FILTERING`

---

## 4. Nullable Codec Contract (`kandra-runtime`)

**Problem:** The codec spec says "handle nullable types" but does not define the exact
contract. ScyllaDB returns `null` for missing/unset columns. Calling `row.getString(col)`
on a null column throws. This causes `NullPointerException` in production.

**Change — `KandraCodec.decode` explicit null contract:**

```kotlin
fun decode(row: Row, column: ColumnSchema): Any? {
    // 1. Check isNull FIRST before any typed getter
    if (row.isNull(column.cqlName)) {
        // If Kotlin type is nullable → return null (correct)
        // If Kotlin type is non-nullable → throw KandraQueryException with clear message
        if (column.type.isMarkedNullable) return null
        throw KandraQueryException(
            "Column '${column.cqlName}' is NULL in Scylla but " +
            "property '${column.propertyName}' is non-nullable in ${column.type}. " +
            "Mark the property nullable or ensure the column always has a value."
        )
    }
    // 2. Only call typed getter after null check passes
    return when (column.type.classifier) {
        String::class -> row.getString(column.cqlName)
        UUID::class -> row.getUuid(column.cqlName)
        Int::class -> row.getInt(column.cqlName)
        Long::class -> row.getLong(column.cqlName)
        // ... etc
    }
}
```

**New tests:**
- Nullable `String?` field decodes to `null` when column is null in Scylla
- Non-nullable `String` field throws `KandraQueryException` when column is null in Scylla
- All primitive types (`Int`, `Long`, `Boolean`, `Double`) handle null correctly
- `UUID` field handles null correctly

---

## 5. Caller-Controlled Batch (`kandra-runtime` + `kandra-ktor`)

**Problem:** Creating a user + wallet in CoinX is two `save()` calls with no atomicity
between them. If the wallet insert fails after the user insert succeeds, the DB is
inconsistent.

**New API — `KandraSession.batch`:**

```kotlin
// On the Kandra runtime object accessible from Application
suspend fun batch(block: suspend KandraBatchScope.() -> Unit)
fun batchBlocking(block: KandraBatchScope.() -> Unit)

class KandraBatchScope(private val batchStatement: BatchStatement) {
    // All operations in this scope are collected into the batch
    fun <T : Any> KandraSuspendRepository<T>.save(entity: T)
    fun <T : Any> KandraSuspendRepository<T>.delete(entity: T)
    fun <T : Any> KandraSuspendRepository<T>.saveIfNotExists(entity: T): Boolean
    // Note: findAll / findById are NOT available in batch scope — reads cannot be batched
}
```

**Implementation:**
- `KandraBatchScope` collects `BoundStatement`s without executing them
- When `block` exits, execute the collected statements as a single `LOGGED BatchStatement`
- Lookup table inserts for all entities in the batch are included in the same batch
- `saveIfNotExists` inside a batch throws `KandraQueryException` — LWT cannot be mixed
  with non-LWT statements in the same batch. Document this clearly.

**Change — expose `batch` on the Ktor `Application`:**

```kotlin
val Application.kandra: KandraRuntime
    get() = attributes[KandraRuntimeKey]

// Usage in routes
application.kandra.batch {
    userRepo.save(user)
    walletRepo.save(wallet)
}
```

**New tests:**
- `batch` executes all operations in a single `LOGGED BatchStatement`
- Failure in batch rolls back all operations (Scylla LOGGED BATCH guarantee)
- `findAll` inside batch scope throws `KandraQueryException`
- `saveIfNotExists` inside batch scope throws `KandraQueryException`
- Empty batch is a no-op

---

## 6. `@SecondaryIndex` Annotation (`kandra-core` + `kandra-runtime`)

**Problem:** No way to create secondary indexes for low-cardinality fields like
`accountStatus` or `isVerified` needed for admin dashboards.

**New annotation in `kandra-core`:**

```kotlin
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class SecondaryIndex
// KDoc MUST warn: secondary indexes use scatter-gather across all nodes.
// Only use for low-cardinality fields and non-hot-path queries.
```

**Change — `TableSchema`:**

```kotlin
data class TableSchema(
    ...
    val secondaryIndexes: List<ColumnSchema>
)
```

**Change — `DdlGenerator`:** generate `CREATE INDEX IF NOT EXISTS` statements for each
`@SecondaryIndex` column, included in `allStatements()`:

```sql
CREATE INDEX IF NOT EXISTS users_account_status_idx ON users (account_status);
```

**Change — `QueryExecutor`:** when a predicate targets a `@SecondaryIndex` column, generate
the query directly (no two-step lookup needed — unlike `@LookupIndex`). Log a `WARN` every
time a secondary index query executes in production to remind developers of the cost.

**Usage:**

```kotlin
@ScyllaTable("users")
data class User(
    @PartitionKey val userId: UUID,
    @SecondaryIndex   // low cardinality — only a few possible values
    val accountStatus: AccountStatus,
    ...
)

// Query works without ALLOW FILTERING
val flagged = userRepo.findAll {
    +UserTable.accountStatus.eq(AccountStatus.FLAGGED)
}
```

**New tests:**
- `@SecondaryIndex` DDL generates `CREATE INDEX IF NOT EXISTS`
- Query on `@SecondaryIndex` field generates valid CQL without `ALLOW FILTERING`
- `@SecondaryIndex` query logs a `WARN`

---

## 7. Prepared Statement Cache Eviction (`kandra-runtime`)

**Problem:** The `ConcurrentHashMap` cache grows forever — a memory leak in long-running
servers with many distinct queries.

**Change — replace raw `ConcurrentHashMap` with a bounded LRU cache:**

Use Guava's `CacheBuilder` or a simple `LinkedHashMap`-based LRU. Expose the max size:

```kotlin
class KandraConfig {
    ...
    var preparedStatementCacheSize: Int = 1000   // max cached prepared statements
}
```

**Implementation in `StatementBuilder`:**

```kotlin
private val cache: Cache<String, PreparedStatement> = CacheBuilder.newBuilder()
    .maximumSize(config.preparedStatementCacheSize.toLong())
    .recordStats()   // enables hit/miss stats for debug logging
    .build()
```

Log a `WARN` when a cache eviction occurs — it means a prepared statement will be
re-prepared, which is a round trip to Scylla and a performance hit.

**New tests:**
- Cache does not exceed `preparedStatementCacheSize`
- Eviction triggers a `WARN` log
- Re-evicted statements are re-prepared correctly (no stale reference)

---

## 8. `USING TIMESTAMP` Support (`kandra-runtime`)

**Problem:** Without explicit write timestamps, Scylla uses server-side `now()` for
conflict resolution. In a distributed CoinX setup where events can arrive out of order
(e.g. webhook retries), last-write-wins based on server time is wrong.

**Change — `StatementBuilder`:** add timestamp overload:

```kotlin
fun insertPrimary(
    schema: TableSchema,
    entity: Any,
    ttlSeconds: Int? = null,
    ifNotExists: Boolean = false,
    timestampMicros: Long? = null    // ADD — USING TIMESTAMP in microseconds
): BoundStatement
// Generates: INSERT INTO ... VALUES ... USING TIMESTAMP ?
```

**Change — repository API:**

```kotlin
fun save(entity: T, ttlSeconds: Int? = null, timestampMicros: Long? = null)
suspend fun save(entity: T, ttlSeconds: Int? = null, timestampMicros: Long? = null)
```

**Helper in `kandra-core`:**

```kotlin
object KandraTimestamp {
    fun now(): Long = System.currentTimeMillis() * 1000L   // ms → microseconds
    fun fromInstant(instant: Instant): Long = instant.toEpochMilli() * 1000L
}
```

**Usage:**

```kotlin
// Idempotent webhook handler — use event timestamp, not server time
walletRepo.save(wallet, timestampMicros = KandraTimestamp.fromInstant(event.occurredAt))
```

**New tests:**
- `save(entity, timestampMicros = N)` generates `INSERT ... USING TIMESTAMP N`
- `save(entity)` with no timestamp generates standard INSERT (no USING TIMESTAMP)
- `KandraTimestamp.now()` returns a value in microseconds (> `System.currentTimeMillis()`)

---

## 9. Query Debug Logging (`kandra-runtime` + `kandra-ktor`)

**Problem:** No visibility into what CQL is generated. Hard to debug query issues in
CoinX without seeing the actual statements.

**Change — add `debug` block to `KandraConfig`:**

```kotlin
class KandraConfig {
    ...
    val debug: DebugConfig = DebugConfig()
}

class DebugConfig {
    var logQueries: Boolean = false         // log every CQL statement + params at DEBUG
    var logSlowQueriesMs: Long = 0L         // 0 = disabled; log WARN for queries over N ms
    var logBatches: Boolean = false         // log full batch contents at DEBUG
}
```

**Change — `StatementBuilder`:** when `logQueries = true`, log the CQL string at `DEBUG`
level before returning the `BoundStatement`. Do NOT log bound parameter values by default
(they may contain PII) — log only the CQL template.

**Change — `BatchEngine`:** when `logBatches = true`, log each statement in a batch before
execution. When `logSlowQueriesMs > 0`, wrap `session.execute()` with a timer and log
`WARN` if the duration exceeds the threshold.

**Usage:**

```kotlin
install(Kandra) {
    debug {
        logQueries = true
        logSlowQueriesMs = 500
    }
}
```

**New tests:**
- `logQueries = true` produces DEBUG log containing the CQL string
- Bound parameter values are NOT logged even when `logQueries = true`
- `logSlowQueriesMs = 1` triggers WARN log on any query (for testing)
- `logQueries = false` (default) produces no query logs

---

## 10. Test Keyspace Isolation (`kandra-test`)

**Problem:** Parallel test classes share `FakeKandraSession` state and corrupt each other.
Integration tests need fresh keyspace per test class.

**Change — `FakeKandraSession`:** make it instance-scoped with no static state:

```kotlin
// Each call to inMemory() returns a completely isolated instance
val repo1 = KandraTestUtils.inMemory(User::class)
val repo2 = KandraTestUtils.inMemory(User::class)
// repo1 and repo2 share no state
```

**New — `KandraTestcontainers` helper in `kandra-test`:**

```kotlin
object KandraTestcontainers {
    // Starts one shared ScyllaDB container per JVM (reused across test classes)
    val container: CassandraContainer<*> by lazy {
        CassandraContainer("scylladb/scylla:5.2")
            .withExposedPorts(9042)
            .also { it.start() }
    }

    // Creates a fresh keyspace with a random name for each test class
    fun freshKeyspace(vararg classes: KClass<*>): KandraRuntime {
        val keyspace = "kandra_test_${UUID.randomUUID().toString().replace("-", "")}"
        return KandraRuntime.connect(
            contactPoints = container.contactPoint.toString(),
            keyspace = keyspace,
            autoCreateKeyspace = true,
            entities = classes.toList()
        )
    }
}

// Usage in integration tests
class UserRepositoryTest {
    private val db = KandraTestcontainers.freshKeyspace(User::class)
    private val userRepo = db.suspendRepository<User>()

    @AfterEach
    fun cleanup() = db.close()  // drops the random keyspace
}
```

**New tests:**
- Two `inMemory()` instances are fully isolated
- `freshKeyspace()` creates a new keyspace on each call
- Tables are created in the fresh keyspace automatically
- `close()` on a fresh keyspace instance drops the keyspace

---

## 11. Incremental KSP Processing (`kandra-codegen`)

**Problem:** The KSP processor reprocesses all files on every build. Large CoinX codebase
will have slow builds.

**Change — `KandraProcessor`:** implement incremental processing:

```kotlin
class KandraProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSPDeclaration> {
        val symbols = resolver
            .getSymbolsWithAnnotation(ScyllaTable::class.qualifiedName!!)
            .filterIsInstance<KSClassDeclaration>()

        // Mark as originating from the annotated source files only
        symbols.forEach { classDecl ->
            val file = codeGenerator.createNewFile(
                dependencies = Dependencies(
                    aggregating = false,           // non-aggregating = incremental
                    sources = arrayOf(classDecl.containingFile!!)
                ),
                packageName = classDecl.packageName.asString(),
                fileName = "${classDecl.simpleName.asString()}Table"
            )
            file.use { generateTableObject(it, classDecl) }
        }
        return emptyList()
    }
}
```

`aggregating = false` means KSP only reruns this processor for files that changed.

**New tests:**
- Processor generates `UserTable.kt` from `User.kt`
- Adding a new entity only regenerates that entity's table file (verify via KSP test harness)
- Removing `@ScyllaTable` from a class deletes the generated table object

---

## 12. Maven Central Publishing Config (all modules)

**Problem:** The BOM is scaffolded but no module can actually be published — missing
`maven-publish`, `signing`, Sonatype config, and Javadoc JARs.

**Change — create `buildSrc/src/main/kotlin/publish.gradle.kts`** (shared convention plugin):

```kotlin
plugins {
    `maven-publish`
    signing
    id("org.jetbrains.dokka")
}

// Javadoc JAR from Dokka (required by Maven Central)
val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    from(tasks.named("dokkaHtml"))
}

// Sources JAR (required by Maven Central)
val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets["main"].allSource)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifact(javadocJar)
            artifact(sourcesJar)
            pom {
                name.set(project.name)
                description.set("Kotlin-first ScyllaDB ORM as a Ktor plugin")
                url.set("https://github.com/pasakamutuku/kandra")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                developers {
                    developer {
                        id.set("pasakamutuku")
                        name.set("Pasaka Mutuku")
                        email.set("pasaka@coinx.io")
                    }
                }
                scm {
                    url.set("https://github.com/pasakamutuku/kandra")
                    connection.set("scm:git:git://github.com/pasakamutuku/kandra.git")
                }
            }
        }
    }
    repositories {
        maven {
            name = "sonatype"
            url = uri(
                if (version.toString().endsWith("SNAPSHOT"))
                    "https://s01.oss.sonatype.org/content/repositories/snapshots/"
                else
                    "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
            )
            credentials {
                username = providers.gradleProperty("sonatypeUsername").orNull
                password = providers.gradleProperty("sonatypePassword").orNull
            }
        }
    }
}

signing {
    useGpgCmd()   // uses local GPG — no secrets in build file
    sign(publishing.publications["maven"])
}
```

**Apply to all publishable modules** in each `build.gradle.kts`:
```kotlin
apply(from = rootProject.file("buildSrc/src/main/kotlin/publish.gradle.kts"))
```

**Add Dokka to root `build.gradle.kts`:**
```kotlin
plugins {
    id("org.jetbrains.dokka") version "1.9.20"
}
```

**Add to `gradle.properties`:**
```properties
# Set in ~/.gradle/gradle.properties — never commit
# sonatypeUsername=your_sonatype_token_username
# sonatypePassword=your_sonatype_token_password
```

**Update GitHub Actions CI** to include publish step on tag push:

```yaml
name: CI
on:
  push:
    branches: [main]
    tags: ['v*']
  pull_request:

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '17', distribution: 'temurin' }
      - run: ./gradlew test --no-daemon

  publish:
    needs: test
    if: startsWith(github.ref, 'refs/tags/v')
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '17', distribution: 'temurin' }
      - run: ./gradlew publish --no-daemon
        env:
          ORG_GRADLE_PROJECT_sonatypeUsername: ${{ secrets.SONATYPE_USERNAME }}
          ORG_GRADLE_PROJECT_sonatypePassword: ${{ secrets.SONATYPE_PASSWORD }}
          ORG_GRADLE_PROJECT_signingKey: ${{ secrets.GPG_SIGNING_KEY }}
```

---

## 13. API Stability Markers (`kandra-core`)

**Problem:** No way for consumers to know which APIs are stable, experimental, or internal.
Breaking an internal API in 0.4.0 should not require a major version bump.

**New annotations in `kandra-core`:**

```kotlin
@RequiresOptIn(
    message = "This is an internal Kandra API. It may change or be removed without notice. " +
              "Do not use in your own code.",
    level = RequiresOptIn.Level.ERROR
)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class InternalKandraApi

@RequiresOptIn(
    message = "This Kandra API is experimental and may change in future releases.",
    level = RequiresOptIn.Level.WARNING
)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class ExperimentalKandraApi
```

**Apply immediately to:**

| API | Marker |
|---|---|
| `BatchEngine` | `@InternalKandraApi` |
| `StatementBuilder` | `@InternalKandraApi` |
| `SchemaRegistry` internals | `@InternalKandraApi` |
| `KandraCodec` custom encoder/decoder registration | `@ExperimentalKandraApi` |
| `KandraEventListener` | `@ExperimentalKandraApi` |
| `KandraBatchScope` | `@ExperimentalKandraApi` |
| All repository methods | stable — no annotation |
| All annotations (`@ScyllaTable`, etc.) | stable — no annotation |

---

## 14. CHANGELOG.md (root)

Scaffold `CHANGELOG.md` with entries for all three versions:

```markdown
# Changelog

## [0.3.0-SNAPSHOT] — Unreleased
### Added
- Retry policy with configurable backoff
- Idempotency tokens on all statements
- IN clause on partition keys
- Nullable codec contract with clear error messages
- Caller-controlled batch API (KandraBatchScope)
- @SecondaryIndex annotation
- Prepared statement LRU cache with eviction
- USING TIMESTAMP support
- Query debug logging (logQueries, logSlowQueriesMs)
- Test keyspace isolation (FakeKandraSession + KandraTestcontainers)
- Incremental KSP processing
- Maven Central publishing config
- API stability markers (@InternalKandraApi, @ExperimentalKandraApi)
- Multi-datacenter support (see Part B)

## [0.2.0-SNAPSHOT] — Unreleased
### Added
- Composite partition keys (@PartitionKey(index))
- TTL support (@Ttl annotation + per-save override)
- Keyspace auto-creation with replication strategy config
- Local datacenter config (required fix for Java driver 4.x)
- Lightweight transactions — saveIfNotExists()
- Cursor pagination — findPage()
- Eventual write failure listener (KandraEventListener)
- Schema validation mode (SchemaMode enum)
- LIMIT and ALLOW FILTERING on queries
- deleteById() and deleteBy()
- Collection mutations (append, remove, put)
- Counter table support (@Counter)
- Extensible codec + BigDecimal support
- @CreatedAt / @UpdatedAt auto-fill
- saveAll() batch insert
- exists() check
- Connection pool configuration
- Bill of Materials (kandra-bom)
- GitHub Actions CI
- Apache 2.0 LICENSE

## [0.1.0-SNAPSHOT] — Unreleased
### Added
- Initial release
- @ScyllaTable, @PartitionKey, @ClusteringKey, @LookupIndex, @Column, @Transient annotations
- SchemaRegistry with DDL generation
- BATCH and EVENTUAL lookup consistency modes
- KandraRepository (blocking) and KandraSuspendRepository (coroutine)
- Type-safe query DSL
- Ktor ApplicationPlugin integration
- Kodein-DI auto-binding (kandra-kodein)
- Koin auto-binding (kandra-koin)
- KSP codegen generating type-safe table objects
- FakeKandraSession for unit tests
```

---

## PART B — Multi-Datacenter Support

Add a new module `kandra-multidc` for multi-datacenter features. The core modules
(`kandra-core`, `kandra-runtime`) must remain single-DC compatible — `kandra-multidc`
is an optional add-on.

**Add to `settings.gradle.kts`:**
```kotlin
include("kandra-multidc")
```

**Add to `kandra-bom`:**
```kotlin
api("io.kandra:kandra-multidc:${project.version}")
```

---

## 15. Load Balancing Policy (`kandra-multidc` + `kandra-ktor`)

**Change — add `loadBalancing` block to `KandraConfig`:**

```kotlin
class KandraConfig {
    ...
    val loadBalancing: LoadBalancingConfig = LoadBalancingConfig()
}

class LoadBalancingConfig {
    var tokenAware: Boolean = true          // route to token owner — avoids coordinator hop
    var dcAwareFailover: Boolean = false    // allow remote DC on local DC failure
    var allowedRemoteDcs: List<String> = emptyList()   // DCs to fail over to, in order
    var maxRemoteNodesPerRemoteDc: Int = 1  // limit remote DC usage
}
```

**Change — wire into `buildCqlSession`:**

```kotlin
val lbPolicyBuilder = DefaultLoadBalancingPolicy.builder()
    .withLocalDatacenter(config.localDatacenter)

if (config.loadBalancing.tokenAware) {
    // DefaultLoadBalancingPolicy is already token-aware by default — document this
}

if (config.loadBalancing.dcAwareFailover && config.loadBalancing.allowedRemoteDcs.isNotEmpty()) {
    lbPolicyBuilder.withDcFailover(
        maxNodesPerRemoteDc = config.loadBalancing.maxRemoteNodesPerRemoteDc,
        allowForLocalConsistencyLevels = false
    )
}

CqlSession.builder()
    ...
    .withConfigLoader(
        DriverConfigLoader.programmaticBuilder()
            ...
            .build()
    )
    .build()
```

**New tests:**
- Default config has `tokenAware = true`
- `dcAwareFailover = true` with `allowedRemoteDcs` wires failover into session
- `allowedRemoteDcs` empty with `dcAwareFailover = true` throws `KandraSchemaException`
  at startup with clear message

---

## 16. Consistency Level Per Operation (`kandra-multidc`)

**This is the most important multi-DC feature.**

**New enum in `kandra-multidc`:**

```kotlin
enum class KandraConsistency {
    ONE, TWO, THREE, QUORUM, ALL,
    LOCAL_ONE,      // default read
    LOCAL_QUORUM,   // default write
    EACH_QUORUM,    // write to majority in EVERY DC
    LOCAL_SERIAL,   // LWT Paxos in local DC only
    SERIAL          // LWT Paxos globally across all DCs
}
```

**Change — add consistency config to `KandraConfig`:**

```kotlin
class KandraConfig {
    ...
    val consistency: ConsistencyConfig = ConsistencyConfig()
}

class ConsistencyConfig {
    var defaultRead: KandraConsistency = KandraConsistency.LOCAL_ONE
    var defaultWrite: KandraConsistency = KandraConsistency.LOCAL_QUORUM
    var defaultSerialConsistency: KandraConsistency = KandraConsistency.LOCAL_SERIAL
}
```

**New annotation — per-table consistency override:**

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ReadConsistency(val level: KandraConsistency)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class WriteConsistency(val level: KandraConsistency)
```

**Change — repository API — per-operation override:**

```kotlin
fun findById(id: Any, consistency: KandraConsistency? = null): T?
suspend fun findById(id: Any, consistency: KandraConsistency? = null): T?

fun save(entity: T, consistency: KandraConsistency? = null, ...)
suspend fun save(entity: T, consistency: KandraConsistency? = null, ...)
```

**Consistency resolution order (highest priority first):**
1. Per-operation parameter
2. `@ReadConsistency` / `@WriteConsistency` on entity class
3. `KandraConfig.consistency.defaultRead` / `defaultWrite`

**Change — `StatementBuilder`:** apply resolved consistency to every `BoundStatement`
via `statement.setConsistencyLevel(ConsistencyLevel.valueOf(resolved.name))`.

**Usage:**

```kotlin
// Global defaults
install(Kandra) {
    consistency {
        defaultRead = KandraConsistency.LOCAL_ONE
        defaultWrite = KandraConsistency.LOCAL_QUORUM
    }
}

// Table-level override
@ScyllaTable("wallets")
@WriteConsistency(KandraConsistency.EACH_QUORUM)   // must write to every DC
@ReadConsistency(KandraConsistency.LOCAL_QUORUM)
data class Wallet(...)

// Per-operation override (strongest guarantee for critical path)
walletRepo.save(wallet, consistency = KandraConsistency.EACH_QUORUM)
val balance = walletRepo.findById(walletId, consistency = KandraConsistency.QUORUM)
```

**New tests:**
- Default read consistency is `LOCAL_ONE`
- Default write consistency is `LOCAL_QUORUM`
- `@WriteConsistency` on entity overrides default
- Per-operation parameter overrides entity annotation
- `SERIAL` and `LOCAL_SERIAL` are only valid as serial consistency (for LWT) — throw
  `KandraQueryException` if used as regular read/write consistency

---

## 17. LWT Serial Consistency (`kandra-multidc` + `kandra-runtime`)

**Problem:** `saveIfNotExists` uses Paxos under the hood but serial consistency is not
configured — defaults to driver default which varies by version.

**Change — `saveIfNotExists` signature:**

```kotlin
fun saveIfNotExists(
    entity: T,
    serialConsistency: KandraConsistency = KandraConsistency.LOCAL_SERIAL
): Boolean

suspend fun saveIfNotExists(
    entity: T,
    serialConsistency: KandraConsistency = KandraConsistency.LOCAL_SERIAL
): Boolean
```

**Change — `StatementBuilder.insertPrimary`:** when `ifNotExists = true`, apply serial
consistency via `statement.setSerialConsistencyLevel(...)`.

**`LOCAL_SERIAL` vs `SERIAL`:**
- `LOCAL_SERIAL` — Paxos consensus within local DC only. Faster. Safe when each DC is
  authoritative for its own data partition.
- `SERIAL` — Global Paxos across all DCs. Slower. Required for globally unique constraints
  (e.g. a username that must be unique across all regions).

Document this distinction prominently in KDoc and README.

**Usage:**

```kotlin
// Default — local Paxos (sufficient for CoinX wallet creation per region)
val created = walletRepo.saveIfNotExists(wallet)

// Global uniqueness (e.g. username must be globally unique across all DCs)
val registered = userRepo.saveIfNotExists(user, serialConsistency = KandraConsistency.SERIAL)
```

**New tests:**
- Default serial consistency is `LOCAL_SERIAL`
- `SERIAL` serial consistency is applied when specified
- Non-serial consistency level passed as `serialConsistency` throws `KandraQueryException`

---

## 18. DC Failover Policy (`kandra-multidc`)

**Change — add `failover` block to `KandraConfig`:**

```kotlin
class KandraConfig {
    ...
    val failover: FailoverConfig = FailoverConfig()
}

class FailoverConfig {
    var onLocalDcUnavailable: FailoverPolicy = FailoverPolicy.THROW
    var remoteRetryDelayMs: Long = 50
}

enum class FailoverPolicy {
    THROW,              // throw NoNodeAvailableException immediately (default)
    RETRY_REMOTE_DC,    // retry against allowedRemoteDcs in order
}
```

**Implementation:** wire `FailoverPolicy.RETRY_REMOTE_DC` into the load balancing policy
configured in item 15. When `THROW` (default), the driver's default behaviour is preserved.

**Usage:**

```kotlin
install(Kandra) {
    loadBalancing {
        dcAwareFailover = true
        allowedRemoteDcs = listOf("eu-west-1", "us-east-1")
    }
    failover {
        onLocalDcUnavailable = FailoverPolicy.RETRY_REMOTE_DC
        remoteRetryDelayMs = 100
    }
}
```

**New tests:**
- `THROW` policy propagates `NoNodeAvailableException` to caller
- `RETRY_REMOTE_DC` retries against remote DCs in `allowedRemoteDcs` order
- `RETRY_REMOTE_DC` with empty `allowedRemoteDcs` throws `KandraSchemaException` at startup

---

## 19. Speculative Execution (`kandra-multidc`)

**Problem:** For latency-sensitive CoinX reads (wallet balance, price checks) a slow node
causes tail latency spikes. Speculative execution fires a second request to another node
if the first doesn't respond within N milliseconds and takes whichever replies first.

**Change — add `speculativeExecution` block to `KandraConfig`:**

```kotlin
class KandraConfig {
    ...
    val speculativeExecution: SpeculativeExecutionConfig = SpeculativeExecutionConfig()
}

class SpeculativeExecutionConfig {
    var enabled: Boolean = false
    var delayMillis: Long = 100     // fire second attempt after 100ms
    var maxAttempts: Int = 2        // including the original attempt
}
```

**Change — wire into `buildCqlSession`:**

```kotlin
if (config.speculativeExecution.enabled) {
    DriverConfigLoader.programmaticBuilder()
        .withClass(
            DefaultDriverOption.SPECULATIVE_EXECUTION_POLICY_CLASS,
            ConstantSpeculativeExecutionPolicy::class.java
        )
        .withLong(
            DefaultDriverOption.SPECULATIVE_EXECUTION_DELAY,
            config.speculativeExecution.delayMillis
        )
        .withInt(
            DefaultDriverOption.SPECULATIVE_EXECUTION_MAX,
            config.speculativeExecution.maxAttempts - 1   // driver counts additional attempts
        )
        ...
}
```

**Important:** speculative execution only makes sense for idempotent statements. Kandra
must NOT enable speculative execution for non-idempotent writes (plain INSERT, collection
mutations, counter increments). Apply `statement.setIdempotent(false)` on these — the
driver will skip speculative execution for them automatically.

**Usage:**

```kotlin
install(Kandra) {
    speculativeExecution {
        enabled = true
        delayMillis = 100
        maxAttempts = 2
    }
}
```

**New tests:**
- Speculative execution disabled by default
- When enabled, driver config includes `ConstantSpeculativeExecutionPolicy`
- Non-idempotent statements (INSERT, counter) are not affected by speculative execution

---

## PART C — Database Authentication & Security

---

## 20. Credentials Provider Abstraction (`kandra-core` + `kandra-ktor`)

**Problem:** The current `username`/`password` fields in `KandraConfig` are plaintext
strings. Anyone following the README will hardcode credentials. There is no safe default,
no rotation support, and no abstraction for fetching secrets from external systems.

**New interface in `kandra-core`:**

```kotlin
fun interface KandraAuthProvider {
    fun getCredentials(): KandraCredentials
}

data class KandraCredentials(
    val username: String,
    val password: String
)
```

**New `KandraAuth` factory object in `kandra-core`:**

```kotlin
object KandraAuth {

    // Read from environment variables — SAFE DEFAULT, use this in production
    fun fromEnv(
        usernameVar: String = "SCYLLA_USERNAME",
        passwordVar: String = "SCYLLA_PASSWORD"
    ): KandraAuthProvider = KandraAuthProvider {
        val username = System.getenv(usernameVar)
            ?: throw KandraAuthException(
                "Environment variable '$usernameVar' is not set. " +
                "Set it or configure a different KandraAuthProvider."
            )
        val password = System.getenv(passwordVar)
            ?: throw KandraAuthException(
                "Environment variable '$passwordVar' is not set."
            )
        KandraCredentials(username, password)
    }

    // Read from a file (Docker secrets / Kubernetes secrets mounted as files)
    fun fromFile(
        usernamePath: String,
        passwordPath: String
    ): KandraAuthProvider = KandraAuthProvider {
        val username = java.io.File(usernamePath).readText().trim()
        val password = java.io.File(passwordPath).readText().trim()
        KandraCredentials(username, password)
    }

    // Static plaintext — for local dev and tests ONLY
    // KDoc MUST warn: never use in production
    fun static(username: String, password: String): KandraAuthProvider =
        KandraAuthProvider { KandraCredentials(username, password) }

    // Custom hook — user fetches from Vault, AWS Secrets Manager, etc.
    fun custom(provider: () -> KandraCredentials): KandraAuthProvider =
        KandraAuthProvider { provider() }
}
```

**Change — `AuthConfig` in `KandraConfig`:**

```kotlin
class KandraConfig {
    ...
    // Remove: var username: String? = null
    // Remove: var password: String? = null
    // Replace with:
    val auth: AuthConfig = AuthConfig()
}

class AuthConfig {
    // Default is fromEnv() — no credentials in code by default
    var provider: KandraAuthProvider = KandraAuth.fromEnv()

    // Credential rotation — re-fetch on interval; null = no rotation
    var refreshIntervalSeconds: Long? = null
}
```

**Change — `buildCqlSession`:** call `config.auth.provider.getCredentials()` at session
build time and pass to `.withAuthCredentials(creds.username, creds.password)`. If provider
throws, wrap in `KandraAuthException` and surface at startup.

**Credential rotation implementation:**
When `refreshIntervalSeconds != null`, schedule a coroutine on the `KandraRuntime` scope
that calls `provider.getCredentials()` on the interval and updates a `@Volatile` cached
credentials reference. The driver's custom `AuthProvider` reads from this cache on each
new connection, enabling rolling credential updates without server restart.

**New exception in `kandra-core`:**

```kotlin
class KandraAuthException(message: String, cause: Throwable? = null) : KandraException(message, cause)
```

**Usage:**

```kotlin
// Production — credentials from environment
install(Kandra) {
    auth {
        provider = KandraAuth.fromEnv()
    }
}

// Kubernetes secrets mounted as files
install(Kandra) {
    auth {
        provider = KandraAuth.fromFile(
            usernamePath = "/var/run/secrets/scylla/username",
            passwordPath = "/var/run/secrets/scylla/password"
        )
    }
}

// Custom — AWS Secrets Manager, Vault, etc.
install(Kandra) {
    auth {
        provider = KandraAuth.custom {
            val secret = awsSecretsClient.getSecretValue("coinx/scylla")
            KandraCredentials(secret.username, secret.password)
        }
        refreshIntervalSeconds = 3600   // rotate every hour
    }
}

// Local dev only — never production
install(Kandra) {
    auth {
        provider = KandraAuth.static("cassandra", "cassandra")
    }
}
```

**New tests:**
- `fromEnv()` reads from environment variables correctly
- `fromEnv()` throws `KandraAuthException` when env var is missing
- `fromFile()` reads credentials from file paths
- `fromFile()` throws `KandraAuthException` when file does not exist
- `static()` returns credentials directly
- `custom()` calls the provided lambda
- Credential rotation re-fetches on interval without restarting the session
- Auth failure at session build throws `KandraAuthException` (not raw driver exception)

---

## 21. SSL / TLS Encryption (`kandra-ktor`)

**Problem:** All traffic between Kandra and ScyllaDB is unencrypted by default. For CoinX
handling wallet and transaction data this is unacceptable in any environment beyond local
dev.

**Change — add `ssl` block to `KandraConfig`:**

```kotlin
class KandraConfig {
    ...
    val ssl: SslConfig = SslConfig()
}

class SslConfig {
    var enabled: Boolean = false

    // Enforce — throw KandraAuthException at startup if TLS handshake fails
    var requireEncryption: Boolean = true

    // Verify server cert hostname matches the contact point address
    var hostnameVerification: Boolean = true

    // Trust store — contains the CA cert that signed the Scylla server cert
    var trustStorePath: String? = null
    var trustStorePassword: String? = null
    var trustStoreType: String = "JKS"   // JKS or PKCS12

    // Key store — for mutual TLS (client certificate auth)
    // If set, the client presents a certificate to Scylla for authentication
    var keyStorePath: String? = null
    var keyStorePassword: String? = null
    var keyStoreType: String = "JKS"

    // Minimum TLS version
    var minimumTlsVersion: String = "TLSv1.2"

    // Allowed cipher suites — null = driver default
    var cipherSuites: List<String>? = null
}
```

**Change — wire SSL into `buildCqlSession`:**

```kotlin
if (config.ssl.enabled) {
    val sslContext = buildSslContext(config.ssl)
    CqlSession.builder()
        ...
        .withSslContext(sslContext)
        .build()
}

private fun buildSslContext(ssl: SslConfig): SSLContext {
    val trustManagerFactory = ssl.trustStorePath?.let { path ->
        val trustStore = KeyStore.getInstance(ssl.trustStoreType)
        FileInputStream(path).use { stream ->
            trustStore.load(stream, ssl.trustStorePassword?.toCharArray())
        }
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).also {
            it.init(trustStore)
        }
    }

    val keyManagerFactory = ssl.keyStorePath?.let { path ->
        val keyStore = KeyStore.getInstance(ssl.keyStoreType)
        FileInputStream(path).use { stream ->
            keyStore.load(stream, ssl.keyStorePassword?.toCharArray())
        }
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).also {
            it.init(keyStore, ssl.keyStorePassword?.toCharArray())
        }
    }

    return SSLContext.getInstance("TLS").also {
        it.init(
            keyManagerFactory?.keyManagers,
            trustManagerFactory?.trustManagers,
            null
        )
    }
}
```

**Hostname verification:** when `hostnameVerification = true`, apply via driver config:
```kotlin
DriverConfigLoader.programmaticBuilder()
    .withBoolean(DefaultDriverOption.SSL_HOSTNAME_VALIDATION, ssl.hostnameVerification)
    ...
```

**Minimum TLS version + cipher suites:** apply via the `SSLContext` engine factory
or driver programmatic config.

**Usage:**

```kotlin
// One-way TLS (server cert only — most common)
install(Kandra) {
    ssl {
        enabled = true
        trustStorePath = "/etc/kandra/truststore.jks"
        trustStorePassword = System.getenv("TRUSTSTORE_PASSWORD")
    }
}

// Mutual TLS (client cert auth — replaces username/password)
install(Kandra) {
    ssl {
        enabled = true
        trustStorePath = "/etc/kandra/truststore.jks"
        trustStorePassword = System.getenv("TRUSTSTORE_PASSWORD")
        keyStorePath = "/etc/kandra/keystore.jks"
        keyStorePassword = System.getenv("KEYSTORE_PASSWORD")
    }
    // With mTLS, auth provider is optional — cert IS the identity
    auth {
        provider = KandraAuth.static("", "")  // empty if Scylla uses cert-only auth
    }
}
```

**New tests:**
- `ssl.enabled = false` builds session without SSL context
- `ssl.enabled = true` with valid trust store builds SSL session
- `ssl.enabled = true` with missing trust store path throws `KandraAuthException`
- `hostnameVerification = true` applies to driver config
- `requireEncryption = true` throws `KandraAuthException` if TLS handshake fails
- mTLS config loads key store and passes `KeyManager` to `SSLContext`

---

## 22. Auth Error Handling (`kandra-core` + `kandra-runtime`)

**Problem:** `AuthenticationException` from the driver propagates as a raw untyped
exception. No way to distinguish wrong credentials from cert errors from unreachable
auth server. Stack traces from the driver are confusing to Kandra users.

**Change — wrap all auth-related driver exceptions in `BatchEngine` and session building:**

```kotlin
// In buildCqlSession
try {
    CqlSession.builder()...build()
} catch (e: AllNodesFailedException) {
    val authErrors = e.errors.values.flatten()
        .filterIsInstance<AuthenticationException>()
    if (authErrors.isNotEmpty()) {
        throw KandraAuthException(
            "ScyllaDB authentication failed. Check credentials or certificate config. " +
            "Contact point: ${e.errors.keys.firstOrNull()}",
            authErrors.first()
        )
    }
    throw KandraQueryException("Failed to connect to ScyllaDB: ${e.message}", e)
} catch (e: AuthenticationException) {
    throw KandraAuthException("ScyllaDB authentication failed: ${e.message}", e)
}
```

**Change — extend `KandraEventListener` with auth events:**

```kotlin
interface KandraEventListener {
    // Existing
    fun onEventualWriteFailed(tableName: String, entity: Any, error: Throwable)

    // New auth events
    fun onAuthFailed(contactPoint: String, error: Throwable) {}      // default no-op
    fun onConnectionEstablished(contactPoint: String) {}             // default no-op
    fun onCredentialRefreshed() {}                                   // default no-op
    fun onSslHandshakeFailed(contactPoint: String, error: Throwable) {} // default no-op
}
```

Note: change `KandraEventListener` from `fun interface` to a regular `interface` with
default no-op implementations so existing implementations only need to override the
methods they care about.

**Usage:**

```kotlin
install(Kandra) {
    eventListener = object : KandraEventListener {
        override fun onAuthFailed(contactPoint: String, error: Throwable) {
            alerting.critical("ScyllaDB auth failed on $contactPoint", error)
        }
        override fun onCredentialRefreshed() {
            logger.info("ScyllaDB credentials rotated successfully")
        }
        override fun onSslHandshakeFailed(contactPoint: String, error: Throwable) {
            alerting.critical("TLS handshake failed on $contactPoint", error)
        }
    }
}
```

**New tests:**
- Wrong credentials throw `KandraAuthException` (not raw `AuthenticationException`)
- `onAuthFailed` is called on the event listener when auth fails
- `onConnectionEstablished` is called when session connects successfully
- `onCredentialRefreshed` is called after each successful credential rotation
- `onSslHandshakeFailed` is called when TLS handshake fails
- `KandraEventListener` with only `onEventualWriteFailed` overridden compiles (default no-ops)

---

## 23. Keyspace Permission Validation at Startup (`kandra-ktor`)

**Problem:** A misconfigured service account that can connect but lacks `MODIFY` permission
on the keyspace will silently succeed at startup and fail only on the first real write —
hard to debug in production.

**Change — add permission check to plugin startup sequence** when `schemaMode != NONE`:

```kotlin
// After session connects and before DDL runs
fun validatePermissions(session: CqlSession, keyspace: String, schemaMode: SchemaMode) {
    // Query system_auth.role_permissions for the connected role
    val role = session.execute("SELECT role FROM system.local").one()
        ?.getString("role") ?: return  // can't determine role, skip check

    val permissions = session.execute(
        "SELECT permissions FROM system_auth.role_permissions WHERE role = ? AND resource = ?",
        role, "data/$keyspace"
    ).one()?.getSet("permissions", String::class.java) ?: emptySet()

    // Always required
    if ("SELECT" !in permissions && "ALL" !in permissions) {
        throw KandraAuthException(
            "Role '$role' lacks SELECT permission on keyspace '$keyspace'. " +
            "Grant: GRANT SELECT ON KEYSPACE $keyspace TO $role"
        )
    }
    if ("MODIFY" !in permissions && "ALL" !in permissions) {
        throw KandraAuthException(
            "Role '$role' lacks MODIFY permission on keyspace '$keyspace'. " +
            "Grant: GRANT MODIFY ON KEYSPACE $keyspace TO $role"
        )
    }

    // Only required if autoCreate / VALIDATE mode
    if (schemaMode != SchemaMode.NONE) {
        if ("ALTER" !in permissions && "ALL" !in permissions) {
            logger.warn(
                "Role '$role' lacks ALTER permission on keyspace '$keyspace'. " +
                "This is required for schemaMode = AUTO_CREATE. " +
                "Grant: GRANT ALTER ON KEYSPACE $keyspace TO $role"
            )
        }
    }
}
```

This runs as a `WARN` not an exception for `ALTER` (lack of ALTER only breaks DDL, not
reads/writes). `SELECT` and `MODIFY` throw `KandraAuthException` immediately.

**Add to `KandraConfig`:**

```kotlin
class KandraConfig {
    ...
    var validatePermissions: Boolean = true   // set false to skip — useful in restricted envs
}
```

**New tests:**
- Missing `SELECT` permission throws `KandraAuthException` at startup
- Missing `MODIFY` permission throws `KandraAuthException` at startup
- Missing `ALTER` permission logs `WARN` but does not throw
- `validatePermissions = false` skips the check entirely
- Superuser role with `ALL` permissions passes all checks

---

## Updated Build Order for 0.3.0

Apply in this order — run `./gradlew test` after each step before proceeding:

**Part A — Missing items:**
1. Version bump to `0.3.0-SNAPSHOT` in `gradle.properties`
2. Retry policy — `kandra-ktor` + `kandra-runtime`
3. Idempotency tokens — `kandra-runtime`
4. `IN` clause on partition keys — `kandra-runtime`
5. Nullable codec contract — `kandra-runtime`
6. Caller-controlled batch (`KandraBatchScope`) — `kandra-runtime` + `kandra-ktor`
7. `@SecondaryIndex` — `kandra-core` + `kandra-runtime`
8. Prepared statement LRU cache — `kandra-runtime`
9. `USING TIMESTAMP` — `kandra-runtime`
10. Query debug logging — `kandra-runtime` + `kandra-ktor`
11. Test keyspace isolation + `KandraTestcontainers` — `kandra-test`
12. Incremental KSP — `kandra-codegen`
13. Maven Central publishing config — all modules + `buildSrc`
14. API stability markers — `kandra-core` (apply annotations across all modules)
15. `CHANGELOG.md`

**Part B — Multi-DC:**
16. Scaffold `kandra-multidc` module
17. Load balancing policy config
18. Consistency level per operation (`KandraConsistency`, annotations, per-op override)
19. LWT serial consistency on `saveIfNotExists`
20. DC failover policy
21. Speculative execution

**Part C — Auth & Security:**
22. `KandraAuthProvider` abstraction + `KandraAuth` factory + `KandraAuthException` — `kandra-core`
23. Remove hardcoded `username`/`password` from `KandraConfig`; wire `AuthConfig` — `kandra-ktor`
24. Credential rotation coroutine — `kandra-ktor`
25. SSL/TLS config (`SslConfig`) + `buildSslContext` — `kandra-ktor`
26. Auth error wrapping + extend `KandraEventListener` with auth events — `kandra-core` + `kandra-runtime`
27. Keyspace permission validation at startup — `kandra-ktor`

**Final:**
28. Add `kandra-multidc` to `kandra-bom`
29. Update `README.md`:
    - Auth section: `KandraAuth.fromEnv()` as the documented default, mTLS example,
      credential rotation example, Kubernetes secrets example
    - SSL section: one-way TLS and mTLS config examples
    - Multi-DC section: consistency level guide, DC failover, speculative execution
30. Update `CHANGELOG.md` with all 0.3.0 entries
