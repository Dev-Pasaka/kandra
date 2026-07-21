# ISS-015: `KandraRepository.deleteById` bypassed `@SoftDelete`

**Status:** Fixed — pending live-cluster verification (Testcontainers, see [ISS-013](ISS-013-no-integration-tests.md))

## Problem

Found during a pre-real-database-testing audit (2026-07-21). `KandraRepository.deleteById(vararg
keyValues)` built a raw `LOGGED BATCH` of `deleteById` + lookup-table deletes and called
`session.execute(b)` directly — it never checked `schema.isSoftDelete`. The entity-based
`KandraRepository.delete(entity)` and `KandraSuspendRepository.deleteById` both correctly delegate
to `BatchEngine.delete`/`deleteSuspend`, which do check `isSoftDelete`/`softDeleteTtlSeconds`. The
result: for any `@SoftDelete` entity, the sync `deleteById(uuid)` call permanently tombstoned the
row instead of the intended TTL'd soft delete, while every other delete path on the identical table
behaved correctly — a silent data-handling divergence between two APIs on the same repository.
It also never invalidated the read-through cache.

## Fix

`KandraRepository.deleteById` now looks up the entity first (as it already did, to find lookup rows
to clean up) and, when found, delegates to `batchEngine.delete(schema, entity)` — the same
soft-delete-aware path every other delete method uses — instead of duplicating the batch-building
logic. Also added the cache invalidation that was previously missing on this method. When the
entity doesn't exist, a plain hard-delete-by-key statement is issued (harmless no-op either way).

**File:** `kandra-runtime/.../repository/KandraRepository.kt`.
