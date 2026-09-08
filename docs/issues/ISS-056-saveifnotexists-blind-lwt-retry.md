# ISS-056: `saveIfNotExists`/`saveIfNotExistsSuspend` blindly retry their `IF NOT EXISTS` LWT, risking a false negative on the caller's own successful write

**Status:** Fixed

## Problem

Filed as GH #57.

Filed from a pre-cluster-testing deep review. `saveIfNotExists`/`saveIfNotExistsSuspend`
(`BatchEngine.kt:261-277`, `:542-...`) build the `INSERT ... IF NOT EXISTS` LWT statement and execute
it via `executeWithRetry`/`executeWithRetrySuspend`:

```kotlin
val primaryStmt = statementBuilder.insertPrimary(schema, stamped, ifNotExists = true)
    .setSerialConsistencyLevel(DefaultConsistencyLevel.valueOf(serialConsistency.name))
val rs = executeWithRetry(primaryStmt, schema.tableName, "saveIfNotExists")   // NOT executeOnce
val applied = rs.one()?.getBoolean("[applied]") ?: false
if (!applied) return false
```

This is the exact hazard `executeOnce` was introduced to prevent for the `@Version` LWT update path
(see its doc comment at `BatchEngine.kt:187-199`): a blind retry of a conditional statement risks
observing the client's own prior (successful) attempt as a conflict. That reasoning was applied to
`update()`'s versioned branch but not extended to `saveIfNotExists`, which uses the same LWT
(`IF ...`) mechanism and the same ambiguous-timeout hazard.

**Concrete failure scenario:** the `INSERT ... IF NOT EXISTS` applies server-side (row created), but
the client observes a `WriteTimeoutException` before the ack arrives. `executeWithRetry` retries the
identical statement; the second attempt sees the row already exists (from its own first attempt) and
returns `[applied] = false`. `saveIfNotExists` returns `false` to the caller — reporting failure/
collision for a write the caller's own call actually created. In a "claim this username" or
"acquire this lock row" flow, this produces a false "already taken" result for the caller's own
successful claim.

## Suggested fix direction

Route `saveIfNotExists`/`saveIfNotExistsSuspend`'s primary LWT statement through `executeOnce`/
`executeOnceSuspend` instead of `executeWithRetry`/`executeWithRetrySuspend`, mirroring `update()`'s
`@Version` branch — propagate a genuine transient exception to the caller as-is rather than masking it
as a false "not applied" result. (The batch-lookup insert that follows a successful primary insert,
lines 271-274, is a separate, already-idempotent-by-construction step and is not part of this issue —
though see `ISS-053` regarding its missing consistency level.)

Related root cause to `ISS-055` (retry loop ignoring idempotency more generally) but a distinct code
path and fix, since this one requires switching the entire retry strategy rather than gating on
`isIdempotent()`.

**Files:** `kandra-runtime/src/main/kotlin/io/kandra/runtime/BatchEngine.kt`.

## Fix

`saveIfNotExists`/`saveIfNotExistsSuspend`'s primary LWT insert now routes through
`executeOnce`/`executeOnceSuspend` exactly as suggested, mirroring `update()`'s `@Version` branch. A
genuine transient exception now propagates to the caller as-is instead of being retried into a false
`applied = false`.

Verified: `BatchEngineWriteSafetyTest` reproduces the exact scenario — a `WriteTimeoutException` on
the first attempt (server actually applied the insert) followed by a scripted `applied = false` on a
would-be second attempt — and asserts the `WriteTimeoutException` propagates with exactly one
`session.execute` call (never reaching the scripted false-applied outcome). Also covers the genuine
`applied = false` case (still correctly returns `false`, no exception) for both blocking and suspend.
