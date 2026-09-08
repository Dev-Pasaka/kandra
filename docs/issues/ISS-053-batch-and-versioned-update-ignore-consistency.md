# ISS-053: `LOGGED BATCH` writes and `@Version` LWT updates ignore the configured consistency level entirely

**Status:** Fixed

## Problem

Filed as GH #54.

Filed from a pre-cluster-testing deep review. Every batched write path in `BatchEngine.kt` builds a
`BatchStatement.newInstance(DefaultBatchType.LOGGED)` and folds pre-built `BoundStatement`s into it —
but never calls `.setConsistencyLevel()` on the resulting `BatchStatement`:

```kotlin
// BatchEngine.kt — save(), saveIfNotExists(), saveWithNulls(), update() (non-versioned branch),
// updateForce(), saveAll(), executeBatchScope(), and every *Suspend twin — 18 call sites total
BatchStatement.newInstance(DefaultBatchType.LOGGED).add(statementBuilder.insertPrimary(schema, stamped))
```

The CQL native protocol carries **one** consistency level per batch on the wire — the
`.setConsistencyLevel(resolveWriteConsistency(...).toDriverLevel())` calls that `StatementBuilder`
correctly makes on each individual `BoundStatement` (`StatementBuilder.kt:194,248,304,350,536,...`)
are discarded the moment that statement is `.add()`-ed into a `BatchStatement` — the driver uses the
batch-level consistency (unset here) for execution, not any consistency level attached to the
statements inside it.

Separately, but with the identical symptom: the `@Version` LWT update path
(`buildVersionedUpdateStatement`/`buildVersionedUpdateStatementSuspend`, `BatchEngine.kt:754-823`)
sets `.setSerialConsistencyLevel(DefaultConsistencyLevel.LOCAL_SERIAL)` for the LWT condition, but
never calls `.setConsistencyLevel(...)` for the statement's regular (non-serial) consistency either —
and `update(schema, old, new)`'s public signature doesn't even accept a `consistency` parameter, so
there's no way for a caller to influence it.

Confirmed via `grep`: `CqlSessionBuilder.kt` also never sets a driver-config default
(`DefaultDriverOption.REQUEST_CONSISTENCY` is never touched), so every one of these statements
silently executes at the DataStax Java driver's hardcoded built-in default, `LOCAL_ONE`.

**Impact:** a table configured with `@WriteConsistency` / `consistency { defaultWrite = LOCAL_QUORUM }`
for durability reasons — the normal choice for a multi-DC deployment — has every `save()`, `update()`
(on any `@Version` entity), `saveIfNotExists()`, `saveWithNulls()`, `updateForce()`, and `saveAll()`
call actually commit at `LOCAL_ONE` instead, with zero error, warning, or documentation gap
acknowledging it. A write can be lost on a single-replica failure immediately after commit, while the
application (and its `@ScyllaTable`/config annotations) both believe it has quorum-durable writes.

This also silently defeats **ISS-037's Consistency Strict Mode**: Strict Mode warns based on the
*resolved* consistency level computed from config, but that resolved level is exactly the one being
thrown away here — a deployment relying on Strict Mode to catch accidental `LOCAL_ONE`/`ONE` usage in
a multi-DC topology gets no warning at all for this class of write, because the code path that would
trigger the warning (`resolveWriteConsistency` in `StatementBuilder`) runs, computes `LOCAL_QUORUM`
correctly, and then the value is discarded before the driver ever sees it.

Only the non-batched write paths that call a `StatementBuilder` method directly and pass the resulting
single `BoundStatement` straight to `execute()`/`executeSuspend()` (not through a `BatchStatement`)
correctly honor configured consistency.

## Suggested fix direction

- Compute the resolved consistency level once per batch (all statements in a given `BatchEngine` batch
  share the same schema/table today) and call `.setConsistencyLevel(...)` on the `BatchStatement`
  itself before execution, at every one of the ~18 call sites in `BatchEngine.kt` that build a
  `LOGGED` batch.
- Add a `consistency: KandraConsistency?` parameter to `buildVersionedUpdateStatement`/
  `buildVersionedUpdateStatementSuspend` (and thread it through `update`/`updateSuspend`'s public
  signature, matching `save`'s existing `consistency` parameter) and call
  `.setConsistencyLevel(resolveWriteConsistency(schema, consistency).toDriverLevel())` alongside the
  existing `.setSerialConsistencyLevel(...)` call.
- Add a regression test asserting the actual `BatchStatement`/`BoundStatement` passed to
  `session.execute(...)` carries the configured (non-default) consistency level for each of the
  affected call sites — the existing test suite apparently never asserted on this, since it went
  unnoticed through 52 prior issue fixes.

**Files:** `kandra-runtime/src/main/kotlin/io/kandra/runtime/BatchEngine.kt`.

## Fix

Added `BatchEngine.newLoggedBatch(schema, consistency)`, which resolves the write consistency via
`StatementBuilder.resolveWriteConsistency` (made `internal` for this) and calls
`.setConsistencyLevel(...)` on the `BatchStatement` before any statement is `.add()`-ed to it —
replacing all ~18 bare `BatchStatement.newInstance(DefaultBatchType.LOGGED)` call sites in
`BatchEngine.kt` (`save`, `saveWithNulls`, `update`'s non-versioned branch, `updateForce`, `saveAll`,
`executeBatchScope`/`executeBatchScopeSuspend`, and every `*Suspend` twin). `saveWithNulls`, `update`,
`updateForce`, and `saveAll` (blocking and suspend) all gained a `consistency: KandraConsistency? = null`
parameter, threaded through `KandraRepository`/`KandraSuspendRepository`, matching `save`'s existing
parameter.

For the `@Version` LWT path, `buildVersionedUpdateStatement`/`buildVersionedUpdateStatementSuspend`
now also call `.setConsistencyLevel(resolveWriteConsistency(schema, consistency).toDriverLevel())`
on the bound statement, alongside the existing `.setSerialConsistencyLevel(LOCAL_SERIAL)` — so the
LWT condition and the statement's regular consistency are both configured correctly.

Verified: `BatchEngineConsistencyPropagationTest` asserts on the actual `BatchStatement`/
`BoundStatement` consistency level passed to `session.execute(...)` (via a `ScriptedCqlSession`
enhancement that captures `setConsistencyLevel` calls, since the driver carries no other way to
introspect a bound statement's consistency outside driver internals) — covering `save`, `update`
(both branches), `saveAll`, and per-call overrides. Confirmed the tests fail against the pre-fix code
(reverted `newLoggedBatch`'s `.setConsistencyLevel` call, reran — 5 of 8 tests failed as expected;
restored and reran — all pass).
