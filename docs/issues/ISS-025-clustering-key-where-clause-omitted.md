# ISS-025: Key-based repository operations omitted clustering keys from their WHERE clause

**Status:** Fixed, verified live

## Problem

Found during real-cluster test plan execution (`docs/report:1.0/`, Finding #9 — the highest-severity
finding of that run). `StatementBuilder`'s `selectById`, `deleteById`, `appendToCollection`,
`removeFromCollection`, and `counterUpdate`, plus `BatchEngine`'s versioned-update (`@Version` LWT) and
soft-delete-rewrite statements, all built their WHERE clause from `schema.partitionKeys` only — never
`schema.clusteringKeys` — one repeated pattern across roughly 8 call sites. Consequences on any
clustering-keyed entity: `append`/`remove`/`put`/`update`/`updateForce`/soft-delete all hard-crashed
with a driver-level `InvalidQueryException: Missing mandatory PRIMARY KEY part <clustering-col>`;
`findById` silently returned an arbitrary row from the partition instead of the specific requested row;
`deleteById` silently deleted the **entire partition**, not the one targeted row — a real data-loss
risk. `findById`/`deleteById`'s `vararg` key values were also silently truncated by `.zip()` against
the shorter `partitionKeys` list, with no error, if a caller passed extra values expecting them to
disambiguate a clustering row.

## Fix

Every listed call site now builds its WHERE clause (and binds the corresponding values) from
`schema.partitionKeys + schema.clusteringKeys`. `selectById`/`deleteById`/`appendToCollection`/
`removeFromCollection` now also **fail loudly** (`KandraSchemaException` naming the missing columns)
if fewer key values are supplied than the full primary key requires, instead of silently truncating.
`KandraRepository`/`KandraSuspendRepository`'s `append`/`remove`/`put` now derive the full key
(partition + clustering) from the entity automatically; `increment`/`decrement`'s `partitionKeys: Map`
argument is looked up for clustering-key entries the same way it already was for partition keys.

**Files:** `kandra-runtime/.../StatementBuilder.kt`, `.../BatchEngine.kt`,
`.../repository/KandraRepository.kt`, `.../repository/KandraSuspendRepository.kt`.

**Verification:** live, against a real 3-node ScyllaDB cluster, on a clustering-keyed entity:
`findById` with a partial key now throws `KandraSchemaException` instead of returning an arbitrary
row; `findById` with the full key returns the exact row; `deleteById` with the full key deletes only
the targeted row, confirmed a sibling row in the same partition survives; `append` and `update`
(`@Version` LWT) both succeed instead of throwing `InvalidQueryException`.
