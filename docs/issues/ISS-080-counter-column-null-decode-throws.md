# ISS-080: Counter columns throw KandraQueryException on decode whenever any counter cell is untouched (NULL)

**Status:** Open

## Problem

Filed as GH #93.

`KandraCodec.decode()` (`kandra-runtime/src/main/kotlin/io/kandra/runtime/codec/KandraCodec.kt`) special-cases `List`/`Set`/`Map` (`isCollection`) to skip the non-nullable-NULL check, because Cassandra can only represent an empty collection as NULL at the storage layer. It does not do the same for counter columns, even though `ColumnSchema.isCounter` exists and is populated (`kandra-core/src/main/kotlin/io/kandra/core/SchemaRegistry.kt`).

Cassandra/Scylla counter cells are independent, and a cell that has never been incremented reads back as NULL, not 0. The library's own documented pattern (README/USER_GUIDE) for counter entities is:

```kotlin
@Counter val views: Long = 0,
@Counter val likes: Long = 0
```

The moment a row exists where `views` was incremented but `likes` never was (an entirely ordinary sequence — a post gets viewed before it's liked), any `findById`/`find`/`findAll`/`findPage` on that row throws:

`"Column 'likes' is NULL in Scylla but property 'likes' is non-nullable"`

This is a hard read-path outage for any counter table with more than one counter column, following the library's own documented usage pattern. No test exercises a partially-touched counter row (grep confirms zero references to `isCounter` in `KandraCodec.kt`).

## Impact

Critical — read-path outage under entirely normal, documented usage, not an edge case.

## Suggested fix

Give `column.isCounter` the same decode carve-out `isCollection` gets: treat a NULL counter cell as `0` (not `null`) regardless of the Kotlin property's declared nullability, mirroring Cassandra's own counter semantics.

## Files

`kandra-runtime/src/main/kotlin/io/kandra/runtime/codec/KandraCodec.kt`

Filed from a critical post-fix audit (2026-09-09) ahead of experimental multi-DC cluster testing.
