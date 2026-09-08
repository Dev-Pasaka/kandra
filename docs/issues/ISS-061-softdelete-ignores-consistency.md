# ISS-061: Soft-delete writes bypass the configured consistency level

**Status:** Fixed

## Problem

Filed as GH #62.

Filed from a pre-cluster-testing deep review. `softDeleteBlocking`/`softDeleteSuspend`
(`BatchEngine.kt:685-746`) hand-build their `UPDATE ... USING TTL ...` (data-clearing) and marker-column
`UPDATE` statements via raw `session.prepare(cql).bind(...)`/`session.prepareSuspend(cql).bind(...)` and
pass the result straight to `executeWithRetry`/`executeWithRetrySuspend` — neither statement ever has
`.setConsistencyLevel(...)` called on it, independent of and in addition to `ISS-053`'s batch-consistency
gap (these aren't batched at all).

**Impact:** any `@SoftDelete` entity's `delete()` call always executes at the DataStax driver's default
consistency (`LOCAL_ONE`), ignoring `@WriteConsistency`/`consistency { defaultWrite = ... }`
configuration — the same class of silent downgrade as `ISS-053`, on a code path outside that issue's
scope (soft-delete builds CQL directly rather than going through `StatementBuilder`).

## Suggested fix direction

Call `.setConsistencyLevel(resolveWriteConsistency(schema, consistency).toDriverLevel())` on both
bound statements in `softDeleteBlocking`/`softDeleteSuspend`, matching the pattern used everywhere else
`StatementBuilder`-adjacent code executes a statement. Add a regression test asserting a soft-delete's
statements carry the configured write consistency.

**Files:** `kandra-runtime/src/main/kotlin/io/kandra/runtime/BatchEngine.kt`.

## Fix

`softDeleteBlocking`/`softDeleteSuspend` now resolve `statementBuilder.resolveWriteConsistency(schema,
null)` once and call `.setConsistencyLevel(...)` on both the data-clearing and marker-column bound
statements, matching the pattern used everywhere else `StatementBuilder`-adjacent code executes a
statement. `delete()` (the caller) has no `consistency` override parameter of its own today, so this
resolves to the configured default (`@WriteConsistency`/`consistency { defaultWrite = ... }`) — the
same scope `newLoggedBatch`'s no-override call sites already had before per-call overrides existed.

Verified: `BatchEngineWriteSafetyTest` asserts a soft-delete's statements carry the configured write
consistency (via `ScriptedCqlSession.lastBoundConsistencyLevel`).
