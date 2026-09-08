# ISS-073: No backpressure/admission-control knob for in-flight requests

**Status:** Open

## Problem

Filed as GH #81.

Filed from a critical library-wide review (security/performance/consistency/scalability/developer
experience) done ahead of experimental multi-cluster DC testing.

`BatchEngine.inFlightCount` (`kandra-runtime/src/main/kotlin/io/kandra/runtime/BatchEngine.kt:80`)
tracks currently-executing queries, but only for graceful-shutdown draining — nothing caps it.
There is no request-throttler / max-concurrent-in-flight configuration anywhere in `KandraConfig`.

Under a slow cluster (node pressure, a partial multi-DC outage, compaction storms) combined with
bursty application-side concurrency, this means unbounded concurrent `session.execute`/
`executeSuspend` calls can queue up with no admission control, risking client-side memory pressure
and making a cluster-side slowdown worse rather than shedding load gracefully.

The DataStax driver has native support for this via `advanced.throttler.*` (concurrency-limiting or
rate-limiting throttler), which Kandra never configures.

**Impact:** this is specifically a concern once real multi-DC cluster load testing starts — a
controlled backpressure mechanism is what prevents a transient cluster-side slowdown from turning
into a client-side cascading failure.

## Suggested fix direction

Add a `throttle { }` config block (concurrency-limit and/or rate-limit style, mirroring the
driver's own throttler options) wired to `DefaultDriverOption.REQUEST_THROTTLER_CLASS` and friends
in `CqlSessionBuilder.buildDriverConfig`.

**Files:** `kandra-ktor/src/main/kotlin/io/kandra/ktor/KandraConfig.kt`,
`kandra-ktor/src/main/kotlin/io/kandra/ktor/CqlSessionBuilder.kt`,
`kandra-runtime/src/main/kotlin/io/kandra/runtime/BatchEngine.kt`.
