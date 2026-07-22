# ISS-029: `@LookupIndex` resolution broke entirely for entities with a clustering key

**Status:** Fixed, verified live

## Problem

Found during `docs/test-plan:1.1` re-verification of the ISS-025 fix. `find`/`findAll`/`findPage`/
`exists`/`deleteBy` (any operation that resolves via a `@LookupIndex` predicate) reconstruct the
primary table's key by reading the lookup row and collecting `lookup.partitionKeyColumns` — that's
**all** they ever stored, since `LookupTableSchema` never carried the primary table's clustering-key
columns. That was fine before ISS-025, since `selectById` only ever needed the partition key. Once
ISS-025 made `selectById` require the **full** key (partition + clustering), any entity combining a
`@LookupIndex` with a clustering key — `User` (email/phone lookups + a `bucketedAt` clustering key)
and the realistic-workload capstone's `Post` (postId lookup + a `createdAt` clustering key) both hit
this immediately — broke with `KandraSchemaException: selectById on '<table>' requires N key value(s)
... but M were provided.` on every lookup-based read/delete.

This is a scope gap in the ISS-025 fix, not an independent regression: tightening `selectById`'s
contract was correct, but the lookup-resolution code path (and the lookup table's own schema/DDL) were
never updated to actually supply a clustering-key value, because the lookup table structurally never
stored one.

## Fix

`LookupTableSchema` gained a `clusteringKeyColumns` field, populated the same way
`partitionKeyColumns` already was. This flows through:
- `DdlGenerator.lookupTable` — the generated lookup table now has extra plain columns for each of the
  primary table's clustering-key columns (its own `PRIMARY KEY` is unchanged — still just the lookup
  column itself).
- `StatementBuilder.insertLookup` — writes those extra columns alongside the partition key columns.
- `StatementBuilder.selectByLookup` — selects them back too.
- `QueryExecutor`'s four lookup-resolution call sites (`resolveRows`, `resolveRowsSuspend`, `findPage`,
  `findPageSuspend`) — now collect the **full** key (partition + clustering) from the lookup row before
  calling `selectById`, instead of partition-only. `findPage`'s two sites specifically also fix a
  second, related correctness issue: they used to convert only the partition key into `Eq` predicates
  and fall through to a normal WHERE query, which — on a clustering-keyed entity — would scatter across
  every clustering row in that partition rather than resolving the one row the lookup value actually
  points to. Including the clustering key in those `Eq` predicates fixes both problems with the same
  change.

**Files:** `kandra-core/.../schema/SchemaModel.kt`, `kandra-core/.../SchemaRegistry.kt`,
`kandra-core/.../DdlGenerator.kt`, `kandra-runtime/.../StatementBuilder.kt`,
`kandra-runtime/.../QueryExecutor.kt`.

**Note:** this is a schema/DDL change — existing lookup tables created before this fix (under
`AUTO_CREATE`, which never alters existing tables) won't have the new clustering-key columns until
either `AUTO_MIGRATE` runs against them or they're recreated in a fresh keyspace. This is the same
migration story as any other new column ISS-025-era Kandra adds — `AUTO_MIGRATE` handles it; `AUTO_CREATE`
does not retroactively alter existing tables.

**Verification:** live, against a real 3-node ScyllaDB cluster: `GET`-by-email on a clustering-keyed
`User` and `GET`/`DELETE`-by-postId on the realistic-workload capstone's `Post` (both previously threw
`KandraSchemaException`) now succeed and return/resolve the correct row. Also added permanent
regression coverage in `kandra-test`'s `KandraIntegrationTest.kt` (`IntegrationLookupClustered`,
covering `find`, `findPage`, and `deleteBy`).

**Related finding, not part of this fix:** while verifying this fix's capstone `Post` delete flow, the
now-reachable soft-delete path surfaced a **separate, pre-existing** issue — `BatchEngine`'s
`softDeleteBlocking`/`softDeleteSuspend` unconditionally delete every lookup-table row for an entity,
even when soft-deleting, contradicting the documented "soft delete does not remove lookup rows" claim.
This was never observable before, because the `Post`/`User` lookup+clustering-key combination itself
was broken by this same issue's root cause until now. See [ISS-030](ISS-030-soft-delete-removes-lookup-rows.md).
