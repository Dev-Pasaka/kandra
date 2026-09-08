# ISS-075: Strict Mode warns on LOCAL_ONE/ONE but never checks RF vs (R+W) directly

**Status:** Open

## Problem

Filed as GH #83.

Filed from a critical library-wide review (security/performance/consistency/scalability/developer
experience) done ahead of experimental multi-cluster DC testing.

`ConsistencyConfig` (`kandra-runtime/src/main/kotlin/io/kandra/runtime/ConsistencyConfig.kt`)
defaults to `defaultRead = LOCAL_ONE`, `defaultWrite = LOCAL_QUORUM`. Read-your-writes requires
`R + W ≥ RF`; these defaults only satisfy that for `RF ≤ 3`. This is already honestly documented in
the class KDoc (`ISS-069`/GH #70, item 3) — but Strict Mode (`ISS-037`/GH #5), the library's only
runtime guard here, only fires a WARN when a query resolves to `LOCAL_ONE`/`ONE` in a multi-DC
topology. It never checks the actual RF of the target keyspace/table against the configured R+W.

Net effect: a team running `RF=5` (plausible for a larger multi-DC deployment) on the library's own
defaults gets **no warning at all** — Strict Mode only complains about consistency-level choice,
not about whether that choice is safe for the cluster's actual replication factor. This passes
every test against an RF=1 Testcontainers setup and would only surface as a mystery stale-read bug
under a real RF=5 production topology — exactly the scenario the upcoming multi-DC testing is
meant to catch before it reaches production.

## Suggested fix direction

Extend Strict Mode (or add a separate opt-in startup/runtime check) to read the target table's
actual replication factor (available via `session.getMetadata().getKeyspace(...)`) and warn (or,
in a stricter mode, throw) when the resolved `defaultRead`/`defaultWrite` (or per-call override)
doesn't satisfy `R + W ≥ RF`. This is materially more useful than the current LOCAL_ONE/ONE-only
heuristic, since it directly targets the actual correctness property (read-your-writes) rather than
a proxy for it.

**Files:** `kandra-runtime/src/main/kotlin/io/kandra/runtime/ConsistencyConfig.kt`,
`kandra-runtime/src/main/kotlin/io/kandra/runtime/StatementBuilder.kt`.

Related: ISS-069 (item 3), ISS-037 (Strict Mode).
