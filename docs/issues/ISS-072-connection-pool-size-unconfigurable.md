# ISS-072: Connection-pool size (local/remote) has no Kandra-level configuration

**Status:** Fixed

## Problem

Filed as GH #80.

Filed from a critical library-wide review (security/performance/consistency/scalability/developer
experience) done ahead of experimental multi-cluster DC testing.

`PoolConfig` (`kandra-ktor/src/main/kotlin/io/kandra/ktor/KandraConfig.kt:33-46`) exposes
`maxRequestsPerConnection`, `heartbeatIntervalSeconds`, `requestTimeoutMillis`, and
`connectionTimeoutMillis` — but never sets the DataStax driver's `CONNECTION_POOL_LOCAL_SIZE`/
`CONNECTION_POOL_REMOTE_SIZE` options. Pool size is left entirely at the driver's own default (1
connection per node), with no way to tune it short of dropping to raw driver config outside Kandra
entirely.

This was already noted as an explicitly deferred architectural item in `ISS-069`/GH #70 (item 4:
"No shard-aware driver / no connection-pool-size tuning — Not addressed"), which recommended a
follow-up before cluster-scale load testing. Promoting it to its own actionable issue now that
multi-cluster DC testing is imminent.

**Impact:** for high-concurrency workloads against a real cluster, per-node connection count is one
of the primary throughput levers for the DataStax driver. Without it exposed, operators can't tune
for their actual concurrency needs without bypassing Kandra's config surface.

Related, same underlying note from ISS-069 item 4: Kandra uses the stock OSS DataStax Java driver,
not a shard-aware ScyllaDB driver, so it can't route directly to the owning shard on wide ScyllaDB
nodes regardless of pool size — that's a separate, larger architectural question, noted here for
context but out of scope for this issue.

## Suggested fix direction

Add `localPoolSize`/`remotePoolSize` (or similarly named) fields to `PoolConfig`, wire them to
`DefaultDriverOption.CONNECTION_POOL_LOCAL_SIZE`/`CONNECTION_POOL_REMOTE_SIZE` in
`CqlSessionBuilder.buildDriverConfig`, with sensible defaults and documentation on how to size
them.

**Files:** `kandra-ktor/src/main/kotlin/io/kandra/ktor/KandraConfig.kt`,
`kandra-ktor/src/main/kotlin/io/kandra/ktor/CqlSessionBuilder.kt`.

## Fix

Added `PoolConfig.localPoolSize` (default `1`) and `PoolConfig.remotePoolSize` (default `1`) —
matching the DataStax driver's own defaults, so an unconfigured `pool { }` block changes nothing.
Wired in `CqlSessionBuilder.buildDriverConfig` to
`DefaultDriverOption.CONNECTION_POOL_LOCAL_SIZE`/`CONNECTION_POOL_REMOTE_SIZE`.

KDoc on both fields documents sizing guidance: the real per-node concurrency ceiling is
`localPoolSize * maxRequestsPerConnection` (pool size and per-connection request multiplexing
compound), so raise `localPoolSize` only after profiling actual per-node concurrency under load, and
`remotePoolSize` only matters once cross-DC failover is actually enabled
(`loadBalancing.dcAwareFailover = true` + `failover.onLocalDcUnavailable = RETRY_REMOTE_DC`, per
GH #58 / ISS-057). Also cross-references the still-open, larger architectural point from
`ISS-069`/GH #70 item 4: Kandra uses the stock OSS DataStax driver, not a shard-aware ScyllaDB
driver, so pool size is a coarser lever than shard-aware routing would be — out of scope here.

## Tests

`kandra-ktor/src/test/kotlin/io/kandra/ktor/CqlSessionBuilderTest.kt` (new):

- `buildDriverConfig` defaults both `CONNECTION_POOL_LOCAL_SIZE` and `CONNECTION_POOL_REMOTE_SIZE`
  to `1` when `pool { }` is untouched.
- `buildDriverConfig` reflects configured `localPoolSize`/`remotePoolSize` values exactly.

`./gradlew :kandra-ktor:test --no-daemon` and the full `./gradlew test --no-daemon` both pass.
