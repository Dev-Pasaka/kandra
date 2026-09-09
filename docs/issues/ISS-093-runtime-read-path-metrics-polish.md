# ISS-093: kandra-runtime read-path and metrics polish (no implicit LIMIT 1, non-atomic getOrPut, no cache-stampede protection, generic metrics labels)

**Status:** Open

## Problem

Filed as GH #106.

Assorted read-path, caching, and observability polish items found during a post-fix audit of `kandra-runtime`:

**1. `find()`/`deleteBy()` never add an implicit `LIMIT 1`.** `find()` resolves as `findAll(...).firstOrNull()`; `resolveRows`/`resolveRowsSuspend` only add `LIMIT 1` when `limitOne` is explicitly passed (only `exists()` does that). A `find { predicate }` call matching many rows fetches up to `maxUnpagedResultRows` (10,000) rows over the wire and decodes every one, just to return the first. Under real multi-DC latency (cross-region round trips, `LOCAL_QUORUM` fan-out) this is a needless amplification for what looks like a single-row lookup call.

**2. Non-atomic `getOrPut` on concurrent maps.** `SchemaRegistry.registry.getOrPut` (`kandra-core`) and `StatementBuilder`'s `cache.getOrPut` on a `synchronizedMap` (`kandra-runtime`) are check-then-act, not atomic, on `ConcurrentHashMap`/`synchronizedMap`. Under a first-access race (two requests concurrently triggering `SchemaRegistry.register()` for the same class, or `StatementBuilder.prepare()` for a not-yet-cached CQL string), the underlying build/prepare can run redundantly more than once. Both are idempotent so this is wasted work, not corruption — but `session.prepare()` is a blocking driver round-trip, so the waste isn't free.

**3. No cache-miss stampede protection.** `KandraCache` wraps a plain Caffeine `Cache` (manual get/put), not a `LoadingCache`/`AsyncLoadingCache`. On a burst of concurrent `findById()` calls for the same key right after eviction/TTL expiry, all of them miss and all hit Scylla simultaneously — no request coalescing.

**4. Metrics failure/success labeling silently degrades to `"unknown"/"query"` on most write paths.** The #82/ISS-074 fix's `recordFailure`/`record(...,attempts)` hooks are only meaningfully labeled on a minority of write paths (`save`, `deleteById`, collection/counter ops, the versioned-update LWT). Most call sites — `saveWithNulls`, `update()` on non-versioned entities, `updateForce`, `delete`, `saveAll`/chunked, every `runtime.batch{}`/`batchBlocking{}` commit, and the soft-delete `UPDATE` statements — call `executeWithRetry(batch)`/`executeWithRetrySuspend(batch)` with no `tableName`/`operation` arguments, defaulting to `"unknown"`/`"query"`. `BatchEngineMetricsFailureTest` only asserts against `deleteById` and `save`, both in the correctly-labeled minority, so this gap isn't caught by the new tests.

## Impact

Medium — none of these break correctness, but #1 is a real perf tax under multi-DC latency, and #4 undermines the observability #82 was specifically meant to add for the common write operations.

## Suggested fix

- #1: have `find`/`findSuspend` pass `limitOne = true` into `resolveRows`/`resolveRowsSuspend` unless the caller has already set an explicit `limit()`.
- #2: use `computeIfAbsent` for true single-flight semantics.
- #3: consider `AsyncLoadingCache`/`LoadingCache` if stampede protection is worth the added complexity, or document the current behavior as a known limitation.
- #4: thread `schema.tableName` and an operation string through every `executeWithRetry(Suspend)`/`executeOnce(Suspend)` call site consistently, the same way `save`/`deleteById` already do.

## Files

`kandra-runtime/src/main/kotlin/io/kandra/runtime/QueryExecutor.kt`, `kandra-runtime/src/main/kotlin/io/kandra/runtime/StatementBuilder.kt`, `kandra-runtime/src/main/kotlin/io/kandra/runtime/BatchEngine.kt`, `kandra-runtime/src/main/kotlin/io/kandra/runtime/cache/KandraCache.kt`, `kandra-core/src/main/kotlin/io/kandra/core/SchemaRegistry.kt`

Filed from a critical post-fix audit (2026-09-09) ahead of experimental multi-DC cluster testing.
