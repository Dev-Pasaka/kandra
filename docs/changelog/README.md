# Changelog

All notable changes to Kandra, one file per version — newest first.

| Version | Highlights |
|---|---|
| [0.5.1](0.5.1.md) | `@CacheResult` invalidation fix for microsecond `Instant` keys (#178); `kandra-test` usability — DataStax driver as `api`, `KandraTestUtils.reset()`, public captured-statement view (#179–#181); stable container-session timeouts in tests; installable Claude Code plugin. Released 2026-10-20 |
| [0.5.0](0.5.0.md) | Multi-keyspace support: one `install(Kandra)` and one `CqlSession` serving many keyspaces via `@ScyllaTable(keyspace = ...)` and a `keyspace("x") { }` config block; the feature itself is backward compatible. Also: **breaking** — Ktor 3.6 / Kotlin 2.3 (Ktor 2.x dropped) and the Maven group moves to `com.kandraorm.kandra`; opt-in `SimpleStrategy(disableTablets = true)`, per-keyspace migrations, codegen keyspace validation, and the `KandraTestBackend`/`KandraCloud` test backend. Released 2026-10-20 |
| [0.4.8](0.4.8.md) | 5 real bugs found verifying the published 0.4.7 artifacts against the kandraorm.com quickstart — `implementation`→`api` dependency scopes, `@SoftDelete` marker TTL (including a row-marker gap only a real-cluster test caught), the matching non-nullable-column guard, a codegen default-package fix, and the documented Ktor version range |
| [0.4.7](0.4.7.md) | Consolidates 66 PRs (GH #5–#151) merged with no version bump since 0.4.6: three adversarial review rounds, `saveAndGet`/`saveIfNotExistsAndGet`/`updateAndGet`, `KandraThrottledException`/`throttle { }`, consistency-level validation, and the multi-DC "not yet validated against a genuine multi-region topology" disclosure |
| [0.4.6](0.4.6.md) | `@GeneratedUuid` — UUIDv7/v4 auto-populated key columns, fixing a silent same-millisecond clustering-key collision risk |
| [0.4.5](0.4.5.md) | **Breaking**: query DSL drops the `+` predicate-registration operator, fixing a silent-predicate-drop footgun |
| [0.4.4](0.4.4.md) | Fixes 3 regressions/gaps surfaced while re-verifying 0.4.3's fixes end-to-end: cache-invalidation key mismatch, `@LookupIndex` + clustering key, soft-delete removing lookup rows |
| [0.4.3](0.4.3.md) | Fixes all 5 Critical/High bugs found by the first real-cluster test plan: codegen `Set`/`Map` codegen, cache reflection crash, empty-collection decode, clustering-key WHERE-clause omission, `KandraBatchScope` non-atomicity |
| [0.4.2](0.4.2.md) | Async read path, `findActive()`, `kandra-jakarta`, real-cluster integration tests, migration locking/checksum fixes |
| [0.3.0-SNAPSHOT](0.3.0.md) | Retry policy, `kandra-multidc`, auth/SSL, idempotency, secondary indexes |
| [0.2.0-SNAPSHOT](0.2.0.md) | Composite keys, TTL, LWT, pagination, counters, collections |
| [0.1.0-SNAPSHOT](0.1.0.md) | Initial release |

For the reasoning behind individual fixes, see [`docs/issues/`](../issues/README.md). For the
current feature surface, see [`docs/features/`](../features/README.md).
