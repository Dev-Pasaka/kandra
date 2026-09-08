# ISS-058: `@Version` LWT updates silently strip TTL on every update to a `@Ttl`-annotated entity

**Status:** Fixed

## Problem

Filed as GH #59.

Filed from a pre-cluster-testing deep review. `buildVersionedUpdateStatement`/
`buildVersionedUpdateStatementSuspend` (`BatchEngine.kt:754-823`) build the CAS update statement with
no `USING TTL` clause:

```kotlin
val cql = "UPDATE ${schema.tableName} SET $setClauses WHERE $whereParts IF ${versionCol.cqlName} = ?"
```

unlike `insertPrimary`/`insertPrimarySuspend` (used by `save()`), which correctly apply
`ttlSeconds ?: schema.defaultTtl` via `USING TTL`. `update(schema, old, new)`'s public signature
doesn't accept a `ttlSeconds` parameter at all for the `@Version` branch, so there's no way to pass one
through even if a caller wanted to.

In Cassandra/Scylla, TTL is a per-cell property, not a per-row one — an `UPDATE` statement with no
`USING TTL` clause writes its touched cells with **no expiry**, regardless of what TTL the row was
originally inserted with.

**Impact:** an entity annotated with both `@Version` (optimistic locking) and `@Ttl(seconds = X)`
(e.g. a lease, session token, or any ephemeral record that also needs concurrent-write protection —
exactly the kind of thing likely to combine both annotations) gets its correct TTL on `save()`, but the
very first `update()` call permanently clears expiry on every non-key column it touches. The row
silently stops expiring, with no error or warning, defeating the retention policy and causing unbounded
storage growth specifically for the class of entity most likely to need both features simultaneously.
This would only surface once a `@Version`+`@Ttl` entity is updated and then observed to never expire —
easy to miss in short-lived unit/integration tests, likely to surface as a slow storage-growth incident
in production.

## Suggested fix direction

Add a `ttlSeconds: Int? = null` parameter to `update`/`updateSuspend`'s public signature (or resolve
`schema.defaultTtl` automatically, matching `insertPrimary`'s fallback), thread it into
`buildVersionedUpdateStatement`/`buildVersionedUpdateStatementSuspend`, and append `USING TTL $ttl` to
the generated CQL exactly as the non-versioned `buildUpdateStatements`/`buildUpdateStatementsSuspend`
path already does (confirmed present at `BatchEngine.kt:697,729`). Add a regression test that inserts
a `@Version`+`@Ttl` entity, updates it, and asserts the updated row's TTL (via
`SELECT TTL(col) FROM ...` or the fake session's equivalent) is still set, not cleared.

**Files:** `kandra-runtime/src/main/kotlin/io/kandra/runtime/BatchEngine.kt`.

## Fix

Added `ttlSeconds: Int? = null` to `update`/`updateSuspend`'s public signature (threaded through
`KandraRepository`/`KandraSuspendRepository.update`) and to
`buildVersionedUpdateStatement`/`buildVersionedUpdateStatementSuspend`, which now resolve
`ttlSeconds ?: schema.defaultTtl` and prepend `USING TTL $effectiveTtl` to the generated CQL exactly
as suggested — matching `insertPrimary`'s existing fallback pattern.

Verified: `BatchEngineWriteSafetyTest` asserts the prepared CQL for a versioned update on a
`@Version`+`@Ttl(seconds = 999)` entity contains `USING TTL 999` (via `ScriptedCqlSession`'s new
`lastPreparedCql` capture — CQL text, not just the bound values, since TTL is baked into the query
string, not a bound parameter), and that an explicit `ttlSeconds` override on `update()` takes
precedence over the entity's `@Ttl` default.
