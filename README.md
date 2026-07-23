# Kandra

**Kandra** is a lightweight Kotlin-first ORM for ScyllaDB/Cassandra that ships as a Ktor server plugin.

First-class support for denormalized lookup tables, composite partition keys, TTL, pagination, LWT, and counter tables — patterns ScyllaDB requires that no existing Kotlin ORM handles natively.

[![CI](https://github.com/Dev-Pasaka/kandra/actions/workflows/ci.yml/badge.svg)](https://github.com/Dev-Pasaka/kandra/actions)
[![Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

---

## Philosophy

- **Zero Spring, zero magic** — just Ktor-native plugin installation
- **DI-native** — auto-binds repositories into Kodein or Koin at install time
- **Fail fast** — all schema errors surface at startup, never at query time
- **Denormalization-aware** — lookup tables are a first-class citizen, not an afterthought

---

## Modules

| Module | Purpose |
|---|---|
| `kandra-core` | Annotations, schema model, DDL generator, registry, exceptions |
| `kandra-runtime` | Driver wiring, codec, batch engine, query DSL, repositories |
| `kandra-ktor` | Ktor `ApplicationPlugin` implementation |
| `kandra-kodein` | Kodein DI auto-binding |
| `kandra-koin` | Koin DI auto-binding |
| `kandra-codegen` | KSP processor — generates type-safe `*Table` objects |
| `kandra-test` | `FakeKandraSession` + Testcontainers integration helpers |
| `kandra-multidc` | Multi-datacenter utilities and documentation |
| `kandra-migrate` | Versioned, checksum-validated schema migration runner |
| `kandra-jakarta` | Jakarta Bean Validation adapter (`JakartaKandraValidator`) |
| `kandra-bom` | Bill of Materials for version alignment |

---

## Documentation

This README covers the common cases end-to-end. For everything else:

| Doc | Contents |
|---|---|
| [`docs/USER_GUIDE.md`](docs/USER_GUIDE.md) | The full reference — every annotation, config option, and repository method in depth |
| [`docs/changelog/`](docs/changelog/README.md) | What changed in each version, one file per version |
| [`docs/features/`](docs/features/README.md) | Feature-by-feature reference, one file per area |
| [`docs/issues/`](docs/issues/README.md) | Known gaps and issues — open, fixed, and closed — with the reasoning behind each |
| [`docs/test-plan/`](docs/test-plan/README.md) | Step-by-step plan for building a real Ktor app against the published artifact and a real ScyllaDB cluster, with an exhaustive functional/edge-case coverage matrix and scoring rubric |
| [`docs/history/`](docs/history/) | The original build specs used to generate each version (0.1.0 → 0.4.0) — historical context, not current docs |
| [`docs/site/`](docs/site/README.md) | Build prompts for the separate documentation website project, one file per Kandra version |
| [API docs (Dokka)](https://dev-pasaka.github.io/kandra/) | Generated per-release from source, published on tag |
| [`.claude/skills/`](.claude/skills/) | Per-module Claude Code skills — see [Using with Claude Code](#using-with-claude-code) below |

---

## Using with Claude Code

Kandra ships [Claude Code](https://claude.com/claude-code) skills at `.claude/skills/` — a project-wide
overview (`kandra`) plus one exhaustive, module-specific skill per module (`kandra-core`, `kandra-runtime`,
`kandra-ktor`, `kandra-kodein`, `kandra-koin`, `kandra-codegen`, `kandra-test`, `kandra-multidc`,
`kandra-migrate`). Each module skill documents every public class, function, and property in that module,
traced from the actual implementation — including footguns that don't show up as compile errors (e.g.
`update(old, new)` taking two entities, or which entity annotations `kandra-codegen` actually reads versus
which are silently ignored).

To get this context in your own project, copy the whole folder in:

```bash
mkdir -p .claude/skills
cp -r /path/to/kandra/.claude/skills/. .claude/skills/
```

Claude Code loads the relevant skill automatically based on what you're doing — defining `@ScyllaTable`
entities, calling repository methods, configuring `install(Kandra) { ... }`, wiring DI, writing tests, or
authoring a schema migration.

---

## Quick Start

### 1. Add dependencies (with BOM)

```kotlin
// build.gradle.kts
dependencies {
    implementation(platform("ke.co.coinx.kandra:kandra-bom:0.4.6"))
    implementation("ke.co.coinx.kandra:kandra-ktor")
    implementation("ke.co.coinx.kandra:kandra-koin")   // or kandra-kodein
    ksp("ke.co.coinx.kandra:kandra-codegen")           // optional — type-safe table objects
    testImplementation("ke.co.coinx.kandra:kandra-test")
    // Optional: multi-DC utilities
    implementation("ke.co.coinx.kandra:kandra-multidc")
    // Optional: versioned schema migrations
    implementation("ke.co.coinx.kandra:kandra-migrate")
}
```

### 2. Define your entities

```kotlin
@ScyllaTable("users")
data class User(
    @PartitionKey
    val userId: UUID,

    @LookupIndex(tableSuffix = "by_email", consistency = LookupConsistency.BATCH)
    val email: String,

    @LookupIndex(tableSuffix = "by_phone", consistency = LookupConsistency.EVENTUAL)
    val phone: String,

    @Column("full_name")
    val name: String,

    @CreatedAt val createdAt: Instant = Instant.EPOCH,
    @UpdatedAt val updatedAt: Instant = Instant.EPOCH
)

// Composite partition key
@ScyllaTable("transactions_by_user_chain")
data class Transaction(
    @PartitionKey(index = 0) val userId: UUID,
    @PartitionKey(index = 1) val chain: String,
    @ClusteringKey(order = ClusteringOrder.DESC, index = 0)
    val createdAt: Instant,
    val amount: Double,
    val status: TransactionStatus
)

// TTL — OTP codes expire in 5 minutes
@ScyllaTable("otp_codes")
@Ttl(300)
data class OtpCode(
    @PartitionKey val phone: String,
    val code: String,
    val createdAt: Instant
)

// Counter table
@ScyllaTable("chain_stats")
data class ChainStats(
    @PartitionKey val chain: String,
    @Counter val totalTransactions: Long,
    @Counter val totalVolumeUsd: Long
)
```

### 3. Install the plugin

```kotlin
fun Application.configureDatabase() {
    install(Kandra) {
        contactPoints = "scylla:9042"
        keyspace = "coinx"
        localDatacenter = "datacenter1"
        autoCreateKeyspace = true             // CREATE KEYSPACE IF NOT EXISTS
        schemaMode = SchemaMode.AUTO_CREATE   // CREATE TABLE IF NOT EXISTS
        register(User::class, Transaction::class, OtpCode::class, ChainStats::class)

        // Auth — defaults to env vars SCYLLA_USERNAME / SCYLLA_PASSWORD
        auth {
            provider = KandraAuth.fromEnv()          // production default
            // provider = KandraAuth.static("cassandra", "cassandra")  // local dev
            // provider = KandraAuth.fromFile("/run/secrets/db-creds")  // k8s secrets
            refreshIntervalSeconds = 3600            // optional credential rotation
        }

        pool {
            requestTimeoutMillis = 10_000
            maxRequestsPerConnection = 32768
        }

        retry {
            maxAttempts = 3
            backoffMillis = 100
        }

        debug {
            logQueries = false        // CQL templates at DEBUG (no bound values)
            logSlowQueriesMs = 500    // WARN if query exceeds this threshold
        }

        eventListener = object : KandraEventListener {
            override fun onEventualWriteFailed(tableName: String, entity: Any, error: Throwable) {
                logger.error("Eventual write failed on $tableName for $entity", error)
            }
        }
    }
    kandraKoin()   // or kandraKodein()
}
```

### 4. Use in routes (Koin)

```kotlin
fun Route.userRoutes() {
    val userRepo by inject<KandraSuspendRepository<*>>(named("UserSuspendRepo"))
    val txnRepo  by inject<KandraSuspendRepository<*>>(named("TransactionSuspendRepo"))

    post("/users") {
        val user = call.receive<User>()
        userRepo.save(user)
        // ↑ @CreatedAt and @UpdatedAt auto-set to Instant.now()
        // ↑ writes users (primary) + users_by_email (BATCH) atomically
        // ↑ users_by_phone fires asynchronously (EVENTUAL)
        call.respond(HttpStatusCode.Created)
    }

    post("/users/register") {
        val user = call.receive<User>()
        val inserted = userRepo.saveIfNotExists(user)   // LWT — no duplicate emails
        if (!inserted) call.respond(HttpStatusCode.Conflict)
        else call.respond(HttpStatusCode.Created)
    }

    get("/users/email/{email}") {
        // Generated by kandra-codegen — fully type-safe:
        val user = userRepo.find { UserTable.email eq call.parameters["email"]!! }
        call.respond(user ?: HttpStatusCode.NotFound)
    }

    get("/users/{id}") {
        val user = userRepo.findById(UUID.fromString(call.parameters["id"]!!))
        call.respond(user ?: HttpStatusCode.NotFound)
    }

    // Paginated transaction history
    get("/users/{id}/transactions") {
        val pageToken = call.request.queryParameters["page"]
        val page = txnRepo.findPage(pageSize = 20, pageToken = pageToken) {
            TransactionTable.userId eq UUID.fromString(call.parameters["id"]!!)
        }
        call.respond(mapOf("items" to page.items, "nextPage" to page.nextPageToken))
    }

    // Existence check without SELECT *
    get("/users/exists/{email}") {
        val exists = userRepo.exists { UserTable.email eq call.parameters["email"]!! }
        call.respond(mapOf("exists" to exists))
    }
}
```

---

## Annotations Reference

| Annotation | Target | Description |
|---|---|---|
| `@ScyllaTable(name)` | Class | Maps a class to a CQL table |
| `@PartitionKey(index)` | Property | Partition key field. Use multiple with `index` for composite PKs |
| `@ClusteringKey(order, index)` | Property | Clustering column |
| `@LookupIndex(tableSuffix, consistency)` | Property | Denormalized lookup table |
| `@Column(name)` | Property | Overrides the CQL column name |
| `@Transient` | Property | Exclude from all CQL operations |
| `@Ttl(seconds)` | Class | Default TTL for the primary table |
| `@Counter` | Property | ScyllaDB COUNTER column (all non-key cols must use this) |
| `@CreatedAt` | Property | Auto-set to `Instant.now()` on INSERT (must be `Instant`) |
| `@UpdatedAt` | Property | Auto-set to `Instant.now()` on INSERT and UPDATE |
| `@Version` | Property | Optimistic lock counter (`Long`/`Instant`) — `update()` uses LWT `IF version = ?` |
| `@SoftDelete(ttlSeconds)` | Class | `delete()` sets a TTL on non-key columns instead of issuing `DELETE` |
| `@Sensitive` | Property | Masked as `***` in `KandraEntityLogger` output — never logged in plaintext |
| `@CacheResult(ttlSeconds, maxSize)` | Class | Caffeine-backed `findById` cache, invalidated on save/update/delete |

---

## Composite Partition Keys

```kotlin
@ScyllaTable("transactions_by_user_chain")
data class Transaction(
    @PartitionKey(index = 0) val userId: UUID,   // ┐ together form
    @PartitionKey(index = 1) val chain: String,  // ┘ PRIMARY KEY ((user_id, chain), ...)
    @ClusteringKey(order = ClusteringOrder.DESC, index = 0) val createdAt: Instant,
    val amount: Double
)
```

Generated DDL:
```sql
CREATE TABLE IF NOT EXISTS transactions_by_user_chain (
    user_id UUID,
    chain TEXT,
    created_at TIMESTAMP,
    amount DOUBLE,
    PRIMARY KEY ((user_id, chain), created_at)
) WITH CLUSTERING ORDER BY (created_at DESC);
```

---

## Lookup Tables

A `@LookupIndex` field triggers automatic creation of a denormalized table:

```kotlin
@LookupIndex(tableSuffix = "by_email", consistency = LookupConsistency.BATCH)
val email: String
```

Generates:
```sql
CREATE TABLE IF NOT EXISTS users_by_email (
    email TEXT,
    user_id UUID,
    PRIMARY KEY (email)
);
```

**`LookupConsistency.BATCH`** — included in the same LOGGED batch (atomic).  
**`LookupConsistency.EVENTUAL`** — written asynchronously via `CoroutineScope.launch` after the batch commits.

---

## Schema Modes

```kotlin
install(Kandra) {
    schemaMode = SchemaMode.AUTO_CREATE  // CREATE TABLE IF NOT EXISTS (default)
    schemaMode = SchemaMode.VALIDATE     // throws on missing columns; warns on extras
    schemaMode = SchemaMode.NONE         // skip all DDL — you manage it
}
```

---

## Keyspace Auto-Creation

```kotlin
install(Kandra) {
    autoCreateKeyspace = true
    replicationStrategy = ReplicationStrategy.SimpleStrategy(replicationFactor = 1)
    // or:
    replicationStrategy = ReplicationStrategy.NetworkTopologyStrategy(
        dcReplicationMap = mapOf("us-east" to 3, "eu-west" to 2)
    )
}
```

---

## LWT — saveIfNotExists

```kotlin
val inserted = userRepo.saveIfNotExists(user)
if (!inserted) throw ConflictException("Email already registered")
```

Generates `INSERT ... IF NOT EXISTS`. Lookup table writes only happen when `[applied] == true`. Cannot be batched with other statements (ScyllaDB constraint).

---

## TTL

```kotlin
@ScyllaTable("otp_codes")
@Ttl(300)  // 5 minutes default
data class OtpCode(@PartitionKey val phone: String, val code: String)

// Override per-save:
otpRepo.save(otp, ttlSeconds = 120)  // 2 minutes for this one
```

---

## Pagination

```kotlin
val page1 = txnRepo.findPage(pageSize = 20) {
    TransactionTable.userId eq userId
}
// page1.nextPageToken is a base64 paging state

val page2 = txnRepo.findPage(pageSize = 20, pageToken = page1.nextPageToken) {
    TransactionTable.userId eq userId
}
```

---

## LIMIT and querying a non-key column

Kandra does not support `ALLOW FILTERING` at all — there is no DSL escape hatch for it, by design (see
[Secondary Index](#secondary-index-030) below). Querying a column that isn't a partition/clustering key
requires `@SecondaryIndex` on it.

```kotlin
// 5 most recent transactions
val recent = txnRepo.findAll(limit = 5) {
    TransactionTable.userId eq userId
}

// Requires @SecondaryIndex on `status` (see below) — logs a WARN when used, since it's a
// scatter-gather query across all nodes, not a partition-scoped one
val flagged = userRepo.findAll {
    UserTable.status eq AccountStatus.FLAGGED
}
```

---

## Counter Tables

```kotlin
statsRepo.increment(ChainStats::totalTransactions, mapOf("chain" to "BASE"), by = 1L)
statsRepo.increment(ChainStats::totalVolumeUsd, mapOf("chain" to "BASE"), by = amountUsd)
statsRepo.decrement(ChainStats::totalTransactions, mapOf("chain" to "BASE"))
```

Counter tables cannot use `save()` — call `increment()`/`decrement()` only.

---

## Collection Mutations

Atomic — no read-modify-write race condition:

```kotlin
// Add roles to user (SET col = col + ?)
userRepo.append(user, User::roles, setOf("vip", "early_adopter"))

// Remove a tag (SET col = col - ?)
userRepo.remove(user, User::tags, setOf("inactive"))

// Update wallet metadata (MAP update)
walletRepo.put(wallet, Wallet::metadata, mapOf("kyc_level" to "1"))
```

---

## Custom Codec

```kotlin
install(Kandra) {
    codec.registerEncoder(WalletAddress::class) { it.value }
    codec.registerDecoder(WalletAddress::class) { row, col -> WalletAddress(row.getString(col)!!) }
}
```

`BigDecimal` is supported natively → CQL `DECIMAL`.

---

## deleteById / deleteBy

```kotlin
userRepo.deleteById(userId)
userRepo.deleteBy { UserTable.email eq "pasaka@coinx.io" }
// deleteBy resolves via lookup, fetches full entity, then cleans all lookup tables
```

---

## saveAll

```kotlin
walletRepo.saveAll(listOf(wallet1, wallet2, wallet3))
// Emits a single LOGGED batch for all primary + BATCH lookup inserts
```

---

## Type-Safe Queries (kandra-codegen)

KSP generates a `*Table` object for each entity:

```kotlin
// Generated: UserTable.kt
object UserTable : KandraTable<User> {
    val userId = KandraColumnRef<UUID>("user_id")
    val email  = KandraColumnRef<String>("email", isLookup = true)
    val name   = KandraColumnRef<String>("full_name")
}
```

Use in queries:
```kotlin
val user = userRepo.find { UserTable.email eq "alice@coinx.io" }
val users = userRepo.findAll { UserTable.userId isIn listOf(id1, id2) }
```

---

## Testing

```kotlin
@AfterEach fun cleanup() = SchemaRegistry.clear()

@Test
fun `save captures batch statement`() {
    val runtime = KandraTestUtils.inMemory(User::class)
    val fakeSession = runtime.session as FakeKandraSession
    val repo = runtime.repository(User::class)

    repo.save(User(UUID.randomUUID(), "alice@coinx.io", "+1234567890", "Alice"))

    assert(fakeSession.capturedBatches().isNotEmpty())
}
```

---

## Kotlin → CQL Type Mapping

| Kotlin | CQL |
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
| `Map<K,V>` | `MAP<K,V>` |
| Any `Enum` subclass | `TEXT` (stored as name) |
| Custom via codec | Any |

---

## Running Tests

```bash
# Unit tests (no database required)
JAVA_HOME=<jdk-21-path> ./gradlew :kandra-core:test

# All modules (ktor integration tests require a ScyllaDB instance)
JAVA_HOME=<jdk-21-path> ./gradlew test -x :kandra-ktor:test

# Full build
JAVA_HOME=<jdk-21-path> ./gradlew build
```

> **Note:** Run with Java 21. The default Java 25 breaks the Kotlin compiler embedded in Gradle 8.11 (version string parse bug).

---

## Authentication (0.3.0)

`KandraAuth` replaces the old `username`/`password` string fields with a pluggable provider:

```kotlin
// From environment variables (recommended for production)
KandraAuth.fromEnv()                                  // reads SCYLLA_USERNAME, SCYLLA_PASSWORD
KandraAuth.fromEnv("DB_USER", "DB_PASS")             // custom var names

// From a file (Kubernetes secrets, Docker secrets)
KandraAuth.fromFile("/run/secrets/scylla-credentials") // JSON {"username":"…","password":"…"}

// Hardcoded — for local dev only
KandraAuth.static("cassandra", "cassandra")

// Custom — Vault, AWS Secrets Manager, etc.
KandraAuth.custom { KandraCredentials(vault.read("username"), vault.read("password")) }
```

**Credential rotation** — set `refreshIntervalSeconds` and Kandra re-fetches credentials in the background without restarting the session. The new credentials apply to the next new connection:

```kotlin
auth {
    provider = KandraAuth.custom { secretsClient.getCassandraCredentials() }
    refreshIntervalSeconds = 3600
}
```

The `onCredentialRefreshed()` / `onAuthFailed()` callbacks on `KandraEventListener` let you observe rotation events.

---

## SSL / TLS (0.3.0)

One-way TLS (server authentication only):

```kotlin
install(Kandra) {
    ssl {
        enabled = true
        trustStorePath = "/etc/ssl/scylla-truststore.jks"
        trustStorePassword = System.getenv("TRUST_STORE_PASSWORD")
        hostnameVerification = true
    }
}
```

Mutual TLS (mTLS — client certificate required):

```kotlin
ssl {
    enabled = true
    trustStorePath = "/etc/ssl/scylla-truststore.jks"
    trustStorePassword = System.getenv("TRUST_STORE_PASSWORD")
    keyStorePath = "/etc/ssl/client-keystore.p12"
    keyStorePassword = System.getenv("KEY_STORE_PASSWORD")
    keyStoreType = "PKCS12"
}
```

SSL handshake failures are wrapped as `KandraAuthException`. Keyspace permission validation runs at startup — disable with `validatePermissions = false` in restricted environments.

---

## Multi-Datacenter (0.3.0)

Add `kandra-multidc` and configure in the plugin:

```kotlin
install(Kandra) {
    localDatacenter = "us-east"

    loadBalancing {
        tokenAware = true
        dcAwareFailover = true
        allowedRemoteDcs = listOf("eu-west")
    }

    failover {
        onLocalDcUnavailable = FailoverPolicy.RETRY_REMOTE_DC  // default: THROW
    }

    speculativeExecution {
        enabled = true
        delayMillis = 100   // fire speculative request after 100ms
        maxAttempts = 2
    }
}
```

**Consistency level guide:**

| Scenario | `defaultRead` | `defaultWrite` |
|---|---|---|
| Single DC (default) | `LOCAL_ONE` | `LOCAL_QUORUM` |
| Multi-DC active-active | `LOCAL_QUORUM` | `EACH_QUORUM` |
| Strong global | `QUORUM` | `QUORUM` |

Override at the class level with annotations:

```kotlin
@ReadConsistency(KandraConsistency.LOCAL_QUORUM)
@WriteConsistency(KandraConsistency.EACH_QUORUM)
@ScyllaTable("critical_balances")
data class Balance(...)
```

Or per-call:

```kotlin
repo.save(entity, consistency = KandraConsistency.EACH_QUORUM)
repo.findById(id, consistency = KandraConsistency.QUORUM)
```

Resolution order: **per-call override → class annotation → `ConsistencyConfig` default**

---

## Caller-Controlled Batches (0.3.0)

`KandraBatchScope` lets you group saves and deletes from different repositories into a single LOGGED BATCH:

```kotlin
@OptIn(ExperimentalKandraApi::class)
application.kandra.batch {
    userRepo.saveInBatch(user)
    walletRepo.saveInBatch(wallet)
    auditRepo.saveInBatch(auditEntry)
}
// ↑ all three execute as one atomic LOGGED BATCH
```

`saveInBatch`/`deleteInBatch` (not `save`/`delete`) are deliberate: Kotlin always resolves a
same-named repository member over an extension, even a member-extension of the batch scope
itself, so a same-named batch method would silently never be reachable.

Limitations: reads are not available inside a batch; `saveIfNotExists` throws (LWT cannot be mixed with regular statements in a batch).

---

## USING TIMESTAMP (0.3.0)

Idempotent event replay — set an explicit write timestamp to avoid duplicate data:

```kotlin
import io.kandra.core.KandraTimestamp

// Replay from a Kafka event
repo.save(entity, timestampMicros = KandraTimestamp.fromInstant(event.occurredAt))

// Write-time "now"
repo.save(entity, timestampMicros = KandraTimestamp.now())
```

TTL and TIMESTAMP can be combined:

```kotlin
repo.save(entity, ttlSeconds = 300, timestampMicros = KandraTimestamp.fromInstant(event.at))
// → INSERT ... USING TTL 300 AND TIMESTAMP 1719273600000000
```

---

## IN Queries on Partition Keys (0.3.0)

Scatter-gather reads for multiple partition keys:

```kotlin
val users = userRepo.findAll {
    UserTable.userId isIn listOf(id1, id2, id3)
}
// ↑ generates: SELECT * FROM users WHERE user_id IN ?
// ↑ empty list → returns emptyList() immediately (no query)
// ↑ DEBUG log: "IN query on partition key users.user_id"
```

Only works for single-column partition keys. Composite partition key tables throw `KandraQueryException`.

---

## Secondary Index (0.3.0)

For columns that need ad-hoc queries without a lookup table:

```kotlin
@ScyllaTable("products")
data class Product(
    @PartitionKey val productId: UUID,
    @SecondaryIndex val category: String,  // → CREATE INDEX IF NOT EXISTS products_category_idx ON products (category)
    val name: String
)

// Query by secondary index
val electronics = productRepo.findAll { ProductTable.category eq "electronics" }
// ↑ WARN: "Secondary index query on products.category — scatter-gather across all nodes"
```

Use `@LookupIndex` instead for high-cardinality columns — it creates a proper denormalized table.

---

## Test Keyspace Isolation (0.3.0)

`KandraTestcontainers` provides per-test isolated Cassandra keyspaces via Testcontainers:

```kotlin
// Add to build.gradle.kts:
// testImplementation("ke.co.coinx.kandra:kandra-test")
// testImplementation("org.testcontainers:cassandra:1.19.8")

class UserRepositoryTest {
    private lateinit var db: KandraRuntimeHandle

    @BeforeEach
    fun setup() {
        db = KandraTestcontainers.freshKeyspace(User::class)
    }

    @AfterEach
    fun cleanup() = db.close()  // drops the keyspace

    @Test
    fun `save and find user`() {
        val repo = db.runtime.repository<User>()
        val user = User(UUID.randomUUID(), "alice@example.com", "Alice")
        repo.save(user)
        assertEquals(user, repo.findById(user.userId))
    }
}
```

Each test class gets an isolated `kandra_test_{uuid}` keyspace. The shared `CassandraContainer` starts once per JVM (lazy singleton).

---

## Optimistic Locking (0.4.2)

```kotlin
@ScyllaTable("balances")
data class Balance(
    @PartitionKey val userId: UUID,
    val amountUsd: BigDecimal,
    @Version val version: Long = 1L
)

balanceRepo.save(balance)              // version starts at 1
val loaded = balanceRepo.findById(userId)!!
balanceRepo.update(loaded, loaded.copy(amountUsd = newAmount))
// ↑ UPDATE ... IF version = ? — throws KandraOptimisticLockException on conflict

balanceRepo.updateForce(loaded.copy(amountUsd = newAmount))  // bypasses the version check entirely
```

`@Version` also accepts `Instant` fields. `save()` always sets the initial version; only `update()` enforces the LWT check.

---

## UNSET vs NULL (0.4.2)

Kandra distinguishes "field not provided" from "field explicitly cleared" to avoid accidental tombstones:

```kotlin
userRepo.save(user.copy(middleName = null))
// ↑ null nullable fields encode to UNSET → stmt.unset(idx) → no tombstone written

userRepo.saveWithNulls(user.copy(middleName = null))
// ↑ writes an explicit NULL — use only when you intend to tombstone the column
```

---

## Tombstone-Aware Soft Delete (0.4.2)

```kotlin
@ScyllaTable("sessions", gcGraceSeconds = 3600)
@SoftDelete(ttlSeconds = 86_400)
data class Session(@PartitionKey val sessionId: UUID, val userId: UUID)

sessionRepo.delete(session)
// ↑ UPDATE ... USING TTL 86400 — sets a TTL on non-key columns instead of DELETE
```

`gcGraceSeconds` on `@ScyllaTable` controls how long the tombstoned row's primary key survives before ScyllaDB's compaction reclaims it. For a `findActive()` that filters out soft-deleted rows, add `@SoftDelete(ttlSeconds = ..., markerProperty = "isDeleted")` with a `Boolean` field — see [`docs/features/repositories.md`](docs/features/repositories.md#findactive-soft-delete-entities-only) and [ISS-007](docs/issues/ISS-007-find-active-soft-delete.md).

---

## Batch Size Guard (0.4.2)

```kotlin
install(Kandra) {
    batchWarnThresholdKb = 50     // WARN log when a batch approaches ScyllaDB's limit (default: 5)
    batchMaxChunkSize = 100       // max statements per LOGGED BATCH chunk (default: 100)
    batchAutoChunk = true         // saveAll() silently splits oversized batches (default: true)
}

walletRepo.saveAll(largeListOfWallets)
// ↑ auto-chunked into multiple LOGGED BATCHes if batchAutoChunk = true
```

---

## Sensitive Field Masking (0.4.2)

```kotlin
data class User(
    @PartitionKey val userId: UUID,
    val email: String,
    @Sensitive val ssn: String,
    @Sensitive val apiKey: String
)
```

`KandraEntityLogger` masks `@Sensitive` fields as `***` in any log output — DEBUG query logs, error messages, and `toString()` calls never leak these values in plaintext.

---

## Validation Hook (0.4.2)

```kotlin
install(Kandra) {
    validate<User> { user ->
        buildList {
            if (!user.email.contains("@")) add(KandraValidationError("email", "must be a valid address"))
            if (user.age < 18) add(KandraValidationError("age", "must be an adult"))
        }
    }
}
```

Runs before every `save()`/`update()`. A non-empty list throws `KandraValidationException` with the collected `KandraValidationError`s — nothing is written to ScyllaDB.

---

## AUTO_MIGRATE Schema Mode (0.4.2)

```kotlin
install(Kandra) {
    schemaMode = SchemaMode.AUTO_MIGRATE
    // ↑ CREATE TABLE IF NOT EXISTS, then diffs against system_schema.columns
    // ↑ ALTER TABLE ADD for any new columns found on the entity
    // ↑ logs WARN for Scylla columns with no matching entity field
}
```

Safer than `AUTO_CREATE` for evolving schemas in place — it never drops or renames columns, only adds.

---

## Versioned Schema Migrations (`kandra-migrate`, 0.4.2)

For changes `AUTO_MIGRATE` can't express (renames, data backfills, index changes):

```kotlin
object V2_AddPhoneIndex : KandraMigration(version = 2, name = "add phone secondary index") {
    override fun up(session: CqlSession) {
        session.execute("CREATE INDEX IF NOT EXISTS users_phone_idx ON users (phone)")
    }
}

val runner = KandraMigrationRunner(session)
runner.run(V2_AddPhoneIndex)
// ↑ applies pending migrations in version order, records checksums in a kandra_migrations table
// ↑ throws KandraMigrationException if an already-applied migration's checksum no longer matches
```

Call this **before** `install(Kandra)` with `schemaMode = SchemaMode.NONE` for migration-managed schemas.

---

## Micrometer Metrics (0.4.2)

```kotlin
install(Kandra) {
    metrics {
        enabled = true
        recorder = KandraMetrics { table, operation, durationMs ->
            meterRegistry.timer("kandra.query", "table", table, "operation", operation)
                .record(durationMs, TimeUnit.MILLISECONDS)
        }
    }
}
```

`recorder` fires after every query (blocking and suspend paths) with the table name, operation (`save`, `update`, `delete`, `saveAll`, `deleteAll`, `batch`), and duration — bridge it into Micrometer, Dropwizard, or any metrics backend you use.

---

## Health Check & Graceful Shutdown (0.4.2)

```kotlin
install(Kandra) {
    healthCheck = true   // exposes GET /kandra/health → {"status": "UP"} or {"status": "DOWN"}

    shutdown {
        graceful = true
        drainTimeoutMs = 5_000   // wait for in-flight queries before closing the session
    }
}
```

On `ApplicationStopping`, Kandra sets `KandraRuntime.isShuttingDown = true` and drains `inFlightCount` down to zero (or the timeout) before closing the driver session.

---

## CQL Injection Guard (0.4.2)

```kotlin
userRepo.raw("SELECT * FROM users WHERE email = '$untrustedInput'")
// ↑ WARN logged: raw CQL with string literals and no bound parameters

val query = KandraRawQuery.cql("SELECT * FROM users WHERE email = ?").bind(untrustedInput).build()
userRepo.rawQuery(query)
// ↑ safe — always parameterised
```

---

## Query Caching (0.4.2)

```kotlin
@ScyllaTable("products")
@CacheResult(ttlSeconds = 60, maxSize = 10_000)
data class Product(@PartitionKey val productId: UUID, val name: String, val priceUsd: BigDecimal)

productRepo.findById(id)   // first call hits ScyllaDB, populates the Caffeine cache
productRepo.findById(id)   // subsequent calls within ttlSeconds are served from cache
productRepo.save(updated)  // save/update/delete invalidate the cached entry
```

`caffeine` is a `compileOnly` dependency — caching is disabled gracefully if it's not on the classpath.
