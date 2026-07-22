# Functional Coverage Matrix — Results

Mirrors test-plan/03-functional-coverage-matrix.md. Only rows with a recorded Result have been executed;
blank Result = not yet attempted. Environment for all rows below unless noted: primary 3-node ScyllaDB 5.4
cluster (docker-compose.scylla.yml), keyspace `kandra_sample` RF=3, published kandra 0.4.2 artifacts,
JDK 21 (JBR 21.0.10), auth disabled (SCYLLA_USERNAME/PASSWORD set to empty string to skip auth — see
finding in PROGRESS.md re: KandraAuth.fromEnv() throwing on truly-unset vars).

## 3.1 Core annotations

| # | Annotation / behavior | Result | Note |
|---|---|---|---|
| C1 | `@ScyllaTable(name, gcGraceSeconds)` | **PASS** | `DESCRIBE TABLE users` shows `gc_grace_seconds = 864000` exactly. |
| C2 | `@ScyllaTable` missing entirely | **PASS** | `KandraSchemaException: Class 'BrokenNoTable' is missing @ScyllaTable annotation.` |
| C3 | `@PartitionKey` single | **PASS** | `PRIMARY KEY (user_id, bucketed_at)` — matches expected shape. |
| C4 | `@PartitionKey` composite | **PASS** | `AuditLog` DDL: `PRIMARY KEY ((tenant_id, entity_type), occurred_at)` — exact double-paren composite shape. |
| C5 | Duplicate `@PartitionKey(index)` | **PASS** | `KandraSchemaException: Duplicate @PartitionKey index 0 on BrokenDupPkIndex` |
| C6 | No `@PartitionKey` at all | **PASS** | `KandraSchemaException: Class 'BrokenNoPk' has no @PartitionKey property. Exactly one is required.` |
| C7 | `@ClusteringKey(order, index)` | **PASS** | `users`: `CLUSTERING ORDER BY (bucketed_at DESC)`. `audit_logs`: `CLUSTERING ORDER BY (occurred_at DESC)`. |
| C8 | `@LookupIndex` BATCH | BLOCKED (partial) | Table structure confirmed correct (`users_by_email(email PK, user_id)`) via DDL inspection. Same-request atomicity (row present immediately after save) NOT YET independently timed/verified — needs a dedicated save-then-immediately-query-lookup-table check. |
| C9 | `@LookupIndex` EVENTUAL | not yet attempted | |
| C10 | Duplicate `@LookupIndex` table name | **PASS** | `KandraSchemaException: Class 'BrokenDupLookup' has duplicate @LookupIndex table name 'broken_dup_lookup_by_x' on properties: x, y` |
| C11 | `@Column(name)` | **PARTIAL** | `display_name` column present as expected, BUT the plan's own chosen example (`displayName` → `display_name`) is what `camelToSnake` auto-derivation would ALSO produce with no `@Column` override at all — this test case doesn't actually distinguish "explicit override took effect" from "coincidentally matches auto-derivation." Not a Kandra bug — a gap in the test case's design. To truly verify C11, need a property where the explicit name differs from auto-derivation (not yet done). |
| C12 | `@Transient` | **PASS** | `sessionToken` absent entirely from `users` DDL. |
| C13 | `@Ttl(seconds)` class default | **PASS** | `otp_codes` DDL: `default_time_to_live = 300` exactly. |
| C14 | `@Counter` all-non-key | **PASS** | `page_view_counters` DDL: both `views counter`, `unique_visitors counter`. |
| C15 | `@Counter` mixed with non-counter | **PASS** | `KandraSchemaException: Class 'BrokenMixedCounter' mixes @Counter and non-@Counter columns. All non-key columns must be @Counter in a counter table.` |
| C16 | `@Counter` + non-counter `@LookupIndex` | **PASS** (with nuance) | Does throw as expected — BUT via the exact same generic "mixes @Counter and non-@Counter columns" message as C15, not a distinct lookup-specific check/message. Functionally correct (throws), but not a functionally distinct validation path from C15 as the skill's phrasing might suggest. |
| C17 | `@CreatedAt` | not yet attempted (need update-then-recheck) | |
| C18 | `@UpdatedAt` | not yet attempted | |
| C19 | `@CreatedAt`/`@UpdatedAt` on non-`Instant` field | **PASS** | `KandraSchemaException: @CreatedAt on 'BrokenCreatedAtType.createdAt' must be an Instant field.` |
| C20 | Two `@CreatedAt` on one entity | **PASS** | `KandraSchemaException: At most one @CreatedAt per entity (BrokenDoubleCreatedAt)` |
| C21 | `@Version` `Long` | **PASS** | Confirmed via direct cqlsh read after `save()`: `version = 1` on first save (not 0). |
| C22 | `@Version` on invalid type | **PASS** | `KandraSchemaException: @Version column 'BrokenVersionType.version' must be Long or Instant, got: kotlin.String` — names the actual KType as expected. |
| C23 | `@SoftDelete(ttlSeconds, markerProperty)` | not yet attempted (full timing sequence — see file 4 §4.3) | |
| C24 | `@Sensitive` masking | not yet attempted (plain Kotlin `toString()` does NOT apply masking — must use internal `KandraEntityLogger.safeToString`, confirmed by reading source; masking is a consumer concern per kandra-core skill) | |
| C25 | `@CacheResult(ttlSeconds, maxSize)` | **FAIL — Critical** | See PROGRESS.md finding #7: `KandraCache.resolveMethod()` throws `IllegalAccessException` against a real Caffeine cache instance on every reflective call (`invalidate` observed first, on `save()`). `@CacheResult` had to be removed from `User` to unblock the rest of testing. Cannot be independently verified as "cache hit on 2nd findById" since the cache crashes before ever being usable. |
| C26 | `@CacheResult` without Caffeine on classpath | not yet attempted (independent of the C25 bug — this specific scenario does NOT hit the crash since no real Caffeine object is ever constructed) | |
| C27 | `@SecondaryIndex` | **PASS** (structure); WARN-log claim not yet independently confirmed | `CREATE INDEX users_status_idx ON kandra_sample.users (status)` present. ScyllaDB additionally auto-creates a backing materialized view (`users_status_idx_index`) for this index — this is ScyllaDB's own implementation detail for global secondary indexes, not something Kandra's DDL generator explicitly emits (Kandra only issues `CREATE INDEX`). |
| C28 | `@ReadConsistency`/`@WriteConsistency` (class) | not yet attempted (needs driver tracing or node-kill differential test, see file 4 §4.15) | |
| C29 | Kotlin→CQL type mapping | **PASS** (DDL-level), with an important scope clarification tied to Finding #6 | `users` DDL confirms: `uuid`, `timestamp`, `decimal`, `map<text,text>`, `set<text>`, `text` (enum) all correctly mapped by `DdlGenerator`/`kandra-core`. **This is a DIFFERENT code path from the broken `kandra-codegen` KSP Table-ref generation (Finding #6)** — the actual DDL type mapping for Set/Map is correct; only the KSP-generated `*Table.kt`'s `KandraColumnRef<T>` type-argument generation is broken. Scoping this precisely: kandra-core's schema/DDL layer handles collections fine; kandra-codegen's KSP processor does not. |
| C30 | Enum column round-trip | **PASS** | Created user with `status=ACTIVE` (raw request field), read back via `GET /users?status=ACTIVE` — decoded correctly as `UserStatus.ACTIVE` (route returned the entity without decode errors). |
| C31 | Enum column with a stale stored value | not yet attempted | |

