# ISS-073: No backpressure/admission-control knob for in-flight requests

**Status:** Fixed

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

## Fix

Added a `throttle { }` config block (`ThrottleConfig`, on `KandraConfig`) — concurrency-limit style,
the simplest and most broadly useful of the driver's own throttler options:

- `enabled: Boolean = false`
- `maxConcurrentRequests: Int = 10_000`
- `maxQueueSize: Int = 10_000`

Off by default: the driver's own default throttler (`PassThroughRequestThrottler`) never queues or
rejects, matching what every existing Kandra deployment already runs under, so an unconfigured
`throttle { }` block changes nothing. When `enabled = true`, `CqlSessionBuilder.buildDriverConfig`
wires `DefaultDriverOption.REQUEST_THROTTLER_CLASS` to the driver's
`ConcurrencyLimitingRequestThrottler`, plus `REQUEST_THROTTLER_MAX_CONCURRENT_REQUESTS` and
`REQUEST_THROTTLER_MAX_QUEUE_SIZE` from the configured values. Requests beyond the queue size are
rejected immediately by the driver with `RequestThrottlingException` rather than queued indefinitely.

Did not touch `BatchEngine.inFlightCount` or `BatchEngine.kt` — per the task scope, that's Kandra's
own separate in-flight tracker used purely for graceful-shutdown draining, and this fix only wires
the driver-level (session/connection) admission control, which is the layer the driver's native
throttler actually operates at. `ThrottleConfig`'s KDoc calls out explicitly that the two are
unrelated, to head off confusion between them in review.

**Rate-limiting throttler** (the driver's other built-in option,
`RateLimitingRequestThrottler`/`max-requests-per-second`) was not exposed — concurrency-limiting is
the more directly useful admission-control knob for the stated goal (bounding in-flight requests
under a slow/overloaded cluster) and keeps the config surface to two numbers instead of three. Can be
added as a follow-up `ThrottleConfig` variant/mode if a rate-based ceiling is specifically needed
later.

## Tests

`kandra-ktor/src/test/kotlin/io/kandra/ktor/CqlSessionBuilderTest.kt` (new):

- `buildDriverConfig` leaves `REQUEST_THROTTLER_CLASS` at the driver's own default
  (`PassThroughRequestThrottler`) when `throttle { }` is untouched.
- `buildDriverConfig` wires `ConcurrencyLimitingRequestThrottler` with the configured
  `maxConcurrentRequests`/`maxQueueSize` when `throttle.enabled = true`.

`./gradlew :kandra-ktor:test --no-daemon` and the full `./gradlew test --no-daemon` both pass.
