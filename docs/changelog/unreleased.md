# [Unreleased] — on `main`, not in 0.5.0

Changes merged after the 0.5.0 tag. Move this file's contents into the next version's file when it is cut.

### Fixed

- **`@CacheResult` invalidation with microsecond `Instant` keys (#178, PR #182).** CQL `timestamp` has millisecond precision, but `findById` cached
  under the caller-supplied key (an untruncated `Instant.now()`) while `save`/`update`/`delete` invalidated under the entity read back from the database
  (milliseconds). The keys never matched, so the stale entity — with its old `@Version` — survived, and the next optimistic-locked `update()` failed with a
  spurious `KandraOptimisticLockException` until the cache TTL expired. `KandraCache` now normalizes keys (an `Instant` is truncated to milliseconds, and
  composite keys element by element) on read, write and invalidate. On 0.5.0, truncate caller-supplied `Instant` keys with `.truncatedTo(ChronoUnit.MILLIS)`.

### Changed — `kandra-test` (#179, #180, #181, PR #183)

- The DataStax driver is now an `api` dependency of `kandra-test`, `kandra-runtime` and `kandra-ktor` (their public types expose `CqlSession`), so test code
  no longer needs its own `java-driver-core`. A `verifyDriverIsApi` task runs in `check` to prevent regressions.
- `KandraTestUtils.reset()` and `KandraRuntime.closeAndReset()` clear the process-global schema registry without an `@OptIn(InternalKandraApi::class)`.
- `FakeKandraSession.capturedStatementViews()` / `capturedBatchViews()` return a public `CapturedStatement(cql, values)` view, including the unset-vs-`null`
  distinction (`isUnset(i)`), so tests can assert on bound values. `FakeBoundStatement` stays internal.
