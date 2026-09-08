# ISS-067: `QueryExecutor.decodeEntity` rebuilds the full column map on every row decoded instead of caching it per schema

**Status:** Fixed

## Problem

Filed as GH #68.

Filed from a pre-cluster-testing deep performance review. `decodeEntity` (`QueryExecutor.kt:521-542`)
already caches the entity's constructor and its parameter list on `TableSchema.reflection` (per the
comment at lines 522-524, added for `ISS-034`) — but rebuilds `allCols` from scratch on **every row**:

```kotlin
val allCols = buildList {
    addAll(schema.partitionKeys)
    addAll(schema.clusteringKeys)
    addAll(schema.columns)
    addAll(schema.lookupTables.map { it.indexColumn })
}.associateBy { it.propertyName }
```

This list-concatenation-plus-`associateBy` (allocating a new `List`, a new intermediate mapped list for
`lookupTables`, and a new `Map`) is identical for every row decoded against a given `TableSchema` — the
schema doesn't change between rows. For `findAll`/`findPage` calls returning many rows, this is O(rows)
redundant allocation and hashing work on what should be an O(1)-per-decode lookup, following exactly the
pattern `ISS-034` already fixed for constructor/reflection metadata one level up.

**Impact:** a real, measurable per-row CPU/allocation cost on every read path that decodes more than a
handful of rows — proportionally worse for large `findAll` result sets or high-throughput
`findPage`/pagination-heavy workloads under real cluster load, i.e. exactly the scenario the upcoming
load testing will exercise.

## Suggested fix direction

Hoist `allCols` (the `Map<String, ColumnSchema>` keyed by `propertyName`) to a field computed once and
cached on `TableSchema`/`EntityReflection`, the same place `constructorParameters` already lives, and
have `decodeEntity` read it from there instead of rebuilding it per call.

**Files:** `kandra-runtime/src/main/kotlin/io/kandra/runtime/QueryExecutor.kt`.

## Fix

Added `EntityReflection.columnsByProperty: Map<String, ColumnSchema>`, computed once in
`SchemaRegistry.buildEntityReflection` from the same superset `columnSchemas` list every other
per-category column list (`partitionKeys`/`clusteringKeys`/`columns`/lookup columns) is already
filtered from — so it's a strict superset of the old per-call `allCols`, built exactly once per
schema registration instead of once per row. `decodeEntity` now reads
`schema.reflection.columnsByProperty` directly instead of rebuilding the list-concat-plus-`associateBy`
on every call. `KandraEntityLogger.safeToString` (wired in as part of this same review pass, see
ISS-069 item 1) was also switched to the cached map instead of its own linear scan across
`schema.columns`/`partitionKeys`/`clusteringKeys`, for the same reason.

Verified: full `kandra-core`/`kandra-runtime` suite passes, including `QueryExecutorReflectionCacheTest`
(pre-existing coverage of `decodeEntity`'s correctness via `findAll`/`findById`) — decoded entity
field values are unaffected by moving the map from per-call to per-schema.
