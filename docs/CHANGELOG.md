# Changelog

All notable changes to Kandra are documented here.

## [0.3.0-SNAPSHOT] — Unreleased

### Added

**Part A — Gap fixes:**
- **Retry policy** — configurable linear backoff (`retry { maxAttempts = 3; backoffMillis = 100 }`); retries on `WriteTimeoutException`, `ReadTimeoutException`, `NoNodeAvailableException` by default; suspend variant uses `delay()` not `Thread.sleep()`
- **Idempotency tokens** — all statements now carry correct `setIdempotent()` flags: SELECT + DELETE + LWT INSERT = idempotent; plain INSERT + collection mutations + counter updates = non-idempotent; enables safe driver-level retry and speculative execution
- **`IN` clause on partition keys** — `isIn(listOf(id1, id2, id3))` generates `WHERE pk IN ?`; empty list short-circuits to empty result; DEBUG log on use (scatter-gather); non-PK `IN` without secondary index or ALLOW FILTERING throws `KandraQueryException`
- **Nullable codec contract** — `decode()` checks `row.isNull()` before any typed getter; nullable Kotlin type → returns `null`; non-nullable Kotlin type → throws `KandraQueryException` with clear message (no more NPE)
- **Caller-controlled batch** (`KandraBatchScope`) — `application.kandra.batch { userRepo.save(user); walletRepo.save(wallet) }` executes as a single LOGGED BATCH; `saveIfNotExists` inside batch throws immediately (LWT constraint)
- **`@SecondaryIndex` annotation** — creates `CREATE INDEX IF NOT EXISTS` DDL; queries on `@SecondaryIndex` columns work without `ALLOW FILTERING`; WARN logged on every secondary index query (scatter-gather reminder)
- **Prepared statement LRU cache** — replaces unbounded `ConcurrentHashMap`; configurable via `preparedStatementCacheSize = 1000`; WARN logged on eviction
- **`USING TIMESTAMP`** — `save(entity, timestampMicros = KandraTimestamp.fromInstant(event.occurredAt))` for idempotent event replay; `KandraTimestamp.now()` and `KandraTimestamp.fromInstant(Instant)` helpers in `kandra-core`
- **Query debug logging** — `debug { logQueries = true; logSlowQueriesMs = 500; logBatches = true }`; CQL template logged at DEBUG (no bound values — PII safe)
- **Test keyspace isolation** — `KandraTestcontainers.freshKeyspace(User::class)` creates an isolated random keyspace per test class; `@AfterEach fun cleanup() = db.close()` drops it
- **Incremental KSP processing** — codegen already uses `aggregating = false` (one source file → one generated file, only reprocessed on change)
- **Maven Central publishing config** — `buildSrc/src/main/kotlin/publish.gradle.kts` convention plugin with `maven-publish`, `signing`, sources JAR, POM metadata, Sonatype repository wiring; apply per-module
- **API stability markers** — `@InternalKandraApi` (ERROR-level opt-in) on `BatchEngine`, `StatementBuilder`, `SchemaRegistry`, `DdlGenerator`; `@ExperimentalKandraApi` (WARNING-level opt-in) on `KandraCodec` custom registration, `KandraEventListener`, `KandraBatchScope`, `KandraAuth`
- **`CHANGELOG.md`**

**Part B — Multi-datacenter support:**
- New **`kandra-multidc`** module — optional add-on for multi-DC deployments
- **Load balancing config** — `loadBalancing { tokenAware = true; dcAwareFailover = true; allowedRemoteDcs = listOf("eu-west") }`; validates non-empty `allowedRemoteDcs` when `dcAwareFailover = true` at startup
- **Consistency level per operation** — `KandraConsistency` enum in `kandra-core`; `@ReadConsistency` / `@WriteConsistency` class annotations; per-op override: `save(entity, consistency = KandraConsistency.EACH_QUORUM)`; resolution order: per-op > annotation > `ConsistencyConfig` default
- **LWT serial consistency** — `saveIfNotExists(entity, serialConsistency = KandraConsistency.SERIAL)` for globally unique constraints; `LOCAL_SERIAL` is the safe default for per-region uniqueness; non-serial value throws `KandraQueryException`
- **DC failover policy** — `failover { onLocalDcUnavailable = FailoverPolicy.RETRY_REMOTE_DC }` wired into load balancing; `THROW` preserves default driver behaviour
- **Speculative execution** — `speculativeExecution { enabled = true; delayMillis = 100; maxAttempts = 2 }`; only fires for idempotent statements (non-idempotent writes opt themselves out via `setIdempotent(false)`)

