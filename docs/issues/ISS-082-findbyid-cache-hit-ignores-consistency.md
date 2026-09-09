# ISS-082: findById() cache hits silently ignore the caller's consistency override

**Status:** Open

## Problem

Filed as GH #95.

`KandraRepository.findById`/`KandraSuspendRepository.findById` (`kandra-runtime/src/main/kotlin/io/kandra/runtime/repository/`) resolve by checking the local cache first and only calling `executor.findById(entityClass, *idValues, consistency = consistency)` — the only place `consistency` is actually applied — on a cache miss.

USER_GUIDE.md documents `repo.findById(uuid, consistency = KandraConsistency.LOCAL_QUORUM)` as the way to force a strongly-consistent read (e.g. immediately after a write, for read-your-writes across DCs), with no caveat that `@CacheResult` defeats it. A caller doing exactly this will silently get a value that never touched Scylla at all if it happens to already be cached from an earlier, weaker-consistency read.

## Impact

Critical — the consistency-override mechanism this experimental round exists partly to validate is silently dropped on cache hits, which will produce misleading results about the cluster's actual consistency behavior rather than a visible error.

## Suggested fix

Either bypass the cache entirely whenever a non-null `consistency` override is passed, or loudly gate the combination (warn or throw if `consistency != null` and `@CacheResult` is active on that entity).

## Files

`kandra-runtime/src/main/kotlin/io/kandra/runtime/repository/KandraRepository.kt`, `kandra-runtime/src/main/kotlin/io/kandra/runtime/repository/KandraSuspendRepository.kt`

Filed from a critical post-fix audit (2026-09-09) ahead of experimental multi-DC cluster testing.
