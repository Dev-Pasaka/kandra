# ISS-055: `BatchEngine`'s retry loop ignores statement idempotency — non-idempotent writes can double-apply on retry

**Status:** Fixed

## Problem

Filed as GH #56.

Filed from a pre-cluster-testing deep review; independently identified by three separate review passes
(data-integrity, performance, and distributed-systems), then confirmed directly against source.

`StatementBuilder` deliberately calls `.setIdempotent(false)` on every statement type that is *not*
safe to execute twice: plain `INSERT` (`insertPrimary`, `StatementBuilder.kt:193`), lookup `INSERT`
(`:375,395`), collection `append`/`remove`/`put` (`:535,558,580,603`), and counter
`increment`/`decrement` (`:645,669`) — specifically so that a retry can't double-apply them.

But `BatchEngine.executeWithRetry`/`executeWithRetrySuspend` (`BatchEngine.kt:123-166`) decide whether
to retry **purely by exception class**, never consulting `statement.isIdempotent()`:

```kotlin
} catch (e: Throwable) {
    if (retryConfig.retryOn.none { it.isInstance(e) }) throw e   // no isIdempotent() check
    lastError = e
    val backoff = minOf(retryConfig.backoffMillis * (attempt + 1), retryConfig.maxBackoffMillis)
    logger.warn { "Retrying after ${e::class.simpleName} ..." }
    Thread.sleep(backoff)   // delay(backoff) in the suspend twin
}
```

`WriteTimeoutException` is in `RetryConfig`'s default `retryOn` set. Every non-idempotent statement
type above is routed straight through this retry wrapper (`BatchEngine.kt:389,394,398,403,408,413,
417,422,426,431` for append/remove/put/increment/decrement; `:257` for `save()`'s batch, which
includes lookup inserts).

This is a regression introduced by the ISS-047 fix, which routed `append`/`remove`/`put`/`increment`/
`decrement` through `executeWithRetry` "so they get the same safety net as every other write" without
extending the reasoning `executeOnce` was built for (see `executeOnce`'s doc comment,
`BatchEngine.kt:187-199`, written for exactly this hazard on the `@Version` LWT path) to these other
non-idempotent statement types.

**Concrete failure scenario:** `increment(schema, "hits", key, by = 1)` triggers a
`WriteTimeoutException` where the coordinator actually applied the write to a quorum of replicas before
the client's timeout fired — a routine outcome under load or a GC pause on a live cluster, and
essentially unreproducible against `FakeKandraSession` or a quiet single-node Testcontainers instance
(which is exactly why this survived 52 prior issue-fixing passes). `executeWithRetry` sees
`WriteTimeoutException` matches `retryOn` and blindly resends — the counter is now incremented twice,
silently, with the caller observing a successful call and no error. The same mechanism can double-apply
a collection `append`, or double-insert a lookup row via `save()`'s batch retry.

## Suggested fix direction

`executeWithRetry`/`executeWithRetrySuspend` should not blindly retry a `WriteTimeoutException` (or any
exception with genuinely ambiguous server-side outcome) when `statement is BoundStatement &&
!statement.isIdempotent` (or `is BatchStatement` where any constituent statement is non-idempotent) —
either surface the exception to the caller as-is (mirroring `executeOnce`'s existing approach for the
`@Version` path) or require the caller to explicitly opt into retrying a non-idempotent op. Read
timeouts and `NoNodeAvailableException` (no write attempted) remain safe to retry regardless.

Add a regression test using a fake session that returns success-after-timeout semantics (the write
"actually landed" server-side despite the client seeing a timeout) for `increment`/`append`/
`saveIfNotExists`, asserting the operation is not silently re-applied.

See also `ISS-056` (a related but distinct manifestation of the same root cause — blind retry of an
LWT `IF NOT EXISTS` insert).

**Files:** `kandra-runtime/src/main/kotlin/io/kandra/runtime/BatchEngine.kt`.

## Fix

Both `executeWithRetry` and `executeWithRetrySuspend` now check `statement.isIdempotent() != true`
after the exception-class check and, if true, rethrow immediately instead of retrying. This covers
both cases the issue called out without needing to special-case `BatchStatement`/constituent
statements: a lone `BoundStatement` (append/remove/put/increment/decrement, all explicitly marked
`.setIdempotent(false)` by `StatementBuilder`) is caught directly, and a `BatchStatement` (`save()`,
`updateForce()`, etc.) is caught too, because Kandra never explicitly marks a `LOGGED BATCH`
idempotent — its `isIdempotent()` defaults to `null`, which also fails the `!= true` check. This is a
conservative default (every current `LOGGED BATCH` genuinely is composed of non-idempotent statements
today), but note for future work: if a genuinely all-idempotent batch is ever built, it won't retry
either unless something explicitly calls `.setIdempotent(true)` on the batch itself.

Verified: `BatchEngineTest`'s two existing retry tests (`retries a retryable failure...`, `gives up
after maxAttempts...`) were rewritten to drive `deleteById` (a lone, genuinely idempotent statement)
instead of `save()`, since `save()`'s batch must now correctly *not* retry — added a new test
(`never retries a non-idempotent statement...`) asserting exactly that. The same false-assumption
existed in `BatchEngineCollectionCounterTest` (`increment`/`append` "retries" tests — rewritten to
"never retries"), `BatchEngineEventualWriteTest` and `BatchEngineUpdateForceEventualTest` (EVENTUAL
lookup-insert "retries" tests — rewritten; the failure is now reported to the event listener on the
first attempt instead of being silently retried away), and `KandraBatchScopeSafetyTest` (caller-batch
commit "retries" tests — rewritten). Confirmed all of these fail against the pre-fix code and pass
against the fix.