**Part C — Authentication & security:**
- **`KandraAuthProvider` abstraction** — `KandraAuth.fromEnv()` (recommended default), `fromFile()` (k8s secrets), `static()` (dev only), `custom { ... }` (Vault/AWS SM); replaces hardcoded `username`/`password` in config; `KandraAuthException` for all auth failures
- **Credential rotation** — `auth { refreshIntervalSeconds = 3600 }` schedules periodic re-fetch without session restart; `onCredentialRefreshed()` event fires on success
- **SSL/TLS** — `ssl { enabled = true; trustStorePath = "..."; hostnameVerification = true }`; mutual TLS with `keyStorePath`; minimum TLS version + cipher suites; `KandraAuthException` on handshake failure
- **Auth error wrapping** — `AllNodesFailedException` + `AuthenticationException` wrapped into `KandraAuthException` at session build time
- **`KandraEventListener` extended** — changed from `fun interface` to `interface` with default no-ops; new callbacks: `onAuthFailed`, `onConnectionEstablished`, `onCredentialRefreshed`, `onSslHandshakeFailed`; existing implementations continue to compile
- **Keyspace permission validation** — queries `system_auth.role_permissions` at startup; throws `KandraAuthException` if `SELECT` or `MODIFY` missing; warns if `ALTER` missing (needed for `AUTO_CREATE`); disable with `validatePermissions = false`

### Changed
- `KandraEventListener` changed from `fun interface` to `interface` — existing single-method implementations remain compatible (lambda syntax works via `object : KandraEventListener { override fun onEventualWriteFailed(...) }`)
- `auth.provider` default changed from plaintext `username`/`password` to `KandraAuth.fromEnv()` — set `SCYLLA_USERNAME` and `SCYLLA_PASSWORD` env vars, or override to `KandraAuth.static("cassandra", "cassandra")` for local dev
- `StatementBuilder` and `BatchEngine` now require `@OptIn(InternalKandraApi::class)` — they are internal APIs; use repositories instead

---

## [0.2.0-SNAPSHOT] — Unreleased

### Added
- Composite partition keys (`@PartitionKey(index)`) — `TableSchema.partitionKeys: List<ColumnSchema>`
- `@Ttl(seconds)` + per-save TTL override (`save(entity, ttlSeconds = 120)`)
- Keyspace auto-creation with `SimpleStrategy` / `NetworkTopologyStrategy`
- Local datacenter config (required for DataStax Java driver 4.x)
- Lightweight transactions — `saveIfNotExists()` with `[applied]` check
- Cursor pagination — `findPage(pageSize, pageToken)` using DataStax ByteBuffer paging state
- EVENTUAL write failure listener (`KandraEventListener`)
- Schema validation mode (`SchemaMode` enum: `AUTO_CREATE`, `VALIDATE`, `NONE`)
- `LIMIT` and `ALLOW FILTERING` in query context (with WARN log on `ALLOW FILTERING`)
- `deleteById()` and `deleteBy { }` on repositories
- Collection mutations (`append`, `remove`, `put`)
- Counter table support (`@Counter`, `increment()`, `decrement()`)
- Extensible codec + `BigDecimal` → CQL `DECIMAL` support
- `@CreatedAt` / `@UpdatedAt` auto-fill via data class `copy()` reflection
- `saveAll(entities, useBatch)` batch insert
- `exists { }` check via `SELECT pk LIMIT 1`
- Connection pool configuration (`PoolConfig`)
- Bill of Materials (`kandra-bom`)
- GitHub Actions CI
- Apache 2.0 LICENSE

---

## [0.1.0-SNAPSHOT] — Unreleased

### Added
- Initial release
- `@ScyllaTable`, `@PartitionKey`, `@ClusteringKey`, `@LookupIndex`, `@Column`, `@Transient` annotations
- `SchemaRegistry` with DDL generation
- `BATCH` and `EVENTUAL` lookup consistency modes
- `KandraRepository` (blocking) and `KandraSuspendRepository` (coroutine)
- Type-safe query DSL (`KandraColumnRef`, `KandraPredicate`, `QueryContext`)
- Ktor `ApplicationPlugin` integration
- Kodein-DI auto-binding (`kandra-kodein`)
- Koin auto-binding (`kandra-koin`)
- KSP codegen generating type-safe `*Table` objects
- `FakeKandraSession` for unit tests
