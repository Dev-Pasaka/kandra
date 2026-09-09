# ISS-078: DDL bootstrap claim's completion/release writes have no holder fencing; clock-skew staleness check has a local-clock fallback bug

**Status:** Open

## Problem

Filed as GH #91.

Two related correctness gaps in `claimAndRunDdlBootstrap` (`kandra-ktor/src/main/kotlin/io/kandra/ktor/Kandra.kt`, mirrored in `kandra-migrate`'s own copy):

**1. No holder fencing on `markDdlLockDone`/`releaseDdlLock`.** `reclaimStaleDdlLock` correctly uses a CAS (`IF claimed_at = ?`) so only one waiter can win a reclaim. But once a claim resolves, `markDdlLockDone` (`UPDATE ... SET status = 'DONE' WHERE lock_name = ?`) and `releaseDdlLock` (`DELETE ... WHERE lock_name = ?`) are unconditional — neither checks `IF holder = ?`.

Concrete failure: Instance A claims `schema-bootstrap` and runs a long `AUTO_MIGRATE` pass across many entities — genuinely plausible to exceed the fixed 2-minute `DDL_CLAIM_STALE_THRESHOLD` under real multi-DC latency (this is one claim for the entire registered-entity pass, and the threshold is not configurable). While A is still alive and running, instance B's waiter judges the claim stale, wins the CAS reclaim (A hasn't touched `claimed_at`), and B now also starts running the same DDL — concurrently with A, exactly the schema-disagreement risk #79 exists to prevent. When A finishes, its unconditional `markDdlLockDone` clobbers B's active claim; if A's action throws instead, its unconditional `releaseDdlLock` deletes the row out from under B, letting a third instance start racing again while B is still running.

**2. Clock-skew-safe staleness check silently falls back to the local JVM clock.** `ddlLockServerNow` reads `toTimestamp(now())` from the same row (`WHERE lock_name = ?`) whose existence is exactly what's in question. If that row was deleted (e.g. by finding #1's unconditional `releaseDdlLock`) or hasn't been read consistently, it falls back to `Instant.now()` — precisely the thing the entire design (explicitly documented as mirroring #64's clock-skew-proof approach) exists to avoid. A staleness comparison made against a skewed local clock in that fallback window can misjudge a fresh claim as stale (premature reclaim) or vice versa.

Neither path is exercised by `DdlBootstrapClaimTest` — its scenarios are "many racers hit a never-claimed lock" or "one racer's claim is already dead before anyone looks," never "reclaim while the original claimant is still alive and later completes."

## Impact

High. Both gaps are more likely to trigger under real multi-DC latency (the exact conditions the upcoming experimental test is meant to validate) than in the CI environment that validated the #79 fix.

## Suggested fix

- Add `IF holder = ?` to both `markDdlLockDone` and `releaseDdlLock`, binding the caller's own `holder` UUID; treat a failed CAS (lost ownership mid-run) as its own error condition rather than silently no-oping.
- Have `ddlLockServerNow` query server time from a row/table guaranteed to always exist (e.g. `system.local`), not from the specific lock row.
- Consider periodically renewing `claimed_at` (a heartbeat) during a long-running `action()`, or make the staleness threshold configurable and size it for real multi-DC DDL latency.

## Files

`kandra-ktor/src/main/kotlin/io/kandra/ktor/Kandra.kt`, `kandra-migrate/src/main/kotlin/io/kandra/migrate/KandraMigrationRunner.kt`

Filed from a critical post-fix audit (2026-09-09) ahead of experimental multi-DC cluster testing, re-reviewing the brand-new #79 fix. Related to the previous issue (same mechanism, different bug).
