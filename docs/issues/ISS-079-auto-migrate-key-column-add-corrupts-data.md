# ISS-079: AUTO_MIGRATE can silently ALTER TABLE ADD a missing partition/clustering key as a plain column, causing row collisions

**Status:** Open

## Problem

Filed as GH #92.

`SchemaMode.AUTO_MIGRATE`'s column-diff loop (`kandra-ktor/src/main/kotlin/io/kandra/ktor/Kandra.kt`, the `entityColumns.forEach` block) builds its diff set as `partitionKeys + clusteringKeys + columns + lookupTables.indexColumn` with no distinction between key and non-key roles, then for any entry missing from `system_schema.columns` calls `DdlGenerator.alterTableAddColumn(schema, col)` and executes it.

CQL has no way to `ALTER TABLE ADD` a column into an existing primary key. If a developer adds a new `@PartitionKey`/`@ClusteringKey` property to an entity whose table already exists — a plausible real-world edit ("add a clustering key for versioning") — this emits a plain `ALTER TABLE t ADD newcol type;`, which Scylla/Cassandra executes as a regular column, not a key component. The entity code, `DdlGenerator`, and `SchemaRegistry` all continue to believe this column participates in row identity, but the physical table's primary key is unchanged. Two logically-distinct entities the application differentiates only by this new "key" column now map to the same physical row, and writes silently overwrite each other's other fields.

Nothing warns at registration time (`SchemaRegistry` has no way to know the physical schema) or at DDL-execution time (this is logged only as `INFO: added column`, indistinguishable from an ordinary safe migration).

## Impact

Critical — real, silent data loss/overwrite the first time anyone adds a key column to an existing entity under `AUTO_MIGRATE`. No test exercises this scenario.

## Suggested fix

In the diff loop, check whether a missing column is a partition/clustering key (`col.isPartitionKey || col.clusteringKey != null`) and, if so, refuse to `ALTER TABLE ADD` it — throw a fatal `KandraSchemaException` instead, since this always indicates an incompatible primary-key change requiring manual table recreation, never a safe auto-migration.

## Files

`kandra-ktor/src/main/kotlin/io/kandra/ktor/Kandra.kt`, `kandra-core`'s `DdlGenerator`

Filed from a critical post-fix audit (2026-09-09) ahead of experimental multi-DC cluster testing.
