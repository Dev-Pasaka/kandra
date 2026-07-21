---
name: kandra-ktor
description: Exhaustive API reference for kandra-ktor — the Kandra Ktor ApplicationPlugin, KandraConfig and all its nested config blocks (pool, auth, ssl, retry, debug, consistency, loadBalancing, failover, speculativeExecution, shutdown, metrics), SchemaMode, install lifecycle. Load when configuring install(Kandra) { ... } or writing Ktor application setup code.
---

# kandra-ktor

`kandra-ktor` is a single Ktor `ApplicationPlugin<KandraConfig>` (the `Kandra` val) plus the `KandraConfig`
DSL it's configured with. Installing it opens a `CqlSession`, optionally creates the keyspace, applies schema
DDL, wires the batch/retry/metrics runtime, registers a health-check route, and hooks graceful shutdown.
Everything below is read directly from:

- `kandra-ktor/src/main/kotlin/io/kandra/ktor/Kandra.kt` — the plugin itself
- `kandra-ktor/src/main/kotlin/io/kandra/ktor/KandraConfig.kt` — config + all nested config classes
- `kandra-ktor/src/main/kotlin/io/kandra/ktor/CqlSessionBuilder.kt` — driver session/DDL construction

## Install lifecycle — exact order of operations

`install(Kandra) { ... }` runs this sequence synchronously inside `createApplicationPlugin`'s body
(`Kandra.kt` lines 60-271). Nothing below is async except the credential-rotation loop and the shutdown hooks,
which are registered but fire later.

1. **Validate `keyspace` is set.** Blank `keyspace` throws `KandraSchemaException` immediately — this is the
   first thing checked, before any network call.
2. **Open the session.**
   - If `autoCreateKeyspace = true`: build a *bootstrap* session with `withKeyspace = false` (no keyspace
     attached yet — see `CqlSessionBuilder.kt:18`), run `CREATE KEYSPACE IF NOT EXISTS ...` using
     `replicationStrategy`, then `USE <keyspace>` on that same session and keep it as the plugin's session
     (no reconnect). Logs `"Kandra: keyspace '<ks>' ensured."`
   - Else: build the session directly with the keyspace attached (`buildCqlSession(config)`, `withKeyspace`
     defaults to `true`). If the keyspace doesn't exist yet, this throws at driver-connect time — auto-create
     is off, so you own keyspace provisioning.
3. **Fire `eventListener?.onConnectionEstablished(contactPoints)`.**
4. **Permission validation** (`validatePermissions()` in `Kandra.kt:274`) — only runs when
   `config.validatePermissions = true` **and** `schemaMode != SchemaMode.NONE`. See "Permission validation"
   below for exact behavior.
5. **Register entities.** Every class passed to `register(...)` is pushed into `SchemaRegistry` (process-global
   singleton, same one used by `kandra-core`).
6. **Apply schema DDL** per `schemaMode` — see "SchemaMode" table below. This is where `CREATE TABLE`,
   `ALTER TABLE`, or validation queries run, in registration order.
7. **Build the runtime.** A dedicated `CoroutineScope(SupervisorJob() + Dispatchers.IO)` (`pluginScope`) is
   created — this is what eventual/lookup writes and the credential-refresh loop run on, scoped to application
   lifetime. Then, in order: `StatementBuilder` → `BatchEngine` (batch limits + metrics recorder + registered
   validators wired onto it here) → `KandraRuntime(session, batchEngine, codec)`.
8. **Publish attributes** onto the Ktor `Application`: `KandraSessionKey`, `KandraCodecKey`, `KandraRuntimeKey`,
   and (if set) `KandraEventListenerKey`. These back the `application.kandraSession` / `.kandraCodec` /
   `.kandra` extension properties.
9. **Health check route** — if `healthCheck = true` (default), registers `GET /kandra/health` via
   `application.routing { ... }`. See below for exact response contract.
10. **Credential rotation loop** — only started if `auth.refreshIntervalSeconds != null`. Launches on
    `pluginScope`, loops forever: `delay(interval)` → `auth.provider.getCredentials()` → on success fires
    `eventListener?.onCredentialRefreshed()`, on any `Exception` logs and fires
    `eventListener?.onAuthFailed(contactPoints, e)` — the loop itself never dies from a failed refresh, it just
    logs and waits for the next tick.
