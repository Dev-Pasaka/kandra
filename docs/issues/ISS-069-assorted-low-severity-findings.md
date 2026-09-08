# ISS-069: Assorted lower-severity findings from the pre-cluster-testing deep review

**Status:** Partially fixed — items 1 and 6 done (this PR); items 2-5 open, tracked separately below

## Problem

Filed as GH #70.

Several smaller findings from the same review pass that produced ISS-053 through ISS-068, each too
narrow to warrant its own file but each worth tracking individually.

### 1. `@Sensitive` redaction (`safeToString()`) is dead code — never called — **Fixed**

`kandra-runtime/.../KandraEntityLogger.kt` defines `safeToString()`, which correctly redacts columns
annotated `@Sensitive`. It has zero call sites anywhere in the runtime module. No current log statement
interpolates a full entity, so there's no active leak today — but a developer who annotates a field
`@Sensitive` reasonably expects it to affect *something*. Right now it's inert. Either wire it into
debug/event-listener logging paths, or document explicitly that `@Sensitive` requires the consumer to
call `safeToString()` themselves.

**Fix:** wired into `QueryExecutor`'s existing `debugConfig.logQueries` debug-logging paths
(`findById`/`findAll`/`findByIdSuspend`/`findAllSuspend`) — a decoded entity is now logged via
`KandraEntityLogger.safeToString(entity, schema)` instead of never being logged at all, so
`@Sensitive` columns are actually redacted wherever an entity reaches a log line. Also fixed a latent
bug surfaced while testing this: `safeToString` called `prop.call(entity)` without
`prop.isAccessible = true`, which throws `IllegalAccessException` via kotlin-reflect's JVM access
check for any entity class that isn't a public top-level class — now fixed. Covered by
`KandraEntityLoggerTest` (redaction + non-sensitive-field passthrough).

### 2. `raw()`/`rawQuery()`'s injection guard defaults to warn-only, not fail-closed

`QueryExecutor.kt:273-283` — confirmed `ISS-050`'s fix works exactly as documented (the heuristic fires
unconditionally, `rawQuery`/`rawQuerySuspend` are covered, `rawQueryStrictMode = true` genuinely fails
closed). This is a deliberate, documented non-breaking default, not a bug — flagged here only as a
security-review recommendation: any team exposing `raw()` to code that builds queries from less-trusted
input should turn `rawQueryStrictMode` on rather than relying on the warn-only default.

### 3. Default consistency levels stop guaranteeing read-your-writes above RF 3

`kandra-runtime/.../ConsistencyConfig.kt:18-19` defaults to `defaultRead = LOCAL_ONE`,
`defaultWrite = LOCAL_QUORUM`. Read-your-writes requires `R + W ≥ RF`; these defaults satisfy that only
for `RF ≤ 3` (`1 + 2 = 3`). A keyspace with `RF = 5` (plausible for a larger multi-DC deployment) using
these defaults silently stops guaranteeing read-your-writes, and nothing in Strict Mode (`ISS-037`,
which only fires on `LOCAL_ONE`/`ONE` usage in a multi-DC topology, not on an RF/consistency mismatch)
surfaces it. Worth a documentation callout — this is exactly the kind of thing that passes every test
against an `RF=1` Testcontainers setup and surfaces as a mystery stale-read bug only under a real
`RF=5` production topology.

### 4. No shard-aware driver / no connection-pool-size tuning

No `CONNECTION_POOL_LOCAL_SIZE`/`REMOTE_SIZE` driver option is ever set (stock DataStax driver default:
1 pooled connection per node), and Kandra uses the stock OSS DataStax Java driver rather than a
shard-aware ScyllaDB driver, so it can't route requests directly to the owning shard on wide ScyllaDB
nodes. Not a bug — an architectural note worth surfacing explicitly before cluster-scale load testing,
since it's a real throughput ceiling that no Kandra-level config change alone will remove.

### 5. Retry backoff has no jitter

`BatchEngine.kt`'s linear backoff (`backoffMillis * (attempt + 1)`, capped by `maxBackoffMillis`, at
both `executeWithRetry` and `executeWithRetrySuspend`) is fully deterministic. Under a transient failure
correlated across many concurrent requests (a GC pause, a brief network blip affecting one coordinator),
all callers retry at synchronized intervals — a self-inflicted retry burst against the cluster rather
than spread-out load. Add randomized jitter to the backoff calculation.

### 6. No anti-pattern warning for `List<T>` collection columns — **Fixed**

Kandra fully supports `List<T>` columns (DDL generation + `append`/`remove`), despite the well-documented
Cassandra/Scylla list-column anti-pattern (per-element-timestamp ordering can silently reorder elements
under concurrent appends; removal creates one tombstone per removed element; reads get more expensive as
the list grows). `deleteAll` already warns about tombstone-growth risk elsewhere in the codebase — list
columns get no equivalent warning at schema-registration or DDL-generation time.

**Fix:** `SchemaRegistry` now logs a WARN at registration time for any property typed `List<*>`,
recommending `Set`/`Map` where order isn't significant — same registration-time-WARN pattern already
used elsewhere in the codebase (e.g. Strict Mode, ISS-037), never blocking registration.

## Suggested fix direction

Address each independently — most are documentation additions or small guardrail warnings rather than
behavioral fixes; items 1 and 6 could reasonably be small code changes (wiring an existing redaction
utility; adding a registration-time warning log for `List<T>` columns).

**Files:** `kandra-runtime/src/main/kotlin/io/kandra/runtime/KandraEntityLogger.kt`,
`kandra-runtime/src/main/kotlin/io/kandra/runtime/QueryExecutor.kt`,
`kandra-runtime/src/main/kotlin/io/kandra/runtime/ConsistencyConfig.kt`,
`kandra-runtime/src/main/kotlin/io/kandra/runtime/BatchEngine.kt`,
`kandra-core/src/main/kotlin/io/kandra/core/SchemaRegistry.kt`.
