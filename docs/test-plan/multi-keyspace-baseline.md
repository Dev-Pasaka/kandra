# Multi-keyspace baseline (ISS-109)

Baseline of the existing test suite before multi-keyspace work (branch off `feature/multi-keyspace`).

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

kandra-multidc has no tests of its own.

## Cloud-backed results (shared Scylla Cloud cluster)

Cluster facts: one DC `AWS_US_EAST_1`, 3 nodes, racks `use1-az4`/`use1-az4`/`use1-az2`. Plain auth on
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
| kandra-ktor (`KandraPluginTest`, `AutoCreateRedeployTest`, `DdlBootstrapClaimTest`, ...), kandra-kodein/koin DI tests, kandra-migrate, kandra-runtime live tests | No: they read `KandraTestcontainers.container.contactPoint/localDatacenter` directly and build their own unauthenticated sessions and keyspaces. Needs a shared "connection provider" (contact point, DC, auth, ssl) in kandra-test; recommended before Phase 3. New multi-keyspace tests should use `KandraCloud` directly |
| `SslRoundTripIntegrationTest`, multi-DC compose tests | No: need their own containers (custom TLS / multi-DC) |

## Running

```
# Docker-backed (no env needed)
./gradlew test --continue

# Cloud smoke / your own cloud tests (skipped when env unset)
source /Users/pasaka/Developer/Kandra/.kandra-cloud.env
./gradlew :kandra-test:test --tests '*KandraCloudSmokeTest*'
KANDRA_CLOUD_TLS=true ./gradlew :kandra-test:test --tests '*KandraCloudSmokeTest*'   # TLS on 9142

# Existing kandra-test integration tests against the cloud
KANDRA_TEST_BACKEND=cloud ./gradlew :kandra-test:test --tests '*KandraIntegrationTest*'
```
