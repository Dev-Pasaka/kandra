# End-to-End Example: Tables → Production Routes

A complete walkthrough — from `@ScyllaTable` entities to production Ktor route handlers — using the
patterns recommended elsewhere in this repo: typed `kandra-codegen` DI accessors over hand-typed
Koin/Kodein qualifiers, one repository singleton per entity (not built per-request), and full plugin
config (auth, retry, consistency, health, graceful shutdown) wired once at startup.

Every signature below is verified against current source (`kandra-core`/`kandra-runtime`/`kandra-ktor`
as of 0.4.7-in-progress) — if something here looks off after a future change, that's drift to fix, not
intentional simplification.

## 1. Define your entities

```kotlin
import io.kandra.core.annotations.*
import java.time.Instant
import java.util.UUID

@ScyllaTable(tableName = "users", gcGraceSeconds = 864000)
@SoftDelete(ttlSeconds = 2_592_000, markerProperty = "isDeleted")   // 30 days
@CacheResult(ttlSeconds = 60, maxSize = 10_000)                      // findById cache
data class User(
    @PartitionKey(index = 0)
    val userId: UUID,

    @LookupIndex(tableSuffix = "by_email", consistency = LookupConsistency.BATCH)
    val email: String,

    @LookupIndex(tableSuffix = "by_phone", consistency = LookupConsistency.EVENTUAL)
    val phone: String,

    @Column(name = "full_name")
    val name: String,

    @Version
    val version: Long = 1L,

    @SecondaryIndex
    val isDeleted: Boolean = false,   // findActive() marker column — indexed, no ALLOW FILTERING

    @CreatedAt val createdAt: Instant = Instant.EPOCH,
    @UpdatedAt val updatedAt: Instant = Instant.EPOCH
)

@ScyllaTable(tableName = "transactions_by_user")
data class Transaction(
    @PartitionKey(index = 0) val userId: UUID,
    @ClusteringKey(order = ClusteringOrder.DESC, index = 0) val createdAt: Instant,
    val amount: Double,
    val status: String
)
```

**Why these annotations:** `@Version` gives optimistic locking on `update()` (a single-shot LWT, no
auto-retry — see [ISS-032](issues/ISS-032-versioned-update-spurious-optimistic-lock.md) for why).
`@SoftDelete` + `@SecondaryIndex` on the marker column lets `findActive()` query the index directly
instead of requiring `allowFullScan = true` (see [ISS-036](issues/ISS-036-findactive-allow-filtering-scope.md)).
`@LookupIndex` on `email`/`phone` gives you email/phone-keyed lookups without a secondary table you
maintain by hand.

## 2. Install the plugin

```kotlin
import io.kandra.ktor.*
import io.kandra.core.KandraAuth
import io.kandra.core.KandraEventListener

fun Application.configureDatabase() {
    install(Kandra) {
        contactPoints   = System.getenv("SCYLLA_CONTACT_POINTS") ?: "scylla:9042"
        keyspace        = "myapp"
        localDatacenter = "dc-us-east"
        autoCreateKeyspace = false          // production: schema managed separately
        schemaMode      = SchemaMode.VALIDATE
        register(User::class, Transaction::class)

        auth {
            provider = KandraAuth.fromEnv()   // SCYLLA_USERNAME / SCYLLA_PASSWORD
            refreshIntervalSeconds = 3600
        }

        pool {
            requestTimeoutMillis = 10_000
            maxRequestsPerConnection = 32768
        }

        retry {
            maxAttempts = 3
            backoffMillis = 100
        }

        consistency {
            strictMode = true   // WARN if a query resolves to LOCAL_ONE/ONE in a multi-DC deployment
        }

        loadBalancing {
            allowedRemoteDcs = listOf("dc-eu-west")   // multi-DC — this is what makes strictMode fire
        }

        debug {
            logQueries = false
            logSlowQueriesMs = 500
        }

        healthCheck = true   // GET /kandra/health

        shutdown {
            graceful = true
            drainTimeoutMs = 5000
        }

        batch {
            warnThresholdKb = 5
            maxChunkSize = 100
            autoChunk = true
        }

        eventListener = object : KandraEventListener {
            override fun onEventualWriteFailed(tableName: String, entity: Any, error: Throwable) {
                log.error("EVENTUAL write failed on $tableName", error)
            }
        }
    }

    kandraKoin()   // binds one KandraRepository/KandraSuspendRepository pair per registered entity
}
```

## 3. Wire DI — prefer the generated typed accessors

With `kandra-codegen` + `koin-core` both on the classpath, KSP generates one accessor pair per entity:

```kotlin
// generated: UserKoinDi.kt, TransactionKoinDi.kt — nothing to write by hand
fun KoinComponent.userRepo(): KandraRepository<User>
fun KoinComponent.userSuspendRepo(): KandraSuspendRepository<User>
fun KoinComponent.transactionRepo(): KandraRepository<Transaction>
fun KoinComponent.transactionSuspendRepo(): KandraSuspendRepository<Transaction>
```

These wrap `kandraKoin()`'s `named("UserSuspendRepo")` lookup internally — you never see or type the
qualifier string, and the return type is already correct, no cast. Why not hand-write the qualifier or
call `application.kandra.suspendRepository<User>()` inline instead?

