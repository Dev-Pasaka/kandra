# ISS-019: Collection/counter statements ignored configured consistency levels

**Status:** Fixed — pending live-cluster verification (Testcontainers, see [ISS-013](ISS-013-no-integration-tests.md))

## Problem

Found during a pre-real-database-testing audit (2026-07-21). `StatementBuilder.appendToCollection`,
`removeFromCollection`, and `counterUpdate` never called `.setConsistencyLevel(...)`, unlike
`insertPrimary`/`selectById`. `@WriteConsistency` and any explicit per-call override were silently
ignored for `append()`/`remove()`/`put()`/`increment()`/`decrement()` — only the driver profile's
default consistency applied.

## Fix

Added an optional `consistency: KandraConsistency? = null` parameter to `appendToCollection`,
`removeFromCollection`, and `counterUpdate`, resolved the same way as writes
(`resolveWriteConsistency`: per-call override → `@WriteConsistency` annotation → configured
default) and applied via `.setConsistencyLevel(...)`. Threaded the same optional parameter through
`KandraRepository`/`KandraSuspendRepository`'s `append`/`remove`/`put`/`increment`/`decrement`.

**Files:** `kandra-runtime/.../StatementBuilder.kt`, `repository/KandraRepository.kt`,
`repository/KandraSuspendRepository.kt`.
