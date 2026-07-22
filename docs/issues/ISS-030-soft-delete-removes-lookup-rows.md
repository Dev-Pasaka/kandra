# ISS-030: Soft-delete unconditionally removed lookup-table rows

**Status:** Fixed, verified live

## Problem

Found while verifying the ISS-029 fix's effect on the realistic-workload capstone's `Post.delete()`
flow. `BatchEngine.softDeleteBlocking`/`softDeleteSuspend` — the code path `delete(entity)` uses when
`@SoftDelete` is present — unconditionally deleted every `@LookupIndex` row for the entity being
soft-deleted, at the very end of the soft-delete sequence (after rewriting non-key columns with a TTL
and setting the marker column). This directly contradicts the documented design: a soft-deleted row
still "exists" (queryable, non-key columns not yet TTL'd) until its TTL expires — it should remain
resolvable via its `@LookupIndex` for exactly the same reason `findById` still finds it.

This was never independently observable before: `Post` (the capstone entity combining `@LookupIndex`
and `@SoftDelete`) couldn't even reach its own soft-delete path via a lookup-driven `find`/`delete`
until ISS-029 was fixed (the request failed earlier, at lookup resolution itself, for a clustering-keyed
entity). Fixing ISS-029 made this pre-existing bug reachable and observable for the first time.

## Fix

Removed the unconditional `schema.lookupTables.forEach { ... deleteLookup(...) }` block from both
`softDeleteBlocking` and `softDeleteSuspend` entirely. Hard delete (the other branch of `delete()`,
used when `@SoftDelete` is absent) is unaffected and still correctly removes lookup rows as before.

**File:** `kandra-runtime/.../BatchEngine.kt`.

**Verification:** live, against a real 3-node ScyllaDB cluster, on the realistic-workload capstone's
`Post` (which combines `@LookupIndex(postId)` with `@SoftDelete`): `POST /posts` → `DELETE /posts/{id}`
(soft delete) → `GET /posts/{id}` (resolves via the same `@LookupIndex` lookup) now correctly returns
the post (marked `isDeleted: true`) instead of a lookup-resolution failure. Also added permanent
regression coverage in `kandra-test`'s `KandraIntegrationTest.kt`
(`IntegrationSoftDeletedLookup`, combining `@SoftDelete` and `@LookupIndex` on the same entity).
