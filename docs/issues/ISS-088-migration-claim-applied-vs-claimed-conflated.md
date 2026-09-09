# ISS-088: Migration claim resolution conflates losing a race to an APPLIED row with losing to a CLAIMED row

**Status:** Open

## Problem

Filed as GH #101.

`KandraMigrationRunner.run()` (`kandra-migrate/src/main/kotlin/io/kandra/migrate/KandraMigrationRunner.kt`) does:

```kotlin
val lostRace = claim(migration)
if (lostRace != null) {
    handleUnresolvedClaim(lostRace, migration)   // no check on lostRace.status here
    return
}
```

`claim()`'s "lost race" return value can carry either `status = CLAIMED` (genuinely in-progress or crashed elsewhere) **or** `status = APPLIED` (the other instance already fully finished) — the LWT failure just returns whatever row is there now. `handleUnresolvedClaim` never inspects `row.status`; it unconditionally computes `age = Duration.between(row.claimedAt ?: row.appliedAt, serverNow(...))` and either warns+halts or throws, based purely on age since `claimed_at` — a field `markApplied()` never clears even after success.

Concrete failure: instance A claims migration v5, applies it in under a second, marks it `APPLIED`. Instance B's `loadApplied()` snapshot (taken moments earlier, or delayed by real cross-DC replication lag if reads use a non-linearizable consistency level) doesn't yet see v5's row, so B calls `claim(v5)`. The LWT correctly detects the existing row (Paxos/SERIAL is cluster-wide, so this part is safe) and returns it with `status = APPLIED`. B passes this into `handleUnresolvedClaim`, which computes `age` from the original `claimed_at` — if v5 was applied even slightly more than `staleClaimThreshold` (default 10 min) ago, **B throws `KandraMigrationException`, reporting a fully successful migration as an abandoned crashed claim requiring manual operator intervention.** Since `run()` is normally called synchronously during app boot, this can fail startup outright. In the fast/simultaneous case it merely causes a spurious WARN+halt of the rest of that `run()` call.

No test covers "lost the race against an already-APPLIED row" — `KandraMigrationClaimConcurrencyTest`'s `SlowMigration` is specifically designed (via its sleep) to keep every racer inside the CLAIMED window, so this path is untested.

## Impact

High. A pre-existing bug (ISS-018/ISS-043/ISS-063's territory) that's more likely to surface under real multi-DC replication lag than in a single-node CI test environment, and can fail application startup for a fully successful migration.

## Suggested fix

In the `claim()`-lost-race branch, check `lostRace.status` first: if `APPLIED`, verify checksum and `continue` exactly like the pre-existing-row branch does; only call `handleUnresolvedClaim` when `lostRace.status == CLAIMED`.

## Files

`kandra-migrate/src/main/kotlin/io/kandra/migrate/KandraMigrationRunner.kt`

Filed from a critical post-fix audit (2026-09-09) ahead of experimental multi-DC cluster testing.
