# Kandra — Issues

Every issue Kandra has tracked gets its own file here, numbered `ISS-NNN`. Once an item is fixed
*and verified* (ideally against a live cluster, not just unit tests), leave it in place with a
`Fixed` status rather than deleting it — this folder doubles as a record of what's been shaken out
before. Entries prior to ISS-007 predate this folder and were removed once fixed, per the original
policy; nothing was lost, they're just not represented here.

## Open

Filed 2026-09-08 from a deep technical review (data integrity/consistency, performance/concurrency,
security, and distributed-systems correctness passes) done ahead of experimental testing against a
real ScyllaDB cluster. None of these have fixes yet.

| ID | GH | Severity | Title |
|---|---|---|---|
| [ISS-055](ISS-055-retry-ignores-idempotency.md) | [#56](https://github.com/Dev-Pasaka/kandra/issues/56) | Critical | `BatchEngine`'s retry loop ignores statement idempotency — non-idempotent writes can double-apply on retry |
| [ISS-056](ISS-056-saveifnotexists-blind-lwt-retry.md) | [#57](https://github.com/Dev-Pasaka/kandra/issues/57) | High | `saveIfNotExists`/`saveIfNotExistsSuspend` blindly retry their LWT, risking a false negative on the caller's own successful write |
| [ISS-058](ISS-058-versioned-update-drops-ttl.md) | [#59](https://github.com/Dev-Pasaka/kandra/issues/59) | High | `@Version` LWT updates silently strip TTL on every update to a `@Ttl`-annotated entity |
| [ISS-059](ISS-059-batchscope-blocking-collect.md) | [#60](https://github.com/Dev-Pasaka/kandra/issues/60) | High | `KandraBatchScope`'s statement collection still blocks the coroutine dispatcher on cache-miss prepare (ISS-049 leftover) |
| [ISS-061](ISS-061-softdelete-ignores-consistency.md) | [#62](https://github.com/Dev-Pasaka/kandra/issues/62) | Medium | Soft-delete writes bypass the configured consistency level |
| [ISS-069](ISS-069-assorted-low-severity-findings.md) | [#70](https://github.com/Dev-Pasaka/kandra/issues/70) | Low | Assorted lower-severity findings — items 1 (`@Sensitive` redaction) and 6 (list-column warning) fixed; items 2-5 open (warn-only injection guard default, RF>3 read-your-writes gap, no shard-aware driver/pool tuning, retry backoff lacks jitter) |

## Fixed — pending live-cluster verification

These compile and pass unit tests, but haven't yet been run against a real Testcontainers-backed
cluster in this environment (no Docker daemon available). Run `./gradlew test` somewhere with
Docker before relying on them.

| ID | Title |
|---|---|
| [ISS-007](ISS-007-find-active-soft-delete.md) | `findActive()` for `@SoftDelete` entities |
| [ISS-013](ISS-013-no-integration-tests.md) | No integration tests against a real cluster |
| [ISS-014](ISS-014-blocking-query-executor.md) | `QueryExecutor` blocking calls in the suspend read path |
| [ISS-015](ISS-015-delete-by-id-bypasses-soft-delete.md) | `KandraRepository.deleteById` bypassed `@SoftDelete` |
| [ISS-017](ISS-017-migration-checksum-not-body-based.md) | Migration checksum didn't hash the migration body |
| [ISS-018](ISS-018-migration-no-locking.md) | No locking in `KandraMigrationRunner` |
| [ISS-019](ISS-019-collection-counter-consistency.md) | Collection/counter statements ignored consistency levels |

## Fixed

| ID | Title |
|---|---|
| [ISS-011](ISS-011-jakarta-bean-validation.md) | Jakarta Bean Validation not auto-detected |
| [ISS-021](ISS-021-allow-filtering-error-message.md) | Error message referenced a non-existent `allowFiltering()` |
| [ISS-022](ISS-022-codegen-collection-raw-types.md) | `kandra-codegen` generated invalid Kotlin for any `Set`/`Map` column |
| [ISS-023](ISS-023-cache-reflection-illegal-access.md) | `KandraCache` crashed with `IllegalAccessException` on every real-Caffeine call |
| [ISS-024](ISS-024-empty-collection-decode-throws.md) | Non-nullable empty `Set`/`Map` columns were permanently unreadable |
| [ISS-025](ISS-025-clustering-key-where-clause-omitted.md) | Key-based repository operations omitted clustering keys from their WHERE clause |
| [ISS-026](ISS-026-batch-scope-save-unreachable.md) | `KandraBatchScope`'s `save`/`delete` were structurally unreachable — batches never batched |
| [ISS-027](ISS-027-batch-scope-save-if-not-exists-guard-unreachable.md) | `KandraBatchScope`'s `saveIfNotExists` guard was also unreachable |
| [ISS-028](ISS-028-cache-invalidation-key-mismatch.md) | Cache invalidation silently missed the real entry for clustering-keyed entities |
| [ISS-029](ISS-029-lookup-index-clustering-key-broken.md) | `@LookupIndex` resolution broke entirely for entities with a clustering key |
| [ISS-030](ISS-030-soft-delete-removes-lookup-rows.md) | Soft-delete unconditionally removed lookup-table rows |
| [ISS-031](ISS-031-runtime-tests-ktor-ci-exclusion.md) | `kandra-runtime` had no unit tests, and `kandra-ktor`'s tests never ran in CI |
| [ISS-032](ISS-032-versioned-update-spurious-optimistic-lock.md) | `@Version` LWT updates were blindly retried on transient errors, causing spurious `KandraOptimisticLockException` |
| [ISS-033](ISS-033-eventual-lookup-bypasses-safeguards.md) | `EVENTUAL` lookup writes bypassed retry, `inFlightCount`, and the shutdown gate |
| [ISS-034](ISS-034-uncached-entity-reflection.md) | Entity reflection (copy fn, properties, constructor) re-resolved uncached on every call |
| [ISS-035](ISS-035-lookupindex-softdelete-storage-growth.md) | `@LookupIndex` + `@SoftDelete` storage-growth implication was undocumented |
| [ISS-036](ISS-036-findactive-allow-filtering-scope.md) | `findActive()`'s `ALLOW FILTERING` was a silent default — now an explicit `allowFullScan` opt-in |
| [ISS-037](ISS-037-consistency-strict-mode.md) | Consistency Strict Mode — warn on `LOCAL_ONE`/`ONE` in multi-DC deployments |
| [ISS-038](ISS-038-typed-di-codegen-accessors.md) | `kandra-koin`/`kandra-kodein` qualifiers were hand-typed strings with no compile-time safety |
| [ISS-039](ISS-039-migration-lwt-prerequisite.md) | Migration runner requires LWT support as an explicit prerequisite |
| [ISS-040](ISS-040-fake-session-bind-unsupported.md) | `FakeKandraSession` couldn't execute any prepared statement — `bind()` always threw |
| [ISS-041](ISS-041-di-qualifier-collision.md) | `kandra-koin`/`kandra-kodein` qualifiers collided for same-named entities in different packages |
| [ISS-042](ISS-042-shutdown-drain-busy-wait.md) | Graceful shutdown drain busy-waited with `Thread.sleep` instead of suspending |
| [ISS-043](ISS-043-migration-crash-safety.md) | Interrupted or racing migration runs could leave a migration marked applied before it completes |
| [ISS-044](ISS-044-schema-registry-validation-gaps.md) | `SchemaRegistry` didn't validate several illegal annotation states at registration time |
| [ISS-045](ISS-045-ddl-generator-invalid-cql.md) | `DdlGenerator` could silently produce invalid or data-losing CQL |
| [ISS-046](ISS-046-codegen-content-assertion-tests.md) | `kandra-codegen`'s test suite never asserted on generated file content |
| [ISS-047](ISS-047-batchengine-safety-bypass.md) | Several write paths bypassed `BatchEngine`'s shutdown gate, retry, and in-flight tracking |
| [ISS-048](ISS-048-repository-statementbuilder-config-bypass.md) | `KandraRepository`/`KandraSuspendRepository` built their own default `StatementBuilder`, discarding the plugin's configured consistency/codec/debug/cache-size on every read |
| [ISS-049](ISS-049-suspend-blocking-prepare.md) | Suspend read/write paths still blocked the coroutine dispatcher on prepared-statement cache misses |
| [ISS-050](ISS-050-raw-query-injection-guard.md) | `raw()`/`rawQuery()`'s CQL-injection guard only fired under a narrow condition and never blocked execution |
| [ISS-051](ISS-051-columnref-cqlname-validation.md) | `KandraColumnRef`'s public constructor accepted an unvalidated `cqlName` |
| [ISS-052](ISS-052-jakarta-codegen-health-polish.md) | Assorted polish — Jakarta validator factory reuse, codegen nullability, health endpoint debounce |
| [ISS-053](ISS-053-batch-and-versioned-update-ignore-consistency.md) | `LOGGED BATCH` writes and `@Version` LWT updates ignored the configured consistency level entirely |
| [ISS-054](ISS-054-generic-reads-ignore-read-consistency.md) | Generic `find`/`findAll`/`findPage` reads ignored configured read consistency, with no override |
| [ISS-057](ISS-057-multidc-failover-inert.md) | Multi-DC failover/load-balancing config was validated and documented, but never wired into the driver |
| [ISS-060](ISS-060-credential-rotation-noop.md) | Credential rotation (`auth.refreshIntervalSeconds`) refreshed credentials but never applied them to the live session |
| [ISS-062](ISS-062-migration-checksum-false-positive-risk.md) | `KandraMigration.checksum()`'s bytecode hash risked false-positive startup failures after cosmetic recompilation |
| [ISS-063](ISS-063-migration-claim-staleness-clock-skew.md) | Migration claim staleness was computed from wall-clock timestamps across potentially skewed app instances |
| [ISS-064](ISS-064-keyspace-dc-identifiers-unvalidated.md) | Keyspace and DC-name identifiers were spliced unvalidated into CQL/DDL |
| [ISS-065](ISS-065-credentials-tostring-leak.md) | `KandraCredentials`' auto-generated `toString()` would print the plaintext password |
| [ISS-066](ISS-066-findall-no-row-cap.md) | `findAll`/`exists`-style reads had no default row cap — memory-exhaustion vector |
| [ISS-067](ISS-067-decodeentity-rebuilds-column-map.md) | `QueryExecutor.decodeEntity` rebuilt the full column map on every row decoded instead of caching it |
| [ISS-068](ISS-068-localrequestsperconnection-dead-config.md) | `PoolConfig.localRequestsPerConnection` was dead configuration — removed |

## Closed — not a bug

| ID | Title |
|---|---|
| [ISS-016](ISS-016-collection-codec-lookup.md) | Collection codec lookup — verified correct against driver internals |

## Known limitation — by design

| ID | Title |
|---|---|
| [ISS-020](ISS-020-fake-session-lwt-semantics.md) | `FakeKandraSession` never exercises real LWT semantics |
