# ISS-014: `QueryExecutor` was entirely blocking, but is the sole read path for suspend repositories

**Status:** Fixed — pending live-cluster verification (Testcontainers, see [ISS-013](ISS-013-no-integration-tests.md))

## Problem

Found during a pre-real-database-testing audit (2026-07-21). Every method on `QueryExecutor`
(`findById`, `findAll`, `findPage`, `raw`, `rawQuery`, `resolveRows`, …) called `session.execute(...)`
synchronously — there was no suspend variant. `KandraSuspendRepository`'s reads all delegated to
this same blocking executor. Against `FakeKandraSession` this is invisible (fake calls return
instantly), but against a real cluster every `find`/`findById`/`findPage`/`raw` call inside a
coroutine blocks the calling dispatcher thread for the full network round-trip. Under load with
`Dispatchers.IO`/a limited thread pool this starves the pool and causes request pile-ups/timeouts
that only appear under real latency — the exact same class of bug that had already been fixed on
the write path (`BatchEngine`/`KandraSuspendRepository` collection & counter methods switched from
`execute` to `executeSuspend`) but was missed for the entire read path.

## Fix

Added suspend twins for every `QueryExecutor` method (`findByIdSuspend`, `findAllSuspend`,
`findSuspend`, `findPageSuspend`, `existsSuspend`, `rawSuspend`, `rawQuerySuspend`, and a private
`resolveRowsSuspend`), built on the existing `CqlSession.executeSuspend`/`executeSuspendAll`/
`prepareSuspend` extensions (`driver/CqlSessionExtensions.kt`) which use `executeAsync().await()`
under the hood. `KandraSuspendRepository` now calls the suspend variants exclusively; the blocking
`KandraRepository` (sync API) is unchanged and still uses the original blocking methods, which is
correct for that API.

**Files:** `kandra-runtime/.../QueryExecutor.kt`, `repository/KandraSuspendRepository.kt`.