## 3.2 SchemaMode

| # | Mode | Result | Note |
|---|---|---|---|
| S1 | `AUTO_CREATE` | **PASS** | Fresh keyspace; all 5 registered entities' tables + lookup tables created (`CREATE TABLE IF NOT EXISTS` behavior consistent with observed DDL for all tables inspected so far). |
| S2-S7 | (AUTO_MIGRATE new col / type change / removed col, VALIDATE match / mismatch, NONE) | not yet attempted | |

## 3.3-3.13

Not yet attempted — save/update/delete/read/collection/counter families (R/U/D/F/K series), plugin config
(P series), DI (I series), migrations (M series), kandra-test (T series), kandra-jakarta (J series),
multi-DC/consistency (X series, except X1 below).

| # | Behavior | Result | Note |
|---|---|---|---|
| X1 | `KandraMultiDc.describe()` output | **PASS** | Logged exactly 7 lines (header + 6 data lines), last data line is `Failover policy: THROW` — matches "6 lines minimum, always ending in Failover policy line." |

## File 4 edge cases — results so far

| Scenario | Result | Note |
|---|---|---|
| §4.12 bullet 3 — `KandraAuth.fromEnv()` unset env vars | **PASS** | `KandraAuthException: Environment variable 'SCYLLA_USERNAME' is not set. Set it or configure a different KandraAuthProvider.` when only username unset (asymmetric wording — suggests alternative provider). Source-confirmed (`KandraAuth.kt:39-47`) the password-missing message (`"Environment variable 'SCYLLA_PASSWORD' is not set."`) genuinely lacks that same suggestion — asymmetry confirmed exactly as documented. |
| file 4 §4.10 — validation order on double-violation entity | **PASS (informational)** | `BrokenDoubleViolation` (no `@PartitionKey` AND invalid `@Version` type simultaneously) throws the **no-`@PartitionKey`** error, not the `@Version`-type error — confirms partition-key validation runs before version-type validation in `SchemaRegistry`'s check order. |

