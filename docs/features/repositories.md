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

// ...AndGet siblings — same write, but return what was actually persisted instead of
// Unit/Boolean (generated id, @CreatedAt/@UpdatedAt, post-write @Version — all otherwise discarded):
users.saveAndGet(user)                 // User
users.saveIfNotExistsAndGet(user)      // User? — null if it already existed
users.updateAndGet(old, new)           // User — avoids a spurious KandraOptimisticLockException
                                        // from reusing old's stale version in a follow-up update()
users.deleteAll(entities)          // warns if > tombstoneWarnThreshold
users.deleteById(uuid)             // also respects @SoftDelete
users.deleteBy { UserTable.email eq "x@y.com" }

users.findById(uuid)
users.find { UserTable.email eq "x@y.com" }
users.findAll { UserTable.status eq "active" }    // unpaged — see row-cap note below
users.findAll(limit = 100) { UserTable.status eq "active" }
users.findPage(pageSize = 20, pageToken = token) { ... }
users.exists { UserTable.email eq "x@y.com" }
users.findActive()                 // see below — @SoftDelete(markerProperty = "...") only
users.findActive(allowFullScan = true)  // opt in to ALLOW FILTERING when there's no @SecondaryIndex

users.raw("SELECT * FROM users WHERE status = ?", "active")
users.rawQuery(KandraRawQuery.cql("SELECT * FROM users WHERE status = ?").bind("active").build())

users.append(user, User::tags, setOf("new-tag"))
users.remove(user, User::tags, setOf("old-tag"))
users.put(user, User::meta, mapOf("k" to "v"))

// increment()/decrement() are only valid on a counter-table repository — a separate repo, since
// increment()'s `field` must be a KProperty1 of *that* repository's own entity type:
val stats = application.kandra.repository<PostStats>()
stats.increment(PostStats::views, mapOf("postId" to id), by = 1)
```

`UserTable`/`PostStats` above are the `kandra-codegen`-generated type-safe column-reference objects
— predicates are written directly as `Table.column eq value` inside the query block; there is no
`where { }` wrapper and no raw string column names anywhere in the query DSL.

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

### Row cap on unpaged reads

`findAll`/`find`/`findActive` without an explicit `limit` cap at `maxUnpagedResultRows` (10,000
rows) — logging a loud WARN and **truncating** to that many rows rather than throwing. There's no
exception, so a caller that never checks the server log has no way to know the result was
incomplete. For a result set that might exceed this, use `findPage()`/`findPageSuspend()` instead of
relying on an unbounded `findAll`.

## Batch scope

Collect multiple saves and deletes into a single LOGGED batch, using `saveInBatch`/`deleteInBatch`
— **not** `save()`/`delete()`, which would silently execute immediately instead of joining the
batch (Kotlin always resolves a repository's own real `save`/`delete` member over a same-named
extension, even inside the batch scope itself):

```kotlin
application.kandra.batch {
    userRepo.saveInBatch(user)
    walletRepo.saveInBatch(wallet)
}
```

- `saveInBatchAndGet(entity)` is `saveInBatch` that also returns the entity as it will be persisted, with `@GeneratedUuid`/`@CreatedAt`/`@UpdatedAt` already
  resolved (it does not inject an initial `@Version`, unlike standalone `save()`).
- `batch { }` is `suspend` and its scope (`KandraBatchScope`) takes `KandraSuspendRepository`s; commit uses the non-blocking `executeSuspend`.
  `batchBlocking { }` is the non-suspend counterpart with its own scope type (`KandraBlockingBatchScope`) that takes blocking `KandraRepository`s. Mixing
  the two is a compile error.
- `saveIfNotExistsInBatch` exists only to throw: an LWT cannot share a `LOGGED BATCH` with regular statements.

## UNSET vs NULL

By default Kandra uses `UNSET` binding for nullable `null` fields — no tombstone is written. Use `saveWithNulls()` to intentionally write `NULL` (e.g. to overwrite a previously set value with empty).

| Leaves a `null` column unchanged (UNSET) | Clears a `null` column (writes `NULL`) |
|---|---|
| `save()` | `saveWithNulls()` |
| `update()` / `updateAndGet()` (plain and `@Version`) | `updateWithNulls()` / `updateWithNullsAndGet()` *(0.5.2, GH #189)* |
| `saveInBatch()` / `saveInBatchAndGet()` | `saveWithNullsInBatch()` / `saveWithNullsInBatchAndGet()` *(0.5.2, GH #189)* |

Through 0.5.1 a versioned (`@Version`) `update()` wrote `NULL` for every null column; from 0.5.2 it leaves them UNSET like the other writes.

## Raw queries (injection-safe)

```kotlin
// Builder enforces parameterisation
val rows = repo.rawQuery(
    KandraRawQuery.cql("SELECT * FROM users WHERE status = ?")
        .bind("active")
        .build()
)
```

`raw(cql)` logs a `WARN` whenever `cql` looks like it has a value or extra syntax spliced directly
into it — a single-quoted string literal, a `;` statement terminator, or a `--`/`/* */` comment
marker — **regardless of whether other `?` parameters are also bound** in the same string (GH
#32/#142: one legitimately bound placeholder elsewhere doesn't make an embedded literal/terminator/
comment any safer). It's a heuristic, not a parser — it can miss a punctuation-free payload and can
false-positive on a legitimately embedded literal. Set `debug { rawQueryStrictMode = true }` to make
it throw `KandraQueryException` instead of only warning, so `raw()`/`rawQuery()` fail closed.

## Consistency overrides

`append`/`remove`/`put`/`increment`/`decrement` all take an optional `consistency:
KandraConsistency?` parameter, resolved the same way as writes (per-call override →
`@WriteConsistency` annotation → configured default).
