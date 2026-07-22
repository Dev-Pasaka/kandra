# 3. Functional Coverage Matrix

Every row is one thing to actually run against the real cluster and record a result for. "Expected"
is what the documentation (`.claude/skills/kandra-*/SKILL.md`, `docs/features/`, `docs/issues/`)
claims should happen — your job is to confirm or refute it, not to assume it. Fill in the **Result**
column with what you actually observed (use the vocabulary from
[the scoring rubric](05-scoring-rubric-and-reporting.md): PASS / FAIL / PARTIAL / BLOCKED / N/A) plus
a one-line note. Leave nothing blank — an empty row is an incomplete run.

## 3.1 Core annotations (`kandra-core`)

| # | Annotation / behavior | Verification | Expected (per docs) | Result |
|---|---|---|---|---|
| C1 | `@ScyllaTable(name, gcGraceSeconds)` | Inspect generated DDL for `users` table (`debug.logQueries` or `DESCRIBE TABLE`) | `WITH ... gc_grace_seconds = 864000` present | |
| C2 | `@ScyllaTable` missing entirely | Register a class with no `@ScyllaTable` | `KandraSchemaException` at `register()` | |
| C3 | `@PartitionKey` single | `User` DDL | `PRIMARY KEY (user_id, bucketed_at)` shape | |
| C4 | `@PartitionKey` composite | `AuditLog` DDL | `PRIMARY KEY ((tenant_id, entity_type), occurred_at)` — double-paren composite | |
| C5 | Duplicate `@PartitionKey(index)` | Deliberately construct a broken entity with two `index = 0` | `KandraSchemaException` mentioning duplicate index | |
| C6 | No `@PartitionKey` at all | Deliberately construct a broken entity | `KandraSchemaException` ("no @PartitionKey property") | |
| C7 | `@ClusteringKey(order, index)` | `User`/`AuditLog` DDL | `CLUSTERING ORDER BY (bucketed_at DESC)` / `(occurred_at DESC)` | |
| C8 | `@LookupIndex` BATCH | `User.email` — save a user, then `SELECT * FROM users_by_email` immediately (same request) | Row present immediately (atomic with primary write) | |
| C9 | `@LookupIndex` EVENTUAL | `User.phone` — save a user, immediately query `users_by_phone` | Row may be **absent** immediately after save (fire-and-forget after commit) — retry with a short delay | |
| C10 | Duplicate `@LookupIndex` table name | Two properties, same `tableSuffix` | `KandraSchemaException` | |
| C11 | `@Column(name)` | `User.displayName` → `display_name` | Column named `display_name` in DDL, not `display_name` derived automatically (confirm explicit override actually takes effect vs coincidentally matching auto-derivation) | |
| C12 | `@Transient` | `User.sessionToken` | Column absent from DDL entirely | |
| C13 | `@Ttl(seconds)` class default | `OtpCode` — save one, `SELECT TTL(code) FROM otp_codes` | ~300 (minus elapsed) | |
| C14 | `@Counter` all-non-key | `PageViewCounter` DDL | Both `views`/`unique_visitors` are `COUNTER` type | |
| C15 | `@Counter` mixed with non-counter | Deliberately broken entity (counter + plain column) | `KandraSchemaException` ("mixes @Counter and non-@Counter") | |
| C16 | `@Counter` + non-counter `@LookupIndex` | Deliberately broken entity — see [file 4](04-edge-cases-and-adversarial-tests.md) | `KandraSchemaException` (per `kandra-core` skill gotcha — confirm this specific combo actually throws) | |
| C17 | `@CreatedAt` | Save a `User` twice (insert, then `update`) | Set only on first insert, unchanged on update | |
| C18 | `@UpdatedAt` | Save then `update` a `User` | Changes on every save/update | |
| C19 | `@CreatedAt`/`@UpdatedAt` on non-`Instant` field | Deliberately broken entity | `KandraSchemaException` ("must be an Instant field") | |
| C20 | Two `@CreatedAt` on one entity | Deliberately broken entity | `KandraSchemaException` ("At most one @CreatedAt") | |
| C21 | `@Version` `Long` | `User.version` — `save()` then inspect | Initialized to `1L` on first save | |
| C22 | `@Version` on invalid type | Deliberately broken entity (`@Version val x: String`) | `KandraSchemaException` naming the actual `KType` | |
| C23 | `@SoftDelete(ttlSeconds, markerProperty)` | `Wallet` — see full sequence in [file 4 §4.3](04-edge-cases-and-adversarial-tests.md#43-softdelete--findactive-timing-window) | TTL'd row, `findActive()` excludes it immediately | |
| C24 | `@Sensitive` masking | `User.passwordHash` — log the entity via `KandraEntityLogger.safeToString` or trigger a log line that renders it | `***` in place of the real value | |
| C25 | `@CacheResult(ttlSeconds, maxSize)` | `User` — call `findById` twice in a row, second should be cache-served | No second query hits the cluster within `ttlSeconds` (verify via query logging/metrics count) | |
| C26 | `@CacheResult` without Caffeine on classpath | Temporarily remove the Caffeine dependency, rebuild | Cache silently no-ops (WARN logged), `findById` still works, just uncached | |
| C27 | `@SecondaryIndex` | `User.status` — query `findAll { +UserTable.status.eq(ACTIVE) }` | Works, logs WARN every call (scatter-gather) | |
| C28 | `@ReadConsistency`/`@WriteConsistency` (class) | `User` — confirm via `nodetool` / driver tracing that reads/writes actually use `LOCAL_QUORUM` | Resolution order: per-call > class annotation > config default — confirm class annotation wins over the plugin's `consistency { }` default | |
| C29 | Kotlin→CQL type mapping | Every column in every entity | Matches the table in `docs/features/core-annotations.md`/`kandra-core` skill exactly (`UUID`→`UUID`, `Instant`→`TIMESTAMP`, `BigDecimal`→`DECIMAL`, `Set<String>`→`SET<TEXT>`, `Map<String,String>`→`MAP<TEXT,TEXT>`, enum→`TEXT`, etc.) | |
| C30 | Enum column round-trip | `User.status` — save `ACTIVE`, read back | Decodes back to `UserStatus.ACTIVE` via `valueOf` | |
| C31 | Enum column with a stored value that no longer matches any constant | Manually `UPDATE users SET status = 'BOGUS' WHERE ...` via `cqlsh`, then `findById` | `IllegalArgumentException` (NOT a Kandra exception — confirm the exact exception type) | |

## 3.2 SchemaMode (`kandra-ktor`)

| # | Mode | Verification | Expected | Result |
|---|---|---|---|---|
| S1 | `AUTO_CREATE` | Fresh keyspace, install with `AUTO_CREATE` | `CREATE TABLE IF NOT EXISTS` for every registered entity + lookup tables | |
| S2 | `AUTO_MIGRATE` — new column added to an entity | Add a field to `User` after tables already exist, reinstall with `AUTO_MIGRATE` | `ALTER TABLE ADD` for the new column, logged INFO | |
| S3 | `AUTO_MIGRATE` — column type changed | Change an existing field's Kotlin type, reinstall | Logged at ERROR only, **not** thrown, **not** auto-fixed — confirm the app still starts | |
| S4 | `AUTO_MIGRATE` — column removed from entity | Remove a field, reinstall | Extra Scylla column logged at WARN, left alone (not dropped) | |
| S5 | `VALIDATE` — schema matches | Install with `VALIDATE` against an already-correct schema | Starts cleanly, no DDL issued | |
| S6 | `VALIDATE` — entity column missing from Scylla | Add a new field to an entity, install with `VALIDATE` (don't `AUTO_MIGRATE` first) | `KandraSchemaException`, startup fails | |
| S7 | `NONE` | Install with `NONE` against the migration-managed `invoices` table | Logs "skipping all DDL", zero DDL issued, app starts | |

## 3.3 Repository methods — save family

| # | Method | Route (file 2) | Expected | Result |
|---|---|---|---|---|
| R1 | `save()` — nullable field left `null` | N/A — inspect via `cqlsh` after a save with a null optional field | `UNSET` binding, **no tombstone** (confirm via `nodetool` / SSTable inspection or a subsequent read from a replica that never saw the write) | |
| R2 | `save()` on counter table | Attempt `save()` on `PageViewCounter` | `KandraQueryException` immediately | |
| R3 | `saveIfNotExists()` — first call | `POST /users/if-not-exists` | Returns `true`, row created | |
| R4 | `saveIfNotExists()` — conflicting second call | Same route, same partition key again | Returns `false`, **no** lookup-table writes attempted (verify `users_by_email` unchanged) | |
| R5 | `saveWithNulls()` | `POST /users/with-nulls` with an explicit null field | Actual tombstone written (confirm via `nodetool` / replica read) | |
| R6 | `saveAll(useBatch = true)`, under chunk threshold | `POST /users/bulk` with 30 entities (`batchMaxChunkSize = 50`) | Single `LOGGED BATCH`, one round trip | |
| R7 | `saveAll(useBatch = true)`, over chunk threshold | Same route with 200 entities | Multiple independent `LOGGED BATCH`es (4 chunks of 50) — confirm via `debug.logBatches` | |
| R8 | `saveAll(useBatch = false)` | Direct call in a test, not a route | Per-entity `save()`, **validators skipped** — confirm a Jakarta-invalid entity in the list is NOT rejected here even though it would be via `save()` | |

## 3.4 Repository methods — update family

| # | Method | Route | Expected | Result |
|---|---|---|---|---|
| U1 | `update(old, new)` with `@Version` present, no conflict | `PUT /users/{id}` | LWT succeeds, version increments | |
| U2 | `update(old, new)` with stale `old` (conflict) | Load `old` twice concurrently (two requests), update both | Second `update()` throws `KandraOptimisticLockException` | |
| U3 | `updateForce(entity)` | `PUT /users/{id}/force` | Bypasses version check entirely, always succeeds | |
| U4 | `update(old, new)` when the `@LookupIndex` field (`email`) changes | Update a user's email | Old `users_by_email` row deleted, new one inserted, in the same batch | |
| U5 | `updateForce(entity)` when the `@LookupIndex` field changes | Same, via `updateForce` | Per docs: stale lookup row is **not** cleaned up (only two-arg `update` diffs against a real "old") — confirm this actually reproduces | |

## 3.5 Repository methods — delete family

| # | Method | Route | Expected | Result |
|---|---|---|---|---|
| D1 | `delete(entity)` — no `@SoftDelete` | `DELETE /users/{id}` (load-then-delete) | Real `DELETE`, row gone immediately | |
| D2 | `delete(entity)` — `@SoftDelete` present | Delete a `Wallet` | `UPDATE ... USING TTL`, row still present until TTL expiry | |
| D3 | `deleteAll(entities)` | Bulk delete route | Sequential per-entity deletes, WARN logged if count exceeds `tombstoneWarnThreshold` | |
| D4 | `deleteById()` — blocking repo, `@SoftDelete` entity | `DELETE /wallets/by-id/{id}` via the blocking repo specifically | **Now fixed** per `docs/issues/ISS-015` — confirm it honors soft-delete (TTL'd, not hard-deleted) rather than reproducing the old bug | |
| D5 | `deleteById()` — cache invalidation | Cache a `User` via `findById`, then `deleteById`, then `findById` again | **Now fixed** — confirm the cache entry was invalidated (stale row should NOT be served) | |
| D6 | `deleteBy { }` | `DELETE /users/by-email/{email}` | Resolves via lookup, deletes the matched entity; **cache is not invalidated** by this path per docs — confirm | |

## 3.6 Repository methods — read family

| # | Method | Route | Expected | Result |
|---|---|---|---|---|
| F1 | `findById()` | `GET /users/{id}` | Cache hit on 2nd call within TTL | |
| F2 | `find { }` via `@LookupIndex` | `GET /users/by-email/{email}` | Two-step lookup→primary query | |
| F3 | `find { }` — no predicates | Call with an empty block | `KandraQueryException` ("must have at least one predicate") | |
| F4 | `findAll(limit) { }` | `GET /users?status=ACTIVE` | `@SecondaryIndex` branch, WARN logged | |
| F5 | `findAll` — `IN` on partition key | Query `userId isIn listOf(...)` | Scatter-gather `SELECT ... WHERE pk IN ?`, DEBUG logged | |
| F6 | `findAll` — `IN` on composite partition key | Same on `AuditLog` (composite PK) | `KandraSchemaException`, not `KandraQueryException` — confirm the **type** | |
| F7 | `findAll` — `IN` on a non-PK, non-indexed column | Any entity, `IN` on a plain column | `KandraQueryException` — read the **actual message text** and confirm it still references a nonexistent `allowFiltering()`, or was fixed per `docs/issues/ISS-021` | |
| F8 | `findPage()` — no predicates | `GET /users/page` | Full token-range scan, INFO logged on first page | |
| F9 | `findPage()` — pagination across pages | Page through more rows than fit in one page | `nextPageToken` non-null until exhausted, then null | |
| F10 | `findPage()` via `@LookupIndex` predicate, lookup miss | Page with a nonexistent email | `KandraPage(emptyList(), null, false)` immediately | |
| F11 | `exists { }` — direct predicate | `GET /users/exists?...` on a partition-key predicate | Cheap `SELECT pk LIMIT 1` | |
| F12 | `exists { }` — via `@LookupIndex` | Same on `email` | Full `SELECT *` under the hood (not cheap) — confirm via query log | |
| F13 | `raw(cql, params)` — no params, literal in CQL | `GET /users/raw` with a hand-built literal query | WARN logged (injection-risk heuristic) | |
| F14 | `rawQuery(KandraRawQuery)` | `GET /users/raw-query` | No WARN (always parameterized) | |
| F15 | Suspend read path doesn't block the dispatcher | Fire N concurrent suspend `findById` calls from a single-threaded dispatcher test | **Now fixed** per `docs/issues/ISS-014` — confirm none of the blocking-thread symptoms described there reproduce (see [file 4 §4.1](04-edge-cases-and-adversarial-tests.md#41-suspend-read-path-concurrency)) | |

## 3.7 Repository methods — collection & counter family

| # | Method | Route | Expected | Result |
|---|---|---|---|---|
| K1 | `append()` on `Set<String>` | `POST /users/{id}/tags` | `col = col + ?` — verify multiple concurrent appends from different requests don't lose data (CQL-level set union, should be safe) | |
| K2 | `remove()` on `Set<String>` | `DELETE /users/{id}/tags` | `col = col - ?` | |
| K3 | `put()` on `Map<String,String>` | `PUT /users/{id}/metadata` | Merge-by-key, not full overwrite — verify other existing keys survive | |
| K4 | `increment()` | `POST /page-views/{id}/increment` | `+1` (or configured `by`) | |
| K5 | `decrement()` | `POST /page-views/{id}/decrement` | `-1` (or configured `by`) — confirm sign handling | |
| K6 | `increment`/`decrement` on non-counter table | Attempt on `User` | `KandraSchemaException` | |
| K7 | These five bypass retry | Force a transient `WriteTimeoutException` during an `append()` call (e.g. throttle the network to one node) | Per docs: **no retry** — confirm it fails immediately rather than retrying like `save()` would | |

## 3.8 Plugin config (`kandra-ktor`)

| # | Config | Verification | Expected | Result |
|---|---|---|---|---|
| P1 | `keyspace` blank | Install with `keyspace = ""` | `KandraSchemaException`, before any network call | |
| P2 | `autoCreateKeyspace = true` | Fresh cluster, no pre-existing keyspace | Keyspace created with configured `replicationStrategy` | |
| P3 | `autoCreateKeyspace = false`, keyspace missing | Point at a keyspace that doesn't exist | Driver-level connect failure (not a Kandra-specific message) | |
| P4 | `validatePermissions` — missing `SELECT`/`MODIFY` | Use the low-privilege role from the auth-enabled cluster (1.2.2) | `KandraAuthException` naming the exact `GRANT` needed | |
| P5 | `validatePermissions` — missing `ALTER` only | Role with `SELECT`+`MODIFY` but not `ALTER` | WARN only, does **not** throw | |
| P6 | `pool.requestTimeoutMillis` | Set very low (e.g. 1ms) against a real cluster under load | Requests actually time out — confirm the config is load-bearing, not a documented-but-unused field like some `PoolConfig` fields | |
| P7 | `pool.localRequestsPerConnection` | Set to an extreme value | Per docs, **not actually applied** — confirm no observable behavior change | |
| P8 | `ssl { enabled = true, ... }` | Requires a TLS-enabled cluster variant — see file 4 if you build one | Handshake succeeds with correct trust store; `KandraAuthException` on a deliberately wrong trust store | |
| P9 | `loadBalancing.tokenAware` | Per docs, declared but **not read** by `CqlSessionBuilder` | Confirm: does the driver's own default token-aware policy apply regardless, or is routing measurably different with this flag off vs on? (Expect no difference either way since it's unused.) | |
| P10 | `loadBalancing.dcAwareFailover = true` + empty `allowedRemoteDcs` | Install with this combination | `KandraSchemaException` at session-build time, before any connection | |
| P11 | `failover.onLocalDcUnavailable = RETRY_REMOTE_DC` + empty `allowedRemoteDcs` | Same class of misconfiguration, different field | `KandraSchemaException` — confirm this **independent** check also fires | |
| P12 | `speculativeExecution.enabled` | Slow down one node artificially (e.g. `tc` network delay in the container), issue a `findById` | A speculative request fires after `delayMillis` — confirm via node-level query counts, not just app-level latency | |
| P13 | `shutdown.graceful = true` with slow in-flight writes | Trigger a slow save, then stop the application immediately | Shutdown blocks (`Thread.sleep(50)` poll loop) until drained or `drainTimeoutMs` elapses; WARN logged if it hits the timeout | |
| P14 | `shutdown.graceful = false` | Same scenario with graceful off | Session closes immediately, no drain wait | |
| P15 | `healthCheck = true` | `GET /kandra/health` | `200 {"status":"UP"}` while cluster reachable | |
| P16 | `healthCheck` during a cluster outage | Stop all ScyllaDB containers, then hit `/kandra/health` | `503 {"status":"DOWN"}`, exception only in logs (not the response body) | |
| P17 | `metrics.enabled = true`, no `recorder` | Install with this combination | WARN logged at install, no crash, metrics simply not recorded | |
| P18 | `debug.logQueries = true` | Any write | CQL template at DEBUG, bound values **never** present in the log line | |
| P19 | `validate<T> { }` custom hook | Save a `User` violating the custom rule | `KandraValidationException` with correct `errors` | |
| P20 | `validate<T>` and `validateJakarta<T>` registered for the same class | Register both in sequence, then save an entity that only one of the two would reject | Confirm which validator actually runs — expected: **last registration wins**, not both | |
| P21 | `auth.refreshIntervalSeconds` | Wait past one interval, check logs | `onCredentialRefreshed`/`onAuthFailed` fires on schedule; confirm (per docs) whether the live session's auth actually changes or only the provider's internal cache does | |
| P22 | `eventListener.onEventualWriteFailed` | Force an `EVENTUAL` lookup write to fail (e.g. drop the lookup table manually mid-run) | Listener invoked, exception does not propagate to the caller of `save()` | |

## 3.9 DI integrations

| # | Integration | Verification | Expected | Result |
|---|---|---|---|---|
| I1 | `kandraKoin()` | Resolve `named("UserRepo")`/`named("UserSuspendRepo")` | Resolves, shares plugin's `BatchEngine` (confirm via shutdown-drain behavior being shared) | |
| I2 | `kandraKodein()` | Resolve tag `"User"`/`"UserSuspend"` | Resolves | |
| I3 | `kandraKoin()` called before `install(Koin)` | Deliberately wrong order | Throws (missing Koin instance) | |
| I4 | `kandraKodein()`/`kandraKoin()` called before `install(Kandra)` | Deliberately wrong order | Throws (missing `Application.kandraSession` attribute) | |
| I5 | `bindKandraRepository<T>()` (Kodein, standalone) | Build a small non-Ktor Kodein module per the skill's example | Own `BatchEngine`, own scope — confirm it does **not** share the plugin's shutdown-drain state | |

## 3.10 Migrations (`kandra-migrate`)

| # | Behavior | Verification | Expected | Result |
|---|---|---|---|---|
| M1 | First run | Fresh keyspace, run `V1`+`V2` | Both apply in order, recorded in `kandra_migrations` | |
| M2 | Second run, nothing changed | Re-run the same migrations | Both skipped (checksum matches), logged at DEBUG | |
| M3 | Checksum now includes bytecode (0.4.2 fix) | Edit `V1`'s `up()` body (add a harmless extra statement) without changing version/name/class, re-run | Per `docs/issues/ISS-017`: should now throw `KandraMigrationException` (checksum changed) — confirm this actually reproduces against a real run, not just in theory | |
| M4 | Concurrent runners, same version (0.4.2 fix) | Run two `KandraMigrationRunner.run()` calls concurrently (two threads/processes) against the same fresh keyspace with an unapplied migration | Per `docs/issues/ISS-018`: only one should execute `up()`; confirm via a side effect in `up()` (e.g. a counter increment) that it truly only ran once, not twice | |
| M5 | `up()` throws partway | Migration that executes one statement then throws | Not recorded as applied; already-run DDL stays applied; retried from the top on next `run()` | |
| M6 | `history()` | Call after M1 | Both migrations listed, sorted by version | |

## 3.11 `kandra-test` module

| # | Behavior | Verification | Expected | Result |
|---|---|---|---|---|
| T1 | `FakeKandraSession` + `KandraTestUtils.inMemory` | Write a unit test calling `repo.save(...)` | Throws `UnsupportedOperationException` (documented limitation, ISS-020) — confirm still true in 0.4.2 | |
| T2 | `KandraTestcontainers.freshKeyspace` | Write an integration test | Full repository round-trip works | |
| T3 | Fake session batch capture | Hand-build a `SimpleStatement`-based `BatchStatement`, execute via `FakeKandraSession` | Captured, `wasApplied() == true` always (can't simulate LWT failure) | |

## 3.12 `kandra-jakarta` module

| # | Behavior | Verification | Expected | Result |
|---|---|---|---|---|
| J1 | `validateJakarta<User>()` — valid entity | Save a `User` with a valid email/password | No exception | |
| J2 | `validateJakarta<User>()` — invalid entity | Save with a blank `displayName` or too-short `passwordHash` | `KandraValidationException` with matching `errors` | |
| J3 | `KandraJakartaSupport.isAvailable` — provider present | With Hibernate Validator on the classpath | `true` | |
| J4 | `validateJakarta<T>()` — no provider on classpath | Temporarily remove Hibernate Validator, rebuild | WARN logged, registration skipped, no crash | |

## 3.13 Multi-DC / consistency (`kandra-multidc`, `kandra-core`)

| # | Behavior | Verification | Expected | Result |
|---|---|---|---|---|
| X1 | `KandraMultiDc.describe()` output | Log it at startup | Exact line count/content per docs (6 lines minimum, always ending in the Failover policy line) | |
| X2 | Consistency resolution order | Save with a per-call `consistency` override on a class that also has `@WriteConsistency` | Per-call wins | |
| X3 | `LOCAL_QUORUM` vs `ONE` observable difference | Against the 3-node cluster, kill one node, attempt a `LOCAL_QUORUM` write | Fails/degrades (quorum lost with 1 of 3 down, if RF=3 and 2 nodes required); `ONE` still succeeds | |
| X4 | `saveIfNotExists` default `LOCAL_SERIAL` vs explicit `SERIAL` | Only meaningfully testable on the two-DC cluster (1.2.3) — otherwise mark N/A and say why | Two DCs can independently accept a "unique" row under `LOCAL_SERIAL`; `SERIAL` prevents it | |

---

Once every row above has a recorded result, proceed to
[file 4](04-edge-cases-and-adversarial-tests.md) for scenarios this matrix doesn't cover — boundary
values, malformed input, races, and resource exhaustion.
