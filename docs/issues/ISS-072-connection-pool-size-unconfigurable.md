# ISS-072: Connection-pool size (local/remote) has no Kandra-level configuration

**Status:** Open

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
