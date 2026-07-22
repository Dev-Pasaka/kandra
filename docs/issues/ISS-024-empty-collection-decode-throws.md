# ISS-024: Non-nullable empty `Set`/`Map` columns were permanently unreadable

**Status:** Fixed, verified live

## Problem

Found during real-cluster test plan execution (`docs/report:1.0/`, Finding #8). `KandraCodec.decode`'s
blanket `row.isNull(name)` check ran before any type-specific dispatch, throwing `KandraQueryException`
for any non-nullable Kotlin type when the column was NULL. But Cassandra cannot represent an empty
(non-frozen) `List`/`Set`/`Map` column any other way than NULL at the storage layer — inserting
`emptySet()`/`emptyMap()` simply stores no value, which is correct, expected CQL behavior. The
DataStax driver's own `Row.getSet`/`getMap`/`getList` accessors are specifically designed to return an
empty collection (never null) for a NULL column for exactly this reason — but Kandra's codec threw
before ever reaching them, for any non-nullable collection property. This is the idiomatic, natural way
to declare an optional collection in Kotlin (`tags: Set<String> = emptySet()`), so any freshly-created
row that hadn't had an `append()`/`put()` call was permanently unreadable via `findById`/`find`/
`findAll`/`findPage`.

## Fix

`decode` now resolves the column's classifier before the NULL check and exempts `List`/`Set`/`Map`
from it entirely, regardless of the Kotlin property's declared nullability — falling through to the
existing collection-decode branches, which already call the driver's null-safe accessors.

**File:** `kandra-runtime/.../codec/KandraCodec.kt`.

**Verification:** live save-then-immediate-findById of an entity with untouched `emptySet()`/`emptyMap()`
defaults against a real 3-node ScyllaDB cluster — read back as `[]`/`{}`, not an exception.
