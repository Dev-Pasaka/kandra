# ISS-076: `kandra-ktor` (SSL/pool/failover) and `kandra-migrate` have thin test coverage relative to their risk surface

**Status:** Fixed (GH #84)

## Problem

Filed as GH #84.

Filed from a critical library-wide review (security/performance/consistency/scalability/developer
experience) done ahead of experimental multi-cluster DC testing.

Test-file counts by module (main-source LOC in parens):

- `kandra-runtime`: 24 test files (~8,485 LOC) — thorough
- `kandra-ktor`: 2 test files (~1,754 LOC) — thin, and carries the entire SSL/mTLS,
  connection-pool, failover, speculative-execution, and health-check config surface
- `kandra-migrate`: 2 test files (~1,049 LOC) — thin, and carries the crash-safety-critical
  claim/staleness/checksum logic
- `kandra-core`: 4 test files (~2,013 LOC) — thin relative to schema validation logic

Concretely, `SslConfig`/`buildSslContext` (see `ISS-070`) has **zero** test coverage, which is very
likely why that dead-field bug went uncaught.

**Impact:** before multi-DC cluster testing, the modules carrying the most cluster-topology-
sensitive logic (SSL, failover, pool config) are the ones with the least test coverage — the
inverse of where coverage should be concentrated given what's about to be exercised for the first
time against a real cluster.

## Suggested fix direction

At minimum, add coverage for:
- `SslConfig`/`buildSslContext` (see `ISS-070`) — including a real self-signed-cert round trip if
  feasible.
- `LoadBalancingConfig`/`FailoverConfig`/`buildDriverConfig`'s DC-failover wiring beyond the
  existing config-validation tests, ideally exercised against a multi-node Testcontainers topology.
- `KandraMigrationRunner`'s claim/staleness paths under genuine concurrent claim races (not just
  sequential calls).

**Files:** `kandra-ktor/src/test`, `kandra-migrate/src/test`, `kandra-core/src/test`.

## Fix

Three real (non-mocked), Testcontainers-backed additions, each targeting exactly the gap this
issue called out:

