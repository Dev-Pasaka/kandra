# Kandra — Kotlin ScyllaDB ORM as a Ktor Plugin: Claude Code Build Prompt

## Project Overview

Build **Kandra**, a lightweight Kotlin-first ORM for ScyllaDB/Cassandra that ships as a
**Ktor server plugin**. It has first-class support for denormalized lookup tables — a pattern
ScyllaDB requires but no existing Kotlin ORM handles natively.

Kandra will be published as a Ktor plugin to Maven Central and used in production in **CoinX**
(a crypto social finance platform built on Ktor + ScyllaDB).

**Core philosophy:**
- Zero Spring, zero magic
- Ktor-native: installed like any other Ktor plugin
- DI-native: auto-binds repositories into Kodein or Koin at install time
- Fail fast: all schema errors surface at startup, never at query time
- Denormalization-aware: lookup tables are a first-class citizen, not an afterthought

---

## Repository Structure

```
kandra/
├── build.gradle.kts                        # root — shared deps, versioning
├── settings.gradle.kts
├── gradle.properties                       # version catalog
├── kandra-core/                            # annotations, schema model, DDL, registry
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/io/kandra/core/
│       └── test/kotlin/io/kandra/core/
├── kandra-runtime/                         # driver wiring, codec, batch engine, DSL, repositories
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/io/kandra/runtime/
│       └── test/kotlin/io/kandra/runtime/
├── kandra-ktor/                            # Ktor plugin — ApplicationPlugin implementation
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/io/kandra/ktor/
│       └── test/kotlin/io/kandra/ktor/
├── kandra-kodein/                          # Kodein-DI integration module
│   ├── build.gradle.kts
│   └── src/
│       └── main/kotlin/io/kandra/kodein/
├── kandra-koin/                            # Koin integration module
│   ├── build.gradle.kts
│   └── src/
│       └── main/kotlin/io/kandra/koin/
├── kandra-codegen/                         # KSP processor — generates type-safe table objects
│   ├── build.gradle.kts
│   └── src/
│       └── main/kotlin/io/kandra/codegen/
└── kandra-test/                            # FakeKandraSession + test utilities
    ├── build.gradle.kts
    └── src/
        └── main/kotlin/io/kandra/test/
```

---

## Tech Stack

| Concern | Library |
|---|---|
| Language | Kotlin 2.x |
| Build | Gradle 8.x with Kotlin DSL |
| ScyllaDB / Cassandra driver | `com.datastax.oss:java-driver-core:4.17.0` |
| Ktor server | `io.ktor:ktor-server-core:2.3.x` |
| Coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.x` |
| Kodein DI | `org.kodein.di:kodein-di:7.x` + `org.kodein.di:kodein-di-framework-ktor-server-jvm` |
| Koin | `io.insert-koin:koin-ktor:3.x` + `io.insert-koin:koin-core:3.x` |
| KSP | `com.google.devtools.ksp:symbol-processing-api` |
| Reflection | `org.jetbrains.kotlin:kotlin-reflect` |
| Logging | `io.github.oshai:kotlin-logging-jvm:6.x` |
| Testing | JUnit 5 + `org.testcontainers:cassandra` + `io.ktor:ktor-server-test-host` |

---

## Phase 1 — `kandra-core`

### 1.1 Annotations (`io.kandra.core.annotations`)

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ScyllaTable(val name: String)

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class PartitionKey

enum class ClusteringOrder { ASC, DESC }

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class ClusteringKey(
    val order: ClusteringOrder = ClusteringOrder.ASC,
    val index: Int = 0
)

enum class LookupConsistency { BATCH, EVENTUAL }

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class LookupIndex(
    val tableSuffix: String,
    val consistency: LookupConsistency = LookupConsistency.BATCH
)

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Column(val name: String)

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Transient
```

### 1.2 Schema Model (`io.kandra.core.schema`)

