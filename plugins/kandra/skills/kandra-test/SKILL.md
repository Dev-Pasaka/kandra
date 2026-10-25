---
name: kandra-test
description: Exhaustive API reference for kandra-test — FakeKandraSession for unit tests, KandraTestUtils, KandraTestcontainers and KandraTestBackend/KandraCloud for real-ScyllaDB/Cassandra integration tests. Load when writing tests for code that uses Kandra repositories.
---

# kandra-test

Test support module for Kandra, `io.kandra.test.*` (module `kandra-test`). Two testing styles:

| Style | Entry point | Backing | Speed | What it actually exercises |
|---|---|---|---|---|
| Fake-session unit test | `KandraTestUtils.inMemory(...)` | `FakeKandraSession` (in-process, no network) | instant | The full `KandraRepository`/`KandraSuspendRepository` CRUD surface, **structurally** — real statement-building/binding logic, no real CQL semantics (see below). |
| Testcontainers integration test | `KandraTestcontainers.freshKeyspace(...)` | real `cassandra:4.1` container via Testcontainers (or, with `KANDRA_TEST_BACKEND=cloud`, the `KANDRA_CLOUD_*` cluster; see `KandraTestBackend`) | slow (container start + real CQL) | The full, real `KandraRepository`/`KandraSuspendRepository` API against real DDL/DML |

Read this whole file before picking a style. As of GH #28 / ISS-040, the fake-session path runs the entire
repository surface end-to-end (an earlier version of `FakePreparedStatement.bind()` unconditionally threw
`UnsupportedOperationException`, which meant every real repository call — `save`, `findById`, `delete`,
`update`, batch writes — failed immediately; that has been fixed). What it still does *not* give you is real
CQL semantics: no actual encoding/decoding, no real LWT applied/not-applied result, no real data round-trip.
For those, use `KandraTestcontainers`.

## Test module setup (nothing here comes transitively)

`kandra-test` exposes `kandra-core`/`kandra-runtime` and (since 0.5.1) the DataStax driver as `api`, but JUnit is **not** on your test compile classpath. Add
`org.junit.jupiter:junit-jupiter:5.10.x` + `tasks.test { useJUnitPlatform() }`. Reset the schema registry with `KandraTestUtils.reset()` (since 0.5.1, no opt-in); on 0.5.0
add `com.datastax.oss:java-driver-core:4.17.0` yourself and wrap `SchemaRegistry.clear()` in `@OptIn(io.kandra.core.InternalKandraApi::class)`. For `KandraTestcontainers` use Testcontainers
`1.21.4`+ — 1.20.x cannot connect to current Docker releases ("Could not find a valid Docker environment").