**1. A real self-signed-cert SSL round trip** (`kandra-ktor/src/test/kotlin/io/kandra/ktor/SslRoundTripIntegrationTest.kt`).
Generates a real RSA keypair via `keytool` at test time, builds a real server keystore + a
separate client truststore (mirroring a genuine deployment where the client only ever holds the
server's public cert), boots a `cassandra:4.1` Testcontainers instance with a real
`cassandra.yaml` (`kandra-ktor/src/test/resources/ssl/cassandra-ssl.yaml` — the image's own
default file with only `client_encryption_options` edited: `enabled: true`, an absolute keystore
path) and the generated keystore mounted in, then drives a real `install(Kandra)` /
`CqlSessionBuilder` connection through it with `ssl { enabled = true; trustStorePath = ... }`, and
asserts a real `save`/`findById` round trip and a plain query both succeed over the encrypted
connection. This is the exact round trip `ISS-070`/GH #78's PR explicitly deferred as "too
large/flaky relative to direct unit tests" — attempted here for real rather than settling for
config-shape unit tests again, and it passed reliably across multiple runs. Two non-obvious things
discovered while building it, both now documented in the resource file/class comments: (a) once
`client_encryption_options.enabled: true`, Cassandra validates **both** SSL contexts at startup,
so `server_encryption_options`' keystore/truststore paths (irrelevant here — `internode_encryption:
none`) still need to resolve to real files or startup fails; (b) bind-mounting the config/keystore
files read-only breaks the image's own `chown` step in `docker-entrypoint.sh`, so they're mounted
read-write. Tagged `@Tag("manual")`, excluded from `:kandra-ktor:test` (see `excludeTags("manual")`
in `kandra-ktor/build.gradle.kts`) since standing up a TLS-configured single-node cluster is slower
and more Docker-environment-sensitive than the rest of that module's suite — run explicitly via
`./gradlew :kandra-ktor:sslIntegrationTest`.

**2. A real two-datacenter Testcontainers topology** — the part of this issue specifically about
closing the multi-DC coverage gap before real multi-DC cluster testing:
- `kandra-test/src/main/resources/multidc-docker-compose.yml` (+ `multidc-dc2-cassandra.yaml`, a
  copy of the image's default `cassandra.yaml` with only `native_transport_port` changed to
  `9043`): two `cassandra:4.1` services, `dc1`/`dc2`, `GossipingPropertyFileSnitch` via
  `CASSANDRA_ENDPOINT_SNITCH`, DC/rack via `CASSANDRA_DC`/`CASSANDRA_RACK`, gossiped into one
  cluster via `CASSANDRA_SEEDS=cassandra-dc1` on both nodes, `dc2` gated on `dc1`'s
  `service_healthy` healthcheck. Verified directly with `nodetool status` showing both `dc1` and
  `dc2` as real, distinct datacenters in one cluster.
- **Scoped to one node per DC (2 containers total)**, the minimum topology that genuinely has two
  DCs to fail over between — the explicit fallback this issue's own text allows when a fuller
  multi-node-per-DC cluster proves too slow for routine runs. Real 2-node gossip convergence from
  cold already took ~1 minute in this environment (vs. ~15-20s for a single node); doubling node
  count per DC would roughly double that again for coverage this issue doesn't ask for. Reasoning
  recorded in `KandraMultiDcTestcontainers`'s class KDoc.
- **A real cross-container-reachability problem, found and fixed while building this**: a Cassandra
  client discovers cluster peers via `system.peers`/`system.peers_v2` and connects to them
  directly (no proxying through the initially-contacted node) — by default those peers advertise
  Docker's internal bridge-network address, which a driver running on the host (outside that
  Docker network) cannot reach, producing `NoNodeAvailableException`/`NotYetConnectedException`
  once a query needed to reach the second node. Fixed by setting
  `CASSANDRA_BROADCAST_RPC_ADDRESS=localhost` on both nodes (so peers advertise a host-reachable
  address) plus giving `dc2` a distinct `native_transport_port` (`9043` vs. `dc1`'s default
  `9042`), each published 1:1 to the same-numbered host port — required because Cassandra's
  `system.peers_v2` advertises each peer's own port (`native_port`), so two host-reachable nodes on
  the same host cannot share one port. This is documented in the compose file's own file-level
  comment for the next person who hits the same failure mode.
- `kandra-test/src/main/kotlin/io/kandra/test/KandraMultiDcTestcontainers.kt`: the reusable
  fixture, mirroring `KandraTestcontainers`'s lazy-singleton, JVM-shared style. Adds
  `contactPoint(dc)`, `pause(dc)`/`unpause(dc)`/`unpauseAll()` (via the Docker API — simulates a DC
  going unreachable mid-test without tearing the topology down), and
  `freshNetworkTopologyKeyspace(...)` (a genuine `NetworkTopologyStrategy` keyspace, replicated
  across both real DCs).
- `kandra-multidc/src/test/kotlin/io/kandra/multidc/MultiDcFailoverTest.kt`, built on that fixture:
  a token-aware-routing smoke test against the real multi-DC keyspace; `FailoverPolicy.THROW`
  (default) genuinely failing a write during a real `dc1` outage (`pause(DC1)`); the previously-inert
  `LoadBalancingConfig.dcAwareFailover` + `FailoverConfig.onLocalDcUnavailable = RETRY_REMOTE_DC`
  combination (`ISS-057`/GH #58) genuinely, transparently routing a write to `dc2` during the same
  outage and reading it back; and a smoke test proving Strict Mode's RF-vs-consistency warning path
  (`ISS-075`/GH #83) runs cleanly end-to-end against a keyspace with **genuine** multi-DC RF (not a
  single-node stand-in declaring a higher RF than it can actually serve — the caveat
  `KandraTestcontainers`/`KandraMultiDcTestcontainers` both document on their RF parameters).
  Tagged `@Tag("manual")`, excluded from `:kandra-multidc:test` for the same reason as the SSL test
  above — run explicitly via `./gradlew :kandra-multidc:multiDcTest`.

**3. Genuine concurrent-race coverage for `KandraMigrationRunner`'s per-migration claim**
(`kandra-migrate/src/test/kotlin/io/kandra/migrate/KandraMigrationClaimConcurrencyTest.kt`). The
pre-existing `KandraMigrationRunnerTest` covered the claim/staleness/checksum *logic* thoroughly,
but every scenario there drove the race by hand — a single thread, sequential `run()` calls,
hand-inserted rows standing in for "another instance." The recently-merged `DdlBootstrapClaimTest`
(GH #79/ISS-071) already added real thread-pool concurrency for the newer DDL-*bootstrap* claim,
but the older, per-migration claim mechanism (ISS-018/ISS-043/ISS-063) had no equivalent. This adds
it, mirroring `DdlBootstrapClaimTest`'s pattern: a real `ExecutorService`, N genuinely separate
`KandraMigrationRunner` instances (simulating N app instances) racing a real `INSERT ... IF NOT
EXISTS` LWT via a real Testcontainers Cassandra — proving exactly one racer ever calls `up()` on a
never-before-seen migration, every racer converges on the same final `APPLIED` state, and (for a
*genuinely* stale claim — a real `Error` escaping `up()` plus real elapsed wall-clock time past the
threshold, not a forged timestamp) every concurrent discoverer throws rather than any of them
silently re-applying it.

### Test results (this environment, Docker available)

- `:kandra-ktor:test` — pass (existing suite, `manual`-tagged SSL test excluded by default)
- `:kandra-ktor:sslIntegrationTest` — pass (the real SSL round trip, run explicitly)
- `:kandra-migrate:test` — pass, including the new `KandraMigrationClaimConcurrencyTest`
- `:kandra-multidc:test` — pass (existing/no prior tests; `manual`-tagged failover suite excluded by default)
- `:kandra-multidc:multiDcTest` — pass, 4/4 (the real 2-DC failover suite, run explicitly)
- `:kandra-test:test` — pass
- `./gradlew test` (full suite, default task) — pass, no regressions
