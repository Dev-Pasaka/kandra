# ISS-007: `findActive()` for `@SoftDelete` entities

**Status:** Fixed — pending live-cluster verification (Testcontainers, see [ISS-013](ISS-013-no-integration-tests.md))

## Problem

The 0.4.0 spec called for a `findActive()` method. Kandra's soft-delete writes non-key columns
with a TTL via `UPDATE … USING TTL`. After the TTL expires, ScyllaDB removes the column values
but retains the primary-key row as a tombstone until `gc_grace_seconds` passes. There was no CQL
predicate to filter "rows whose TTL has expired" — ScyllaDB exposes `TTL(col)` in SELECT, but that
requires knowing which specific column to check, and it returns `0` (not null) for columns with no
TTL set. Worse, immediately after a soft-delete and *before* the TTL expires, the row's data is
still fully present — so there was no way to distinguish a soft-deleted row from a live one at all.

## Fix

Added an opt-in marker column: `@SoftDelete(ttlSeconds = 86400, markerProperty = "isDeleted")`,
where `markerProperty` names a `Boolean` field on the entity. This is a schema contract change and
is opt-in by design — existing `@SoftDelete` usages without `markerProperty` are unaffected.

- `SchemaRegistry` validates the named property exists and is `Boolean`; resolved onto
  `TableSchema.softDeleteMarkerColumn`.
- `BatchEngine.softDeleteBlocking`/`softDeleteSuspend` exclude the marker column from the
  TTL'd `UPDATE … USING TTL` statement and instead set it to `true` in a **separate `UPDATE`
  with no TTL** — so it persists after the other columns' values expire.
- `KandraRepository.findActive()` / `KandraSuspendRepository.findActive()` query
  `WHERE <marker> = false ALLOW FILTERING`, logging a WARN (scatter-gather across all nodes,
  same style as existing secondary-index/IN-query warnings). Recommend `@SecondaryIndex` on
  the marker column for tables of meaningful size.
- Throws `KandraSchemaException` if called without `markerProperty` configured.

**Files:** `kandra-core/.../annotations/Annotations.kt`, `SchemaRegistry.kt`, `schema/SchemaModel.kt`;
`kandra-runtime/.../BatchEngine.kt`, `QueryExecutor.kt`, `repository/KandraRepository.kt`,
`repository/KandraSuspendRepository.kt`.
