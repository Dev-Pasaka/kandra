# Testing (`kandra-test`)

- `FakeKandraSession` — in-memory CQL session for unit tests. Verifies *structural* behaviour
  (batch composition, save/delete ordering) — not real data round-trips, real LWT semantics, or
  real codec/parameter binding. See
  [ISS-020](../issues/ISS-020-fake-session-lwt-semantics.md).
- `KandraTestUtils` — schema assertion helpers.
- `KandraTestcontainers` — ready-to-use ScyllaDB/Cassandra Testcontainers setup.
  `KandraTestcontainers.freshKeyspace(User::class, ...)` creates an isolated, randomly-named
  keyspace per test class against one shared container per JVM; call `.close()` in `@AfterEach`.
  See `kandra-test/src/test/kotlin/io/kandra/test/KandraIntegrationTest.kt` for a full example
  covering CRUD, `saveAll` auto-chunking, `@Version` optimistic locking, `@SoftDelete` +
  `findActive()`, and graceful shutdown — added in
  [ISS-013](../issues/ISS-013-no-integration-tests.md).