```kotlin
data class ColumnSchema(
    val propertyName: String,
    val cqlName: String,
    val type: KType,
    val isPartitionKey: Boolean = false,
    val clusteringKey: ClusteringKeySchema? = null,
    val lookupIndex: LookupIndexSchema? = null,
    val isTransient: Boolean = false
)

data class ClusteringKeySchema(val order: ClusteringOrder, val index: Int)

data class LookupIndexSchema(val tableName: String, val consistency: LookupConsistency)

data class TableSchema(
    val entityClass: KClass<*>,
    val tableName: String,
    val partitionKey: ColumnSchema,
    val clusteringKeys: List<ColumnSchema>,
    val columns: List<ColumnSchema>,
    val lookupTables: List<LookupTableSchema>
)

data class LookupTableSchema(
    val tableName: String,
    val indexColumn: ColumnSchema,
    val partitionKeyColumn: ColumnSchema,
    val consistency: LookupConsistency
)
```

### 1.3 Schema Registry (`io.kandra.core.SchemaRegistry`)

- Thread-safe (`ConcurrentHashMap`)
- `fun <T : Any> register(klass: KClass<T>): TableSchema`
  - Scans with `kotlin-reflect`
  - Validates: exactly one `@PartitionKey`, no duplicate lookup suffixes
  - Resolves CQL name: `@Column.name` if present, else `camelToSnake(propertyName)`
  - Throws `KandraSchemaException` with actionable messages on invalid config
- `fun get(klass: KClass<*>): TableSchema`
- `fun getOrNull(klass: KClass<*>): TableSchema?`
- `fun all(): List<TableSchema>`
- Internal: `fun camelToSnake(name: String): String`

### 1.4 DDL Generator (`io.kandra.core.DdlGenerator`)

```kotlin
object DdlGenerator {
    fun primaryTable(schema: TableSchema): String
    fun lookupTable(lookup: LookupTableSchema): String
    fun allStatements(schema: TableSchema): List<String>
}
```

**Primary table rules:**
- `PRIMARY KEY ((partition_key))` for simple PKs
- `PRIMARY KEY ((partition_key), c1, c2)` for compound PKs
- `WITH CLUSTERING ORDER BY (col ASC/DESC)` when clustering keys exist

**Lookup table:**
```sql
CREATE TABLE IF NOT EXISTS users_by_email (
    email TEXT,
    user_id UUID,
    PRIMARY KEY (email)
);
```

**Kotlin → CQL type mapping:**

| Kotlin | CQL |
|---|---|
| UUID | UUID |
| String | TEXT |
| Int | INT |
| Long | BIGINT |
| Boolean | BOOLEAN |
| Double | DOUBLE |
| Float | FLOAT |
| Instant | TIMESTAMP |
| LocalDate | DATE |
| ByteArray | BLOB |
| List<*> | LIST<inner type> |
| Set<*> | SET<inner type> |
| Map<*,*> | MAP<k,v> |
| Enum subclass | TEXT |

Throw `KandraSchemaException("Unsupported type: ...")` for unmapped types.

### 1.5 Exceptions (`io.kandra.core.exception`)

```kotlin
open class KandraException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class KandraSchemaException(message: String) : KandraException(message)
class KandraQueryException(message: String, cause: Throwable? = null) : KandraException(message, cause)
```

### 1.6 Core Tests

Test entity used across all tests:

```kotlin
@ScyllaTable("users")
data class User(
    @PartitionKey
    val userId: UUID,
    @ClusteringKey(order = ClusteringOrder.ASC, index = 0)
    val createdAt: Instant,
    @LookupIndex(tableSuffix = "by_email", consistency = LookupConsistency.BATCH)
    val email: String,
    @LookupIndex(tableSuffix = "by_phone", consistency = LookupConsistency.EVENTUAL)
    val phone: String,
    @Column("full_name")
    val name: String,
    @Transient
    val cachedToken: String? = null
)
```

Write JUnit 5 tests covering:
- Simple entity registration
- Compound PK entity registration
- Multiple `@LookupIndex` fields
- `KandraSchemaException` on missing `@PartitionKey`
- `KandraSchemaException` on duplicate `@PartitionKey`
- `@Column` rename reflected in `cqlName`
- `@Transient` excluded from columns
- `camelToSnake` correctness: `userId` → `user_id`, `createdAt` → `created_at`
- DDL output for simple entity is valid CQL
- DDL output for compound PK includes CLUSTERING ORDER BY
- Lookup table DDL has exactly two columns
- All type mappings produce correct CQL strings
- Unsupported type throws `KandraSchemaException`

