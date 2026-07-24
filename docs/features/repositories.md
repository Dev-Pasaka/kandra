# Repositories

### `KandraRepository<T>` (blocking)

```kotlin
val users = application.kandra.repository<User>()

users.save(user)
users.saveAll(listOf(u1, u2))
users.saveWithNulls(user)          // writes actual NULL tombstones
users.saveIfNotExists(user)        // LWT IF NOT EXISTS
users.update(old, new)             // LWT if @Version; otherwise upsert
users.updateForce(user)            // skip @Version check
users.delete(user)                 // respects @SoftDelete
users.deleteAll(entities)          // warns if > tombstoneWarnThreshold
users.deleteById(uuid)             // also respects @SoftDelete
users.deleteBy { where { "email" eq "x@y.com" } }

users.findById(uuid)
users.find { where { "email" eq "x@y.com" } }
users.findAll { where { "status" eq "active" } }
users.findPage(pageSize = 20, pageToken = token) { ... }
users.exists { where { "email" eq "x@y.com" } }
users.findActive()                 // see below — @SoftDelete(markerProperty = "...") only
users.findActive(allowFullScan = true)  // opt in to ALLOW FILTERING when there's no @SecondaryIndex

users.raw("SELECT * FROM users WHERE status = ?", "active")
users.rawQuery(KandraRawQuery.cql("SELECT * FROM users WHERE status = ?").bind("active").build())

users.append(user, User::tags, setOf("new-tag"))
users.remove(user, User::tags, setOf("old-tag"))
users.put(user, User::meta, mapOf("k" to "v"))
users.increment(UserStat::views, mapOf("userId" to id), by = 1)
```

### `KandraSuspendRepository<T>` (coroutines — preferred in Ktor routes)

All methods are `suspend` equivalents of the above, including reads — `findById`, `find`,
`findAll`, `findPage`, `exists`, `raw`, `rawQuery`, and `findActive` all use the driver's async
API internally (`executeAsync().await()`) so they never block the calling coroutine dispatcher.

### `findActive()` (`@SoftDelete` entities only)

Requires `@SoftDelete(markerProperty = "...")` on the entity (see
[core-annotations.md](core-annotations.md#softdelete)) — without it, `findActive()` throws
`KandraSchemaException`. The marker column is a plain `Boolean` field that Kandra writes
permanently (no TTL) on soft-delete, so `findActive()` can reliably filter it out immediately —
even before the other columns' TTL has expired.

**`findActive(allowFullScan: Boolean = false)`** — as of this fix (GH #12), this is the one place in Kandra
that can still emit `ALLOW FILTERING`, and it now requires an explicit, visible opt-in at the call
site instead of a silent default, consistent with how the rest of the library treats
`ALLOW FILTERING` as something the predicate DSL structurally refuses to emit (see
[ISS-021](../issues/ISS-021-allow-filtering-error-message.md)) and how other unsafe-by-default
behavior requires opt-in (`@Version`, `saveIfNotExists`):

- If the marker column has `@SecondaryIndex`, `findActive()` queries the index directly —
  `WHERE <marker> = false`, no `ALLOW FILTERING` needed, and `allowFullScan` is irrelevant.
- If the marker column has **no** `@SecondaryIndex` and `allowFullScan` is left at its default
  `false`, `findActive()` throws `KandraQueryException` at call time — it explains that answering
  the query would require `ALLOW FILTERING`, which Kandra does not emit implicitly, and that you
  should either add `@SecondaryIndex` to the marker column or pass `allowFullScan = true`.
  This is a **breaking change**: existing callers relying on the previous silent-`ALLOW FILTERING`-
  plus-WARN behavior will now get an exception unless they add `@SecondaryIndex` or pass
  `allowFullScan = true`.
- If the marker column has no `@SecondaryIndex` and you explicitly pass `allowFullScan = true`,
  `findActive()` behaves as before 0.5.0: it runs `WHERE <marker> = false ALLOW FILTERING` and logs
  a WARN (scatter-gather across all nodes). Prefer adding `@SecondaryIndex` to the marker column for
  tables of meaningful size instead of opting into the full scan repeatedly.

See [ISS-036](../issues/ISS-036-findactive-allow-filtering-scope.md) for the full rationale.

## Batch scope

Collect multiple saves and deletes into a single LOGGED batch:

```kotlin
application.kandra.batch {
    userRepo.save(user)
    walletRepo.save(wallet)
}
```

## UNSET vs NULL

By default Kandra uses `UNSET` binding for nullable `null` fields — no tombstone is written. Use `saveWithNulls()` to intentionally write `NULL` (e.g. to overwrite a previously set value with empty).

## Raw queries (injection-safe)

```kotlin
// Builder enforces parameterisation
val rows = repo.rawQuery(
    KandraRawQuery.cql("SELECT * FROM users WHERE status = ?")
        .bind("active")
        .build()
)
```

Calling `raw(cql)` with no bind parameters and a CQL string containing literals logs a `WARN`.

## Consistency overrides

`append`/`remove`/`put`/`increment`/`decrement` all take an optional `consistency:
KandraConsistency?` parameter, resolved the same way as writes (per-call override →
`@WriteConsistency` annotation → configured default).
