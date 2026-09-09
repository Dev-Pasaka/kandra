# ISS-085: Strict Mode's RF-vs-consistency math is wrong for multi-DC NetworkTopologyStrategy, causing false-positive warnings

**Status:** Open

## Problem

Filed as GH #98.

Strict Mode's RF-vs-consistency check (added by #83/ISS-075) is `StatementBuilder.replicationFactorOrNull()` (`kandra-runtime/src/main/kotlin/io/kandra/runtime/StatementBuilder.kt`). It sums *all* per-DC replication-factor entries as "RF," then compares against `quorumWeight(level, thatSum)`. For `LOCAL_*` levels — which are satisfied within a single DC — this is the wrong RF to use, and the code's own doc comment already acknowledges it "overstates the RF that actually matters for a LOCAL_* consistency level."

Worked example: a 3-DC keyspace with RF=1 per DC (sum=3) — a perfectly safe, deliberate low-latency configuration — using Kandra's own defaults (`LOCAL_ONE` read + `LOCAL_QUORUM` write). Real per-DC math: R=1, W=1 (quorum of 1 replica is 1), R+W=2 > RF(1) → safe, no warning warranted. The code's sum-based math: `quorumWeight(LOCAL_QUORUM, 3) = 2`, R+W = 1+2 = 3, not > 3 → fires a **false-positive WARN** on a cluster that is actually fine.

This is exactly backwards from what Strict Mode is meant to do for the multi-DC cluster this review is gating, and is likely to produce log spam / alert fatigue that desensitizes operators to genuine warnings (compounded by the check firing unconditionally on every call, with no dedup).

`StrictModeRfIntegrationTest` only exercises `SimpleStrategy` keyspaces (RF=1, RF=3) via `KandraTestcontainers.freshKeyspace(replicationFactor=N)`. There is no test against a `NetworkTopologyStrategy`/multi-DC keyspace, so this exact false-positive behavior — in the feature's actual target scenario — is not caught by the new tests.

## Impact

High. Strict Mode is the tool meant to validate the multi-DC consistency story before/during the upcoming experimental test; as written it will likely misfire on a correctly-configured multi-DC keyspace, undermining confidence in its output right when it matters most.

## Suggested fix

Either divide the summed RF by DC count as an approximation for `LOCAL_*` levels, or (better) read the per-DC replication map directly and use the local DC's own factor when the resolved level is `LOCAL_*`, falling back to the cluster-wide sum only for global levels (`QUORUM`, `ALL`, `EACH_QUORUM`). Add a `NetworkTopologyStrategy` test case to `StrictModeRfIntegrationTest`.

## Files

`kandra-runtime/src/main/kotlin/io/kandra/runtime/StatementBuilder.kt`, `kandra-test/src/test/kotlin/io/kandra/test/StrictModeRfIntegrationTest.kt`

Filed from a critical post-fix audit (2026-09-09) ahead of experimental multi-DC cluster testing, re-reviewing the brand-new #83 fix.