---

## Phase 2 — `kandra-runtime`

### 2.1 Value Codec (`io.kandra.runtime.codec.KandraCodec`)

```kotlin
object KandraCodec {
    fun encode(value: Any?, type: KType): Any?    // Kotlin → driver value
    fun decode(row: Row, column: ColumnSchema): Any?  // Row → Kotlin value
}
```

- Handle all mapped types
- Enums stored/read as their `.name` string
- Handle nullable types — never `!!`

### 2.2 Statement Builder (`io.kandra.runtime.StatementBuilder`)

```kotlin
class StatementBuilder(private val session: CqlSession) {
    fun insertPrimary(schema: TableSchema, entity: Any): BoundStatement
    fun insertLookup(lookup: LookupTableSchema, entity: Any): BoundStatement
    fun deleteLookup(lookup: LookupTableSchema, indexValue: Any): BoundStatement
    fun selectById(schema: TableSchema, id: Any): BoundStatement
    fun selectByLookup(lookup: LookupTableSchema, value: Any): BoundStatement
    fun deleteById(schema: TableSchema, id: Any): BoundStatement
}
```

Cache `PreparedStatement` by CQL string in a `ConcurrentHashMap`. Never prepare the same
statement twice.

### 2.3 Batch Engine (`io.kandra.runtime.BatchEngine`)

```kotlin
class BatchEngine(
    private val session: CqlSession,
    private val statementBuilder: StatementBuilder,
    private val scope: CoroutineScope   // for EVENTUAL fire-and-forget
) {
    fun save(schema: TableSchema, entity: Any)
    fun update(schema: TableSchema, old: Any, new: Any)
    fun delete(schema: TableSchema, entity: Any)

    suspend fun saveSuspend(schema: TableSchema, entity: Any)
    suspend fun updateSuspend(schema: TableSchema, old: Any, new: Any)
    suspend fun deleteSuspend(schema: TableSchema, entity: Any)
}
```

**Save behaviour:**
1. Build a `LOGGED BatchStatement`
2. Add primary table insert
3. Add inserts for all `BATCH` consistency lookup tables
4. Execute the batch
5. After batch commits, fire inserts for `EVENTUAL` lookup tables via `scope.launch` —
   catch and log failures, never throw

**Update behaviour:**
1. Diff old vs new for each lookup field
2. For changed fields: delete old lookup row + insert new lookup row
3. For unchanged fields: insert new lookup row only
4. All `BATCH` consistency changes go in one `LOGGED BatchStatement`
5. `EVENTUAL` changes fire after batch commits

**Delete behaviour:**
- `LOGGED BatchStatement` deleting primary row + all lookup rows

### 2.4 Query DSL (`io.kandra.runtime.dsl`)

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
    infix fun eq(value: T): KandraPredicate = KandraPredicate.Eq(cqlName, value)
    infix fun gt(value: T): KandraPredicate = KandraPredicate.Gt(cqlName, value)
    infix fun gte(value: T): KandraPredicate = KandraPredicate.Gte(cqlName, value)
    infix fun lt(value: T): KandraPredicate = KandraPredicate.Lt(cqlName, value)
    infix fun lte(value: T): KandraPredicate = KandraPredicate.Lte(cqlName, value)
    infix fun isIn(values: List<T>): KandraPredicate = KandraPredicate.In(cqlName, values)
}

class QueryContext {
    internal val predicates = mutableListOf<KandraPredicate>()
    operator fun KandraPredicate.unaryPlus() { predicates.add(this) }
}
```

**Query resolution in `QueryExecutor`:**
1. Inspect predicates — partition key, clustering key, or lookup field
2. Lookup field → two-step: query lookup table for partition key, then query primary table
3. Build CQL `SELECT WHERE` from predicates
4. Decode rows via `KandraCodec`

### 2.5 Repositories (`io.kandra.runtime.repository`)

```kotlin
class KandraRepository<T : Any>(
    private val session: CqlSession,
    private val schema: TableSchema,
    private val entityClass: KClass<T>,
    private val batchEngine: BatchEngine
) {
    fun save(entity: T)
    fun update(old: T, new: T)
    fun delete(entity: T)
    fun findById(id: Any): T?
    fun find(block: QueryContext.() -> Unit): T?
    fun findAll(block: QueryContext.() -> Unit): List<T>
    fun raw(cql: String, vararg params: Any?): List<Row>
}

