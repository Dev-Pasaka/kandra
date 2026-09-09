# ISS-075: Strict Mode warns on LOCAL_ONE/ONE but never checks RF vs (R+W) directly

**Status:** Fixed (GH #83)

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

## Fix

`StatementBuilder` now reads the resolved table's actual replication factor from live driver metadata
(`session.getMetadata().getKeyspace(session.getKeyspace())`, summing every non-`class` entry of
`KeyspaceMetadata.getReplication()` — exact for `SimpleStrategy`, a documented best-effort proxy for
`NetworkTopologyStrategy` since it sums per-DC factors rather than accounting for `LOCAL_*` levels being
satisfied by one DC's replicas) and, when `ConsistencyConfig.strictMode` is `true`, warns whenever the
resolved read/write consistency pair doesn't satisfy `R + W > RF` — note the **strict** inequality, not
`>=`. Unlike the existing `LOCAL_ONE`/`ONE` check, this new check is intentionally **not** gated on
`multiDcTopology`: an RF > 2 keyspace is not exclusive to multi-DC deployments, so the check fires on a
single-DC cluster too. Never throws, matching the rest of Strict Mode's WARN-only contract.

This also corrected a subtle inaccuracy in `ConsistencyConfig`'s own KDoc (added by ISS-069): it had
claimed the library's defaults (`LOCAL_ONE` read + `LOCAL_QUORUM` write, weight `1 + quorum(RF)`)
guarantee read-your-writes "for RF <= 3" using the non-strict `R + W >= RF`. Cassandra's own documented
guidance is the strict `R + W > RF` — with `R + W == RF`, an unlucky replica placement can still make the
read set and write set fully disjoint (e.g. `RF=3`, a write quorum of 2 lands on replicas `{A, B}`, and a
single-replica read lands on `{C}` — no overlap; only `R + W > RF` guarantees overlap, by pigeonhole).
Under the corrected strict formula, the library's defaults only actually guarantee read-your-writes for
`RF <= 2`, not `RF <= 3` — both `ConsistencyConfig`'s KDoc and this new check now reflect that.

Verified with `StrictModeRfIntegrationTest` (`kandra-test`), using a real single-node Testcontainers
Cassandra cluster with a `SimpleStrategy` keyspace created at a chosen replication factor (only the
keyspace *metadata* needs to reflect the RF — no real multi-replica execution is required, since the
check reads declared RF, not runtime replica placement): the WARN fires for the library's
`LOCAL_ONE`/`LOCAL_QUORUM` defaults at `RF=3`, does **not** fire at `RF=1`, and never fires at all when
`strictMode` is left at its default (`false`), even at `RF=3`.
