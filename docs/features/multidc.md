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
`pause`/`unpause` helpers (via the Docker API) to simulate a DC going unreachable mid-test. See
`kandra-multidc/src/test/kotlin/io/kandra/multidc/MultiDcFailoverTest.kt` for real
`dcAwareFailover`/`FailoverPolicy` and Strict Mode tests built on it, and
`KandraMultiDcTestcontainers`'s own KDoc for why the topology is scoped to one node per DC. This
suite is slower than a typical unit-test run (real two-node gossip convergence), so it's tagged out
of the default `test` task — run it explicitly with `./gradlew :kandra-multidc:multiDcTest`.
