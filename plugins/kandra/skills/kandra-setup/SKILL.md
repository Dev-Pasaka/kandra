---
name: kandra-setup
description: How to add Kandra (ScyllaDB/Cassandra ORM for Ktor) to a new or existing project — Gradle coordinates, BOM, which modules to pick, KSP codegen setup, DI choice, version compatibility, first install(Kandra) block. Load when the user wants to start using Kandra, add it to a build, or asks which Kandra artifacts they need.
---

# Setting up Kandra in a project

Group id: `com.kandraorm.kandra` (Maven Central). Current release: **0.5.2** (check Maven Central or the repo's
`gradle.properties` for newer). Built and tested against **Ktor 3.6.x / Kotlin 2.3.x**; Ktor 2.x is unsupported and
Ktor 4.x is not covered.

## 1. Pick modules

| Need | Artifact |
|---|---|
| Always (pulls core + runtime) | `kandra-ktor` |
| DI | `kandra-koin` **or** `kandra-kodein` (pick one) |
| Type-safe `UserTable.email eq "x"` queries | `kandra-codegen` via `ksp(...)` |
| Versioned schema migrations | `kandra-migrate` |
| Jakarta Bean Validation | `kandra-jakarta` + a provider (see `kandra-jakarta` skill) |
| Multi-DC helpers (experimental) | `kandra-multidc` |
| Tests | `kandra-test` (`testImplementation`) |
| Version alignment | `kandra-bom` (`platform(...)`) |

## 2. Gradle (Kotlin DSL)

```kotlin
plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp") version "<ksp matching your Kotlin>"   // only if using codegen
}

dependencies {
    implementation(platform("com.kandraorm.kandra:kandra-bom:0.5.2"))
    implementation("com.kandraorm.kandra:kandra-ktor")
    implementation("com.kandraorm.kandra:kandra-koin")        // or kandra-kodein
    ksp("com.kandraorm.kandra:kandra-codegen")                 // optional
    implementation("com.kandraorm.kandra:kandra-migrate")      // optional
    testImplementation("com.kandraorm.kandra:kandra-test")
}
```

With the BOM, omit versions on every Kandra artifact. Needs `mavenCentral()` in repositories.

## 3. Minimal app

```kotlin
@ScyllaTable("users")
data class User(@PartitionKey val userId: UUID, val email: String)

fun Application.module() {
    install(Kandra) {
        contactPoints = "127.0.0.1:9042"   // String, comma-separated for several nodes
        localDatacenter = "datacenter1"
        keyspace = "app"
        autoCreateKeyspace = true   // defaults to false
        register(User::class)
    }
    kandraKoin()   // or kandraKodein(); then inject KandraRepository<User> / KandraSuspendRepository<User>
}
```

Startup fails fast on schema/permission errors. Use `schemaMode = SchemaMode.VALIDATE` or `NONE` in production and
manage changes with `kandra-migrate`.

## 3b. Local ScyllaDB gotchas

- **Tablets:** `scylladb/scylla:latest` creates new keyspaces with tablets enabled, and a tablets keyspace rejects `SimpleStrategy`. With
  `autoCreateKeyspace = true` the app then fails at startup with `SimpleStrategy doesn't support tablet replication`. For local dev set
  `replicationStrategy = ReplicationStrategy.SimpleStrategy(replicationFactor = 1, disableTablets = true)` (ScyllaDB only; Cassandra rejects the option).
  Real clusters: provision the keyspace ahead of time, or use `NetworkTopologyStrategy`.
- **Auth:** `install(Kandra)` calls `KandraAuth.fromEnv()` unconditionally; for an unauthenticated local node set
  `auth { provider = KandraAuth.static("cassandra", "cassandra") }` or export `SCYLLA_USERNAME`/`SCYLLA_PASSWORD`.
- **Timestamps:** CQL `timestamp` is millisecond precision. If a caller-supplied `Instant` is a key (for example `Instant.now()` as a clustering key), truncate
  it with `.truncatedTo(ChronoUnit.MILLIS)` before returning it to clients — with `@CacheResult` a microsecond `Instant` defeats cache invalidation.

## 4. Where to go next

Load the module skill for the code you are writing: `kandra` (overview + annotations), `kandra-runtime` (repository
calls), `kandra-ktor` (every config block), `kandra-codegen`, `kandra-koin`/`kandra-kodein`, `kandra-migrate`,
`kandra-jakarta`, `kandra-multidc`, `kandra-test`, `kandra-core`.

## Setup pitfalls

- After adding `kandra-codegen`, `*Table` objects appear only after a KSP run (build once); IDE may need a sync.
- Both `kandra-koin` and `kandra-kodein` on the classpath is a mistake — choose one.
- `localDatacenter` must match the cluster's real DC name or the driver fails to connect.
- Registered entities must be passed to `register(...)` inside `install(Kandra)`; DI binding is derived from that list.
- Library-internal APIs (`@InternalKandraApi`, `@ExperimentalKandraApi`) need explicit opt-in; avoid them in app code.
