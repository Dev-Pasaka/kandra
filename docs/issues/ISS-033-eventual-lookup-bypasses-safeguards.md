# ISS-033: `EVENTUAL` lookup writes bypassed retry, `inFlightCount`, and the shutdown gate

**Status:** Fixed

## Problem

`BatchEngine.fireEventual()`, `fireEventualSuspend()`, and `fireEventualStatements()` — which fire
`LookupConsistency.EVENTUAL` lookup writes asynchronously after a `save()`/`update()` batch commits —
called `session.execute(...)`/`session.executeSuspend(...)` directly inside `scope.launch { ... }`,
bypassing everything `executeWithRetry()`/`executeWithRetrySuspend()` normally provide to every other
write path:

1. **No retry** on transient errors — exactly the class of error `RetryConfig` exists for.
2. **Not counted in `inFlightCount`** — so `Kandra.kt`'s graceful-shutdown handler, which busy-waits
   on `runtime.inFlightCount` before closing the session, could complete while an `EVENTUAL` lookup
   write was still in flight.
3. **Not gated by `isShuttingDown`** — new `EVENTUAL` writes fired via `scope.launch` after
   `isShuttingDown` was set were not rejected the way `executeWithRetry` rejects new synchronous
   queries.

Concrete failure: `save()` on an entity with an `EVENTUAL` `@LookupIndex`, called right as
`ApplicationStopping` fired, could have its lookup write execute against an already-`session.close()`'d
`CqlSession`. It was caught by `runCatching` and reported via `eventListener.onEventualWriteFailed()`
if registered, so not a silent black hole — but graceful shutdown didn't actually drain this class of
write, and the docs didn't disclose the gap.

## Fix

Routed `fireEventual()`, `fireEventualSuspend()`, and `fireEventualStatements()` through
`executeWithRetry()`/`executeWithRetrySuspend()` instead of calling
`session.execute(...)`/`session.executeSuspend(...)` directly, so `EVENTUAL` lookup writes now
inherit: retry-on-transient-error per `RetryConfig.retryOn`, `inFlightCount` tracking, and the
`checkNotShuttingDown()` gate — the same guarantees every other write path already has. This makes
"eventual" writes slightly less pure-fire-and-forget (they now retry and participate in shutdown
draining) but closes the correctness gap; that tradeoff is intentional. `fireEventualStatements` also
gained a `tableName` parameter (passed by its two callers, `update()`'s and `updateLookups()`'s lookup
diffing) so retried eventual updates get proper per-table slow-query logging/metrics like every other
write.

Updated the README's Lookup Tables and Graceful Shutdown sections, `docs/USER_GUIDE.md`'s
`@LookupTable`/Graceful Shutdown sections, `docs/features/operations.md`, and
`docs/features/core-annotations.md` to state the new guarantee. Updated the `kandra-runtime` skill doc
to match.

Added `kandra-runtime/src/test/kotlin/io/kandra/runtime/BatchEngineEventualWriteTest.kt` (backed by a
purpose-built fake `CqlSession`, `FakeEventualCqlSession.kt`) proving: a transient error is retried and
the write eventually succeeds; `inFlightCount` increments while the write is executing and decrements
after; and a write attempted after `isShuttingDown` is set is rejected with the same
`KandraQueryException` a synchronous query would throw, without ever calling the driver.

**File:** `kandra-runtime/.../BatchEngine.kt`.
