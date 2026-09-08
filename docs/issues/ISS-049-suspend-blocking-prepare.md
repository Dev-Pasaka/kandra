# ISS-049: Suspend read/write paths still blocked the coroutine dispatcher on prepared-statement cache misses

**Status:** Fixed

## Problem

Filed as GH #27. `StatementBuilder.prepare()` is a private, synchronous function with no suspend
counterpart:

```kotlin
// StatementBuilder.kt (pre-fix)
private fun prepare(cql: String): PreparedStatement {
    if (debugConfig.logQueries) logger.debug { "Kandra CQL: $cql" }
    return cache.getOrPut(cql) { session.prepare(cql) }
}
```

`session.prepare(cql)` is a blocking network round-trip on a cache miss. Every public
`StatementBuilder` method (`insertPrimary`, `insertPrimaryWithNulls`, `insertLookup`, `deleteLookup`,
`selectById`, `selectByLookup`, `selectByPartitionKeyIn`, `deleteById`, `appendToCollection`,
`removeFromCollection`, `counterUpdate`) routed through this one `prepare()`, and the *suspend* call
sites in `QueryExecutor` and `BatchEngine` called these methods as plain (non-suspend) function
calls whose arguments were evaluated eagerly on the calling coroutine before the async execute
happened, e.g.:

```kotlin
// QueryExecutor.kt (pre-fix)
suspend fun <T : Any> findByIdSuspend(...): T? {
    val rs = session.executeSuspend(statementBuilder.selectById(schema, *idValues, consistency = consistency))
    //                               ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ evaluated synchronously first — blocks on cache miss
    ...
}
```

The same pattern recurred in `resolveRowsSuspend`'s lookup and `IN` branches, `findPageSuspend`'s
lookup branch, and throughout `BatchEngine`'s suspend write paths — `saveSuspend`,
`saveIfNotExistsSuspend`, `saveWithNullsSuspend`, `deleteSuspend`, `deleteByIdSuspend`,
`saveAllSuspend`, `updateSuspend`'s non-`@Version` branch, `updateForceSuspend`, and the
`appendSuspend`/`removeSuspend`/`putSuspend`/`incrementSuspend`/`decrementSuspend` counter/collection
methods added in ISS-047 — all called a blocking `StatementBuilder` method synchronously before the
surrounding `executeWithRetrySuspend(...)` call.

