# ISS-054: Generic `find`/`findAll`/`findPage` reads ignore the configured read consistency level

**Status:** Fixed

## Problem

Filed as GH #55.

Filed from a pre-cluster-testing deep review. `QueryExecutor`'s "direct CQL" fallback branch — used by
`findAll`/`find`/`exists` for any predicate that isn't a lookup-index hit or an `IN`-on-partition-key
query — builds and executes its statement with no consistency level at all:

```kotlin
// QueryExecutor.kt:342+ (resolveRows), confirmed via grep: no .setConsistencyLevel() anywhere
// in this branch or its 422+ (resolveRowsSuspend) suspend twin
val prepared = session.prepare(cql)   // line 417
val rs = session.execute(prepared.bind(*params))
return rs.all()                       // line 419
```

Worse, the method signatures for this path don't accept a `consistency` parameter at all — there is no
way for a caller to override it short of dropping to `raw()`. The same gap exists in `findPage`/
`findPageSuspend`'s primary-table select statement (only the lookup-index *pre-resolution* step, when
one is used, applies `resolveReadConsistency`; the actual page fetch against the primary table does
not).

Only `findById` and the lookup-index / `IN`-on-partition-key paths — which route through
`StatementBuilder`'s `selectById`/`selectByLookup`/`selectByPartitionKeyIn`, all of which correctly
call `.setConsistencyLevel(resolveReadConsistency(schema, consistency).toDriverLevel())` — honor
`@ReadConsistency` / `consistency { defaultRead = ... }`. Any other query shape silently reads at the
DataStax driver's built-in default (`LOCAL_ONE`).

**Impact:** `repo.findAll { GreaterThan("createdAt", ts) }`-style predicate queries and any `findPage`
call read at driver-default consistency regardless of configuration. Combined with
`ISS-053` (writes silently downgraded to `LOCAL_ONE`), a deployment that believes it has configured
`LOCAL_QUORUM` reads-after-`LOCAL_QUORUM`-writes for read-your-writes guarantees gets neither side of
that guarantee for any query that isn't a straight `findById`/lookup/IN lookup — with no error, no
warning, and (currently) no parameter available to fix it per-call.

## Suggested fix direction

- Add a `consistency: KandraConsistency? = null` parameter to `resolveRows`/`resolveRowsSuspend` (and
  thread it through from `findAll`/`find`/`exists`/`findPage`/`findPageSuspend`'s public signatures,
  matching the pattern `findById` already uses) and call
  `.setConsistencyLevel(resolveReadConsistency(schema, consistency).toDriverLevel())` on the bound
  statement at every direct-CQL execution point in this file (lines ~371, ~396, ~417-419, and the
  suspend twins), plus the primary-table select in `findPage`/`findPageSuspend`.
- Add a regression test that configures a non-default `defaultRead` consistency and asserts the actual
  statement executed by a generic `findAll`/`findPage` call carries it — this class of query appears to
  have no consistency-level test coverage today.

**Files:** `kandra-runtime/src/main/kotlin/io/kandra/runtime/QueryExecutor.kt`.

## Fix

`StatementBuilder.resolveReadConsistency` made `internal` (matching `resolveWriteConsistency`'s
ISS-053 treatment) so `QueryExecutor` can call it directly. Added `consistency: KandraConsistency? =
null` to `findAll`/`find`/`exists`/`findPage` and their suspend twins, threaded through
`KandraRepository`/`KandraSuspendRepository`, and into `resolveRows`/`resolveRowsSuspend`. Every
direct-CQL, IN-on-partition-key, and lookup-index execution point in those two functions now calls
`.setConsistencyLevel(...)` with the resolved level, and `findPage`/`findPageSuspend`'s primary-table
paged select does the same (previously only the lookup pre-resolution step, when used, was covered).

Verified: `QueryExecutorConsistencyAndRowCapTest` asserts on the actual consistency level attached to
the executed statement (via a `ScriptedCqlSession` capture mechanism, extended from
`BatchEngineConsistencyPropagationTest`'s ISS-053 approach) for `findAll`/`exists`/`findPage`'s
direct-CQL branch, both blocking and suspend, plus a per-call override test. Confirmed these fail
against the pre-fix code (reverted the direct-CQL `.setConsistencyLevel` call, reran — 3 of 8 failed
as expected) and pass against the fix.
