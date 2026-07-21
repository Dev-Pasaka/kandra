# ISS-017: Migration checksum didn't hash the migration body

**Status:** Fixed — pending live-cluster verification (Testcontainers, see [ISS-013](ISS-013-no-integration-tests.md))

## Problem

Found during a pre-real-database-testing audit (2026-07-21). `KandraMigration.checksum()` hashed
only `"${version}:${name}:${qualifiedClassName}"`. The class doc explicitly promises "Kandra
validates checksums on startup and throws `KandraMigrationException` if a previously-applied
migration's body has changed" — but editing the CQL inside an already-applied migration's `up()`
without touching its version/name/class name produced an identical checksum, so the safety net
silently did nothing. A real footgun once migrations run against a live keyspace.

## Fix

`checksum()` now additionally hashes the migration class's own compiled bytecode (read via
`this::class.java.getResourceAsStream(".class")`), so a change to the body of `up()` — or any
lambda/anonymous class it captures as a member of that class file — changes the checksum, while
unrelated recompilation elsewhere in the project does not. This hashes bytecode, not source text,
which is an approximation (e.g. a no-op reformat that the compiler happens to encode identically
would not trip it) but correctly catches real logic edits, which the previous implementation could
never catch under any circumstance.

**File:** `kandra-migrate/.../KandraMigration.kt`.
