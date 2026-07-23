# ISS-013: No integration tests against a real ScyllaDB/Cassandra cluster

**Status:** Fixed — confirmed passing end-to-end against a live Testcontainers-backed cluster (see
[ISS-031](ISS-031-runtime-tests-ktor-ci-exclusion.md)). `kandra-test`'s `KandraIntegrationTest`
(19 tests) and `kandra-ktor`'s `KandraPluginTest` (3 tests) both run green via
`JAVA_HOME=... ./gradlew test --no-daemon`, and `KandraPluginTest` is now included in CI (it was
previously excluded with `-x :kandra-ktor:test` in every job that ran tests).

## Problem

All Ktor/runtime tests were `@Disabled`. The entire execution path from plugin install through
`BatchEngine.save/findById/delete` was untested against a live driver. `KandraTestcontainers`
existed and provided the scaffolding, but no actual test cases were wired up — meaning LWT
applied/not-applied semantics, real CQL parameter binding, and TTL-based soft delete had literally
never been exercised (see [ISS-020](ISS-020-fake-session-lwt-semantics.md) for why the unit-test
fakes can't catch this).

## Fix

- **`kandra-test/src/test/kotlin/io/kandra/test/KandraIntegrationTest.kt`** (new) — real-cluster
  tests via `KandraTestcontainers.freshKeyspace(...)`, each getting its own isolated keyspace:
  save/findById round-trip, delete, `saveAll` auto-chunking (250 rows), `@Version` optimistic-lock
  conflict (`KandraOptimisticLockException` on a stale update), `@SoftDelete` +
  `findActive()` (row still queryable immediately after delete, excluded from `findActive()` via
  the permanent marker column — see [ISS-007](ISS-007-find-active-soft-delete.md)), and graceful
  shutdown (`isShuttingDown` rejects new queries).
- **`kandra-ktor/src/test/kotlin/io/kandra/ktor/KandraPluginTest.kt`** — the two previously
  `@Disabled` tests (hardcoded to `localhost:9042`) now run for real against
  `KandraTestcontainers.container`, with per-test keyspace creation/cleanup, and are re-enabled.
- `kandra-test/build.gradle.kts` and `kandra-ktor/build.gradle.kts` updated with the test-scope
  Testcontainers dependencies needed to actually run these.

## Verification note

Run against a live Docker daemon (`JAVA_HOME=... ./gradlew test --no-daemon`, no exclusions):
`KandraIntegrationTest` (19 tests) and `KandraPluginTest` (3 tests) all pass. Getting
`KandraPluginTest` green required two small fixes to the test itself, tracked under
[ISS-031](ISS-031-runtime-tests-ktor-ci-exclusion.md): the default `KandraAuth.fromEnv()` auth
provider throws when `SCYLLA_USERNAME`/`SCYLLA_PASSWORD` aren't set (never true in CI or local dev),
and the test's generated keyspace name exceeded Cassandra's 48-character limit. Both were artifacts
of the tests having never actually been executed before now, exactly as this issue's original
"compile-verified, not yet observed passing" status warned.

**Files:** `kandra-test/src/test/kotlin/io/kandra/test/KandraIntegrationTest.kt` (new),
`kandra-ktor/src/test/kotlin/io/kandra/ktor/KandraPluginTest.kt`, both modules' `build.gradle.kts`.