class KandraSuspendRepository<T : Any>(...) {
    suspend fun save(entity: T)
    suspend fun update(old: T, new: T)
    suspend fun delete(entity: T)
    suspend fun findById(id: Any): T?
    suspend fun find(block: QueryContext.() -> Unit): T?
    suspend fun findAll(block: QueryContext.() -> Unit): List<T>
    suspend fun raw(cql: String, vararg params: Any?): List<Row>
}
```

---

## Phase 3 — `kandra-ktor`

### 3.1 Plugin Configuration (`io.kandra.ktor.KandraConfig`)

```kotlin
class KandraConfig {
    var contactPoints: String = "localhost:9042"
    var keyspace: String = ""
    var username: String? = null
    var password: String? = null
    var autoCreate: Boolean = true
    var dropAndRecreate: Boolean = false   // guard: throw if true + production profile detected
    internal val entities = mutableListOf<KClass<*>>()

    fun register(vararg classes: KClass<*>) {
        entities.addAll(classes)
    }
}
```

### 3.2 Ktor Plugin (`io.kandra.ktor.Kandra`)

Implement as a Ktor `ApplicationPlugin<KandraConfig>`:

```kotlin
val Kandra = createApplicationPlugin(name = "Kandra", createConfiguration = ::KandraConfig) {
    val config = pluginConfig

    // 1. Build CqlSession from Java driver
    val session = buildCqlSession(config)

    // 2. Register all entities in SchemaRegistry
    config.entities.forEach { SchemaRegistry.register(it) }

    // 3. Run DDL if autoCreate = true
    if (config.autoCreate) {
        SchemaRegistry.all().forEach { schema ->
            DdlGenerator.allStatements(schema).forEach { session.execute(it) }
        }
    }

    // 4. Store session on application for DI modules to access
    application.attributes.put(KandraSessionKey, session)

    // 5. Graceful shutdown
    environment.monitor.subscribe(ApplicationStopped) {
        session.close()
    }
}

// Attribute key
val KandraSessionKey = AttributeKey<CqlSession>("KandraSession")

// Extension to retrieve session anywhere
val Application.kandraSession: CqlSession
    get() = attributes[KandraSessionKey]
```

**`buildCqlSession` must use the DataStax Java driver:**

```kotlin
private fun buildCqlSession(config: KandraConfig): CqlSession {
    return CqlSession.builder()
        .addContactPoints(
            config.contactPoints.split(",").map {
                val (host, port) = it.trim().split(":")
                InetSocketAddress(host, port.toInt())
            }
        )
        .withKeyspace(config.keyspace)
        .apply {
            if (config.username != null && config.password != null) {
                withAuthCredentials(config.username!!, config.password!!)
            }
        }
        .build()
}
```

### 3.3 Installation Usage (document in KDoc and README)

```kotlin
fun Application.configureDatabase() {
    install(Kandra) {
        contactPoints = "localhost:9042"
        keyspace = "coinx"
        autoCreate = true
        register(User::class, Wallet::class, Transaction::class)
    }
}
```

### 3.4 Ktor Plugin Tests

Use `ktor-server-test-host` with a Testcontainers ScyllaDB/Cassandra instance:
- Plugin installs without error
- Tables are created on startup
- `application.kandraSession` is accessible
- Session closes on `ApplicationStopped`

---

## Phase 4 — `kandra-kodein`

Auto-bind repositories into Kodein DI when Kandra plugin is installed.

### 4.1 Kodein Extension (`io.kandra.kodein`)

```kotlin
// Extension installed after Kandra plugin
fun Application.kandraKodein() {
    val session = kandraSession
    val registry = SchemaRegistry

    // Extend the existing Kodein DI instance (assumes Kodein already installed on the app)
    di {
        registry.all().forEach { schema ->
            val entityClass = schema.entityClass

            // Bind KandraRepository<T>
            bind<KandraRepository<*>>(tag = entityClass.simpleName) with singleton {
                KandraRepository(session, schema, entityClass, BatchEngine(session, StatementBuilder(session), GlobalScope))
            }

            // Bind KandraSuspendRepository<T>
            bind<KandraSuspendRepository<*>>(tag = "${entityClass.simpleName}Suspend") with singleton {
                KandraSuspendRepository(session, schema, entityClass, BatchEngine(session, StatementBuilder(session), GlobalScope))
            }
        }
    }
}
```

**Usage in a Ktor app with Kodein:**

```kotlin
fun Application.configureDatabase() {
    install(Kandra) {
        contactPoints = "localhost:9042"
        keyspace = "coinx"
        register(User::class, Wallet::class)
    }
    kandraKodein()  // auto-binds all repositories
}

