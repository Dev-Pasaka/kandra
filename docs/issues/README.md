# Kandra — Issues

Every issue Kandra has tracked gets its own file here, numbered `ISS-NNN`. Once an item is fixed
*and verified* (ideally against a live cluster, not just unit tests), leave it in place with a
`Fixed` status rather than deleting it — this folder doubles as a record of what's been shaken out
before. Entries prior to ISS-007 predate this folder and were removed once fixed, per the original
policy; nothing was lost, they're just not represented here.

## Open

_None currently open._

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

## Closed — not a bug

| ID | Title |
|---|---|
| [ISS-016](ISS-016-collection-codec-lookup.md) | Collection codec lookup — verified correct against driver internals |

## Known limitation — by design

| ID | Title |
|---|---|
| [ISS-020](ISS-020-fake-session-lwt-semantics.md) | `FakeKandraSession` never exercises real LWT semantics |
