# ISS-063: Migration claim staleness is computed from wall-clock timestamps across potentially skewed app instances

**Status:** Fixed

## Problem

Filed as GH #64.

Filed from a pre-cluster-testing deep review. `KandraMigrationRunner`'s distributed-lock mechanism
itself is sound — `claim()` (`KandraMigrationRunner.kt:213-239`) uses a real `INSERT ... IF NOT EXISTS`
LWT, so two instances racing to claim the same migration correctly resolve to exactly one winner
(verified: no TOCTOU gap). This issue is narrower: the **staleness check** used to decide whether a
`CLAIMED`-but-not-`APPLIED` row represents a still-running migration or a crashed one compares
timestamps written by two different machines' clocks:

```kotlin
// KandraMigrationRunner.kt:151-152 (handleUnresolvedClaim)
val claimedAt = row.claimedAt ?: row.appliedAt
val age = Duration.between(claimedAt, Instant.now())   // claimedAt: written by the claiming
                                                          // instance; Instant.now(): read by
                                                          // whichever instance is checking
```

`claimedAt` is persisted via `Instant.now()` on the claiming instance at `:223`; `age` is computed
against `Instant.now()` on whatever instance later calls `run()` and finds the row still `CLAIMED`.
`Duration.between` on two independently-generated wall clocks is only as accurate as the clock sync
between those two machines. NTP drift of a few seconds-to-low-tens-of-seconds is normal in real
fleets, and `staleClaimThreshold` defaults to 10 minutes, so this needs a meaningfully skewed clock to
matter — but it's exactly the class of bug invisible on a single dev machine (one clock, zero skew) and
only reachable on a real multi-instance deployment, which is what the upcoming cluster testing is for.

**Impact:** if the claiming instance's clock is far enough ahead of the checking instance's, `age`
comes out smaller than real elapsed time (`Duration` also permits a negative result if the checking
instance's clock is behind) — a genuinely crashed migration can be perpetually treated as "still within
`staleClaimThreshold`," permanently blocking `run()` (it halts before applying later migrations, per
line 96-98's early return) with only a `WARN` log and no escalation path. Conversely, a checking
instance with a fast clock relative to the claimant could prematurely throw `KandraMigrationException`
on a migration that is still legitimately in progress, aborting deployment.

## Fix

Checked whether Kandra's driver/session already exposed a server-side timestamp query surface (e.g.
in `StatementBuilder.kt`) — it didn't, but the underlying DataStax/Scylla driver supports CQL's
`now()`/`toTimestamp(now())` functions out of the box, evaluated by whichever coordinator processes
the statement, needing no session/API changes. Went with the DB-timestamp fix over "widen the
threshold and document the assumption" since it removes the hazard outright rather than mitigating it.

Both sides of the staleness comparison now come from the ScyllaDB/Cassandra cluster's own clock,
never from an application instance's `Instant.now()`:

- `claim()` assigns `claimed_at` via `toTimestamp(now())` written directly into the INSERT's CQL text
  (evaluated by the coordinator handling that LWT), not bound as an app-generated `Instant`.
- A new private `serverNow(version)` helper reads "now" the same way, via a fresh
  `SELECT toTimestamp(now())` against the row being checked.
- `handleUnresolvedClaim()` now computes `age` as `Duration.between(claimedAt, serverNow(...))` —
  both timestamps sourced from the DB cluster, whether `claimedAt` was written by this instance or a
  different one, and regardless of which instance later checks it.

This eliminates the app-instance-clock-sync dependency entirely rather than merely narrowing it. The
remaining assumption — that nodes *within one* Scylla/Cassandra cluster have reasonably synced clocks
with each other — is far narrower than "every application instance is NTP-synced with every other,"
and is already an existing operational precondition for correct LWT/Paxos behavior in these systems.
Documented this explicitly in `KandraMigrationRunner`'s class KDoc ("Clock source for staleness").

`claim()`'s `IF NOT EXISTS` LWT mechanism itself was not touched — it was already sound.

## Tests

Added to `kandra-migrate/src/test/kotlin/io/kandra/migrate/KandraMigrationRunnerTest.kt`, run against
a real Cassandra instance via `KandraTestcontainers` (Docker available in this environment):

- `claim() writes claimed_at via the cluster's own clock, not this JVM's Instant now` — reads back a
  freshly-claimed row's `claimed_at` alongside an independently-issued `toTimestamp(now())` query,
  asserting they're close — proving `claimed_at` is DB-clock-sourced, not JVM-clock-sourced.
- `a claim that becomes stale purely through real elapsed time is detected via the cluster clock` — a
  crashed claim, a real `Thread.sleep` past a small threshold, and a fresh check correctly throws —
  end to end through real elapsed wall time, not a forged timestamp.
- `a claim that is not yet stale by real elapsed time is treated as still in-progress` — a crashed
  claim checked immediately (no sleep) is correctly treated as fresh, not stale.

All existing claim/skip/checksum-mismatch/crash-safety/legacy-row tests in that file continue to pass
unmodified.

```
./gradlew :kandra-migrate:test --no-daemon
BUILD SUCCESSFUL
11 tests, 0 failures (KandraMigrationRunnerTest)
```

## Files

`kandra-migrate/src/main/kotlin/io/kandra/migrate/KandraMigrationRunner.kt`,
`kandra-migrate/src/test/kotlin/io/kandra/migrate/KandraMigrationRunnerTest.kt`.