## 3.3-3.5 Save/update/delete family (direct-repository harness results)

| # | Method | Result | Note |
|---|---|---|---|
| R1 | `save()` nullable field left null | **PARTIAL/confirm-in-progress** | Confirmed UNSET semantics don't tombstone unrelated columns; deferred precise WRITETIME check due to an unrelated test-harness raw-CQL date literal issue (own bug, not Kandra's — fixed by using bind params, not yet re-run). |
| R2 | `save()` on counter table | not yet attempted | |
| R3 | `saveIfNotExists()` first call | **PASS** | `first=true`, row created. |
| R4 | `saveIfNotExists()` conflicting call, SAME primary key | **PASS** | `first=true, second=false`. Lookup table row count stayed at 1 (not 2) — confirms no lookup writes attempted on the failed second call, exactly as documented. (Note: initial attempt via HTTP route mistakenly varied `bucketedAt` between the two calls since `User`'s PK is composite (partition+clustering) — since each call used `Instant.now()`, both "conflicting" calls actually had different full primary keys and both succeeded, which is CORRECT behavior for that scenario, not a bug — the true single-primary-key conflict test needed the direct-repository harness with an identical entity object.) |
| U1 | `update(old,new)` w/ `@Version`, no conflict | **FAIL — blocked by Finding #9** | `InvalidQueryException: Missing mandatory PRIMARY KEY part bucketed_at` — `update()`'s LWT UPDATE statement omits the clustering key from its WHERE clause. Completely broken on `User` (clustering-keyed entity). |
| U2 | Concurrent `update(old,new)` contention | **FAIL — blocked by Finding #9** | Same root cause as U1 — never reached the actual LWT-contention question. |
| U3 | `updateForce(entity)` | **FAIL (near-certain) — blocked by Finding #9** | Shares the identical `whereParts = partitionKeys-only` code path (`BatchEngine.kt:646-647`) — not yet independently re-isolated after Finding #8 interference, but same root cause applies. |
| U4 | `update(old,new)`, `@LookupIndex` field changes | **PASS** | Old lookup row (`users_by_email` for the original email) confirmed gone, new lookup row confirmed present, after `update()`. (This uses `User`'s partition key only in this specific test's assertions, not a clustering-key-dependent path, so unaffected by Finding #9's WHERE-clause bug — the `update()` call itself still hit the Finding #9 crash in isolation tests, but this particular test happened to run before that was isolated; needs re-confirmation post-fix-report — treat as a plausible PASS on the *lookup-diffing logic specifically*, contingent on U1's blocking bug being worked around for a clean re-run.) |
| U5 | `updateForce(entity)`, `@LookupIndex` field changes | **PASS (confirms documented gap)** | Stale old lookup row confirmed **still present** after `updateForce()` (expected per docs — `updateForce` never cleans up stale lookup rows), new lookup row also present. Matches `kandra-runtime` skill's documented `updateForce` gotcha exactly. |
| D1 | `delete(entity)`, no `@SoftDelete` | **PASS** | Hard delete; `findById` returns null immediately after. |
| D2 | `delete(entity)`, `@SoftDelete` present | **PASS** | See C23/§4.3 below — fully verified via `Wallet`. |
| D4 | `deleteById()` blocking repo, `@SoftDelete` entity | **not yet attempted** (needs blocking, not suspend, repository — not built yet) | |
| D5 | `deleteById()` cache invalidation | **N/A — superseded by Finding #7** | Cache is Critically broken for any real use (Finding #7); not independently testable. |

## 3.6 Read family

| # | Method | Result | Note |
|---|---|---|---|
| F1 | `findById()` | **FAIL — Finding #8 (empty collections) + Finding #9 (clustering key) both apply to `User`** | See findings. `findById` works fine on non-clustering-keyed, non-empty-collection entities (confirmed via `Wallet` in D1/C23 tests). |
| F3 | `find {}` no predicates | **PASS** | `KandraQueryException: Query must have at least one predicate.` exact match. |
| F6 | `findAll` IN on composite PK | **PASS** | `KandraSchemaException: IN on partition key is only supported for single-column partition keys. Table 'audit_logs' has a composite partition key.` — correct type (`KandraSchemaException` not `KandraQueryException`), confirms file 6 §6.6's documented distinction. |
| F7 | `findAll` IN on non-indexed column | **PASS — confirms ISS-021 fix** | `KandraQueryException: IN on column 'display_name' requires either a partition key column or ALLOW FILTERING, which Kandra does not support. Add a @SecondaryIndex to the column instead.` — does NOT reference a nonexistent `allowFiltering()` method (the pre-0.4.2 bug); correctly suggests `@SecondaryIndex`. **Confirmed fixed.** |

## 3.10 Migrations — ALL PASS, confirms three separate 0.4.2 fixes hold up under real concurrency/checksum testing

| # | Behavior | Result | Note |
|---|---|---|---|
| M1 | First run | **PASS** | V1+V2 applied in order, `migration_side_effects` row count = 1. |
| M2 | Second run, nothing changed | **PASS** | Re-run is idempotent — count stayed 1, not 2. |
| M6 | `history()` | **PASS** | Both migrations listed, sorted by version, with checksums and timestamps. |
| file 4 §4.5 | Concurrent runners, same version | **PASS — confirms ISS-018 fix** | Two `KandraMigrationRunner`s racing the identical unapplied migration version, released via `CountDownLatch` as close to simultaneously as possible: side-effect table ended with **exactly 1** row, not 2. The LWT claim-locking fix genuinely works under real concurrency, not just in theory. |
| file 4 §4.6 bullet 1 | Checksum changes when `up()` CQL body changes | **PASS — confirms ISS-017 fix** | Changed a `CREATE TABLE IF NOT EXISTS` column list inside `up()` without touching version/name/class → re-run threw `KandraMigrationException: ... checksum mismatch ... Expected: <hash1>, got: <hash2>`. Confirms the checksum now genuinely includes compiled bytecode, not just `version:name:class`. |

## 4.3 `@SoftDelete` + `findActive()` timing window — ALL STEPS PASS, confirms ISS-007 fix holds under real timing

Tested against `Wallet` (`ttlSeconds=10`, `markerProperty="isDeleted"`), full 6-step sequence from the plan:

| Step | Result |
|---|---|
| 1. `findActive()` includes a fresh wallet | **PASS** |
| 2a. `findById()` immediately after `delete()` still returns the row (non-key cols not yet gone) | **PASS** — `Wallet(..., isDeleted=true)` returned. |
| 2b. `findActive()` immediately after `delete()` already excludes it | **PASS** — marker-column write confirmed synchronous (not fire-and-forget), matching the documented "no TTL on marker column" design intent exactly. |
| 4. Race-loop for ~1s confirms no window where it reappears | **PASS** — never reappeared. |
| 3. After TTL (13s wait): row still exists (tombstone), non-key columns NULL, marker survives | **PASS** — `ownerId=NULL, balanceCents=NULL, isDeleted=true` confirmed via direct read. |
| 3b. `findActive()` still excludes it after TTL expiry | **PASS** — marker didn't get swept away with the TTL'd columns. |

This is a clean, comprehensive confirmation of the single most timing-sensitive 0.4.2 feature per the plan's own framing.

## 3.8 Plugin config — all PASS

| # | Config | Result | Note |
|---|---|---|---|
| P1 | `keyspace` blank | **PASS** | `KandraSchemaException: Kandra: 'keyspace' must be set in the plugin configuration.` — thrown before any network call (via `testApplication` harness, no real contact point even needed to trigger it). |
| P2 | `autoCreateKeyspace = true` | **PASS** | Keyspace created; confirmed via `system_schema.keyspaces` — `{'class': 'SimpleStrategy', 'replication_factor': '3'}` exactly matches configured `ReplicationStrategy.SimpleStrategy(replicationFactor = 3)`. |
| P10 | `dcAwareFailover = true` + empty `allowedRemoteDcs` | **PASS** | `KandraSchemaException: loadBalancing.dcAwareFailover = true but allowedRemoteDcs is empty. Provide at least one remote DC or set dcAwareFailover = false.` — thrown at session-build time, before any connection. |
| P11 | `RETRY_REMOTE_DC` + empty `allowedRemoteDcs` | **PASS** | `KandraSchemaException: failover.onLocalDcUnavailable = RETRY_REMOTE_DC but loadBalancing.allowedRemoteDcs is empty.` — confirmed as an **independent** check from P10 (fires even with `dcAwareFailover` left at its default `false`). |
| P17 | `metrics.enabled = true`, no `recorder` | **PASS** | No exception; WARN logged exactly: `metrics.enabled=true but no recorder was configured — metrics will not be recorded.` |

## 3.9 DI integrations

| # | Integration | Result | Note |
|---|---|---|---|
| I1 | `kandraKoin()` | **PASS** | Resolved both `named("UserRepo")` and `named("UserSuspendRepo")` cleanly, no cast exception. |
| I2 | `kandraKodein()` | **PASS** | Resolved both tag `"User"` and `"UserSuspend"` cleanly, no cast exception. |
| I3-I5 | wrong-order/standalone-binder variants | not yet attempted | |

## Critical/High findings requiring full write-up in final report (see PROGRESS.md for detail, condensed here)

1. **CRITICAL** — `kandra-codegen` generates non-compiling code (`KandraColumnRef<kotlin.collections.Set>`,
   no type args) for any `Set`/`Map` column. Blocks compilation of any codegen-registered entity with a
   collection column. Worked around locally (own project's generated-source patch, not a library fix) to
   continue testing — ties to C29/K1-K3/capstone tag search.
2. **CRITICAL** — `KandraCache.resolveMethod()` throws `IllegalAccessException` on every real-Caffeine
   cache call (reflection against a package-private Caffeine impl class, no `setAccessible(true)`).
   Blocks all writes to any `@CacheResult` entity. `@CacheResult` removed from `User` to unblock testing.
   Ties to C25, D5, file 4 §4.7.
3. **CRITICAL** — non-nullable empty `Set`/`Map` columns (the idiomatic Kotlin default, e.g.
   `tags: Set<String> = emptySet()`) can never be read back once saved — `KandraCodec.decode()`'s blanket
   `row.isNull()` check throws for any non-nullable type, with no collection special-case (unlike the
   DataStax driver's own collection getters, which return empty instead of null for exactly this reason).
   Ties to C29, F1, and the capstone's `Post.tags`.
4. **CRITICAL/HIGH** — every single-row key-based repository operation (`findById`, `deleteById`, `append`,
   `remove`, `put`, `increment`, `decrement`, `update` w/ `@Version`, `updateForce`, and the soft-delete
   rewrite) builds its WHERE clause from partition keys only, never clustering keys — one repeated code
   pattern across ~8 call sites in `StatementBuilder.kt`/`BatchEngine.kt`. On any clustering-keyed entity
   (`User`, `AuditLog` in this plan): `append`/`remove`/`put`/`update`/`updateForce` hard-crash with a
   driver-level `InvalidQueryException`; `findById` silently returns an arbitrary row from the partition;
   `deleteById` silently deletes the **entire partition**, not one row (real data-loss risk). Ties to
   U1-U3, D4, F1, K1-K3, and the capstone (`Post` is also clustering-keyed).
