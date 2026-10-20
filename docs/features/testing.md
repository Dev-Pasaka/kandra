# Testing (`kandra-test`)

- `FakeKandraSession` — in-memory CQL session for unit tests. The full repository stack runs against it (the real
  `StatementBuilder`/`BatchEngine`), and `capturedStatements()`/`capturedBatches()` expose what was built (`capturedStatementViews()`/`capturedBatchViews()` add a public `CapturedStatement(cql, values)` view with the unset-vs-`null` distinction), so it verifies *structural*
  behaviour (batch composition, save/delete ordering) — not real data round-trips, real LWT semantics, or real codec/parameter binding
  (`execute()` always returns an empty, `wasApplied() == true` result). `KandraTestUtils.inMemory(...)` wires its own instance; read it back
  from `runtime.session as FakeKandraSession`. Until 0.4.7 `bind()` threw and repository calls failed under the fake (GH #28 / ISS-040); that is
  fixed. See
  [ISS-020](../issues/ISS-020-fake-session-lwt-semantics.md).
- `KandraTestUtils` — schema assertion helpers. `KandraTestUtils.reset()` (or `runtime.closeAndReset()`) clears the process-global schema registry between tests
  without an `@OptIn(InternalKandraApi::class)`. The DataStax driver is an `api` dependency of `kandra-test`, so `CqlSession`/`BatchStatement` resolve in test code.
  *(`reset()`, `closeAndReset()`, the captured-statement views and the `api` driver dependency are on `main` but not in 0.5.0 — see the
  [unreleased changelog](../changelog/unreleased.md). On 0.5.0, add `java-driver-core` yourself and use `@OptIn(InternalKandraApi::class)` around `SchemaRegistry.clear()`.)*
- Setup your test module needs (JUnit does not come transitively): `org.junit.jupiter:junit-jupiter` with `tasks.test { useJUnitPlatform() }`, and Testcontainers
  `1.21.4`+ for `KandraTestcontainers` (1.20.x cannot connect to current Docker). Calling `SchemaRegistry.clear()` directly still needs
  `@OptIn(InternalKandraApi::class)` — prefer `KandraTestUtils.reset()`.
- `KandraTestcontainers` — ready-to-use ScyllaDB/Cassandra Testcontainers setup.
  `KandraTestcontainers.freshKeyspace(User::class, ...)` creates an isolated, randomly-named
  keyspace per test class against one shared container per JVM; call `.close()` in `@AfterEach`.
  See `kandra-test/src/test/kotlin/io/kandra/test/KandraIntegrationTest.kt` for a full example
  covering CRUD, `saveAll` auto-chunking, `@Version` optimistic locking, `@SoftDelete` +
  `findActive()`, and graceful shutdown — added in
  [ISS-013](../issues/ISS-013-no-integration-tests.md).

## Choosing a backend: `KandraTestBackend` (docker | cloud)

`KandraTestBackend` is the shared connection provider for live-cluster tests. It exposes the contact
points (`contactPointsCsv`), `localDatacenter`, credentials, TLS truststore, `newSession(keyspace)`,
`keyspaceName(tag)`, `createKeyspace` / `dropKeyspace` / `freshKeyspace(tag, rf)` and `effectiveRf(rf)`, so a
test never reads `KandraTestcontainers.container` directly. Every session it builds uses 30 s request and
schema timeouts (the driver's 2 s default is too tight for DDL against a cold container or over a WAN).

`KANDRA_TEST_BACKEND` selects the backend:

- unset or `docker` (default): the shared Testcontainers Cassandra, exactly as before.
- `cloud`: the cluster described by the `KANDRA_CLOUD_*` environment. Fails fast if
  `KANDRA_CLOUD_CONTACT_POINTS` is unset. Keyspaces are named `kt_<tag>_<8 hex>`, RF 1 is bumped to 3
  (`effectiveRf`), and `SimpleStrategy` keyspaces get `AND tablets = {'enabled': false}`.

`KandraTestcontainers.freshKeyspace(...)` honours the switch. After starting the container,
`KandraTestcontainers.container` waits until CQL is actually serving (query plus DDL probe, 3 minute deadline).

### `KandraCloud` environment

| Variable | Meaning |
|---|---|
| `KANDRA_CLOUD_CONTACT_POINTS` | Comma-separated `host[:port]` (port defaults to 9042). Unset means unconfigured. |
| `KANDRA_CLOUD_USERNAME` / `KANDRA_CLOUD_PASSWORD` | Plain auth credentials (used when both are set). |
| `KANDRA_CLOUD_LOCAL_DC` | Local datacenter; defaults to `AWS_US_EAST_1` when unset. |
| `KANDRA_CLOUD_TLS` | `true` enables TLS; contact-point port 9042 is rewritten to 9142. (`KANDRA_CLOUD_SSL=true` is the legacy spelling: TLS with the JVM trust store.) |
| `KANDRA_CLOUD_CA_PEM` | Path to a CA PEM; an in-memory trust store is built from it at runtime. |
| `KANDRA_CLOUD_TRUSTSTORE` / `KANDRA_CLOUD_TRUSTSTORE_PASSWORD` | Alternative custom trust store. Without either, the JVM default trust store is used. |

Never commit values for these variables (or the cluster's hostnames) to the repository; export them in your shell
or a gitignored env file. Tests call `KandraCloud.assumeConfigured()` in `@BeforeAll`, so they are skipped, not
failed, when the cluster is not configured.

```kotlin
@BeforeAll @JvmStatic fun gate() = KandraCloud.assumeConfigured()

@Test fun works() = KandraCloud.freshKeyspace("smoke").use { ks ->
    ks.session.execute("CREATE TABLE t (id int PRIMARY KEY)")   // session is bound to ks.name
}
```

Rules when pointing tests at a shared cluster: only create keyspaces through these helpers (`kt_` prefix), always
close the handle so the keyspace is dropped, and never touch keyspaces you did not create. In `install(Kandra)`
test blocks, the ktor, koin and kodein test source sets use a small `useTestBackend()` helper (a
`TestBackendConfig.kt` per module, test sources only) that fills in contact points, DC, credentials, TLS and a
30 s request timeout from `KandraTestBackend`.

Not every suite can run against the cloud: `SslRoundTripIntegrationTest` and the multi-DC suite need their own
containers, and a few credential-rotation tests skip themselves on cloud. See
[`docs/test-plan/multi-keyspace-baseline.md`](../test-plan/multi-keyspace-baseline.md) for what has been run where.

## Two-datacenter fixture: `KandraMultiDcTestcontainers`

Docker-only; see [multidc.md](multidc.md#testing-against-a-real-multi-dc-topology) for the topology and the `pause`
semantics. The entry point mirrors `KandraTestcontainers.freshKeyspace`:

```kotlin
class DcFailoverTest {
    private val db = KandraMultiDcTestcontainers.freshNetworkTopologyKeyspace(User::class)   // dc1 + dc2, RF 1 each
    @AfterEach fun cleanup() { db.close(); KandraMultiDcTestcontainers.unpauseAll() }

    @Test fun `local DC down`() {
        KandraMultiDcTestcontainers.pause(KandraMultiDcTestcontainers.DC1)
        // ... exercise install(Kandra) with loadBalancing.dcAwareFailover / failover.onLocalDcUnavailable ...
    }
}
```

`freshNetworkTopologyKeyspace(vararg classes, localDatacenter = DC1, dcReplicationMap = mapOf(DC1 to 1, DC2 to 1),
statementBuilderConsistencyConfig = ConsistencyConfig())` creates a `kandra_multidc_<uuid>` keyspace with
`NetworkTopologyStrategy` and returns the same `KandraRuntimeHandle` as the single-DC helper. Also available:
`contactPoint(dc)`, `pause(dc)`, `unpause(dc)`, `unpauseAll()`. If the Compose fixture itself cannot start (a dynamically chosen port still
collided after three attempts, or Docker is not running) it throws `KandraMultiDcFixtureException` with an actionable message rather than a raw
Docker bind error (GH #108 / ISS-095). Always `unpauseAll()` in `@AfterEach`.
