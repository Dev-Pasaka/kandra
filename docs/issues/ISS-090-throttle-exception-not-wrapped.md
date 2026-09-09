# ISS-090: Backpressure throttle rejections leak as an unwrapped driver exception instead of a Kandra exception type

**Status:** Open

## Problem

Filed as GH #103.

When the new backpressure throttle (#81/ISS-073, `throttle.enabled=true`) rejects a request because `maxQueueSize` is exceeded, the DataStax driver throws `com.datastax.oss.driver.api.core.RequestThrottlingException`. `RequestConfig.retryOn` defaults to `{WriteTimeoutException, ReadTimeoutException, NoNodeAvailableException}` — `RequestThrottlingException` is not in it, so `BatchEngine`'s `executeWithRetry`/`executeWithRetrySuspend`/`executeOnce` take the "immediate non-retryable" branch and `throw e` as-is, never wrapped into `KandraQueryException` (or any `Kandra*Exception`).

Every other Kandra error path is wrapped: SSL failures surface as `KandraAuthException`, connection failures and retry exhaustion as `KandraQueryException`, optimistic-lock conflicts as `KandraOptimisticLockException`. This one isn't. A caller that only catches Kandra's documented exception types (a reasonable assumption, since that's the library's stated contract) will not catch a throttling rejection at all — it leaks as an undocumented DataStax driver type.

Separately, enabling `speculativeExecution` alongside `throttle` means each speculative retry counts as an additional request toward `maxConcurrentRequests`, so turning both on can cause self-inflicted throttling under tail latency — worth documenting as an interaction caveat even if not itself a code bug.

## Impact

High. A legitimate backpressure event (exactly the scenario #81 exists to handle gracefully) will crash any caller written against Kandra's documented exception hierarchy, defeating the purpose of adding graceful backpressure in the first place.

## Suggested fix

Catch `RequestThrottlingException` explicitly (independent of `retryOn`, since it's a rejection, not a transient network fault — retrying it immediately is also questionable) and wrap it into a purpose-built exception (e.g. `KandraThrottledException`, or a documented case of `KandraQueryException`) so it participates in the same catch-and-handle story as everything else. Document the speculative-execution/throttle interaction.

## Files

`kandra-runtime/src/main/kotlin/io/kandra/runtime/BatchEngine.kt`, `kandra-ktor/src/main/kotlin/io/kandra/ktor/CqlSessionBuilder.kt`

Filed from a critical post-fix audit (2026-09-09) ahead of experimental multi-DC cluster testing, re-reviewing the brand-new #81 fix.
