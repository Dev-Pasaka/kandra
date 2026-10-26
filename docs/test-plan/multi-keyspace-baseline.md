# Multi-keyspace baseline (ISS-109)

Baseline of the existing test suite before multi-keyspace work (branch off `feature/multi-keyspace`). Test counts
below are a point-in-time snapshot taken then, not a maintained figure; they have changed since (see the later
sections for the post-change runs).

## Docker-backed run (Testcontainers, local Docker Desktop)

`./gradlew test --continue` -> **BUILD SUCCESSFUL (9m20s), 0 failures, 0 errors.**

| Module | Tests | Skipped | Failed |
|---|---|---|---|
| kandra-codegen | 21 | 0 | 0 |
| kandra-core | 91 | 0 | 0 |
| kandra-jakarta | 5 | 0 | 0 |
| kandra-kodein | 3 | 0 | 0 |
| kandra-koin | 3 | 0 | 0 |
| kandra-ktor | 70 | 0 | 0 |
| kandra-migrate | 32 | 0 | 0 |
| kandra-multidc | 0 | 0 | 0 |
| kandra-runtime | 280 | 0 | 0 |
| kandra-test | 41 | 2 (cloud smoke, env unset) | 0 |

`kandra-multidc` shows 0 because its only suite (`MultiDcFailoverTest`) is tagged `manual` and excluded from the
default `test` task; run it with `./gradlew :kandra-multidc:multiDcTest` (needs Docker Compose).

## Cloud-backed results (shared Scylla Cloud cluster)

Cluster facts: one DC `AWS_US_EAST_1`, 3 nodes, spread over availability zones (multi-AZ, not multi-DC). Plain auth on
9042 works; TLS works on 9142 with the supplied CA (`KANDRA_CLOUD_TLS=true`, `KANDRA_CLOUD_CA_PEM`).
Tablets are on by default, so SimpleStrategy keyspaces need `AND tablets = {'enabled': false}`.

- `KandraCloudSmokeTest` (2 tests): pass on plain 9042 and on TLS 9142.
- Existing `KandraIntegrationTest` + `StrictModeRfIntegrationTest` (34 tests) via `KANDRA_TEST_BACKEND=cloud`:
  - With `freshKeyspace` RF=1 on the cloud: 8 failures. 7 were `UnavailableException ... LOCAL_QUORUM
    (1 required but only 0 alive)` (an RF=1 keyspace on this cluster had no live replica for those
    partitions; environmental, not a Kandra bug), 1 was the soft-delete TTL test (2s TTL racing WAN latency).
  - Therefore in cloud mode `freshKeyspace` bumps RF 1 -> 3. Rerun: 33/34 pass; the one failure is
    `StrictModeRfIntegrationTest > strict mode does not warn on RF=1 ...`, which asserts RF=1 metadata and
    is expected to fail under the bump. Not a real failure.
  - The soft-delete TTL test is latency-sensitive; treat intermittent cloud failure as environmental.

## Which tests can be pointed at the cloud

| Tests | Cloud-capable? |
|---|---|
| kandra-test `KandraIntegrationTest`, `StrictModeRfIntegrationTest` (use `KandraTestcontainers.freshKeyspace`) | Yes, via `KANDRA_TEST_BACKEND=cloud` |
| kandra-ktor (`KandraPluginTest`, `AutoCreateRedeployTest`, `DdlBootstrapClaimTest`, ...), kandra-kodein/koin DI tests, kandra-migrate, kandra-runtime live tests | No: they read `KandraTestcontainers.container.contactPoint/localDatacenter` directly and build their own unauthenticated sessions and keyspaces. This was the state at baseline. The shared connection provider this row asked for was added afterwards (`KandraTestBackend`, see "Test backend switch" below) and the ktor/koin/kodein/migrate suites were moved onto it. |
| `SslRoundTripIntegrationTest`, multi-DC compose tests | No: need their own containers (custom TLS / multi-DC) |

## Running

```
# Docker-backed (no env needed)
./gradlew test --continue

# Cloud smoke / your own cloud tests (skipped when env unset)
# export the KANDRA_CLOUD_* variables first (contact points, username/password, local DC; never commit them)
./gradlew :kandra-test:test --tests '*KandraCloudSmokeTest*'
KANDRA_CLOUD_TLS=true ./gradlew :kandra-test:test --tests '*KandraCloudSmokeTest*'   # TLS on 9142

# Existing kandra-test integration tests against the cloud
KANDRA_TEST_BACKEND=cloud ./gradlew :kandra-test:test --tests '*KandraIntegrationTest*'
```

## Test backend switch (`KandraTestBackend`) and flake fixes

`io.kandra.test.KandraTestBackend` (kandra-test) is the shared connection provider for live tests:
contact points CSV, local DC, credentials, TLS truststore, `newSession(ks)`, `keyspaceName(tag)`,
`createKeyspace/dropKeyspace/freshKeyspace`, `effectiveRf(rf)`. `KANDRA_TEST_BACKEND` unset/`docker` keeps the
Testcontainers behaviour; `cloud` targets the `KANDRA_CLOUD_*` cluster (use `KANDRA_CLOUD_TLS=true`; cloud keyspaces
are `kt_<tag>_<8hex>`, RF 1 is bumped to 3, tablets disabled for SimpleStrategy). In ktor/koin/kodein tests,
`KandraConfig.useTestBackend()` (a small `TestBackendConfig.kt` per module) fills an `install(Kandra)` block
(30s pool request timeout, creds, TLS truststore, NetworkTopologyStrategy on cloud).

