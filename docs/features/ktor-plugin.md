# Ktor Plugin (`kandra-ktor`)

### Basic setup

```kotlin
install(Kandra) {
    contactPoints = "localhost:9042"
    keyspace      = "myapp"
    localDatacenter = "datacenter1"
    schemaMode    = SchemaMode.AUTO_CREATE
    register(User::class, Wallet::class)
}
```

### Full config surface

```kotlin
install(Kandra) {
    contactPoints       = "localhost:9042"
    keyspace            = "myapp"
    localDatacenter     = "datacenter1"
    autoCreateKeyspace  = true
    schemaMode          = SchemaMode.AUTO_MIGRATE
    register(User::class)

    pool {
        requestTimeoutMillis    = 10_000
        connectionTimeoutMillis = 5_000
    }

    auth {
        provider            = KandraAuth.static("user", "pass")   // local dev only — see below
        refreshIntervalSeconds = 3600
    }

    consistency {
        defaultRead  = KandraConsistency.LOCAL_QUORUM
        defaultWrite = KandraConsistency.LOCAL_QUORUM
    }

    retry {
        maxAttempts    = 5
        backoffMillis  = 200
        maxBackoffMillis = 2000
    }

    debug {
        logQueries       = true
        logSlowQueriesMs = 500
        logBatches       = false
    }

    // Graceful shutdown: stops accepting new queries; waits for in-flight to drain
    shutdown {
        graceful        = true
        drainTimeoutMs  = 5000
    }

    // Health check: GET /kandra/health → {"status":"UP"} or {"status":"DOWN"}
    healthCheck = true

    // Tombstone warning: logs WARN when deleteAll() targets more than N rows
    tombstoneWarnThreshold = 1000

    // Batch limits
    batchWarnThresholdKb = 5
    batchMaxChunkSize    = 100
    batchAutoChunk       = true

    // Validation hook
    validate<User> { user ->
        buildList {
            if (user.email.isBlank()) add(KandraValidationError("email", "must not be blank"))
        }
    }

    eventListener = object : KandraEventListener { ... }
}
```

See [core-annotations.md](core-annotations.md) and [repositories.md](repositories.md) for the
annotation and repository surface, [migrations.md](migrations.md) for schema migrations,
[multidc.md](multidc.md) for multi-datacenter config, and
[jakarta-validation.md](jakarta-validation.md) for `validateJakarta<T>()`.

### Multiple keyspaces

One install, one `CqlSession`, many keyspaces. `keyspace = "x"` stays the default keyspace;
additional keyspaces are declared (settings only) and entities opt in via
`@ScyllaTable(keyspace = "...")`:

```kotlin
install(Kandra) {
    keyspace = "core"                       // default keyspace (unchanged)
    keyspace("billing") {                   // extra keyspace: settings only
        autoCreate = true
        replicationStrategy = ReplicationStrategy.SimpleStrategy(3)
    }
    register(User::class, Invoice::class)   // Invoice is @ScyllaTable(keyspace = "billing")
}
```

- `keyspace("name") { }` accepts `autoCreate` and `replicationStrategy` for that keyspace.
- Precedence: the entity annotation decides membership; blank means the default keyspace.
- An entity naming an undeclared, non-default keyspace fails startup with `KandraSchemaException`.
- Creation, permission validation, DDL, `system_schema` lookups and `kandra_ddl_locks` entries run
  per keyspace (lock names include the keyspace).
- Zero regression: single-keyspace configs and entities behave exactly as before.
- Non-goals: per-call / per-tenant runtime keyspace selection; logged batches across keyspaces.