// In a route
fun Route.userRoutes() {
    val userRepo by closestDI().instance<KandraSuspendRepository<*>>(tag = "UserSuspend")

    get("/users/{id}") {
        val user = userRepo.findById(UUID.fromString(call.parameters["id"]))
        call.respond(user ?: HttpStatusCode.NotFound)
    }
}
```

**Type-safe helper (generate one per registered entity via extension or codegen):**

```kotlin
// Kodein typed accessor extensions — generated or written manually per entity
inline fun <reified T : Any> DI.Main.bindKandraRepository(
    session: CqlSession,
    schema: TableSchema
) {
    bind<KandraRepository<T>>() with singleton {
        KandraRepository(session, schema, T::class, BatchEngine(...))
    }
    bind<KandraSuspendRepository<T>>() with singleton {
        KandraSuspendRepository(session, schema, T::class, BatchEngine(...))
    }
}
```

---

## Phase 5 — `kandra-koin`

### 5.1 Koin Extension (`io.kandra.koin`)

```kotlin
fun Application.kandraKoin() {
    val session = kandraSession
    val registry = SchemaRegistry

    koin {
        modules(
            module {
                registry.all().forEach { schema ->
                    val entityClass = schema.entityClass

                    single(named("${entityClass.simpleName}Repo")) {
                        KandraRepository(session, schema, entityClass, BatchEngine(session, StatementBuilder(session), GlobalScope))
                    }

                    single(named("${entityClass.simpleName}SuspendRepo")) {
                        KandraSuspendRepository(session, schema, entityClass, BatchEngine(session, StatementBuilder(session), GlobalScope))
                    }
                }
            }
        )
    }
}
```

**Usage in a Ktor app with Koin:**

```kotlin
fun Application.configureDatabase() {
    install(Kandra) {
        contactPoints = "localhost:9042"
        keyspace = "coinx"
        register(User::class, Wallet::class)
    }
    kandraKoin()  // auto-binds all repositories
}

// In a route
fun Route.userRoutes() {
    val userRepo by inject<KandraSuspendRepository<*>>(named("UserSuspendRepo"))

    get("/users/{id}") {
        val user = userRepo.findById(UUID.fromString(call.parameters["id"]))
        call.respond(user ?: HttpStatusCode.NotFound)
    }
}
```

---

## Phase 6 — `kandra-codegen` (KSP Processor)

For each `@ScyllaTable` class, generate a type-safe table object.

**Input:**
```kotlin
@ScyllaTable("users")
data class User(
    @PartitionKey val userId: UUID,
    @LookupIndex(tableSuffix = "by_email", consistency = LookupConsistency.BATCH)
    val email: String,
    @Column("full_name") val name: String
)
```

**Generated output** (`UserTable.kt`, same package):
```kotlin
// GENERATED BY KANDRA — DO NOT EDIT
object UserTable : KandraTable<User> {
    val userId = KandraColumnRef<UUID>("user_id")
    val email  = KandraColumnRef<String>("email", isLookup = true)
    val name   = KandraColumnRef<String>("full_name")
}
```

**KSP processor:**
- Class: `io.kandra.codegen.KandraProcessor : SymbolProcessor`
- Registered via `resources/META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`
- Only processes `@ScyllaTable` annotated classes
- One generated file per entity

**With codegen, routes become fully type-safe:**
```kotlin
val user = userRepo.find { +UserTable.email.eq("pasaka@coinx.io") }
```

---

## Phase 7 — `kandra-test`

```kotlin
// Drop-in fake — no real Scylla needed for unit tests
class FakeKandraSession : CqlSession {
    // In-memory store: Map<tableName, Map<partitionKeyValue, Map<columnName, Any?>>>
    // Intercepts execute() — simulates INSERT / SELECT / DELETE
    fun capturedBatches(): List<BatchStatement>   // assert batch behaviour in tests
    fun tableContents(tableName: String): List<Map<String, Any?>>  // inspect state
}

