# ISS-097: `@SoftDelete` marker written without TTL leaves a permanent key+marker husk row

**Status:** Fixed

## Problem

Filed as GH #154, found while verifying the kandraorm.com quickstart/tutorial against the
published 0.4.7 artifacts.

`@SoftDelete(markerProperty = ...)` wrote the marker column without a TTL, deliberately: it had to
outlive the other columns so `findActive()` could tell the row apart from a live one. The
consequence: after `ttlSeconds`, the data columns expired, but the primary key and `marker = true`
survived forever — every soft-deleted row became a permanent husk. Partitions grew without bound
for delete-heavy workloads (exactly what `@SoftDelete` is meant to help with), and the `@SoftDelete`
KDoc's "avoids tombstone accumulation" claim overstated what actually happened: tombstones were
deferred, not avoided.

## Fix

Added `@SoftDelete(markerTtlSeconds)`, defaulting to `ttlSeconds + gcGraceSeconds` (falling back to
Cassandra/Scylla's real `864000`s default when the entity's `@ScyllaTable.gcGraceSeconds` is itself
left at its own `-1` "use the cluster default" sentinel). `SchemaRegistry.register()` validates it's
strictly greater than `ttlSeconds`, throwing `KandraSchemaException` otherwise.

That alone turned out not to be enough — caught against a real cluster, not unit tests (see
[ISS-098](ISS-098-softdelete-non-nullable-decode-throws.md)'s test for the live-cluster harness):
the row's original `save()` is a plain `INSERT`, which writes Cassandra's row-marker cell with no
TTL of its own, entirely separate from any column's cell. That row marker alone keeps the partition
alive for `SELECT` forever regardless of what TTL a later `UPDATE` puts on individual columns —
`UPDATE` never touches it. So the marker *column* expiring on its own TTL still didn't make the
*row* disappear.

The actual fix: `BatchEngine.softDeleteBlocking`/`softDeleteSuspend` now write the marker via an
`INSERT`, not an `UPDATE` — re-inserting the (unchanged) key columns plus the marker column, all
`USING TTL markerTtlSeconds`. Cassandra resolves same-cell conflicts by latest write time, so this
fresh, TTL'd row-marker cell supersedes the original eternal one from `save()`'s `INSERT`, and the
whole partition — not just its column values — now actually expires once `markerTtlSeconds` elapses.

**Files:** `kandra-core/.../annotations/Annotations.kt` (`markerTtlSeconds` param, KDoc rewrite),
`SchemaRegistry.kt` (resolution + validation), `schema/SchemaModel.kt`
(`softDeleteMarkerTtlSeconds`); `kandra-runtime/.../BatchEngine.kt` (`softDeleteBlocking`/
`softDeleteSuspend`'s marker write, both now `INSERT ... USING TTL`).

**Tests:** `SchemaRegistrySoftDeleteTtlTest` (kandra-core, TTL-resolution formula + validation);
`SoftDeleteMarkerTtlLiveClusterTest` (kandra-runtime, `@Tag("manual")`) — connects directly to a
real local cluster and is what caught the row-marker gap above; the first version of this fix
(marker column TTL alone) passed every unit test while still leaving a permanent husk on a real
cluster.

See GH #154, [PR #158](https://github.com/Dev-Pasaka/kandra/pull/158).