- **Hand-typed `inject<KandraSuspendRepository<*>>(named("UserSuspendRepo"))`** — `kandraKoin()` binds
  a repo per entity in a runtime loop over `SchemaRegistry.all()`, so it can't statically write
  `single<KandraRepository<User>>` per type; JVM generic erasure means every `KandraRepository<T>`
  looks identical to Koin regardless of `T`, so the string qualifier is the only thing disambiguating
  entities. No IDE autocomplete on the string, no compile-time check that it matches a real binding.
- **`application.kandra.suspendRepository<User>()` called inline per request** — this *is* fully typed
  (`reified`, no qualifier needed), but it builds a **brand-new** `StatementBuilder` (its own
  prepared-statement cache) and a brand-new `KandraCache` on every call, and uses a
  default-constructed `ConsistencyConfig`/`DebugConfig` instead of the plugin's — silently dropping
  your `install(Kandra) { consistency {}; debug {} }` config for every read/collection/counter method.
  Fine to call **once** at startup and hold the result; wrong to call per-request.
- **The generated accessor** gets you the same type safety as the reified call, backed by the same
  create-once-reuse-forever Koin singleton as the hand-typed lookup — best of both.

## 4. Use them in routes

```kotlin
import io.kandra.core.exception.KandraOptimisticLockException
import io.kandra.core.exception.KandraValidationException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

fun Route.userRoutes() {
    // Created once per Ktor process (Koin singletons) — reused across every request.
    val userRepo = userSuspendRepo()
    val txnRepo  = transactionSuspendRepo()

    post("/users") {
        val user = call.receive<User>()
        try {
            userRepo.save(user)
            // ↑ @CreatedAt/@UpdatedAt stamped, @Version set to 1L,
            //   users (primary) + users_by_email (BATCH) written atomically,
            //   users_by_phone (EVENTUAL) fires async with the same retry/shutdown safeguards
            call.respond(HttpStatusCode.Created, user)
        } catch (e: KandraValidationException) {
            call.respond(HttpStatusCode.BadRequest, e.errors)
        }
    }

    get("/users/{id}") {
        val id = UUID.fromString(call.parameters["id"]!!)
        val user = userRepo.findById(id)   // checks @CacheResult cache first
        if (user == null) call.respond(HttpStatusCode.NotFound)
        else call.respond(user)
    }

    get("/users/by-email/{email}") {
        val email = call.parameters["email"]!!
        val user = userRepo.find { UserTable.email eq email }   // resolves via the @LookupIndex table
        if (user == null) call.respond(HttpStatusCode.NotFound)
        else call.respond(user)
    }

    get("/users/active") {
        // Indexed via @SecondaryIndex on isDeleted — no ALLOW FILTERING, no allowFullScan needed.
        call.respond(userRepo.findActive())
    }

    put("/users/{id}") {
        val id = UUID.fromString(call.parameters["id"]!!)
        val current = userRepo.findById(id) ?: return@put call.respond(HttpStatusCode.NotFound)
        val patch = call.receive<UserPatch>()

        try {
            userRepo.update(current, current.copy(name = patch.name, email = patch.email))
            // ↑ UPDATE ... IF version = ? — executed exactly once, no auto-retry on transient
            //   errors (see docs/USER_GUIDE.md's @Version section for why)
            call.respond(HttpStatusCode.OK)
        } catch (e: KandraOptimisticLockException) {
            call.respond(HttpStatusCode.Conflict, "row was modified concurrently, re-fetch and retry")
        }
    }

    delete("/users/{id}") {
        val id = UUID.fromString(call.parameters["id"]!!)
        userRepo.deleteById(id)   // @SoftDelete → TTL'd UPDATE, not a hard DELETE
        call.respond(HttpStatusCode.NoContent)
    }

    get("/users/{id}/transactions") {
        val id = UUID.fromString(call.parameters["id"]!!)
        val pageToken = call.request.queryParameters["page"]
        val page = txnRepo.findPage(pageSize = 20, pageToken = pageToken) {
            TransactionTable.userId eq id
        }
        call.respond(
            mapOf(
                "items" to page.items,
                "nextPage" to page.nextPageToken,
                "hasMore" to page.hasMore
            )
        )
    }
}
```

## What each layer is responsible for

| Layer | Owns |
|---|---|
| `@ScyllaTable` entities | Schema shape, key structure, per-column behavior (`@Version`, `@SoftDelete`, `@LookupIndex`, `@CacheResult`) |
| `install(Kandra) { ... }` | Connection, auth, retry, consistency, health, graceful shutdown — read once at startup |
| `kandraKoin()` | Builds **one** repository pair per entity, shared across every request |
| generated `*Repo()` accessors | Type-safe, autocomplete-friendly handle to that singleton — no string qualifiers, no casts |
| route handlers | Business logic only — call the repo, handle `KandraOptimisticLockException`/`KandraValidationException`, respond |

## See also

- [`docs/USER_GUIDE.md`](USER_GUIDE.md) — full annotation and config reference
- [`docs/features/di-integrations.md`](features/di-integrations.md) — DI accessor generation details
- [`docs/features/repositories.md`](features/repositories.md) — full repository API, including `findActive()`
- [`docs/test-plan/`](test-plan/README.md) — how this is verified against a real cluster
