# ISS-068: `PoolConfig.localRequestsPerConnection` is dead configuration

**Status:** Fixed (GH #69, PR #75)

## Problem

Filed as GH #69.

Filed from a pre-cluster-testing deep performance review. `KandraConfig.kt:34` declares:

```kotlin
var localRequestsPerConnection: Int = 1024
```

alongside `maxRequestsPerConnection` (line 35). Confirmed via grep: `CqlSessionBuilder.buildDriverConfig`
only reads `config.pool.maxRequestsPerConnection` (mapped to
`DefaultDriverOption.CONNECTION_MAX_REQUESTS`, `CqlSessionBuilder.kt:96`) —
`localRequestsPerConnection` is never read anywhere in the codebase. Setting it to any value has no
effect on driver behavior. This is the same failure class `ISS-048` fixed for a different config
surface (a config value silently discarded rather than applied) — a user tuning this value for a
connection-pool-sizing exercise (exactly the kind of thing done ahead of a load test) gets no error and
no effect, and would have no way to notice short of reading the driver's actual applied config.

## Suggested fix direction

Either wire it into the driver config (if it's meant to distinguish local vs remote per-connection
request limits, matching the driver's own `CONNECTION_MAX_REQUESTS_REMOTE`/local distinction) or remove
the dead field from `PoolConfig` so it can't be mistaken for a working knob.

**Files:** `kandra-ktor/src/main/kotlin/io/kandra/ktor/KandraConfig.kt`,
`kandra-ktor/src/main/kotlin/io/kandra/ktor/CqlSessionBuilder.kt`.

## Resolution

Fixed in PR #75. Confirmed against the DataStax driver (4.17.0) that no
`CONNECTION_MAX_REQUESTS_REMOTE`/local distinction actually exists for this driver version — the driver
has exactly one per-connection request-limit option (`CONNECTION_MAX_REQUESTS`, already mapped from
`maxRequestsPerConnection`); the driver's only local/remote split is for connection *pool size*
(`CONNECTION_POOL_LOCAL_SIZE`/`CONNECTION_POOL_REMOTE_SIZE`), not per-connection request count. Since
there was no faithful way to wire `localRequestsPerConnection` to real driver behavior, it was removed
from `PoolConfig` entirely (per the issue's second suggested option) rather than left as config that
silently does nothing. The matching line was also dropped from the `pool { }` example in
`docs/USER_GUIDE.md`.
