# ISS-081: findActive()/findActiveSuspend() have no row cap — OOM risk on an ALLOW FILTERING scan

**Status:** Open

## Problem

Filed as GH #94.

`QueryExecutor.findActive`/`findActiveSuspend` (`kandra-runtime/src/main/kotlin/io/kandra/runtime/QueryExecutor.kt`) use `rs.all()` / `session.executeSuspendAll(...)` — the unbounded page-walkers — instead of `boundedAll`/`boundedSuspendAll`, the row-capped helpers every other unpaged read path (`findAll`/`find`/`exists`) was routed through by #67/ISS-066.

This is exactly the read path most likely to trigger the failure: when the soft-delete marker has no `@SecondaryIndex`, `findActive()` requires (and warns that it performs) `ALLOW FILTERING` — a scatter-gather scan across all nodes. An `ALLOW FILTERING` scan with no row cap is a direct path to OOMing the JVM on any table with a non-trivial number of active rows.

ISS-066's own fix writeup explicitly scopes itself away from `findActive` (only mentions the lookup branch and `findPage`), so this was never an oversight that got caught — it's a gap that survived the original fix.

## Impact

Critical for any deployment with soft-delete tables of meaningful size — an unbounded `ALLOW FILTERING` scan is one of the most common ways to bring down a JVM against a real (non-toy) Cassandra/Scylla table.

## Suggested fix

Route `findActive`/`findActiveSuspend` through `boundedAll`/`boundedSuspendAll` (or `executeSuspendUpTo`), same as every other unpaged branch.

## Files

`kandra-runtime/src/main/kotlin/io/kandra/runtime/QueryExecutor.kt`

Filed from a critical post-fix audit (2026-09-09) ahead of experimental multi-DC cluster testing. Related: #67 (ISS-066, the fix this gap escaped).