Flake fixes: (a) the `DriverTimeoutException ... PT2S` came from raw `CqlSession.builder()` calls in test
cleanup/helpers (driver default 2s); every session now comes from `KandraTestBackend` with 30s request/schema timeouts,
and `KandraTestcontainers.container` waits for readiness (query + DDL probe, 3 min deadline) after start. (b) The
`IntegrationWidget`/`IntegrationIndexedWidget` soft-delete TTL went 2s -> 60s: the tests only assert the row is still
present right after the delete and never wait for expiry, so nothing is weakened. Strict-mode RF=1 test now asserts
against `effectiveRf(1)` (no warning at RF=1, RF=3 warning where the cloud bump applies).

Results (docker, run twice, all green): kandra-test 46 (2 skipped: cloud smoke), kandra-ktor 88 (2 skipped),
kandra-koin 5, kandra-kodein 5, kandra-migrate full suites.
Results (`KANDRA_TEST_BACKEND=cloud`, TLS, all green): kandra-test KandraIntegrationTest 30 + StrictModeRfIntegrationTest 4 +
MultiKeyspaceRuntimeIntegrationTest 5; ktor KandraPluginTest 19 (2 skipped, see below), AutoCreateRedeployTest 1,
AutoMigrateKeyColumnGuardTest 3, DdlBootstrapClaimTest 6; koin/kodein DiAccessorsTest 1 each; migrate KandraMigrationRunnerTest 13,
DdlBootstrapClaimTest 6, ClaimConcurrencyTest 3.

Remaining gaps / by design Docker-only:
- `SslRoundTripIntegrationTest` and multi-DC tests need their own containers.
- koin/kodein `*MultiKeyspaceTest`: keyspaces are compile-time constants in `@ScyllaTable(keyspace=...)` (non-`kt_` names), so
  they stay on Docker; cloud multi-keyspace coverage is in ktor `MultiKeyspaceIntegrationTest`/`MultiKeyspaceCloudE2ETest` and kandra-test.
- Two KandraPluginTest credential-rotation tests skip on cloud (`assumeFalse`): they need fake credentials / a no-auth cluster.
- Not run on cloud: the rest of kandra-ktor (config/unit tests need no cluster), kandra-runtime live tests (not migrated here), `KandraMigrationMultiKeyspace*`
  (already has its own cloud variant).


## 2026-10-25: full suite against a new ScyllaDB Cloud cluster (3 nodes, us-east-1, tablets on)

`KANDRA_TEST_BACKEND=cloud ./gradlew test --continue` on `main` after #187-#191/#194/#198/#203: 646 tests, 19 failures, 1 h 31 m.
Measured per statement from here (RTT 245 ms): `CREATE KEYSPACE` ~5 s, `CREATE TABLE` 1-2 s, `CREATE INDEX` ~3 s, `DROP KEYSPACE` ~8 s,
LWT ~0.4 s. One-keyspace `install(Kandra)` takes 30-65 s, three keyspaces 140-197 s. The coordinator's clock is ~120-250 ms ahead of
this Mac. Failure classes and what was done (#205, PR #206):

| Failures | Cause | Fix |
|---|---|---|
| 5 ktor `testApplication` tests, `UncompletedCoroutinesError` after 1 m | Ktor 3.6's `testApplication` runs `runTestWithRealTime(timeout = 60.seconds)`, no parameter, and the kotlinx `default_timeout` property does not apply to an explicit timeout; `install(Kandra)` is 30-200 s of WAN DDL (and 39-73 s for `AutoCreateRedeployTest`'s two installs on Docker under load) | `kandraTestApplication` (kandra-ktor test helper): `runTestApplication` under a 10-minute `runTestWithRealTime`, used at every call site |
| 2 ktor `DdlBootstrapClaimTest` lost-fencing tests | the test's simulated reclaim was a plain `UPDATE`; its client timestamp lost to the claim's Paxos timestamp (#203 ordering) | simulation is an LWT (`IF EXISTS`), both copies |
| 2 migrate `DdlBootstrapClaimTest` concurrency tests, 30 s latch | 8 simultaneous bootstraps serialise `CREATE TABLE IF NOT EXISTS` through Raft (~2 s each) plus Paxos contention | `KandraTestBackend.concurrencyBudgetSeconds` (30 s Docker, 180 s cloud) |
| migrate concurrent runners: `WriteTimeoutException` (CAS) from `claim()` | real bug: CAS timeout under contention propagated | runner and lock helpers re-read and retry (#205) |
| `KandraPluginTest` cleanup: `system.peers` timed out after PT5S | driver connection-init query timeout not raised by the test builders (nor by `pool.requestTimeoutMillis` in production) | 30 s in test builders; production derives from request timeout |
| `ReservedIdentifierIntegrationTest` exhaustive check, PT30S | 65 sequential `CREATE TABLE`s | one table with every reserved word as a column |
| 6 kandra-test failures, `UnknownHostException` | DNS resolution on this Mac dropped during the run; also hit a v0.5.1 baseline attempt | none (environment); rerun |
| `KandraMigrationChecksumLayoutTest` legacy-row upgrade (Docker full check, once) | the fixture wrote the simulated 0.5.1 row with a plain `INSERT` (client timestamp); the runner's conditional upgrade is an LWT (ballot timestamp). Most likely the Docker VM clock trailed the host at that moment (it wanders by seconds; precise probes showed -10 ms and +2 s minutes apart), so the CAS applied but its cell lost. Not proven. | fixture writes the row with `IF NOT EXISTS`, as 0.5.1's `claim()` did |

v0.5.1 baseline of the migrate/ktor `DdlBootstrapClaimTest` and `KandraMigrationMultiKeyspaceCloudTest` classes on the same cluster, run **alone**: 19/19 pass. The `main` failures above occurred inside the full 90-minute run; the same classes alone on `main` + #203 are compared below once run.