object KandraTestUtils {
    // Returns a fully wired Kandra backed by FakeKandraSession
    fun inMemory(vararg classes: KClass<*>): KandraRuntime
}
```

---

## Global Requirements

- **No Spring dependencies anywhere** — not even transitively
- **Java driver only** — `com.datastax.oss:java-driver-core:4.17.0` is the sole Scylla/Cassandra dependency
- **All public API has KDoc** — class purpose, parameters, throws, example usage
- **Thread-safe** — `SchemaRegistry` and `PreparedStatement` cache use `ConcurrentHashMap`
- **No `!!` in library code** — handle nulls with `?: throw KandraException(...)`
- **Fail fast** — all schema validation at `install(Kandra)` time, never at query time
- **Gradle tasks:** `./gradlew test` runs all module tests; `./gradlew build` produces publishable JARs

---

## Coding Style

- Kotlin idioms throughout — no Java-style verbosity
- `data class` for value objects, `object` for singletons
- `kotlin-reflect` not `java.lang.reflect`
- Extension functions over utility classes where natural
- Sealed classes for sum types (predicates, results)
- `Result<T>` wrapping for operations that can fail gracefully

---

## Build Order (complete and test each before starting next)

1. `kandra-core` — annotations, schema model, registry, DDL, exceptions, tests ✓
2. `kandra-runtime` — codec, statement builder, batch engine, DSL, repositories ✓
3. `kandra-ktor` — Ktor `ApplicationPlugin`, session lifecycle, Testcontainers integration test ✓
4. `kandra-kodein` — Kodein DI auto-binding ✓
5. `kandra-koin` — Koin DI auto-binding ✓
6. `kandra-codegen` — KSP processor generating table objects ✓
7. `kandra-test` — FakeKandraSession + test utils ✓
8. Root `README.md` — quickstart, installation, full `User` entity example, DI examples for both Kodein and Koin ✓

---

## README Quickstart to Generate

The README must include a complete working example showing:

```kotlin
// 1. Install plugin (Application.kt)
fun Application.configureDatabase() {
    install(Kandra) {
        contactPoints = "localhost:9042"
        keyspace = "coinx"
        autoCreate = true
        register(User::class, Wallet::class, Transaction::class)
    }
    kandraKoin()   // or kandraKodein()
}

// 2. Entity definition
@ScyllaTable("users")
data class User(
    @PartitionKey val userId: UUID,
    @LookupIndex(tableSuffix = "by_email", consistency = LookupConsistency.BATCH)
    val email: String,
    @LookupIndex(tableSuffix = "by_phone", consistency = LookupConsistency.EVENTUAL)
    val phone: String,
    @Column("full_name") val name: String
)

// 3. Route usage (Koin)
fun Route.userRoutes() {
    val userRepo by inject<KandraSuspendRepository<*>>(named("UserSuspendRepo"))

    post("/users") {
        val user = call.receive<User>()
        userRepo.save(user)   // writes users + users_by_email (BATCH) + users_by_phone (EVENTUAL)
        call.respond(HttpStatusCode.Created)
    }

    get("/users/email/{email}") {
        val user = userRepo.find { +UserTable.email.eq(call.parameters["email"]!!) }
        call.respond(user ?: HttpStatusCode.NotFound)
    }
}
```
