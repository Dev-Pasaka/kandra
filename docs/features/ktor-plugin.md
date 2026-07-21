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
        provider            = KandraAuth.plainText("user", "pass")
        refreshIntervalSeconds = 3600
    }

    consistency {
        read  = KandraConsistency.LOCAL_QUORUM
        write = KandraConsistency.LOCAL_QUORUM
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