**Testcontainers comes with `kandra-test` (unreleased, next: 0.5.2, GH #190).** `org.testcontainers:cassandra` and
`org.testcontainers:testcontainers` are `api` dependencies of `kandra-test` at the version it was built against (1.21.4).
On 0.5.1 and earlier they were `compileOnly`, so `KandraTestcontainers.freshKeyspace()` failed with
`NoClassDefFoundError: org/testcontainers/containers/CassandraContainer` unless you added
`testImplementation("org.testcontainers:cassandra:1.21.4")` yourself — keep that line if you are on 0.5.1.

## FakeKandraSession (`io.kandra.test.FakeKandraSession`)

`class FakeKandraSession : CqlSession` — in-process implementation of the DataStax driver's `CqlSession` interface. No network, no ScyllaDB process.

| Member | Signature | Behavior (verified from source) |
|---|---|---|
| `capturedStatements()` | `fun capturedStatements(): List<Statement<*>>` | Snapshot copy of **every** `Statement` (batch or not) passed through the **synchronous** `execute(Statement<*>)` overload, in call order. |
| `capturedBatches()` | `fun capturedBatches(): List<BatchStatement>` | `capturedStatements().filterIsInstance<BatchStatement>()` — a filtered view, not a separately-tracked list. |
| `capturedStatementViews()` | `fun capturedStatementViews(): List<CapturedStatement>` | **Public** read view (GH #181): every captured statement in call order as `CapturedStatement(cql: String, values: List<Any?>)`, with `BatchStatement`s flattened into their children. A value bound as CQL `null` (`saveWithNulls`, a tombstone write) reads back as Kotlin `null`; a position never bound or explicitly unset (plain `save()` of a `null` field) reads back as `FakeUnset` — `CapturedStatement.isUnset(index)` tests for it. Bind order follows the column list in `cql`. |
| `capturedBatchViews()` | `fun capturedBatchViews(): List<List<CapturedStatement>>` | One entry per captured `BatchStatement`, keeping batch boundaries. |
| `reset()` | `fun reset()` | Clears `capturedStatements()`/`capturedBatches()`. Nothing else is stateful, so this is the only thing to reset between assertions. |
| `tableContents(tableName)` | `fun tableContents(tableName: String): List<Map<String, Any?>>` | **Always returns `emptyList()`** — literally `= emptyList()` in the source, regardless of `tableName` or anything previously executed. Despite the name, it does **not** track rows written via `execute()`. Don't use it to assert "what got saved" — use `capturedStatementViews()` / `capturedBatchViews()` instead. |
| `execute(statement)` | `override fun execute(statement: Statement<*>): ResultSet` | Appends `statement` to `capturedStatementsList` (this is what `capturedStatements()`/`capturedBatches()` read). **Always returns `FakeResultSet.empty()`** no matter the statement — an empty result set with `wasApplied() == true`, `one() == null`, `all() == emptyList()`. |
| `execute(request, resultType)` | `override fun <RequestT: Request, ResultT: Any> execute(...): ResultT?` | If `request is Statement<*>`, delegates to `execute(statement)` purely for the capture side effect — **the result is discarded and this overload always returns `null`.** |
| `executeAsync(statement)` | `override fun executeAsync(...): CompletionStage<AsyncResultSet>` | Always completes with an empty `FakeAsyncResultSet`. **Does not append to `capturedStatements()`** — only the synchronous `execute(Statement<*>)` path captures. |
| `prepare(query: String)` | `override fun prepare(query: String): PreparedStatement` | Returns `FakePreparedStatement(query)`. Calling `.bind(...)` on the result returns a working `FakeBoundStatement` (see below) — this used to unconditionally throw `UnsupportedOperationException` (GH #28 / ISS-040); that has been fixed. |
| `prepare(statement: SimpleStatement)` | same | Same fake, built from `statement.query`. |
| `getName()` | → `"FakeKandraSession"` | |
| `getMetadata()` | throws `UnsupportedOperationException` | |
| `isSchemaMetadataEnabled()` | → `false` | |
| `setSchemaMetadataEnabled(...)` | → failed future, `UnsupportedOperationException` | |
| `refreshSchemaAsync()` | → failed future, `UnsupportedOperationException` | |
| `checkSchemaAgreementAsync()` | → completed future `true` | |
| `getContext()` | throws `UnsupportedOperationException` | |
| `getKeyspace()` | → `Optional.empty()` | |
| `getMetrics()` | → `Optional.empty()` | |
| `closeFuture()` / `closeAsync()` / `forceCloseAsync()` | → completed future `null` | no-ops |
| `isClosed()` | → `false` | always reports open |

**No configurable behavior exists.** There is no seam to make `execute()` return specific rows, force `wasApplied() == false` (to simulate a failed LWT / `saveIfNotExists` conflict / optimistic-lock conflict), or inject an exception for a specific statement. `FakeResultSet.wasApplied()` is hardcoded `true` with no override point. If you need to test LWT-failure or error-handling branches, you need Testcontainers (or a hand-rolled mock of `CqlSession` outside this module).

### Internal supporting types (`FakeHelpers.kt`)

All three types in this file are `internal` — implementation details of `FakeKandraSession`, not part of the module's public surface. Listed for completeness since they explain the behavior above:

| Type | Kind | Notes |
|---|---|---|
| `FakeResultSet` | `internal class ... : ResultSet` | Private constructor; companion factories `empty()` and `of(rows: List<Row>)`. `iterator()`/`one()`/`all()` read from the wrapped row list; `wasApplied()` is hardcoded `true`; `getExecutionInfo()`/`getColumnDefinitions()` throw `UnsupportedOperationException`. **Only `empty()` is ever called** from `FakeKandraSession` — `of(rows)` exists but nothing in the module wires real rows into it, so results are always empty in practice. |
| `FakeAsyncResultSet` | `internal class ... : AsyncResultSet` | `currentPage()` is always `emptyList()`, `hasMorePages()` is always `false`, `fetchNextPage()` resolves to a fresh empty instance, `wasApplied()` is hardcoded `true`. |
| `FakePreparedStatement(query: String)` | `internal class ... : PreparedStatement` | `getQuery()` returns the original CQL string; `getId()` returns `ByteBuffer.wrap(query.toByteArray())`. `bind(vararg values)` constructs and seeds a `FakeBoundStatement` (see below) — this used to unconditionally throw `UnsupportedOperationException`; fixed in GH #28 / ISS-040. `boundStatementBuilder(...)`, `getVariableDefinitions()`, `getResultSetDefinitions()` still throw `UnsupportedOperationException` — only `bind()` was fixed. |
| `FakeBoundStatement(preparedStatementRef, query)` | `internal class ... : BoundStatement` | The piece that makes repository calls actually work end-to-end. A minimal, structural stand-in — **no real CQL type encoding/validation/protocol serialization** — that records positionally-bound values verbatim. `set(i, v, targetClass)` stores `v` at index `i`; `setBytesUnsafe(i, v)` stores Kotlin `null` at index `i` (the tombstone-write path — `saveWithNulls`); `unset(i)` stores the `FakeUnset` sentinel at index `i` (the no-tombstone/"leave unchanged" path — a `null`-valued nullable field on a plain `save()`). `boundValues(): List<Any?>` lives on this `internal` class, so it is not reachable from a consumer module — read the same data through the public `FakeKandraSession.capturedStatementViews()` / `capturedBatchViews()` (`CapturedStatement`). An index never touched, or explicitly `unset`, reads back as `FakeUnset`, not Kotlin `null`; distinguishing those two is the whole point (they're different CQL writes). Everything else on the large `BoundStatement`/`Statement` interface (routing keys/tokens, execution profiles, consistency levels, paging state, ...) is stored in a plain field and returned faithfully via its setter/getter pair, but never interpreted — this is a recorder, not a real statement. `getBytesUnsafe`, `getType`, `firstIndexOf`, `codecRegistry`, `protocolVersion` all throw `UnsupportedOperationException` (no real byte/type layer exists to answer them). |
| `FakeUnset` | `object` (public) | Sentinel distinguishing "never bound / explicitly unset" from a real bound `null` in `FakeBoundStatement.boundValues()`. `toString()` → `"FakeUnset"`, useful when an assertion failure prints the list. |

### Repository calls work end-to-end under `FakeKandraSession` (fixed — GH #28 / ISS-040)

Traced from `KandraRepository`/`KandraSuspendRepository` → `StatementBuilder`/`QueryExecutor` in `kandra-runtime`:

- **Every** statement-building path — `insertPrimary`, `insertPrimaryWithNulls`, `insertLookup`, `deleteLookup`, `deleteById`, `selectById`, `selectByLookup`, the versioned-`UPDATE ... IF version = ?` LWT builder, `counterUpdate`, `rawQuery`, `findPage` — calls `session.prepare(cql)` and then `prepared.bind(...)` to produce the `BoundStatement` it actually executes.
- Under `FakeKandraSession`, `prepare(cql)` returns a `FakePreparedStatement`, and `.bind(...)` now returns a working `FakeBoundStatement` that records every positionally-bound value (see `FakeBoundStatement` above).
- So `repo.save(entity)`, `repo.delete(entity)`, `repo.update(old, new)`, `repo.findById(...)`, `repo.saveAll(...)`, `repo.raw(...)` — the full `KandraRepository`/`KandraSuspendRepository` surface — run to completion through a runtime built by `KandraTestUtils.inMemory(...)`, exercising the real `StatementBuilder`/`BatchEngine` code paths.

**An earlier version of `FakePreparedStatement.bind()` unconditionally threw `UnsupportedOperationException`**,
which meant every real repository operation failed immediately — contradicting `FakeKandraSession`'s own KDoc
("wire a full repository stack without Testcontainers"). That has been fixed; don't assume repository calls
still throw under the fake session.

**What's still not real, even after the fix:** `FakeBoundStatement`/`FakeResultSet` perform no actual CQL type
encoding/decoding, no query semantics, and no real data storage — `execute()` always returns an empty,
`wasApplied() == true` result regardless of the statement (see "No configurable behavior exists" above). So
`FakeKandraSession` is genuinely useful for **structural** assertions — did `save()` build the batch you
expect, in what order, with what values bound (via `capturedStatementViews()` / `capturedBatchViews()`, each
`CapturedStatement` carrying the CQL and bound values) — but not for asserting on actual round-tripped data, real LWT applied/not-applied
outcomes, or `KandraOptimisticLockException`/`saveIfNotExists() == false` paths. For those, use
`KandraTestcontainers`.

## KandraTestUtils / `io.kandra.test.KandraRuntime`

```kotlin
object KandraTestUtils {
    fun inMemory(vararg classes: KClass<*>): KandraRuntime
}
```

- Calls `SchemaRegistry.register(it)` for each class (additive — `registry.getOrPut`, process-global, never cleared automatically).
- Returns `KandraRuntime(FakeKandraSession())`.

```kotlin
class KandraRuntime internal constructor(val session: CqlSession) : AutoCloseable
```

Constructor is `internal` — you can only obtain one via `KandraTestUtils.inMemory(...)`. This `KandraRuntime` (package `io.kandra.test`) is a **different class** from the production `io.kandra.runtime.KandraRuntime` used by `KandraTestcontainers` (see next section) — same simple name, different package, different constructor shape, different repository-lookup calling convention. Don't confuse the two.

| Member | Signature | Behavior |
|---|---|---|
| `session` | `val session: CqlSession` | The `FakeKandraSession` instance — cast to `FakeKandraSession` to call `capturedBatches()`/`reset()`. |
| `repository(klass)` | `fun <T: Any> repository(klass: KClass<T>): KandraRepository<T>` | `SchemaRegistry.get(klass)` (throws `KandraSchemaException` if `klass` wasn't passed to `inMemory(...)` first) then constructs `KandraRepository(session, schema, klass, batchEngine)`. **Requires an explicit `X::class` argument** — no reified no-arg overload here. |
| `suspendRepository(klass)` | `fun <T: Any> suspendRepository(klass: KClass<T>): KandraSuspendRepository<T>` | Same, for the suspend repository. |
| `close()` | `override fun close()` | `scope.cancel("KandraRuntime test instance closed")` — cancels the internal `CoroutineScope(SupervisorJob() + Dispatchers.IO)` backing eventual (`@LookupIndex(consistency = EVENTUAL)`) writes. Does **not** close `session` and does **not** call `SchemaRegistry.clear()`. |
| `closeAndReset()` | `fun closeAndReset()` | `close()` then `KandraTestUtils.reset()` — one call for an `@AfterEach` that wants both. |

Internally, the class builds its own `BatchEngine(session, StatementBuilder(session), scope)` — the same production `BatchEngine`/`StatementBuilder` used everywhere else, which is exactly why the "Known limitation" above applies: nothing here is a simplified/fake version of the query-building logic, only the `CqlSession` underneath it is fake.

## KandraTestcontainers (`io.kandra.test.KandraTestcontainers`)

```kotlin
object KandraTestcontainers {
    val container: CassandraContainer<*>
    fun freshKeyspace(
        vararg classes: KClass<*>,
        replicationFactor: Int = 1,
        statementBuilderConsistencyConfig: ConsistencyConfig = ConsistencyConfig()
    ): KandraRuntimeHandle
}
```

| Member | Details |
|---|---|
| `container` | `by lazy { CassandraContainer("cassandra:4.1").withExposedPorts(9042).also { it.start(); awaitReady(it) } }`. Kotlin's default `by lazy` mode is `SYNCHRONIZED`, so this **is** a thread-safe lazy singleton, shared across every test class in the same JVM — verified from the source, not assumed. `.start()` is called **inside** the lazy initializer itself, so the very first access (from any test class, in any order) blocks synchronously while Testcontainers pulls/starts the container (can take tens of seconds on a cold Docker cache). Note the image is real Apache **Cassandra** `4.1`, not an actual ScyllaDB image — Kandra targets ScyllaDB in production but tests run against Cassandra-protocol compatibility. There is no shutdown code anywhere in this file — the container is left running until the JVM exits and Testcontainers' Ryuk reaper cleans it up. |
| `freshKeyspace(vararg classes, replicationFactor, statementBuilderConsistencyConfig)` | See call sequence below. `replicationFactor` (default `1`) is the `SimpleStrategy` RF; on the cloud backend RF 1 is bumped to 3. `statementBuilderConsistencyConfig` is passed to the `StatementBuilder` (e.g. `strictMode = true`). |

Readiness: after `start()` the lazy initializer polls (query plus a create/drop-keyspace DDL probe, 3 minute deadline, throws `IllegalStateException("Cassandra container not ready after 3 minutes")`) until CQL is really serving, so the first test does not pay for the cold start.

`freshKeyspace` call sequence, read straight from the implementation:

1. Docker backend: `keyspace = "kandra_test_${UUID.randomUUID().toString().replace("-", "")}"` — a fresh, dash-stripped random UUID per call, so concurrent/parallel test classes never collide. Cloud backend (`KANDRA_TEST_BACKEND=cloud`): `KandraTestBackend.keyspaceName("it")` = `kt_it_<8 hex>`.
2. Opens a throwaway **bootstrap** `CqlSession` via `KandraTestBackend.newSession()` (30 s timeouts, no keyspace set).
3. Executes `KandraTestBackend.createKeyspaceCql(keyspace, replicationFactor)` on it (`CREATE KEYSPACE IF NOT EXISTS <ks> WITH replication = {'class': 'SimpleStrategy', 'replication_factor': <rf>}`; the cloud backend applies the RF bump and adds `AND tablets = {'enabled': false}`), then closes it.
4. Opens a second, real `CqlSession` with `KandraTestBackend.newSession(keyspace)` — this is the session everything below actually uses.
5. For each class in `classes`: `SchemaRegistry.register(klass)` (same process-global registry as the fake-session path, additive) then executes every statement from `DdlGenerator.allStatements(schema)` on the session — this really issues `CREATE TABLE` (and any `@LookupIndex` lookup-table DDL) against the container.
6. Builds `KandraCodec.default`, `StatementBuilder(session, codec)`, a fresh `CoroutineScope(SupervisorJob() + Dispatchers.IO)`, and `BatchEngine(session, statementBuilder, scope)`.
7. Constructs the **production** `io.kandra.runtime.KandraRuntime(session, batchEngine, codec)` — same class Ktor wires up in real apps, not a test double.
8. Returns `KandraRuntimeHandle(session, runtime, scope, keyspace)`.

```kotlin
class KandraRuntimeHandle(
    session: CqlSession,          // private
    val runtime: KandraRuntime,   // io.kandra.runtime.KandraRuntime — the production class
    scope: CoroutineScope,        // private
    val keyspace: String
)
```

| Member | Signature | Behavior |
|---|---|---|
| `runtime` | `val runtime: io.kandra.runtime.KandraRuntime` | The real production runtime — also exposes `runtime.batch { }` / `batchBlocking { }` (LOGGED-batch DSL, `@ExperimentalKandraApi`), `runtime.isHealthy(): Boolean` (suspend; runs `SELECT release_version FROM system.local`), `runtime.isShuttingDown`, `runtime.inFlightCount`, in addition to repository lookup. |
| `keyspace` | `val keyspace: String` | The randomly generated keyspace name actually created in step 3 above. |
| `repository<T>()` | `inline fun <reified T: Any> repository(): KandraRepository<T>` | Delegates to `runtime.repository<T>()` — **reified, no `KClass` argument**: call as `db.repository<User>()`. Different calling convention than `io.kandra.test.KandraRuntime.repository(User::class)` above — easy to transcribe wrong when switching between the two test styles. |
| `suspendRepository<T>()` | `inline fun <reified T: Any> suspendRepository(): KandraSuspendRepository<T>` | Same, suspend variant. |
| `close()` | `fun close()` | `runCatching { session.execute("DROP KEYSPACE IF EXISTS $keyspace") }`, then `runCatching { session.close() }` — **both wrapped in `runCatching`, so failures are silently swallowed**, not surfaced to the test — then `scope.cancel("KandraRuntimeHandle closed")`. Does **not** stop or close the shared `container` (it keeps running for the rest of the JVM) and does **not** call `SchemaRegistry.clear()`. |

## KandraTestBackend (`io.kandra.test.KandraTestBackend`)

Shared connection provider for live tests. `KANDRA_TEST_BACKEND` unset or `docker` = the Testcontainers container (behaviour unchanged); `cloud` = the `KANDRA_CLOUD_*` cluster (fails fast with `KANDRA_TEST_BACKEND=cloud but KANDRA_CLOUD_CONTACT_POINTS is unset`).

| Member | Details |
|---|---|
| `isCloud: Boolean` | True when `KANDRA_TEST_BACKEND=cloud`. |
| `contactPointsCsv: String` | `host:port` CSV for Kandra's `contactPoints` config string. |
| `localDatacenter: String` | Container DC, or `KANDRA_CLOUD_LOCAL_DC`. |
| `username` / `password: String` | Cloud credentials; blank on Docker (AllowAllAuthenticator). Never log. |
| `tlsEnabled: Boolean`, `truststore: KandraCloud.TempTruststore?` | Cloud + TLS only; the truststore is built once per JVM from `KANDRA_CLOUD_CA_PEM` and deleted on exit. |
| `effectiveRf(requested: Int): Int` | Cloud: RF 1 becomes 3. Docker: unchanged. Assert replication metadata against this. |
| `keyspaceName(tag: String): String` | Docker `kandra_<tag>_<32 hex>`; cloud `kt_<tag>_<8 hex>`. |
| `sessionBuilder(): CqlSessionBuilder`, `newSession(keyspace: String? = null): CqlSession` | 30 s request/schema timeouts; caller closes. |
| `createKeyspace(name, rf = 1)`, `createKeyspaceCql(name, rf = 1)`, `dropKeyspace(name)` | SimpleStrategy; cloud also disables tablets. `dropKeyspace` swallows failures. |
| `freshKeyspace(tag, rf = 1): FreshKeyspace` | `AutoCloseable` with `name`, `session` (bound) and `admin` (unbound); `close()` always drops the keyspace. |

## KandraCloud (`io.kandra.test.KandraCloud`)

Env-driven harness for a live (e.g. Scylla Cloud) cluster; no credentials or hostnames live in the repo. `isConfigured` is true when `KANDRA_CLOUD_CONTACT_POINTS` is set; `assumeConfigured()` is a JUnit assumption (skips, not fails). Variables: `KANDRA_CLOUD_CONTACT_POINTS` (CSV `host[:port]`, default port 9042), `KANDRA_CLOUD_USERNAME` / `KANDRA_CLOUD_PASSWORD`, `KANDRA_CLOUD_LOCAL_DC` (default `AWS_US_EAST_1`), `KANDRA_CLOUD_TLS=true` (rewrites port 9042 to 9142; `KANDRA_CLOUD_SSL=true` is the legacy spelling with JVM trust), `KANDRA_CLOUD_CA_PEM` (in-memory trust store from the PEM), `KANDRA_CLOUD_TRUSTSTORE` + `KANDRA_CLOUD_TRUSTSTORE_PASSWORD`. Members: `localDc`, `tlsEnabled`, `contactPointsCsv()`, `username`, `password`, `tempTruststore()`, `sessionBuilder()`, `newSession(keyspace?)`, `freshKeyspace(prefix, rf = 1)` (`prefix` must match `[a-z0-9]{1,20}`; creates `kt_<prefix>_<random8>` with `AND tablets = {'enabled': false}`; `close()` always drops it). Unlike `KandraTestBackend.freshKeyspace`, `KandraCloud.freshKeyspace` does not bump RF.

`KandraMultiDcTestcontainers` (same package) is a separate, Docker-only two-datacenter (`dc1`/`dc2`) Compose fixture (two gossiping `cassandra:4.1` nodes on dynamically chosen ports):

```kotlin
object KandraMultiDcTestcontainers {
    const val DC1 = "dc1"; const val DC2 = "dc2"
    fun contactPoint(dc: String): InetSocketAddress
    fun pause(dc: String); fun unpause(dc: String); fun unpauseAll()
    fun freshNetworkTopologyKeyspace(
        vararg classes: KClass<*>,
        localDatacenter: String = DC1,
        dcReplicationMap: Map<String, Int> = mapOf(DC1 to 1, DC2 to 1),
        statementBuilderConsistencyConfig: ConsistencyConfig = ConsistencyConfig()
    ): KandraRuntimeHandle   // keyspace kandra_multidc_<uuid>, NetworkTopologyStrategy; db.close() drops it
}
class KandraMultiDcFixtureException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
```

`KandraMultiDcFixtureException` is thrown when the Compose fixture can't start (port collision after 3 attempts, Docker not running). Always call `unpauseAll()` in `@AfterEach` after pausing a DC. See `docs/features/multidc.md`.

## Example: FakeKandraSession-based unit test

Real repository calls run to completion under the fake session — assert on the structural shape of what got
built (which statements, in what order, what values), not on real data or real LWT outcomes:

```kotlin
import io.kandra.test.FakeKandraSession
import io.kandra.test.KandraTestUtils
import io.kandra.core.SchemaRegistry
import com.datastax.oss.driver.api.core.cql.BatchStatement
import com.datastax.oss.driver.api.core.cql.BoundStatement
import com.datastax.oss.driver.api.core.cql.DefaultBatchType
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FakeKandraSessionCaptureTest {

    private val fakeSession = FakeKandraSession()

    @AfterEach
    fun tearDown() {
        fakeSession.reset()
    }

    @Test
    fun `execute captures batch statements for inspection`() {
        // SimpleStatement doesn't need prepare()/bind(), so it works fine under the fake.
        val batch = BatchStatement.newInstance(DefaultBatchType.LOGGED)
            .add(SimpleStatement.newInstance("INSERT INTO users (id, name) VALUES (?, ?)", "u1", "Ada"))
            .add(SimpleStatement.newInstance("INSERT INTO user_email_lookup (email, id) VALUES (?, ?)", "ada@x.com", "u1"))

        val result = fakeSession.execute(batch)

        assertEquals(1, fakeSession.capturedBatches().size)
        assertEquals(2, fakeSession.capturedBatches().single().size)
        assertEquals(true, result.wasApplied()) // always true — cannot simulate LWT failure here
    }

    @Test
    fun `save builds one LOGGED batch with the expected INSERT`() {
        val runtime = KandraTestUtils.inMemory(User::class)
        val session = runtime.session as FakeKandraSession   // the fake wired into THIS runtime, not a separate FakeKandraSession()
        val repo = runtime.repository(User::class)

        repo.save(User(id = "u1", name = "Ada", email = "ada@example.com"))

        val view = session.capturedStatementViews().single()   // public CapturedStatement(cql, values)
        assertEquals("INSERT INTO users (id, name, email) VALUES (?, ?, ?)", view.cql)
        assertEquals(3, view.values.size)

        runtime.closeAndReset() // close + KandraTestUtils.reset(): schema registration is process-global
    }
}
```

## Example: Testcontainers-based integration test

This is where full repository round-trips actually work, against a real container:

```kotlin
import io.kandra.test.KandraTestcontainers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class UserRepositoryIntegrationTest {

    private val db = KandraTestcontainers.freshKeyspace(User::class)
    private val userRepo = db.repository<User>()          // reified — no ::class argument
    private val userSuspendRepo = db.suspendRepository<User>()

    @AfterEach
    fun tearDown() {
        db.close()   // drops db.keyspace and closes db's own session; the shared container stays up
    }

    @Test
    fun `save then findById round-trips through real ScyllaDB-compatible CQL`() {
        val user = User(id = "u1", name = "Ada", email = "ada@example.com")

        userRepo.save(user)
        val found = userRepo.findById(user.id)

        assertEquals(user, found)
    }

    @Test
    fun `suspend repository works the same way`() = runTest {
        val user = User(id = "u2", name = "Grace", email = "grace@example.com")

        userSuspendRepo.save(user)

        assertEquals(user, userSuspendRepo.findById(user.id))
    }
}
```

## Gotchas worth double-checking in review

- **`repo.save()`/`repo.findById()`/etc. now run to completion under `KandraTestUtils.inMemory()`** — an earlier version of `FakePreparedStatement.bind()` unconditionally threw `UnsupportedOperationException`, breaking the entire repository surface; that's fixed (GH #28 / ISS-040). Don't assume the fake session only supports hand-built `BatchStatement`/`SimpleStatement` execution — real repository calls work, structurally.
- **`tableContents(tableName)` always returns `emptyList()`.** It is not backed by anything captured — don't use it to assert on saved data; use `capturedStatementViews()` / `capturedBatchViews()` instead.
- **No way to simulate a failed LWT / optimistic-lock conflict via `FakeKandraSession`.** `FakeResultSet.wasApplied()` is hardcoded `true`; there's no seam to flip it. Test `KandraOptimisticLockException`/`saveIfNotExists() == false` paths against Testcontainers.
- **`executeAsync(...)` does not populate `capturedStatements()`/`capturedBatches()`** — only the synchronous `execute(Statement<*>)` overload captures. If the code under test uses the async driver API, both will stay empty even though statements were "executed."
- **Two different `KandraRuntime` classes, two different repository call conventions.** `io.kandra.test.KandraRuntime.repository(User::class)` (explicit `KClass` arg) vs. `KandraRuntimeHandle.repository<User>()` (reified, no arg, delegates to production `io.kandra.runtime.KandraRuntime`). Mixing up the call style across the two test styles is a common copy-paste mistake.
- **`SchemaRegistry` is process-global and neither test style clears it for you.** `inMemory()` and `freshKeyspace()` both call the additive `SchemaRegistry.register(...)`; `KandraRuntime.close()` / `KandraRuntimeHandle.close()` never call `SchemaRegistry.clear()`. Call `KandraTestUtils.reset()` (public, stable; or `runtime.closeAndReset()`) in `@AfterEach` if a test depends on a clean registry — no `@OptIn(InternalKandraApi::class)` needed, unlike calling `SchemaRegistry.clear()` directly (e.g. asserting a schema-validation failure).
- **The Testcontainers container is a JVM-wide singleton that is never explicitly stopped.** `KandraTestcontainers.container` starts once (blocking, on first access, from whichever test class hits it first) and is reused by every subsequent `freshKeyspace()` call in the same JVM run; only the per-call keyspace is isolated and dropped in `close()`. Expect the first integration test in a run to be noticeably slower than the rest.
- **`KandraRuntimeHandle.close()` swallows errors.** Both the `DROP KEYSPACE` and `session.close()` calls are wrapped in `runCatching` — a failed teardown will not fail your test and will not log anything from this code.
- **The container image is Apache Cassandra `4.1`, not a ScyllaDB image**, even though Kandra targets ScyllaDB in production — tests run against Cassandra-protocol compatibility, not the real target database.
