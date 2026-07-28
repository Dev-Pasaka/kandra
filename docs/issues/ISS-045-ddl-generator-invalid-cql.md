# ISS-045: `DdlGenerator` could silently produce invalid or data-losing CQL

**Status:** Fixed

## Category

Bug (fail-fast violation).

## Problem

Filed as GH #31. Two independent gaps:

1. **Duplicate `cqlName`s were silently dropped instead of erroring.** `DdlGenerator.primaryTable()`
   and `StatementBuilder.insertPrimary`/`insertPrimaryWithNulls` each independently assembled the same
   column list and called `.distinctBy { it.cqlName }` on it. If two columns resolved to the same
   `cqlName` — a typo'd explicit `@Column` name, or `camelToSnake`'s `trimStart('_')` colliding two
   differently-named properties (e.g. `_archived` and `archived` both becoming `archived`) —
   `distinctBy` silently kept the first and discarded the second everywhere, with no error. A caller
   who thought they were writing two columns was actually only ever writing one.
2. **No `frozen<...>` support.** `DdlGenerator.mapType()` mapped `List`/`Set`/`Map` to CQL collection
   types with no `frozen<>` wrapping anywhere, and nothing restricted `@PartitionKey`/`@ClusteringKey`
   from being a collection type. ScyllaDB (like Cassandra) requires collection columns used as
   primary-key components, and collections nested inside another collection (e.g.
   `Map<String, List<T>>`), to be `frozen<...>` — a non-frozen collection in either position is
   rejected outright at `CREATE TABLE` time, and nothing caught this before DDL reached the cluster.

## Fix

**Duplicate names:** `SchemaRegistry.buildSchema()` now groups `partitionKeys + clusteringKeys +
regularColumns` (which already includes `@LookupIndex` columns) by `cqlName` and throws
`KandraSchemaException` naming the colliding properties on any group larger than one — catching both
the explicit-typo and the `camelToSnake` collision cases at registration time. The `.distinctBy {
it.cqlName }` calls in `DdlGenerator.primaryTable()` and `StatementBuilder.insertPrimary`/
`insertPrimaryWithNulls` were kept (not removed) since `@LookupIndex` columns are deliberately listed
twice in that column-list construction and still need de-duping for that structural reason — but
they're now only ever de-duping intentional double-listing, never silently discarding a genuine
property collision, since the registry guarantees no duplicate `cqlName`s reach this point.

**`frozen<...>` support**, implemented for both nesting cases described in the issue:
- Collection-typed partition/clustering key columns are wrapped in `FROZEN<...>` — checked directly
  via the column's own `isPartitionKey`/`clusteringKey` fields. Applied in `primaryTable()` and in
  `lookupTable()` for the `indexColumn` (always that table's own PK regardless of its role on the
  origin schema).
- Any collection nested inside another collection, at arbitrary depth — not limited to the Map-value
  case. Type resolution now threads a `nested` flag through recursive calls into `List`/`Set`/`Map`
  type arguments; any collection classifier resolved with `nested = true` gets wrapped in
  `FROZEN<...>`. This covers `Map<K, List<V>>`, `List<Set<T>>`, `Set<Map<K,V>>`, etc. generically,
  while top-level (unnested, non-key) collection columns are left unwrapped as before.

## Files

- `kandra-core/src/main/kotlin/io/kandra/core/SchemaRegistry.kt`
- `kandra-core/src/main/kotlin/io/kandra/core/DdlGenerator.kt`
- `kandra-runtime/src/main/kotlin/io/kandra/runtime/StatementBuilder.kt`
- `kandra-core/src/test/kotlin/io/kandra/core/SchemaRegistryTest.kt`
- `kandra-core/src/test/kotlin/io/kandra/core/DdlGeneratorTest.kt`
