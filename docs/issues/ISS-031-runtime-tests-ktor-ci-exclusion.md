# ISS-031: `kandra-runtime` had no unit tests, and `kandra-ktor`'s tests never ran in CI

**Status:** Fixed

## Problem

Two gaps, inverted relative to where the risk actually was (GitHub #16):

1. **`kandra-runtime` had no test source set at all.** `BatchEngine.kt` (818 lines),
   `QueryExecutor.kt` (491 lines), `StatementBuilder.kt` (351 lines), and `KandraCodec.kt`
   (150 lines) had zero unit tests — only indirect coverage via `kandra-test`'s Testcontainers-based
   `KandraIntegrationTest.kt`. Untested at the unit level: the retry/backoff branches in
   `executeWithRetry`/`executeWithRetrySuspend`, the `BATCH` vs `EVENTUAL` lookup-consistency split,
   batch auto-chunking around `batchMaxChunkSize`, and `StatementBuilder`'s idempotency/UNSET-vs-NULL
   logic. `kandra-core` already had this pattern (focused unit tests for `DdlGenerator`,
   `SchemaRegistry`, `KandraUuid`) — it had just never been applied to `kandra-runtime`.

2. **`kandra-ktor`'s own tests were excluded from CI in both jobs that used them.**
   `.github/workflows/ci.yml` ran `./gradlew test --no-daemon -x :kandra-ktor:test` in both the
   `test` job and the `publish` job. `kandra-ktor/src/test/kotlin/io/kandra/ktor/KandraPluginTest.kt`
   exists and is written against real `KandraTestcontainers` (not hardcoded `localhost`), covering
   `SchemaRegistry` registration and the plugin's `install()` path — but it never ran on any
   push/PR/release. [ISS-013](ISS-013-no-integration-tests.md) had documented these tests as
   "compile-verified, not yet observed passing" because the authoring environment had no Docker
   daemon; the exclusion was never revisited once CI (GitHub Actions, which has Docker) became
   available. Running it for the first time surfaced two real bugs that had been masked by the
   exclusion: the default `KandraAuth.fromEnv()` auth provider throws when `SCYLLA_USERNAME`/
   `SCYLLA_PASSWORD` aren't set (never set in CI or local dev), and the test's generated keyspace
   name (`kandra_ktor_test_` + a 32-char UUID = 49 chars) exceeded Cassandra's 48-character
   keyspace-name limit.

## Fix

- **Added `kandra-runtime/src/test/kotlin/io/kandra/runtime/`** with `BatchEngineTest.kt` (10 tests)
  and `StatementBuilderTest.kt` (9 tests), plus `FakeDriverSupport.kt` — a hand-rolled, configurable
  `CqlSession`/`PreparedStatement`/`BoundStatement` test double. `kandra-test`'s `FakeKandraSession`
  couldn't be reused (it depends on `kandra-runtime`, so a reverse dependency would be circular),
  and it can't drive failure/retry branches anyway (`wasApplied()` hardcoded `true`, `prepare().bind()`
  unconditionally throws). `FakeDriverSupport` uses a `java.lang.reflect.Proxy` for `BoundStatement`
  (confirmed via `javap` against the driver jar that it's a plain interface, despite a comment
  elsewhere claiming it's final) so `StatementBuilder`'s real `session.prepare(cql).bind(...)` path
  actually runs and its output can be inspected. Covers: retryable-exception retry-then-succeed,
  retry exhaustion wrapping into `KandraQueryException`, non-retryable exceptions skipping the retry
  loop entirely, shutdown rejection, the `BATCH`-vs-`EVENTUAL` lookup split (`save`/`saveAll`), batch
  auto-chunking around `batchMaxChunkSize` (under/at/over the limit, chunking disabled), and
  `StatementBuilder`'s idempotency flags plus UNSET-vs-explicit-NULL binding (`save` vs
  `saveWithNulls`). Deliberately does **not** cover genuine LWT `[applied]` semantics — per
  [ISS-020](ISS-020-fake-session-lwt-semantics.md), that needs a real cluster and stays in
  `kandra-test`'s `KandraIntegrationTest`.
- **Removed `-x :kandra-ktor:test`** from both the `test` job and the `publish` job in
  `.github/workflows/ci.yml` — `KandraPluginTest` now runs on every push/PR/release, against a real
  Testcontainers-backed Cassandra container (GitHub Actions' `ubuntu-latest` runners have Docker).
- **Fixed `KandraPluginTest.kt`** so it actually passes: both `install(Kandra) { ... }` blocks now
  set `auth { provider = KandraAuth.static("", "") }` (blank credentials — the Testcontainers
  Cassandra image runs with `AllowAllAuthenticator`, so no auth is required, and a blank username
  makes `buildCqlSession` skip `withAuthCredentials` entirely) instead of relying on the default
  `KandraAuth.fromEnv()`, which throws when `SCYLLA_USERNAME`/`SCYLLA_PASSWORD` aren't set. Also
  shortened the generated keyspace-name prefix from `kandra_ktor_test_` to `kandra_ktor_` so the
  full name (prefix + 32-char UUID) stays under Cassandra's 48-character keyspace-name limit.
- **Updated [ISS-013](ISS-013-no-integration-tests.md)** — its "compile-verified, not yet observed
  passing" status is now "confirmed passing", both from a local run against Docker and from CI
  actually executing `KandraPluginTest` going forward.

## Verification

- `JAVA_HOME=... ./gradlew :kandra-runtime:test --no-daemon` — 19 new tests, all green.
- `JAVA_HOME=... ./gradlew :kandra-ktor:test --no-daemon` — 3 tests, all green, against a live
  Testcontainers Cassandra container.
- `JAVA_HOME=... ./gradlew test --no-daemon` (full suite, no `-x` exclusion) — 96 tests across every
  module, all green.

**Files:** `kandra-runtime/src/test/kotlin/io/kandra/runtime/BatchEngineTest.kt` (new),
`kandra-runtime/src/test/kotlin/io/kandra/runtime/StatementBuilderTest.kt` (new),
`kandra-runtime/src/test/kotlin/io/kandra/runtime/FakeDriverSupport.kt` (new),
`.github/workflows/ci.yml`, `kandra-ktor/src/test/kotlin/io/kandra/ktor/KandraPluginTest.kt`,
`docs/issues/ISS-013-no-integration-tests.md`.
