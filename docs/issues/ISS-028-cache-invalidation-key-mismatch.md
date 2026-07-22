# ISS-028: Cache invalidation silently missed the real cache entry for clustering-keyed entities

**Status:** Fixed, verified live

## Problem

Found during `docs/test-plan:1.1` re-verification of the ISS-025 fix. `KandraRepository`/
`KandraSuspendRepository`'s `save`/`saveIfNotExists`/`saveAll`/`update`/`updateForce`/`saveWithNulls`/
`delete`/`deleteAll` all invalidated the `@CacheResult` cache using `partitionKeyOf(entity)` — a helper
that collects **only** `schema.partitionKeys`, returning a bare single value for any entity (e.g. just
`userId` for `User`). But `findById`'s real cache key is derived from its actual `idValues` arguments:
a bare single value only when the entity's full primary key is exactly one column, otherwise an ordered
`List` of every value passed in. Since ISS-025 made `findById` require the **full** key (partition +
clustering) for any clustering-keyed entity, its real cache key became a multi-element `List` — which
never equals the bare single value `partitionKeyOf` produces. Every invalidation call for a
clustering-keyed cached entity silently missed the real entry, so `update()` (and friends) appeared to
succeed while `findById` kept serving the pre-update, stale cached row until the TTL expired on its own.

This was not a live bug before ISS-025 shipped: `findById(id)` (partition-key-only, one argument) was
the "normal" way to call it on a clustering-keyed entity at the time (silently wrong-row, a different
already-known issue), and in that world its cache key was *also* a bare single value, matching
`partitionKeyOf`'s shape by coincidence. Tightening `findById`'s key contract in ISS-025 changed the
cache key's shape too, but the invalidation helper was never updated to match — a scope gap in that
fix, not a new mistake introduced independently.

## Fix

Replaced `partitionKeyOf` with `cacheKeyOf`, which reuses the existing `keyValuesOf` helper (already
used by `append`/`remove`/`put` to derive the full key) and applies the exact same shape convention
`findById` uses: a bare single value when the full key is one column, otherwise the ordered `List`.

**Files:** `kandra-runtime/.../repository/KandraRepository.kt`,
`kandra-runtime/.../repository/KandraSuspendRepository.kt`.

**Verification:** live, against a real 3-node ScyllaDB cluster: saved a clustering-keyed `@CacheResult`
entity, `findById` (full key) to populate the cache, waited past a second, `update()`'d a field, then
`findById` (same full key) again — now returns the fresh value, not the stale cached one. Also added
permanent regression coverage in `kandra-test`'s `KandraIntegrationTest.kt`
(`IntegrationCachedClustered`, covering `update`, `updateForce`, and `delete`).
