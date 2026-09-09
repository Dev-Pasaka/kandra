# ISS-074: `KandraMetrics.record()` is only ever called on the success path

**Status:** Fixed (GH #82)

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

## Fix

`KandraMetrics` gained two additions, both backward-compatible (default bodies, so it's still a valid
`fun interface` with `record(tableName, operation, durationMs)` as its sole abstract member and every
existing implementation — including bare SAM lambdas — keeps compiling unchanged):

- `record(tableName, operation, durationMs, attempts)` — an overload of the existing success callback
  that also reports how many attempts the query took (a query that only succeeded on its 3rd attempt
  still round-tripped 3 times). Defaults to delegating to the original 3-arg `record`.
- `recordFailure(tableName, operation, durationMs, attempts, exceptionType)` — new no-op-by-default
  callback for a query that ultimately failed with no successful result: retry exhaustion, an immediate
  non-retryable exception (wrong type for `retryOn`, or a non-idempotent statement), or rejection because
  Kandra is shutting down.

`BatchEngine`'s `executeWithRetry`/`executeWithRetrySuspend`/`executeOnce`/`executeOnceSuspend` now call
`recordFailure` from every failure exit: the two immediate-throw branches inside the retry loop (wrong
exception type, non-idempotent statement), the loop-exhaustion branch (`attempts` = `maxAttempts`,
`exceptionType` from the last observed error), the `executeOnce`/`executeOnceSuspend` catch block
(`attempts = 1`), and a new `checkNotShuttingDown(tableName, operation)` overload used at all four call
sites for the shutdown-rejection case (`attempts = 0`, `durationMs = 0`, since it fails before any attempt
or timing starts). The success path now calls the new 4-arg `record` overload with the actual attempt
count instead of the old 3-arg call.

Verified with `BatchEngineMetricsFailureTest`: `recordFailure` fires with the correct table/operation/
duration/attempts/exceptionType on retry exhaustion, on an immediate non-retryable exception type, on a
non-idempotent statement, and on a shutdown-rejection (zero attempts, zero duration) — and does **not**
fire when a query eventually succeeds, while `record`'s attempt count correctly reflects a multi-attempt
success.
