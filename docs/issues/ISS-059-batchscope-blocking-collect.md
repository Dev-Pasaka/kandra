# ISS-059: `KandraBatchScope`'s statement collection still blocks the coroutine dispatcher on prepared-statement cache misses (ISS-049 leftover gap)

**Status:** Fixed (GH #60)

## Problem

Filed as GH #60.

Filed from a pre-cluster-testing deep review, confirming a gap `ISS-049` explicitly flagged as
out-of-scope at the time.

`BatchEngine.collectSave`/`collectDelete` (`BatchEngine.kt:475-497`) are plain (non-suspend) functions
that build statements via `StatementBuilder`'s **blocking** `insertPrimary`/`insertLookup`/
`deleteById`/`deleteLookup` methods (`prepare()`, not `prepareSuspend()`). They are called from
`KandraBatchScope.saveInBatch`/`deleteInBatch` — also plain, non-suspend functions
(`KandraBatchScope.kt`) — which are in turn called from inside
`KandraRuntime.batch(block: suspend KandraBatchScope.() -> Unit) { }`, i.e. from what is, at the call
site, genuinely suspend-context user code (e.g. a Ktor request coroutine).

Note: the final batch **commit** is correctly suspend-safe — `KandraBatchScope.executeSuspend()`
(`KandraBatchScope.kt:92`) routes through `BatchEngine.executeBatchScopeSuspend`
(`BatchEngine.kt:518`), which uses `session.executeSuspend`/async prepare correctly per the ISS-049
fix. Only the statement-*collection* step (`saveInBatch`/`deleteInBatch`, called once per entity added
to the batch before commit) still blocks.

**Impact:** the first `saveInBatch`/`deleteInBatch` call for a given entity type inside `batch { }`
(prepared-statement cache miss on that CQL string, or any cache eviction under table/query churn)
blocks the calling coroutine's dispatcher thread for a full network round-trip to prepare the
statement — the exact ISS-049/GH#27 hazard, on a path ISS-049 itself deliberately left unfixed. On a
limited dispatcher (e.g. Ktor's default request dispatcher, or any dispatcher shared with other
request-handling coroutines), this can stall unrelated coroutines sharing the same thread pool. This
API is currently `@ExperimentalKandraApi`-gated, but is otherwise user-facing and documented.

## Suggested fix direction

Add `collectSaveSuspend`/`collectDeleteSuspend` using `StatementBuilder`'s suspend prepare methods
(`insertPrimarySuspend`, `insertLookupSuspend`, `deleteByIdSuspend`, `deleteLookupSuspend` — all added
by ISS-049) and route `KandraBatchScope.saveInBatch`/`deleteInBatch` through them when called from the
suspend `batch { }` variant, mirroring the pattern ISS-049 already established for every other
suspend write path. If `KandraBatchScope` also exposes a blocking `batchBlocking { }` variant sharing
the same scope type, keep the existing blocking methods for that entry point (same dual-API pattern
`StatementBuilder` itself uses).

**Files:** `kandra-runtime/src/main/kotlin/io/kandra/runtime/BatchEngine.kt`,
`kandra-runtime/src/main/kotlin/io/kandra/runtime/KandraBatchScope.kt`.

## Fix

Added `BatchEngine.collectSaveSuspend`/`collectDeleteSuspend`, mirroring `collectSave`/`collectDelete`
but built on `StatementBuilder`'s suspend prepare path (`insertPrimarySuspend`, `insertLookupSuspend`,
`deleteByIdSuspend`, `deleteLookupSuspend`) so a prepared-statement cache miss during collection never
blocks the calling coroutine's dispatcher thread.

`KandraBatchScope.saveInBatch`/`deleteInBatch` on `KandraSuspendRepository` are now themselves declared
`suspend` and call the new suspend collectors. This is the API-shape change the original report
anticipated: since these are `suspend` functions, Kotlin now enforces at compile time that they can
only be called from `KandraRuntime.batch`'s suspend block — reaching for them inside
`KandraRuntime.batchBlocking`'s plain `() -> Unit` block is a compile error, not a runtime foot-gun. The
`KandraRepository` (blocking) overloads of `saveInBatch`/`deleteInBatch` are unchanged — still
non-suspend, still backed by the original blocking `collectSave`/`collectDelete` — and remain the only
pair usable from `batchBlocking { }`. No behavior changed for either entry point; only the suspend path's
internal prepare mechanism did.

Verified with `KandraBatchScopeSafetyTest`, using the existing `PrepareCallTrackingSession` fake
(blocking `CqlSession.prepare()` throws an `AssertionError`, `prepareAsync()` succeeds normally): the
suspend `batch { }` path collects `saveInBatch`/`deleteInBatch` statements purely through `prepareAsync`
on a cold cache, with zero calls to the blocking `prepare()`; a control test confirms `batchBlocking { }`
still uses the blocking path as intended (unchanged).
