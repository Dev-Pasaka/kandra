# ISS-083: Lookup-index reads and versioned-update lookup-table writes both bypass configured consistency

**Status:** Open

## Problem

Filed as GH #96.

Two independent, confirmed gaps in the same area, found and cross-validated by two separate reviewers during a post-fix audit:

**1. Read side — `StatementBuilder.selectByLookup`/`selectByLookupSuspend` never apply consistency.** (`kandra-runtime/src/main/kotlin/io/kandra/runtime/StatementBuilder.kt`) The bound statement is returned with `.setIdempotent(true)` but no `.setConsistencyLevel(...)` call anywhere. Every `find`/`findAll`/`findPage` that resolves via a `@LookupIndex` predicate calls this for the first hop (lookup-table to primary key), then correctly applies consistency on the second hop (`selectById`). The lookup-table hop — the one most likely to be stale, since `LookupConsistency.EVENTUAL` lookups are written asynchronously — always runs at the raw driver-default consistency, ignoring the per-call override, `@ReadConsistency`, and `consistency { defaultRead }`.

**2. Write side — `BatchEngine.updateLookups`/`updateLookupsSuspend` never apply write consistency.** (`kandra-runtime/src/main/kotlin/io/kandra/runtime/BatchEngine.kt`) These build their batch directly via `BatchStatement.newInstance(DefaultBatchType.LOGGED)` and call `executeWithRetry(batch)`/`executeWithRetrySuspend(batch)` with no consistency ever set. Every other write batch in this file is built via `newLoggedBatch(schema, consistency)`, which resolves and applies write consistency (and triggers the Strict Mode RF check). `updateLookups`/`updateLookupsSuspend` — invoked after every successful `@Version`-checked `update()` to sync lookup-table rows — bypass that resolution entirely. This is the exact defect class already fixed for soft-delete writes (#62/ISS-061) and batch/versioned-update writes (#54/ISS-053), left unaddressed here, and it also completely bypasses Strict Mode's RF-vs-consistency check (#83) since `resolveWriteConsistency` is never invoked.

Net effect: for any `@Version` + `@LookupIndex` entity, both the lookup-table write on every `update()` and the lookup-table read on every lookup-based query run at driver-default consistency instead of the app's configured/overridden consistency — undercutting exactly the RF>3 read-your-writes guidance `ConsistencyConfig`'s own KDoc already flags as a known risk area (#70/ISS-069 item 3).

## Impact

Critical. A team that raises `defaultRead`/`defaultWrite` specifically to preserve read-your-writes on a higher-RF multi-DC cluster still gets both halves of every lookup-index round trip served at whatever the bare driver profile default is.

## Suggested fix

- `selectByLookup`/`selectByLookupSuspend`: call `.setConsistencyLevel(resolveReadConsistency(...).toDriverLevel())`, same as `selectById`.
- `updateLookups`/`updateLookupsSuspend`: route through `newLoggedBatch(schema, consistency)` (thread `consistency` through from `update`/`updateSuspend`, which already has it in scope but currently doesn't pass it down).

## Files

`kandra-runtime/src/main/kotlin/io/kandra/runtime/StatementBuilder.kt`, `kandra-runtime/src/main/kotlin/io/kandra/runtime/BatchEngine.kt`

Filed from a critical post-fix audit (2026-09-09) ahead of experimental multi-DC cluster testing. Related: #54 (ISS-053), #62 (ISS-061), #83 (ISS-075) — same defect class, different call sites.
