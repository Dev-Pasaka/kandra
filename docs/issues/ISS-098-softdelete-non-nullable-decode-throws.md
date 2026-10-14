# ISS-098: `@SoftDelete` reads throw once a deleted row's TTL expires if any property is non-nullable

**Status:** Fixed

## Problem

Filed as GH #153, found while verifying the kandraorm.com quickstart/tutorial against the
published 0.4.7 artifacts.

`@SoftDelete` writes every non-key, non-marker column with `USING TTL ttlSeconds`
(`BatchEngine.softDeleteBlocking`/`softDeleteSuspend`). Once that TTL expires, those cells read
back as `NULL`. Decoding a row where a non-nullable property's column is `NULL` throws
(`KandraCodec`):

```
KandraQueryException: Column 'title' is NULL in Scylla but property 'title' is non-nullable (String).
```

So any read that could reach a soft-deleted-but-not-yet-fully-expired row — `findAll`, a
partition-scoped `find`, `findActive(allowFullScan = true)` — failed outright. For an entity with a
non-nullable `title: String`, one deleted row older than `ttlSeconds` broke every page read for
that partition.

## Fix

`SchemaRegistry.register()` now rejects a `@SoftDelete` entity at registration time if any
persisted, non-key, non-marker, non-counter property is non-nullable **and has no constructor
default** — reusing `TableSchema`'s already-captured `constructorParameters`
(`KParameter.isOptional`), the same reflection surface used elsewhere in this file. Throws
`KandraSchemaException` naming the offending property, e.g. `"Todo.title must be nullable:
@SoftDelete expires it after ttlSeconds"`.

This check applies even on an entity with no `markerProperty` configured at all — the NULL-on-expiry
problem comes from `ttlSeconds` alone, independent of whether `findActive()` is usable on the
entity.

Paired with [ISS-097](ISS-097-softdelete-marker-no-ttl-permanent-husk.md)'s fix (the marker now
also expires, instead of leaving a permanent husk), the decode-NULL window is bounded (between
`ttlSeconds` and `markerTtlSeconds`) rather than the row living forever in that broken state.

**Files:** `kandra-core/.../SchemaRegistry.kt` (the registration-time guard).

**Tests:** `SchemaRegistrySoftDeleteTtlTest` — covers non-nullable-with-no-default (throws),
non-nullable-with-a-default (allowed), and the no-`markerProperty` case (guard still applies).

See GH #153, [PR #158](https://github.com/Dev-Pasaka/kandra/pull/158) (same PR as ISS-097 — both
fixes touch the same `@SoftDelete` lifecycle).
