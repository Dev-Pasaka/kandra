# ISS-087: Cache invalidate-after-write race can pin a stale value indefinitely; @CacheResult is per-process and undocumented as such

**Status:** Open

## Problem

Filed as GH #100.

Two related caching gaps in `kandra-runtime/src/main/kotlin/io/kandra/runtime/repository/KandraRepository.kt` (and the identical pattern in `KandraSuspendRepository.kt`) plus `cache/KandraCache.kt`:

**1. Invalidate-after-write race can pin a stale value indefinitely.** Every write does `batchEngine.write(...)` then `cache.invalidate(key)` — classic cache-aside. Race: thread A's `findById` misses cache and begins its DB read (observing the *old* value) while thread B's `save()` commits the new value and invalidates (a no-op, since A hasn't cached anything yet); A's read then completes and calls `cache.put(key, oldValue)` *after* B's invalidate has already run. The cache now holds a stale entry with nothing left to evict it until TTL expires (`ttlSeconds` is caller-configurable to arbitrarily high values). There is no versioning/timestamp guard on `put`, and `KandraCache` is a bare get/put/invalidate wrapper with no compare-and-swap. This interleaving is fully determined by the code as written — reachable under any concurrent writers, the normal case in production.

**2. `@CacheResult` is strictly per-process, and this is undocumented.** No documentation anywhere (README, USER_GUIDE, the annotation's own KDoc) states that the cache is local to a single JVM/process with zero cross-instance invalidation. For a horizontally-scaled or multi-DC deployment (the explicit scenario about to be tested — typically multiple app instances, often one set per DC), a write against instance A's local cache never invalidates instance B/C's cached copies of the same row; those keep serving stale data until their own TTL expires, independent of any consistency level configured for the write. This compounds with finding #1.

## Impact

High. Both undermine the freshness guarantees an app relying on `@CacheResult` would reasonably assume, in ways that will be very hard to distinguish from a genuine cluster-consistency problem during experimental multi-DC testing.

## Suggested fix

- Standard cache-aside mitigations for #1: invalidate-before-write plus a short delayed second invalidate, or a version/timestamp check before `put` that refuses to cache a value older than the last known invalidation, or move to a read-through design with per-key locking.
- Documentation fix for #2 at minimum: state plainly that the cache is per-process, and recommend against `@CacheResult` on tables where cross-instance freshness matters (or pair it with a short TTL). A real fix would need a distributed invalidation channel — likely out of scope for the current local-Caffeine design, but worth an explicit decision either way.

## Files

`kandra-runtime/src/main/kotlin/io/kandra/runtime/repository/KandraRepository.kt`, `kandra-runtime/src/main/kotlin/io/kandra/runtime/repository/KandraSuspendRepository.kt`, `kandra-runtime/src/main/kotlin/io/kandra/runtime/cache/KandraCache.kt`, `README.md`, `docs/USER_GUIDE.md`

Filed from a critical post-fix audit (2026-09-09) ahead of experimental multi-DC cluster testing.
