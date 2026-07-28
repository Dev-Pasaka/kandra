# ISS-047: Several write paths bypassed `BatchEngine`'s shutdown gate, retry, and in-flight tracking

**Status:** Fixed

## Category

Bug.

## Problem

Filed as GH #29. `BatchEngine` centralizes three safety mechanisms behind
`executeWithRetry`/`executeWithRetrySuspend`: the `checkNotShuttingDown()` gate, `inFlightCount`
tracking (used by graceful-shutdown drain), and retry-on-transient-error per `RetryConfig`. Four
write paths bypassed all three by calling `session.execute`/`session.executeSuspend` directly:

1. Repository counter/collection methods (`append`, `remove`, `put`, `increment`, `decrement`) on
   both `KandraRepository` and `KandraSuspendRepository`.
2. `deleteById`'s "not found" branch — the found-entity branch correctly routed through
   `batchEngine.delete()`, but the not-found branch built and executed the statement itself.
3. `KandraBatchScope.execute()`, called from `KandraRuntime.batch()` — a `suspend fun` per its public
   signature that awaited nothing async and blocked the calling coroutine's thread for the entire
   batch write.
4. `BatchEngine.updateForce`'s eventual lookup writes, fired inline via `scope.launch { session.execute(...) }`
   with failures only logged, never forwarded to `eventListener.onEventualWriteFailed` like every other
   eventual-write path.

A counter `increment()`/`decrement()` or collection `append()`/`remove()`/`put()` called during the
graceful-shutdown drain window was not rejected and not retried on a transient error, despite
`RetryConfig` being configured for exactly that case elsewhere in the same module. `KandraRuntime.batch { }`
silently defeated the coroutine-friendly contract the rest of the suspend API upholds.

## Fix

Added matching `append`/`remove`/`put`/`increment`/`decrement`/`deleteById` methods on `BatchEngine`
itself (blocking + `*Suspend` variants), each building its statement and executing it through
`executeWithRetry`/`executeWithRetrySuspend` — the repositories now call these instead of touching
`session.execute`/`session.executeSuspend` directly.

`KandraBatchScope` split into a blocking `execute()` (used by `batchBlocking`) and a genuinely suspend
`executeSuspend()` (used by `batch()`, since it's already `suspend`), both delegating to new
`BatchEngine.executeBatchScope`/`executeBatchScopeSuspend` — the suspend path now uses
`session.executeSuspend` instead of blocking the calling coroutine's thread, and both are
shutdown-gated and retried. `KandraBatchScope` no longer needs a `CqlSession` reference at all.

`BatchEngine.updateForce`'s eventual writes now route through the existing `fireEventualStatements`
helper (the same one `update()` already used) instead of an inline `scope.launch { session.execute(...) }`.

**Additional bug found and fixed in the same pass:** `updateSuspend`'s and `updateLookupsSuspend`'s
eventual-write branches had the identical bypass (raw `session.executeSuspend(...)`, no retry, no
listener forwarding) even though the blocking `update()`/`updateLookups()` were already fixed under
[ISS-033](ISS-033-eventual-lookup-bypasses-safeguards.md). Added `fireEventualStatementsSuspend`
mirroring the blocking helper and routed both suspend paths, plus `updateForceSuspend`, through it.

Added 31 new tests across three new files covering shutdown-gate rejection, retry-on-transient-error,
`inFlightCount` tracking, and `eventListener.onEventualWriteFailed` forwarding for all fixed paths.

## Files

- `kandra-runtime/src/main/kotlin/io/kandra/runtime/BatchEngine.kt`
- `kandra-runtime/src/main/kotlin/io/kandra/runtime/KandraBatchScope.kt`
- `kandra-runtime/src/main/kotlin/io/kandra/runtime/KandraRuntime.kt`
- `kandra-runtime/src/main/kotlin/io/kandra/runtime/repository/KandraRepository.kt`
- `kandra-runtime/src/main/kotlin/io/kandra/runtime/repository/KandraSuspendRepository.kt`
- `kandra-runtime/src/test/kotlin/io/kandra/runtime/BatchEngineCollectionCounterTest.kt` (new)
- `kandra-runtime/src/test/kotlin/io/kandra/runtime/BatchEngineUpdateForceEventualTest.kt` (new)
- `kandra-runtime/src/test/kotlin/io/kandra/runtime/KandraBatchScopeSafetyTest.kt` (new)