11. **Register shutdown hooks** (do not run yet — just subscribed):
    - On `ApplicationStopping`: if `shutdown.graceful = true`, sets `runtime.isShuttingDown = true` (new
      `batch`/`batchBlocking` calls now throw `KandraException`), then busy-waits (`Thread.sleep(50)` poll
      loop, **blocking**, not suspending) until `inFlightCount == 0` or `shutdown.drainTimeoutMs` elapses.
      Logs a WARN with the remaining in-flight count if the deadline is hit without draining.
    - On `ApplicationStopped`: closes the `CqlSession`, then cancels `pluginScope`. The comment in the source
      is explicit about why this order matters: cancelling *after* `session.close()` guarantees no coroutine
      can start new work on an already-closed session.

```kotlin
fun Application.module() {
    install(Kandra) {
        contactPoints = "localhost:9042"
        keyspace = "coinx"
        localDatacenter = "datacenter1"
        autoCreateKeyspace = true
        schemaMode = SchemaMode.AUTO_CREATE
        register(User::class, Wallet::class)
        pool { requestTimeoutMillis = 10_000 }
        auth { provider = KandraAuth.fromEnv() }
        debug { logQueries = true; logSlowQueriesMs = 500 }
    }
}
```

## Permission validation

Runs only when `validatePermissions = true` (default) and `schemaMode != NONE`. Implementation
(`Kandra.kt:274-316`, `@InternalKandraApi`):

