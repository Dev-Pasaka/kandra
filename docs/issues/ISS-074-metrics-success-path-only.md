# ISS-074: `KandraMetrics.record()` is only ever called on the success path

**Status:** Open

## Problem

Filed as GH #82.

Filed from a critical library-wide review (security/performance/consistency/scalability/developer
experience) done ahead of experimental multi-cluster DC testing.

`BatchEngine.kt`'s `executeWithRetry`/`executeWithRetrySuspend`/`executeOnce`/`executeOnceSuspend`
(around lines 160, 200, 244, 266) all call `metricsRecorder?.record(tableName, operation, elapsed)`
only inside the success branch, immediately after `session.execute(...)`/`session.executeSuspend(...)`
returns. When the retry loop exhausts (`KandraQueryException("Query failed after N attempts", ...)`),
when a non-retryable exception is thrown immediately, or when a query is rejected because
`isShuttingDown` is set, `record()` is never called.

`KandraMetrics` (`kandra-core/src/main/kotlin/io/kandra/core/KandraMetrics.kt`) is the library's
only pluggable hook for bridging into an external metrics backend (Micrometer, Dropwizard, etc.).
As currently wired, anyone using it for dashboards/alerts/SLOs gets latency-of-successful-calls
only — no failure rate, no retry count, no visibility into timeouts or shutdown-rejections per
table/operation.

**Impact:** this is a real observability blind spot for anyone running Kandra against a real
cluster where transient failures and retries are expected and need to be monitored — exactly the
situation multi-DC testing is meant to validate.

## Suggested fix direction

Extend `KandraMetrics` (or add a second callback) to also report failures, e.g.
`recordFailure(tableName, operation, durationMs, attempts, exceptionType)`, and call it from the
`catch` paths in `executeWithRetry`/`executeWithRetrySuspend` when the loop exhausts or a
non-retryable exception propagates. Consider also recording retry-attempt counts on the success
path (a query that succeeded on attempt 3 still round-tripped 3 times).

**Files:** `kandra-runtime/src/main/kotlin/io/kandra/runtime/BatchEngine.kt`,
`kandra-core/src/main/kotlin/io/kandra/core/KandraMetrics.kt`.
