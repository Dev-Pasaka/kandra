# ISS-021: Error message referenced a non-existent `allowFiltering()` DSL method

**Status:** Fixed

## Problem

Found during a pre-real-database-testing audit (2026-07-21). When an `IN` predicate targeted a
column that is neither a partition key nor a `@SecondaryIndex`, `QueryExecutor` threw
`KandraQueryException` telling the caller to "Add `allowFiltering()` to the query or use a
`@SecondaryIndex`." No such method exists on `QueryContext` — the DSL's own doc comment states
"Kandra does not support `ALLOW FILTERING`" as a deliberate design choice. The error message
contradicted the actual design and pointed callers at a method that would fail to compile.

## Fix

Reworded the exception message to match the documented design: it now says the query would
require `ALLOW FILTERING`, which Kandra does not support, and to add a `@SecondaryIndex` to the
column instead — no false promise of a DSL escape hatch.

**File:** `kandra-runtime/.../QueryExecutor.kt`.
