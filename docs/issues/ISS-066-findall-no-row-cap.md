# ISS-066: `findAll`/`exists`-style reads have no default row cap — unbounded result materialization is a memory-exhaustion vector

**Status:** Fixed

## Problem

Filed as GH #67.

Filed from a pre-cluster-testing deep review. `QueryExecutor.resolveRows`/`resolveRowsSuspend` (the
direct-CQL and lookup/IN branches used by `findAll`/`find`/`exists`) call `.all()` on the result set
with no page-size ceiling and no way to pass one — unlike `findPage`/`findPageSuspend`, which take an
explicit `pageSize`. `.all()` (or `executeSuspendAll`) pulls every matching row into an in-memory
`List` before returning.

**Impact:** any application code path that maps a caller-influenced filter to `findAll`/`find`/`exists`
(a common pattern — e.g. a search/filter endpoint in a web app built on this ORM) can be driven to
materialize an unbounded result set into application heap by a predicate matching a wide partition or
many partitions. This is a plain availability/DoS concern (memory exhaustion, GC pressure, OOM), not
an injection vector — the query itself is safely parameterized — but it's the kind of thing that's easy
to miss until a wide-partition query in production takes down a JVM that a paginated equivalent
wouldn't have.

## Suggested fix direction

Consider a configurable hard cap on non-paged reads (e.g. `KandraConfig`-level
`maxUnpagedResultRows`, defaulting to something conservative, throwing or logging a loud warning if
exceeded), or steer the API surface itself — e.g. deprecate/discourage `findAll` for predicates that
aren't known-bounded (id lookups, small `IN` lists) in favor of requiring `findPage` for anything
filter-driven. At minimum, document the unbounded-materialization behavior prominently next to
`findAll`'s signature, since `findPage`'s existence otherwise implies `findAll` is the "give me
everything, use with caution" escape hatch — which it is, but that isn't stated anywhere obvious today.

**Files:** `kandra-runtime/src/main/kotlin/io/kandra/runtime/QueryExecutor.kt`.

## Fix

Added `QueryExecutor.maxUnpagedResultRows: Int = 10_000` (a constructor parameter, not yet a
plugin-level `KandraConfig` knob — see "Not yet done" below). `resolveRows`'s direct-CQL and IN
branches now collect rows via a new private `boundedAll(rs)` helper that stops iterating once the cap
is hit, logs a loud WARN recommending `findPage()`, and truncates rather than throwing.
`resolveRowsSuspend`'s twins use a new `CqlSession.executeSuspendUpTo(statement, cap)` driver
extension (added alongside the existing `executeSuspendAll`) that stops fetching further pages once
the cap is reached, plus the same truncate-and-warn in a `boundedSuspendAll` helper. The lookup-index
branch (a single primary-table row by full key) and `findPage`/`findPageSuspend` (already explicitly
paged) are unaffected, matching the issue's stated scope.

**Not yet done:** the cap is a hardcoded-default constructor parameter, not exposed as a
`KandraConfig`-level setting from `kandra-ktor` — that module was being edited concurrently by another
fix in this same review batch (GH #58/#61/#65/#69) when this was fixed, and adding a cross-module
config knob risked colliding with that work. Wiring `maxUnpagedResultRows` into `KandraConfig`/
`install(Kandra) { }` (per the issue's suggested fix direction) is a reasonable, low-risk follow-up.

Verified: `QueryExecutorConsistencyAndRowCapTest` proves a query returning more rows than the
configured cap is truncated to exactly the cap (both blocking and suspend), and that a result under
the cap passes through unchanged.