Only two things in the runtime already did real async prepare: `resolveRowsSuspend`'s direct-CQL
branch and `findPageSuspend`'s own primary-select branch (both call `session.prepareSuspend(cql)`
directly, bypassing `StatementBuilder`), and the `@Version` LWT update path
(`buildVersionedUpdateStatementSuspend`, whose doc comment explicitly notes it "avoids blocking the
dispatcher"). `softDeleteSuspend` also did its own inline `session.prepareSuspend(cql)`. Everything
else that went through `StatementBuilder` did not get this treatment.

**Impact:** the first `save()`/`findById()`/`delete()`/`append()`/`increment()`/etc. call for a given
entity type (cache miss on that CQL string), or any cache eviction under table/query churn, blocked
the calling coroutine dispatcher thread for a full driver round-trip inside what callers reasonably
assume is a non-blocking suspend function — on a limited dispatcher (e.g. Ktor's default request
dispatcher) this can stall unrelated coroutines sharing the same thread pool.

## Fix

Gave `StatementBuilder` a second, private suspend `prepare`:

```kotlin
private suspend fun prepareSuspend(cql: String): PreparedStatement {
    if (debugConfig.logQueries) logger.debug { "Kandra CQL: $cql" }
    cache[cql]?.let { return it }
    val prepared = session.prepareSuspend(cql)
    return cache.getOrPut(cql) { prepared }
}
```

The cache is checked up front so a hit never suspends at all; on a miss, `session.prepareSuspend`
(`prepareAsync` under the hood) is awaited and the result inserted via `getOrPut`, so two coroutines
racing to prepare the same CQL string still converge on a single cached `PreparedStatement` instance.
The cache itself (keyed by CQL string) is shared unchanged between the blocking and suspend paths —
whichever path populates it first, the other gets a cache hit for the same CQL.

Added a suspend counterpart of every `StatementBuilder` method that suspend call sites use for
statement preparation: `insertPrimarySuspend`, `insertPrimaryWithNullsSuspend`, `insertLookupSuspend`,
`deleteLookupSuspend`, `selectByIdSuspend`, `selectByLookupSuspend`, `selectByPartitionKeyInSuspend`,
`deleteByIdSuspend`, `appendToCollectionSuspend`, `removeFromCollectionSuspend`, and
`counterUpdateSuspend`. Each mirrors its blocking twin's CQL construction, binding, and idempotency/
consistency setup exactly, differing only in calling `prepareSuspend` instead of `prepare`. The
existing blocking API is untouched — blocking callers (`KandraRepository`, `KandraBatchScope`) see no
behavioral or signature change.

Updated every suspend call site to use the new methods instead of the blocking ones:

- `QueryExecutor.findByIdSuspend`, `resolveRowsSuspend`'s lookup branch (both the lookup-table select
  and the resolved primary-table select) and `IN` branch, and `findPageSuspend`'s lookup-resolution
  select. `raw`/`rawQuery`/`rawSuspend`/`rawQuerySuspend`/`rawQuery` were **not** touched — they
  already called `session.prepareSuspend` directly and are owned by a separate, concurrent fix
  (GH #32).
- `BatchEngine.saveSuspend`, `saveIfNotExistsSuspend`, `saveWithNullsSuspend`, `deleteSuspend`,
  `deleteByIdSuspend`, `saveAllSuspend`, `updateSuspend` (its non-`@Version` full-row-overwrite
  branch), `updateForceSuspend`, `updateLookupsSuspend`, `fireEventualSuspend`, and the
  `appendSuspend`/`removeSuspend`/`putSuspend`/`incrementSuspend`/`decrementSuspend` counter/
  collection methods. A new private `buildUpdateStatementsSuspend` mirrors the existing
  `buildUpdateStatements` but builds its lookup delete/insert statements via
  `deleteLookupSuspend`/`insertLookupSuspend`, used by `updateSuspend`, `updateForceSuspend`, and
  `updateLookupsSuspend`.

`KandraRepository`/`KandraSuspendRepository` needed no changes — per ISS-047/ISS-048, their
`append`/`remove`/`put`/`increment`/`decrement`/`deleteById`("not found" branch) call sites already
route through `BatchEngine`'s `*Suspend` methods rather than touching `StatementBuilder` directly, so
fixing those `BatchEngine` methods was sufficient.

Not in scope for this fix (left as-is, tracked separately): `KandraBatchScope`'s `collectSave`/
`collectDelete` (used by `saveInBatch`/`deleteInBatch` inside `KandraRuntime.batch { }`/
`batchBlocking { }`) still call `StatementBuilder`'s blocking methods, and the batch scope's final
commit is already documented (see the `kandra-runtime` skill notes on `KandraBatchScope`) as blocking
the calling coroutine even inside the suspend `batch { }` variant — that's a pre-existing, distinct
gap in the experimental batch-scope API, not part of this issue's scope.

### Folded-in fix: `counterUpdate` overflow on `Long.MIN_VALUE`

While rewriting `counterUpdate` to add its suspend twin, also fixed GH #36 item 3: `counterUpdate`
bound `Math.abs(delta)` as the counter delta magnitude, with the sign baked into the CQL operator
(`+`/`-`). `Math.abs(Long.MIN_VALUE)` overflows back to `Long.MIN_VALUE` itself (two's-complement has
no positive representation for it) — a `decrement(by = Long.MIN_VALUE)` call would silently bind a
negative magnitude to a CQL statement that reads `SET hits = hits - ?`, effectively *increasing* the
counter instead of decreasing it (or vice versa), with no error at all.

Added an explicit guard, shared by both `counterUpdate` and `counterUpdateSuspend`, checked *before*
either statement is prepared (so an invalid delta never wastes a network round-trip, sync or async):

```kotlin
private fun safeAbsoluteDelta(delta: Long, schema: TableSchema, columnName: String): Long {
    if (delta == Long.MIN_VALUE) {
        throw KandraSchemaException(
            "counterUpdate on '${schema.tableName}.$columnName' received delta = Long.MIN_VALUE " +
            "(${Long.MIN_VALUE}), which cannot be negated/absolute-valued without overflowing back " +
            "to itself (two's-complement has no positive counterpart for Long.MIN_VALUE). Use a " +
            "delta in [Long.MIN_VALUE + 1, Long.MAX_VALUE]."
        )
    }
    return Math.abs(delta)
}
```

`Long.MIN_VALUE + 1` (the actual boundary) still works correctly — the guard rejects exactly the one
value `Math.abs` cannot represent, nothing more.

## Test plan

Added three new test files under `kandra-runtime/src/test/kotlin/io/kandra/runtime/`:

- `StatementBuilderSuspendPrepareTest.kt` — exercises every new `StatementBuilder` suspend method
  directly (paired with its blocking counterpart) against a new `PrepareCallTrackingSession` fake
  (added to `FakeDriverSupport.kt`) whose blocking `prepare()` throws an `AssertionError` and whose
  `prepareAsync()` succeeds normally, plus a cache-sharing test proving a second suspend call for an
  identical CQL string is a pure cache hit (no repeated async prepare).
- `BatchEngineSuspendPreparePathTest.kt` — end-to-end coverage proving `BatchEngine`'s suspend write
  paths (`saveSuspend`, `saveIfNotExistsSuspend`, `saveWithNullsSuspend`, `deleteSuspend`,
  `deleteByIdSuspend`, `saveAllSuspend`, `updateSuspend`, `updateForceSuspend`, `appendSuspend`,
  `removeSuspend`, `putSuspend`, `incrementSuspend`, `decrementSuspend`) never call blocking prepare.
- `QueryExecutorSuspendPreparePathTest.kt` — same, for `findByIdSuspend`, `findAllSuspend`/
  `existsSuspend` via a `@LookupIndex` predicate and via an `IN` predicate, and `findPageSuspend` via
  a `@LookupIndex` predicate.

Also added counter-overflow regression tests to `StatementBuilderTest.kt` for both `counterUpdate`
and `counterUpdateSuspend`.

Verified the fix is load-bearing: with the source changes to `StatementBuilder.kt`, `QueryExecutor.kt`,
and `BatchEngine.kt` reverted (tests kept), the new suspend-prepare-path tests fail — the
`StatementBuilder`-level tests fail to compile (the suspend methods they call don't exist pre-fix),
and the 15 end-to-end `BatchEngine`/`QueryExecutor` tests all fail at runtime with the expected
`AssertionError: Blocking CqlSession.prepare("...") was called from what must be a suspend-only code
path`, naming the exact CQL that leaked through. Restoring the fix, all tests pass.

**Files:** `kandra-runtime/.../StatementBuilder.kt`, `kandra-runtime/.../QueryExecutor.kt`,
`kandra-runtime/.../BatchEngine.kt`,
`kandra-runtime/src/test/kotlin/io/kandra/runtime/FakeDriverSupport.kt`,
`kandra-runtime/src/test/kotlin/io/kandra/runtime/FakeEventualCqlSession.kt`,
`kandra-runtime/src/test/kotlin/io/kandra/runtime/repository/KandraRepositoryConfigTest.kt` (added
missing `prepareAsync` overrides to existing test-double `CqlSession` implementations — needed once
suspend paths genuinely call it), plus the three new test files listed above.
