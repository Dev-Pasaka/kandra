# ISS-086: Suspend/blocking batch-collection split is bypassable by mixing repository types in one batch{} block

**Status:** Open

## Problem

Filed as GH #99.

The suspend/blocking batch-collection split added by #60/ISS-059 makes `KandraSuspendRepository<T>.saveInBatch`/`deleteInBatch` genuine `suspend` functions routed through the new `collectSaveSuspend`/`collectDeleteSuspend` — good, this closes the case the fix targeted. But `KandraRepository<T>.saveInBatch`/`deleteInBatch` (the blocking repository type, backed by the original blocking `collectSave`/`collectDelete`/`prepare()`) are still plain, non-suspend functions.

Kotlin allows calling any non-suspend function from inside a suspend lambda, so nothing stops mixed usage like:

```kotlin
val blockingRepo = runtime.repository<User>()
val suspendRepo  = runtime.suspendRepository<Wallet>()
runtime.batch {                       // suspend block
    blockingRepo.saveInBatch(user)    // still resolves to the BLOCKING collectSave/prepare()
    suspendRepo.saveInBatch(wallet)   // suspend-safe
}
```

This compiles and runs, and `blockingRepo.saveInBatch` still calls the blocking `CqlSession.prepare()` on a cache miss — blocking the calling coroutine's dispatcher thread, exactly the problem #60 exists to prevent, just reached through the other repository type. `KandraBatchScopeSafetyTest` never exercises this mixed case — every test uses either all-suspend-repo or all-blocking-repo statements within a given batch, never both together inside `runtime.batch { }`.

## Impact

High. The fix closes the common case but leaves an easy-to-hit escape hatch: any codebase with both repository types available (which is normal — most apps use `KandraRepository` for some entities and `KandraSuspendRepository` for others) can silently reintroduce the dispatcher-blocking bug inside a suspend `batch { }` block.

## Suggested fix

Either make the blocking `saveInBatch`/`deleteInBatch` extensions only resolvable from `batchBlocking`'s non-suspend receiver type (e.g. a distinct scope class per entry point instead of one `KandraBatchScope` with both overload sets), or have `KandraRuntime.batch` detect/reject a blocking-repo-originated statement at runtime.

## Files

`kandra-runtime/src/main/kotlin/io/kandra/runtime/KandraBatchScope.kt`

Filed from a critical post-fix audit (2026-09-09) ahead of experimental multi-DC cluster testing, re-reviewing the brand-new #60 fix.
