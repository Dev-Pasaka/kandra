# ISS-013: No integration tests against a real ScyllaDB/Cassandra cluster

**Status:** Fixed — tests written and compile-verified; not yet run end-to-end (this environment
has no Docker daemon). Run `./gradlew test` in an environment with Docker (e.g. CI) to execute them.

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

Confirmed the Docker-dependent tests fail fast (~19s) with a clear
`Could not find a valid Docker environment` error rather than hanging, in this Docker-less
environment — meaning they are structurally wired correctly and will run once Docker is available.
They have not yet been observed passing against a live container; do that before relying on this
suite in CI.

**Files:** `kandra-test/src/test/kotlin/io/kandra/test/KandraIntegrationTest.kt` (new),
`kandra-ktor/src/test/kotlin/io/kandra/ktor/KandraPluginTest.kt`, both modules' `build.gradle.kts`.
