# 2. Sample Application Build Plan

Step-by-step construction of one Ktor application that deliberately exercises every Kandra
annotation, every config knob, both DI integrations, migrations, and Jakarta validation — against the
real cluster from file 1. Follow the steps in order; each step should compile and (where noted) run
successfully before moving to the next. Don't batch all the code in at once — if step 4 breaks, you
want to know it's step 4, not "somewhere in 400 lines I just wrote."

## Step 1 — Project scaffold

Already covered in [file 1, section 1.4](01-prerequisites-and-environment.md#14-ktor-server-template).
Confirm `./gradlew build` succeeds with just a bare `Application.kt` before continuing.

## Step 2 — Entity definitions

Create these in `src/main/kotlin/com/example/kandratest/model/`. Each is chosen to force a specific,
sometimes deliberately awkward, combination of annotations — read the inline comments, they explain
*why* each combination was chosen, not just what it does.

### 2.1 `User` — the kitchen-sink entity

Exercises: composite-free single partition key, a clustering key that is deliberately a *different*
`Instant` field from `@CreatedAt` (a realistic but easy-to-confuse combination — time-bucketing key
vs. audit timestamp), two `@LookupIndex` columns at different consistency levels, a renamed column,
a masked column, a native secondary index, collection columns (`Set`/`Map`), a `BigDecimal` column, a
`@Version` column, class-level read/write consistency overrides, a `@CacheResult`, and Jakarta Bean
Validation constraints stacked on top of Kandra annotations on the same properties.

```kotlin
package com.example.kandratest.model

import io.kandra.core.KandraConsistency
import io.kandra.core.annotations.*
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class UserStatus { ACTIVE, SUSPENDED, DELETED }

@ScyllaTable(name = "users", gcGraceSeconds = 864000)
@ReadConsistency(KandraConsistency.LOCAL_QUORUM)
@WriteConsistency(KandraConsistency.LOCAL_QUORUM)
@CacheResult(ttlSeconds = 30, maxSize = 500)
data class User(
    @PartitionKey val userId: UUID,
    @ClusteringKey(order = ClusteringOrder.DESC) val bucketedAt: Instant,   // clustering key — NOT the @CreatedAt field below, deliberately
    @field:Email
    @LookupIndex(tableSuffix = "by_email", consistency = LookupConsistency.BATCH)
    val email: String,
    @LookupIndex(tableSuffix = "by_phone", consistency = LookupConsistency.EVENTUAL)
    val phone: String,
    @field:NotBlank
    @Column(name = "display_name")
    val displayName: String,
    @field:Size(min = 8)
    @Sensitive
    val passwordHash: String,
    @SecondaryIndex val status: UserStatus,
    val tags: Set<String> = emptySet(),
    val metadata: Map<String, String> = emptyMap(),
    val balance: BigDecimal = BigDecimal.ZERO,
    @Version val version: Long = 0L,
    @CreatedAt val createdAt: Instant = Instant.EPOCH,
    @UpdatedAt val updatedAt: Instant = Instant.EPOCH,
    @Transient val sessionToken: String? = null
)
```

### 2.2 `Wallet` — soft delete + `findActive()`

Deliberately short `ttlSeconds` (10s, not the 86400s default) so a test run can actually observe the
TTL expiring within a normal test session rather than waiting a full day.

```kotlin
@ScyllaTable(name = "wallets")
@SoftDelete(ttlSeconds = 10, markerProperty = "isDeleted")
data class Wallet(
    @PartitionKey val walletId: UUID,
    val ownerId: UUID,
    val balanceCents: Long = 0L,
    val isDeleted: Boolean = false
)
```

### 2.3 `OtpCode` — class-level default TTL (`@Ttl`, distinct from `@SoftDelete`)

```kotlin
@ScyllaTable(name = "otp_codes")
@Ttl(seconds = 300)
data class OtpCode(
    @PartitionKey val phone: String,
    val code: String,
    val issuedAt: Instant = Instant.now()
)
```

### 2.4 `PageViewCounter` — counter table

Every non-key column must be `@Counter` — see the deliberately-broken counterexample in
[file 4](04-edge-cases-and-adversarial-tests.md) for what happens if you get this wrong.

```kotlin
@ScyllaTable(name = "page_view_counters")
data class PageViewCounter(
    @PartitionKey val pageId: UUID,
    @Counter val views: Long = 0L,
    @Counter val uniqueVisitors: Long = 0L
)
```

### 2.5 `AuditLog` — composite partition key + clustering key

```kotlin
@ScyllaTable(name = "audit_logs")
data class AuditLog(
    @PartitionKey(index = 0) val tenantId: UUID,
    @PartitionKey(index = 1) val entityType: String,
    @ClusteringKey(order = ClusteringOrder.DESC, index = 0) val occurredAt: Instant,
    val actorId: UUID,
    val action: String
)
```

### 2.6 `Invoice` — migration-managed (not registered with `AUTO_CREATE`)

Deliberately started with two columns; a migration in step 6 adds a third. Do **not** pass this class
to `register(...)` in the main `install(Kandra) { }` block — it's demonstrated separately with
`schemaMode = SchemaMode.NONE`, per the ordering rule in the `kandra-migrate` docs.

```kotlin
@ScyllaTable(name = "invoices")
data class Invoice(
    @PartitionKey val invoiceId: UUID,
    val amount: BigDecimal,
    val issuedAt: Instant,
    val status: String? = null   // added by V2 migration in step 6 — null until that migration runs
)
```

## Step 3 — Install the plugin with every config knob populated

Populate every block at least once — the point is to exercise config surface, not to produce a
"sensible" production config. Capture the built `KandraConfig` for `KandraMultiDc.describe()` at the
end (the `install(Kandra) { ... }` lambda's receiver **is** the `KandraConfig` instance; assign
`this` to an outer `var` on the last line of the block to keep a reference to it).

```kotlin
package com.example.kandratest

import com.example.kandratest.model.*
import io.kandra.core.KandraConsistency
import io.kandra.core.KandraEventListener
import io.kandra.core.KandraMetrics
import io.kandra.core.KandraValidationError
import io.kandra.core.KandraAuth
import io.kandra.jakarta.validateJakarta
import io.kandra.ktor.*
import io.kandra.kodein.kandraKodein
import io.kandra.koin.kandraKoin
import io.kandra.multidc.KandraMultiDc
import io.ktor.server.application.*
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.concurrent.TimeUnit

lateinit var kandraConfigRef: KandraConfig
val meterRegistry = SimpleMeterRegistry()

fun Application.configureKandra() {
    install(Kandra) {
        contactPoints = System.getenv("SCYLLA_CONTACT_POINTS") ?: "localhost:9042"
        keyspace = System.getenv("SCYLLA_KEYSPACE") ?: "kandra_sample"
        localDatacenter = System.getenv("SCYLLA_LOCAL_DC") ?: "datacenter1"
        autoCreateKeyspace = true
        replicationStrategy = ReplicationStrategy.SimpleStrategy(replicationFactor = 3)
        schemaMode = SchemaMode.AUTO_CREATE
        validatePermissions = true
        preparedStatementCacheSize = 1000
        tombstoneWarnThreshold = 500
        batchWarnThresholdKb = 5
        batchMaxChunkSize = 50      // deliberately low — forces saveAll(200) in file 3 to auto-chunk
        batchAutoChunk = true
        healthCheck = true

        register(User::class, Wallet::class, OtpCode::class, PageViewCounter::class, AuditLog::class)

        pool {
            requestTimeoutMillis = 10_000
            connectionTimeoutMillis = 8_000
            maxRequestsPerConnection = 32768
            heartbeatIntervalSeconds = 30
        }

        auth {
            // Only meaningful against the auth-enabled cluster variant (1.2.2). Against the plain
            // cluster in 1.2.1, leave SCYLLA_USERNAME/SCYLLA_PASSWORD unset — fromEnv() will throw
            // KandraAuthException at session-build time if the env vars are missing AND auth is
            // actually required; confirm which behavior you actually observe (see file 4).
            provider = KandraAuth.fromEnv()
            refreshIntervalSeconds = 3600
        }

        retry {
            maxAttempts = 3
            backoffMillis = 100
            maxBackoffMillis = 2000
        }

        debug {
            logQueries = true       // CQL templates only — confirm bound values never appear in logs
            logSlowQueriesMs = 200
            logBatches = true
        }

        consistency {
            defaultRead = KandraConsistency.LOCAL_QUORUM
            defaultWrite = KandraConsistency.LOCAL_QUORUM
            defaultSerialConsistency = KandraConsistency.LOCAL_SERIAL
        }

        loadBalancing {
            tokenAware = true
            dcAwareFailover = false   // flip only against the two-DC cluster, 1.2.3
        }

        failover {
            onLocalDcUnavailable = FailoverPolicy.THROW
        }

        speculativeExecution {
            enabled = true
            delayMillis = 100
            maxAttempts = 2
        }

        shutdown {
            graceful = true
            drainTimeoutMs = 5000
        }

        metrics {
            enabled = true
            recorder = KandraMetrics { table, op, ms ->
                meterRegistry.timer("kandra.query", "table", table, "operation", op)
                    .record(ms, TimeUnit.MILLISECONDS)
            }
        }

        // NOTE — deliberate footgun, see file 4/6: validate<T> and validateJakarta<T> for the SAME
        // class overwrite each other (last registration wins — it's a Map, not a list). Only one is
        // active here. Test the overwrite behavior explicitly and separately in file 4.
        validateJakarta<User>()

        eventListener = object : KandraEventListener {
            override fun onEventualWriteFailed(tableName: String, entity: Any, error: Throwable) {
                log.error("Eventual write failed on $tableName: $entity", error)
            }
            override fun onAuthFailed(contactPoint: String, error: Throwable) {
                log.error("Auth failed on $contactPoint", error)
            }
            override fun onConnectionEstablished(contactPoint: String) {
                log.info("Kandra connected: $contactPoint")
            }
            override fun onCredentialRefreshed() {
                log.info("Kandra credentials refreshed")
            }
            override fun onSslHandshakeFailed(contactPoint: String, error: Throwable) {
                log.error("SSL handshake failed on $contactPoint", error)
            }
        }

        kandraConfigRef = this
    }

    log.info(KandraMultiDc.describe(kandraConfigRef))

    kandraKoin()
    kandraKodein()
}
```

Wire `install(Koin) { }` (from `org.koin.ktor.plugin.Koin`) **before** `configureKandra()` runs, or
`kandraKoin()`'s internal `getKoin()` call throws — see the `kandra-koin` skill's call-order note.
`kandraKodein()` does not require a separate explicit Kodein install call first (it opens its own
`di { }` block), but both DI calls must come **after** `install(Kandra)` regardless, since both read
`Application.kandraSession`/`Application.kandra` attributes that only exist post-install.

## Step 4 — Routes covering every repository method

One route (minimum) per method, on both the blocking (`KandraRepository`) and suspend
(`KandraSuspendRepository`) variants where practical — prefer the suspend repository for the actual
handler bodies (Ktor route handlers are suspend functions already), but add at least one route that
deliberately uses the **blocking** repository from inside a route handler to observe whether it
measurably stalls the request-handling dispatcher under load (see file 4's blocking-repo-in-a-route
edge case).

Build this as one file per entity under `src/main/kotlin/com/example/kandratest/routes/`. Minimum
route set — table below list the exact method under test per route so nothing is skipped silently:

| Route | Method(s) exercised |
|---|---|
| `POST /users` | `save` |
| `POST /users/if-not-exists` | `saveIfNotExists` |
| `POST /users/with-nulls` | `saveWithNulls` |
| `POST /users/bulk` (body: 200 users) | `saveAll` — forces auto-chunking at `batchMaxChunkSize = 50` |
| `PUT /users/{id}` | `update(old, new)` — handler must load `old` via `findById` first, then apply changes and call `update` |
| `PUT /users/{id}/force` | `updateForce` |
| `DELETE /users/{id}` | `delete(entity)` — load then delete |
| `DELETE /users/bulk` | `deleteAll` |
| `DELETE /users/by-id/{id}` | `deleteById` |
| `DELETE /users/by-email/{email}` | `deleteBy { }` via the lookup predicate |
| `GET /users/{id}` | `findById` |
| `GET /users/by-email/{email}` | `find { }` via `@LookupIndex` |
| `GET /users?status=ACTIVE` | `findAll { }` via `@SecondaryIndex` predicate |
| `GET /users/page?size=20&token=...` | `findPage` |
| `GET /users/exists?email=...` | `exists { }` |
| `GET /users/raw?status=...` | `raw(cql, params)` |
| `GET /users/raw-query?status=...` | `rawQuery(KandraRawQuery...)` |
| `POST /users/{id}/tags` | `append` on the `tags: Set<String>` field |
| `DELETE /users/{id}/tags` | `remove` |
| `PUT /users/{id}/metadata` | `put` on the `metadata: Map<String,String>` field |
| `POST /wallets` | `save` (Wallet) |
| `DELETE /wallets/{id}` | `delete` — triggers soft delete |
| `GET /wallets/active` | `findActive()` |
| `POST /page-views/{pageId}/increment` | `increment` |
| `POST /page-views/{pageId}/decrement` | `decrement` |
| `GET /health` | Kandra's own `/kandra/health` (registered automatically — just confirm it responds) |

For every route, log (or return in a debug response field) the entity's `toString()` and confirm
`@Sensitive` fields render as `***` per `KandraEntityLogger` — this is the easiest way to catch a
masking regression without instrumenting the logger directly.

## Step 5 — Migrations (separate run mode)

`kandra-migrate` must run **before** `install(Kandra)`, paired with `schemaMode = SchemaMode.NONE` for
the migration-managed table — running it after `AUTO_CREATE`/`AUTO_MIGRATE` risks the plugin's own
DDL racing the runner's. Because the main app uses `AUTO_CREATE` for its other five entities, build
this as a **separate alternate entry point** (e.g. gated by an env var `KANDRA_RUN_MIGRATIONS=true`
checked before `configureKandra()`, or a wholly separate `main()` — either is fine, just don't let it
silently share a schema mode with the rest of the app).

```kotlin
import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.migrate.KandraMigration
import io.kandra.migrate.KandraMigrationRunner

object V1_CreateInvoices : KandraMigration(version = 1, name = "create invoices table") {
    override fun up(session: CqlSession) {
        session.execute("""
            CREATE TABLE IF NOT EXISTS invoices (
                invoice_id UUID PRIMARY KEY,
                amount DECIMAL,
                issued_at TIMESTAMP
            )
        """.trimIndent())
    }
}

object V2_AddInvoiceStatusColumn : KandraMigration(version = 2, name = "add status column to invoices") {
    override fun up(session: CqlSession) {
        session.execute("ALTER TABLE invoices ADD status TEXT")
    }
}

fun runInvoiceMigrations(session: CqlSession) {
    KandraMigrationRunner(session).run(V1_CreateInvoices, V2_AddInvoiceStatusColumn)
}
```

Run this **twice** in sequence against the same keyspace (simulating a redeploy) and confirm the
second run logs both migrations as already-applied/skipped rather than re-running `up()` — this is
the most basic correctness property of the whole module and costs nothing extra to check.

## Step 6 — What "done" for this file looks like

- [ ] All six entities in step 2 register successfully at startup (no `KandraSchemaException`).
- [ ] `install(Kandra)` completes with every config block populated, and the multi-DC description
      logs all 8 lines described in the `kandra-multidc` docs.
- [ ] Both `kandraKoin()` and `kandraKodein()` resolve a `User` repository via their respective
      lookup conventions (`named("UserRepo")` for Koin, tag `"User"` for Kodein) without a cast
      exception.
- [ ] Every route in the step 4 table returns a 2xx/expected response at least once against the real
      cluster.
- [ ] The migration run in step 5 is idempotent on a second invocation.
- [ ] `GET /kandra/health` returns `200 {"status":"UP"}` while the cluster is up.

Proceed to [file 3](03-functional-coverage-matrix.md) to turn "it ran once" into a real coverage
checklist, then [file 4](04-edge-cases-and-adversarial-tests.md) for the adversarial pass.