1. `SELECT role FROM system.local` — if `role` is null (common on ScyllaDB, which doesn't populate this),
   logs an informational message and returns early. **No error is raised** in this case — permission
   validation is a best-effort check, not a hard gate on ScyllaDB.
2. Otherwise queries `system_auth.role_permissions WHERE role = ? AND resource = 'data/<keyspace>'`.
3. Missing `SELECT` (and not `ALL`) → throws `KandraAuthException` with the exact `GRANT` statement to run.
4. Missing `MODIFY` (and not `ALL`) → throws `KandraAuthException`, same pattern.
5. Missing `ALTER` (and not `ALL`), only checked when `schemaMode != NONE` → **logs a WARN, does not throw.**
6. Any other exception (e.g. `system_auth` not accessible in this deployment) is swallowed at DEBUG level —
   permission validation degrades silently rather than blocking startup.

## SchemaMode — exact startup behavior

```kotlin
enum class SchemaMode { AUTO_CREATE, AUTO_MIGRATE, VALIDATE, NONE }
```

| Mode | What runs at install, precisely |
|---|---|
| `AUTO_CREATE` (default) | For every registered entity schema, executes `DdlGenerator.allStatements(schema)` — `CREATE TABLE IF NOT EXISTS` for the primary table and every `@LookupIndex` table. Never touches existing tables beyond `IF NOT EXISTS`. |
| `AUTO_MIGRATE` | **Step 1**: same as `AUTO_CREATE` (idempotent create). **Step 2**: for each schema, reads `system_schema.columns` for that table and diffs it against `partitionKeys + clusteringKeys + columns + lookupTables.indexColumn` (deduped by CQL name). Any entity column missing from Scylla gets `ALTER TABLE ... ADD` via `DdlGenerator.alterTableAddColumn` (logged at INFO). Any column that exists in both but with a mismatched CQL type is only **logged at ERROR** with a suggested manual fix (`DROP` + re-add) — it does **not** throw and does **not** auto-fix the type. Columns present in Scylla but absent from the entity are logged at WARN and left alone. **Never drops or renames columns.** |
| `VALIDATE` | Read-only: diffs entity columns against `system_schema.columns` per table. Any entity column missing from Scylla **throws `KandraSchemaException`** (fails startup) telling you to migrate or switch to `AUTO_MIGRATE`. Extra columns in Scylla not on the entity are only logged at WARN. |
| `NONE` | No DDL at all, no column diffing — logs `"Kandra: schemaMode=NONE — skipping all DDL."` and moves on. Use this alongside a separate `kandra-migrate` runner (see the `kandra` skill) when you own migrations explicitly. |

For renames, drops, backfills, or index changes — none of these modes handle it. Use the separate
`kandra-migrate` module (`KandraMigrationRunner`) before `install(Kandra)` with `schemaMode = NONE`.

## Health check route

Registered only when `healthCheck = true` (default). Exposes:

```
GET /kandra/health
```

- Calls `runtime.isHealthy()` → `SELECT release_version FROM system.local` against the live session.
- Success → `200 OK`, body `{"status":"UP"}`, `Content-Type: application/json`.
- Any exception during the query → `503 Service Unavailable`, body `{"status":"DOWN"}` (the exception itself
  is only logged at WARN, not exposed in the response).

## `KandraConfig` — top-level properties

```kotlin
class KandraConfig {
    var contactPoints: String = "localhost:9042"
    var keyspace: String = ""                       // required — blank throws KandraSchemaException at install
    var localDatacenter: String = "datacenter1"

    var autoCreateKeyspace: Boolean = false
    var replicationStrategy: ReplicationStrategy = ReplicationStrategy.SimpleStrategy(replicationFactor = 1)

    var schemaMode: SchemaMode = SchemaMode.AUTO_CREATE
    var validatePermissions: Boolean = true          // SELECT + MODIFY required; ALTER only warns

    var preparedStatementCacheSize: Int = 1000

    var tombstoneWarnThreshold: Int = 1000           // WARN when deleteBy/deleteAll would generate more tombstones than this
    var batchWarnThresholdKb: Int = 5                // WARN when a batch exceeds this estimated size
    var batchMaxChunkSize: Int = 100                 // statements per auto-chunk
    var batchAutoChunk: Boolean = true                // large batches auto-split into batchMaxChunkSize chunks

    var healthCheck: Boolean = true                  // registers GET /kandra/health

    var eventListener: KandraEventListener? = null

    // read-only handles to nested config — mutate via the block form, e.g. pool { ... }, not by reassigning
    val pool: PoolConfig
    val retry: RetryConfig             // from kandra-runtime
    val debug: DebugConfig             // from kandra-runtime
    val codec: KandraCodec             // from kandra-runtime.codec
    val consistency: ConsistencyConfig // from kandra-runtime
    val auth: AuthConfig
    val ssl: SslConfig
    val loadBalancing: LoadBalancingConfig
    val failover: FailoverConfig
    val speculativeExecution: SpeculativeExecutionConfig
    val shutdown: ShutdownConfig
    val metrics: MetricsConfig

    fun register(vararg classes: KClass<*>)
    fun <T : Any> validate(klass: KClass<T>, validator: KandraValidator<T>)
    inline fun <reified T : Any> validate(noinline validator: (T) -> List<KandraValidationError>)
}
```

Notes on top-level fields:
- `contactPoints` is a comma-separated `host:port` list (port optional, defaults to `9042` per entry — see
  `CqlSessionBuilder.kt:38-44`, which splits on `,` and parses the *last* `:` as the port separator so IPv6
  literals with embedded colons still resolve the trailing port correctly).
- `keyspace` has no default — you must set it or install throws.
- `autoCreateKeyspace = false` by default: in most real deployments the keyspace already exists and is
  provisioned by ops, so DDL against `system_schema` requires elevated permissions you may not want the app
  role to have.
- `tombstoneWarnThreshold`, `batchWarnThresholdKb`, `batchMaxChunkSize`, `batchAutoChunk` are **top-level**
  properties on `KandraConfig`, not nested under a `batch { }` block — there is no `batch { }` DSL block in
  this module.
- `register(...)` just appends to an internal `mutableListOf<KClass<*>>` (`entities`) — call it as many times
  as you like, or once with varargs; order is preserved and determines DDL/migration execution order.
- `validate<T> { }` registers a `KandraValidator<T>` keyed by `KClass<T>` into an internal map; last
  registration for a given class wins (a `Map`, so re-registering the same class overwrites, doesn't append).

## `ReplicationStrategy`

```kotlin
sealed class ReplicationStrategy {
    data class SimpleStrategy(val replicationFactor: Int = 1) : ReplicationStrategy()
    data class NetworkTopologyStrategy(val dcReplicationMap: Map<String, Int>) : ReplicationStrategy()
}
```

Only consulted when `autoCreateKeyspace = true`; `keyspaceDdl()` (`CqlSessionBuilder.kt:163`) renders it into
the `CREATE KEYSPACE` statement:

```kotlin
// SimpleStrategy(replicationFactor = 3):
// CREATE KEYSPACE IF NOT EXISTS coinx WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 3}

replicationStrategy = ReplicationStrategy.NetworkTopologyStrategy(
    mapOf("us-east" to 3, "eu-west" to 2)
)
// CREATE KEYSPACE IF NOT EXISTS coinx WITH replication =
//   {'class': 'NetworkTopologyStrategy', 'us-east': 3, 'eu-west': 2}
```

## `pool { }` — `PoolConfig`

```kotlin
class PoolConfig {
    var localRequestsPerConnection: Int = 1024
    var maxRequestsPerConnection: Int = 32768   // -> DefaultDriverOption.CONNECTION_MAX_REQUESTS
    var heartbeatIntervalSeconds: Int = 30      // -> DefaultDriverOption.HEARTBEAT_INTERVAL
    var requestTimeoutMillis: Long = 5000       // -> DefaultDriverOption.REQUEST_TIMEOUT (driver default is 2000ms)
    var connectionTimeoutMillis: Long = 5000    // -> DefaultDriverOption.CONNECTION_CONNECT_TIMEOUT
}
```

Note: `localRequestsPerConnection` is declared but **not actually applied** in `buildDriverConfig()` — only
`maxRequestsPerConnection`, `requestTimeoutMillis`, `connectionTimeoutMillis`, and
`heartbeatIntervalSeconds` are wired to `DefaultDriverOption`s (`CqlSessionBuilder.kt:94-99`). Set it if you
like for documentation purposes, but it currently has no effect on the driver.

```kotlin
pool {
    requestTimeoutMillis = 10_000
    connectionTimeoutMillis = 8_000
    maxRequestsPerConnection = 65536
}
```

## `auth { }` — `AuthConfig`

```kotlin
@OptIn(ExperimentalKandraApi::class)
class AuthConfig {
    var provider: KandraAuthProvider = KandraAuth.fromEnv()
    var refreshIntervalSeconds: Long? = null
}
```

- `provider` default reads `SCYLLA_USERNAME` / `SCYLLA_PASSWORD` from the environment (`KandraAuth.fromEnv()`).
  If credentials come back blank, `buildCqlSession` skips `withAuthCredentials` entirely (no auth attempted).
- Other factories on `KandraAuth` (from `kandra-core`, `KandraAuth.kt`): `fromEnv(usernameVar, passwordVar)`,
  `fromFile(usernamePath, passwordPath)` (Docker/K8s secret files), `static(username, password)`
  (dev/test only — never production), `custom { KandraCredentials(...) }` (Vault, AWS Secrets Manager, etc.).
- `refreshIntervalSeconds`: when non-null, the plugin starts a background loop on `pluginScope` (step 10 in
  the install lifecycle above) that re-fetches credentials on this interval — supports rolling credential
  rotation without an app restart. **The fetched credentials are not currently re-applied to the live
  `CqlSession`'s auth** — `getCredentials()` is called to validate/refresh whatever cache the provider itself
  maintains and to fire the `onCredentialRefreshed`/`onAuthFailed` callbacks; wire your provider's caching
  accordingly if you need the driver connection itself to pick up new creds.
- Failures during initial `getCredentials()` call in `buildCqlSession` wrap into `KandraAuthException` unless
  already one.

```kotlin
auth {
    provider = KandraAuth.fromFile("/run/secrets/scylla_user", "/run/secrets/scylla_pass")
    refreshIntervalSeconds = 3600
}
```

## `ssl { }` — `SslConfig`

```kotlin
class SslConfig {
    var enabled: Boolean = false
    var requireEncryption: Boolean = true      // declared; not read anywhere in CqlSessionBuilder
    var hostnameVerification: Boolean = true   // -> DefaultDriverOption.SSL_HOSTNAME_VALIDATION (only set when enabled)
    var trustStorePath: String? = null
    var trustStorePassword: String? = null
    var trustStoreType: String = "JKS"
    var keyStorePath: String? = null
    var keyStorePassword: String? = null
    var keyStoreType: String = "JKS"
    var minimumTlsVersion: String = "TLSv1.2"  // declared; not read anywhere in CqlSessionBuilder
    var cipherSuites: List<String>? = null     // declared; not read anywhere in CqlSessionBuilder
}
```

- `enabled = true` is required for any of this to matter — `buildCqlSession` only calls `withSslContext(...)`
  when `ssl.enabled`, and `buildDriverConfig` only sets `SSL_HOSTNAME_VALIDATION` when `ssl.enabled`.
- `requireEncryption`, `minimumTlsVersion`, and `cipherSuites` are read directly from the file — they exist as
  config surface but `buildSslContext()`/`buildDriverConfig()` never reference them. `SSLContext.getInstance("TLS")`
  is hardcoded. Don't rely on these three to actually constrain the handshake today.
- `trustStorePath` set → one-way TLS: loads a `KeyStore` of `trustStoreType`, builds a `TrustManagerFactory`.
  `trustStorePath` unset → no trust managers passed (JVM default trust store behavior applies).
- `keyStorePath` set (in addition to trust store) → mutual TLS: loads a client keystore/key manager for
  client-cert auth.
- Any load failure (bad path, bad password) wraps into `KandraAuthException` naming the failing path.

```kotlin
ssl {
    enabled = true
    trustStorePath = "/certs/scylla-truststore.jks"
    trustStorePassword = System.getenv("TRUSTSTORE_PASSWORD")
    hostnameVerification = true
}
```

## `loadBalancing { }` — `LoadBalancingConfig`

```kotlin
class LoadBalancingConfig {
    var tokenAware: Boolean = true                    // declared; not read in CqlSessionBuilder — driver's default token-aware LBP applies regardless
    var dcAwareFailover: Boolean = false
    var allowedRemoteDcs: List<String> = emptyList()
    var maxRemoteNodesPerRemoteDc: Int = 1             // declared; not read in CqlSessionBuilder
}
```

- **Validated eagerly at session-build time**, before any connection attempt (`CqlSessionBuilder.kt:19-32`):
  `dcAwareFailover = true` with an empty `allowedRemoteDcs` throws `KandraSchemaException` immediately.
- Also cross-validated against `failover.onLocalDcUnavailable`: if that's `RETRY_REMOTE_DC` but
  `allowedRemoteDcs` is empty, throws `KandraSchemaException` too — this check fires independently of
  `dcAwareFailover`.
- `tokenAware` and `maxRemoteNodesPerRemoteDc` are declared config surface but **not currently consumed** by
  `buildDriverConfig`/`buildCqlSession` — no driver option is set from them.

```kotlin
loadBalancing {
    dcAwareFailover = true
    allowedRemoteDcs = listOf("eu-west")
}
failover {
    onLocalDcUnavailable = FailoverPolicy.RETRY_REMOTE_DC
}
```

## `failover { }` — `FailoverConfig` / `FailoverPolicy`

```kotlin
enum class FailoverPolicy {
    THROW,            // NoNodeAvailableException immediately (default)
    RETRY_REMOTE_DC   // retry against loadBalancing.allowedRemoteDcs, in order
}

class FailoverConfig {
    var onLocalDcUnavailable: FailoverPolicy = FailoverPolicy.THROW
    var remoteRetryDelayMs: Long = 50    // declared; not read anywhere in CqlSessionBuilder
}
```

`RETRY_REMOTE_DC` requires `loadBalancing.allowedRemoteDcs` to be non-empty or install throws at session-build
time (see above). `remoteRetryDelayMs` is present in the config class but there's no code path in
`CqlSessionBuilder.kt` or `Kandra.kt` that reads it — the actual DC-failover retry mechanics live in the
driver's load-balancing policy configuration, not in an explicit delay loop in this module.

## `speculativeExecution { }` — `SpeculativeExecutionConfig`

```kotlin
class SpeculativeExecutionConfig {
    var enabled: Boolean = false
    var delayMillis: Long = 100
    var maxAttempts: Int = 2
}
```

When `enabled`, wires `ConstantSpeculativeExecutionPolicy` onto the driver:
`SPECULATIVE_EXECUTION_DELAY = delayMillis`, `SPECULATIVE_EXECUTION_MAX = maxAttempts - 1` (the driver counts
the *additional* speculative executions beyond the original, so `maxAttempts = 2` → 1 extra speculative
request fired after `delayMillis` if the first hasn't returned).

```kotlin
speculativeExecution { enabled = true; delayMillis = 150; maxAttempts = 3 }
// original request + up to 2 speculative retries, each fired 150ms after the previous if still pending
```

## `shutdown { }` — `ShutdownConfig`

```kotlin
class ShutdownConfig {
    var drainTimeoutMs: Long = 5000
    var graceful: Boolean = true
}
```

- `graceful = true` (default): on `ApplicationStopping`, sets `isShuttingDown`, then blocks the shutdown
  thread (`Thread.sleep(50)` poll loop — not a suspend function, this genuinely blocks) until
  `inFlightCount()` reaches zero or `drainTimeoutMs` elapses. If the deadline hits with queries still
  in-flight, logs a WARN with the count and proceeds to force-close anyway.
- `graceful = false`: the `ApplicationStopping` block still runs but its `if (config.shutdown.graceful)` body
  is skipped entirely — no drain wait, `isShuttingDown` is never set, session closes immediately on
  `ApplicationStopped`.
- The session is always closed on `ApplicationStopped` regardless of `graceful`.

```kotlin
shutdown { graceful = true; drainTimeoutMs = 10_000 }
```

## `metrics { }` — `MetricsConfig`

```kotlin
class MetricsConfig {
    var enabled: Boolean = false
    var recorder: KandraMetrics? = null
}

fun interface KandraMetrics {
    fun record(tableName: String, operation: String, durationMs: Long)
}
```

- `enabled = true` with `recorder = null` does **not** throw — it logs a WARN at install
  (`"metrics.enabled=true but no recorder was configured — metrics will not be recorded."`) and metrics are
  simply not recorded.
- `operation` values passed to `record()`: one of `"save"`, `"update"`, `"delete"`, `"saveAll"`,
  `"deleteAll"`, `"batch"` (per `KandraMetrics.kt` doc comment on `kandra-core`).
- `recorder` is only wired onto `BatchEngine` (`engine.setMetrics(it)`) when `metrics.enabled = true` — set
  `enabled = false` to fully disable metrics collection even if a recorder is set.

```kotlin
metrics {
    enabled = true
    recorder = KandraMetrics { table, op, durationMs ->
        meterRegistry.timer("kandra.query", "table", table, "operation", op)
            .record(durationMs, TimeUnit.MILLISECONDS)
    }
}
```

## `eventListener` — `KandraEventListener` (from `kandra-core`)

```kotlin
@ExperimentalKandraApi
interface KandraEventListener {
    fun onEventualWriteFailed(tableName: String, entity: Any, error: Throwable)  // must not throw
    fun onAuthFailed(contactPoint: String, error: Throwable) {}
    fun onConnectionEstablished(contactPoint: String) {}
    fun onCredentialRefreshed() {}
    fun onSslHandshakeFailed(contactPoint: String, error: Throwable) {}
}
```

Single, top-level `KandraConfig.eventListener: KandraEventListener?` — not a DSL block, assign directly. Fired
from `Kandra.kt` at: connection established (step 3 of install), credential refresh success/failure (step
10), and internally from `BatchEngine` for eventual lookup-table write failures. `onEventualWriteFailed` has
no default body — you must implement it; every other method is a no-op default so old implementations keep
compiling as new callback methods are added.

```kotlin
eventListener = object : KandraEventListener {
    override fun onEventualWriteFailed(tableName: String, entity: Any, error: Throwable) {
        deadLetterQueue.publish(FailedLookupWrite(tableName, entity))
    }
    override fun onAuthFailed(contactPoint: String, error: Throwable) {
        alerting.critical("ScyllaDB auth failed on $contactPoint", error)
    }
}
```

## `retry { }` — `RetryConfig` (from `kandra-runtime`, re-exposed here)

```kotlin
class RetryConfig {
    var maxAttempts: Int = 3
    var backoffMillis: Long = 100
    var maxBackoffMillis: Long = 2000
    var retryOn: Set<KClass<out Throwable>> = setOf(
        WriteTimeoutException::class, ReadTimeoutException::class, NoNodeAvailableException::class
    )
}
```

Retries with linear backoff on the configured exception set, up to `maxAttempts`, before giving up and
throwing `KandraQueryException`. Add your own retryable types via `retryOn = retryOn + MyException::class`
(it's a plain `Set`, not append-only).

## `debug { }` — `DebugConfig` (from `kandra-runtime`)

```kotlin
class DebugConfig {
    var logQueries: Boolean = false      // logs the CQL template at DEBUG — never bound parameter values (PII safety)
    var logSlowQueriesMs: Long = 0L      // WARN when a query exceeds this; 0 = disabled
    var logBatches: Boolean = false      // logs full batch contents at DEBUG
}
```

Bound values are never logged even with `logQueries = true` — only the parameterized CQL template. This is a
deliberate PII guard, not an oversight, per the file's doc comment.

## `consistency { }` — `ConsistencyConfig` (from `kandra-runtime`)

```kotlin
class ConsistencyConfig {
    var defaultRead: KandraConsistency = KandraConsistency.LOCAL_ONE
    var defaultWrite: KandraConsistency = KandraConsistency.LOCAL_QUORUM
    var defaultSerialConsistency: KandraConsistency = KandraConsistency.LOCAL_SERIAL
}
```

Resolution order (highest priority first): per-call `consistency` argument on a repository method >
`@ReadConsistency`/`@WriteConsistency` on the entity class > these defaults. `KandraConsistency` values:
`ONE, TWO, THREE, QUORUM, ALL, LOCAL_ONE, LOCAL_QUORUM, EACH_QUORUM, LOCAL_SERIAL, SERIAL` (`isSerial` is
`true` for `LOCAL_SERIAL`/`SERIAL`, used for LWT operations like `saveIfNotExists`).

## `codec: KandraCodec` (from `kandra-runtime.codec`)

Exposed as `config.codec` and threaded into `StatementBuilder`, `BatchEngine`, and `KandraRuntime`. Not a DSL
block — it's a plain property (`val codec: KandraCodec = KandraCodec()`) you can inspect but the plugin itself
only passes it through; codec customization is out of scope for `kandra-ktor` specifically (see the codec's
own type in `kandra-runtime` for extension points).

## `validate<T> { }` — entity validation hook

```kotlin
fun <T : Any> validate(klass: KClass<T>, validator: KandraValidator<T>)
inline fun <reified T : Any> validate(noinline validator: (T) -> List<KandraValidationError>)
```

```kotlin
install(Kandra) {
    validate<User> { user ->
        buildList {
            if (user.email.isBlank()) add(KandraValidationError("email", "cannot be blank"))
            if (user.age < 0) add(KandraValidationError("age", "must be non-negative"))
        }
    }
}
```

Registered validators are copied onto `BatchEngine` at install time (`engine.registerValidator(...)`) and run
before writes; a non-empty result list throws `KandraValidationException(errors)` (see `kandra-core`
`KandraValidator.kt`). Re-registering the same `KClass` overwrites the previous validator — it's backed by a
`MutableMap<KClass<*>, KandraValidator<*>>`, not a list.

## `CqlSessionBuilder.kt` internals — what actually builds the driver session

Internal (non-public) functions, but their behavior directly explains what config knobs do:

- `buildCqlSession(config, withKeyspace = true): CqlSession` — validates failover config, splits
  `contactPoints` on `,` (last `:` in each entry is the port separator, default port `9042`), sets
  `localDatacenter`, applies the driver config loader from `buildDriverConfig`, optionally attaches the
  keyspace, resolves auth credentials, optionally builds an SSL context, then calls `.build()`. Wraps
  `AllNodesFailedException` — if any node failure was an `AuthenticationException`, rethrows as
  `KandraAuthException` naming the first failing contact point; otherwise rethrows as `KandraQueryException`.
  A top-level `AuthenticationException` also maps to `KandraAuthException`.
- `buildDriverConfig(config): DriverConfigLoader` — the only place `pool.*`, `ssl.hostnameVerification`, and
  `speculativeExecution.*` are actually translated into `DefaultDriverOption`s (see each section above for
  exactly which fields are/aren't wired).
- `buildSslContext(ssl): SSLContext` — loads trust/key stores, always constructs `SSLContext.getInstance("TLS")`
  (hardcoded protocol string, `minimumTlsVersion`/`cipherSuites` are not applied here).
- `keyspaceDdl(keyspace, strategy): String` — renders the `CREATE KEYSPACE IF NOT EXISTS` statement for either
  `ReplicationStrategy` variant; only called when `autoCreateKeyspace = true`.

## `Kandra` plugin — public surface

```kotlin
val Kandra: ApplicationPlugin<KandraConfig>   // pass to install(Kandra) { ... }

val KandraSessionKey: AttributeKey<CqlSession>
val KandraCodecKey: AttributeKey<KandraCodec>
val KandraRuntimeKey: AttributeKey<KandraRuntime>
val KandraEventListenerKey: AttributeKey<KandraEventListener>

val Application.kandraSession: CqlSession   // attributes[KandraSessionKey]
val Application.kandraCodec: KandraCodec    // attributes[KandraCodecKey]
val Application.kandra: KandraRuntime       // attributes[KandraRuntimeKey]
```

There is no `Application.kandraEventListener` extension property — the `KandraEventListenerKey` attribute is
set (only if `eventListener != null`) but not exposed via a convenience getter; read it with
`application.attributes[KandraEventListenerKey]` if needed, or `attributes.getOrNull(...)`.

```kotlin
routing {
    get("/users/{id}") {
        val repo = application.kandra.suspendRepository<User>()
        val user = repo.findById(UUID.fromString(call.parameters["id"]!!))
        call.respond(user ?: HttpStatusCode.NotFound)
    }
}
```

## Gotchas worth double-checking in review

- `keyspace` has no default and blank throws at install — this is the very first check, before any network
  call, so a missing `keyspace` fails fast rather than surfacing as a confusing driver error.
- `autoCreateKeyspace = false` by default — if you rely on it in one environment (e.g. local dev) but not
  another (staging/prod), a missing keyspace in the non-auto-create environment throws from the driver
  connect call, not from a Kandra-specific check.
- `PoolConfig.localRequestsPerConnection`, `SslConfig.requireEncryption`/`minimumTlsVersion`/`cipherSuites`,
  `LoadBalancingConfig.tokenAware`/`maxRemoteNodesPerRemoteDc`, and `FailoverConfig.remoteRetryDelayMs` are
  all declared config fields that are **not currently read** by `CqlSessionBuilder.kt`. Setting them changes
  nothing today — don't assume they're load-bearing just because they exist on the config class.
- `dcAwareFailover = true` or `failover.onLocalDcUnavailable = RETRY_REMOTE_DC` both independently require
  `loadBalancing.allowedRemoteDcs` to be non-empty, or install throws `KandraSchemaException` before any
  connection is attempted — check both together when reviewing multi-DC config.
- `AUTO_MIGRATE` never fixes column **type** mismatches — it only logs an ERROR with a manual `DROP`+re-add
  suggestion. A silently-mismatched type is exactly the kind of thing that produces confusing runtime codec
  errors later; grep logs for `"type mismatch"` after any entity field type change.
- Health check failures (`/kandra/health` → 503) log the underlying exception at WARN only — the JSON body
  never includes the error detail, so don't rely on curling this endpoint for diagnostics; check logs.
- Graceful shutdown drain (`shutdown.graceful = true`) blocks the shutdown thread with `Thread.sleep(50)` in a
  loop — it is not a suspend function. On a slow drain this holds up whatever is driving Ktor's shutdown
  sequence for up to `drainTimeoutMs`.
- `metrics.enabled = true` without a `recorder` doesn't fail installation — it just silently no-ops with a
  WARN log. Easy to miss in review since nothing breaks.
- `auth.refreshIntervalSeconds` refreshing credentials does not automatically push new credentials into the
  live `CqlSession`'s auth mechanism — the loop calls `getCredentials()` (to validate/refresh whatever the
  provider itself caches) and fires callbacks, but there's no explicit re-auth call against the open session
  in this file.
- `contactPoints` port parsing splits on the *last* colon per comma-separated entry — safe for IPv6 literals
  with a trailing port, but malformed entries without a numeric suffix after the last colon will throw a
  `NumberFormatException` from `.toInt()`, not a Kandra-specific error.
