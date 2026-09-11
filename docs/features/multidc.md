# Multi-DC (`kandra-multidc`)

Configures per-DC consistency levels and local-DC routing policies for multi-region deployments.

## Strict Mode — warn on `LOCAL_ONE`/`ONE` in a multi-DC deployment (GH #5)

`kandra-runtime` and `kandra-multidc` are separate modules with no dependency between them, and
`KandraMultiDc.describe()` is purely a startup-logging string builder — it isn't in the runtime
read/write path and isn't involved in this feature. The multi-DC topology signal Kandra actually uses
for this is core config that already exists for failover: `KandraConfig.loadBalancing.allowedRemoteDcs`
(non-empty implies a multi-DC deployment).

Enable it via the `consistency { }` DSL block, alongside whatever `loadBalancing.allowedRemoteDcs` a
multi-DC deployment already sets for failover:

```kotlin
install(Kandra) {
    consistency {
        strictMode = true // opt-in, default false
    }
    loadBalancing {
        allowedRemoteDcs = listOf("eu-west")
    }
}
```

Behavior:

- **Opt-in, default `false`** — no behavior change unless explicitly enabled.
- **WARN-only, never throws** — logged unconditionally (every matching call, no "warn once" tracking,
  matching the existing `findActive()`-style warning precedent in `QueryExecutor`), never blocks or
  fails the query.
- **Fires when**: `consistency.strictMode == true`, `loadBalancing.allowedRemoteDcs` is non-empty
  (auto-detected — not a separate flag), and a query's resolved consistency (after per-call override →
  `@ReadConsistency`/`@WriteConsistency` → `consistency { defaultRead/defaultWrite }`) is `LOCAL_ONE` or
  `ONE`. `LOCAL_QUORUM` (the usual multi-DC default), `QUORUM`, `EACH_QUORUM`, `ALL`, and every other
  level never trigger it.

See [`ConsistencyConfig`](../USER_GUIDE.md#strict-mode-multi-dc-local_oneone-warning) in the User Guide
for the full consistency-resolution example.

## Testing against a real multi-DC topology

`kandra-test`'s `KandraMultiDcTestcontainers` (GH #84 / ISS-076) spins up a real, two-datacenter,
one-node-per-DC Cassandra cluster via Testcontainers' `ComposeContainer` — `dc1`/`dc2`, plain
`cassandra:4.1`, `GossipingPropertyFileSnitch`, gossiped into one cluster — for tests that need
genuine `NetworkTopologyStrategy` replication and real DC-aware failover, not a single-node stand-in.
It follows `KandraTestcontainers`'s lazy-singleton convention (one topology per JVM) and adds
`pause`/`unpause` helpers (via the Docker API) to simulate a DC going unreachable mid-test — `pause`
freezes the container's userspace via the cgroup freezer (established TCP connections stay live, no
RST/ICMP produced), closer to "the node hung" than a real severed network link; see
`KandraMultiDcTestcontainers.pause`'s KDoc (GH #108 / ISS-095) for the full explanation. Host ports
are chosen dynamically per run (GH #108 / ISS-095) rather than hardcoded, so this doesn't collide with
a local Cassandra/Scylla instance or a concurrent run of the same fixture. See
`kandra-multidc/src/test/kotlin/io/kandra/multidc/MultiDcFailoverTest.kt` for real
`dcAwareFailover`/`FailoverPolicy` and Strict Mode tests built on it, and
`KandraMultiDcTestcontainers`'s own KDoc for why the topology is scoped to one node per DC. This
suite is slower than a typical unit-test run (real two-node gossip convergence), so it's tagged out
of the default `test` task — run it explicitly with `./gradlew :kandra-multidc:multiDcTest`.

### CI coverage (GH #97 / ISS-084)

This suite and `kandra-ktor`'s equivalent `sslIntegrationTest` (a real TLS round trip against a
Testcontainers Cassandra instance) both need Docker and take several minutes, so neither runs as part
of `ci.yml`'s fast `test` job on every push. Instead, `.github/workflows/multidc.yml` runs both
(`./gradlew :kandra-multidc:multiDcTest :kandra-ktor:sslIntegrationTest`) nightly, on demand via
`workflow_dispatch`, and on any push/PR that touches `kandra-multidc/**`, `kandra-ktor/**`,
`kandra-test/**`, or the workflow file itself — so a change to this surface gets real-cluster coverage
before merge, not just at the next nightly run. `ubuntu-latest` GitHub Actions runners ship Docker
preinstalled, so no extra runner setup is needed. No manual pre-release step is required for this
suite specifically; the workflow's `schedule`/`workflow_dispatch` triggers exist for cases (dependency
bumps, base-image drift) that don't show up as a diff against these paths.

### Known gap: not yet validated against a genuine multi-region topology

Every multi-DC test this project has run so far — `KandraMultiDcTestcontainers` above, and the
manual test rounds in `docs/test-plan/` — has been an approximation, not a real multi-region
deployment:

- `KandraMultiDcTestcontainers` runs both "DCs" as containers on **one Docker host**: real
  `NetworkTopologyStrategy` gossip/replication, but no real inter-region network (latency, jitter,
  packet loss) and no real geographic separation. `pause`'s own KDoc already flags this for the
  node-failure simulation specifically (GH #108); the same caveat applies to the topology as a whole.
- The v2.0 test round (`docs/test-plan:2.0/` in the companion testing project) went further —
  genuinely separate physical hosts (a home-lab box + a second machine) — but at RF=1-per-DC on one
  LAN, still without real inter-region latency.
- The v3.0 round attempted this against ScyllaDB Cloud and found that the available tier there
  provisions **multi-AZ, not multi-DC** — a cluster reports one DC name (e.g. `AWS_US_EAST_1`) with
  nodes spread across availability zones (`use1-az1`, `use1-az2`, ...), not separate datacenters.
  Provisioning two separate ScyllaDB Cloud clusters does **not** produce a multi-DC deployment either
  — they're independent rings with no shared keyspace/replication, confirmed via disjoint
  `system.peers` and distinct `cluster_name` UUIDs.

Net effect: `kandra-multidc`'s actual value proposition — cross-DC consistency resolution and
DC-aware failover under real inter-region conditions — has never been exercised against real
multi-region infrastructure. Everything tested so far says the *config surface* and *local-topology
mechanics* work; real cross-region latency, clock skew, and genuine network partition behavior
remain unvalidated. Closing this gap needs either a ScyllaDB Cloud tier that actually offers
multi-region clusters, or a more deliberate self-hosted multi-region setup than any tried so far.
