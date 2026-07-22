# 00. Fix Verification

Nine scenarios: §00.1-§00.6 cover the 6 bugs fixed in `0.4.3` (`docs/report:1.0/SUMMARY.md`); §00.7-
§00.9 cover 3 more (ISS-028/029/030) found while re-verifying those same 6 beyond the direct-repository
level and fixed in `0.4.4`. Each has already been proven at the direct-repository level (`kandra-test`'s
Testcontainers suite, plus a one-off live run against the running docker-compose cluster — see
`docs/test-plan:1.1/README.md`). This file's job is to re-prove each one at the **HTTP-route / capstone
level**, using the same `KandraTestingPlayground` sample app that originally found the bug, per the
README's "remove the workarounds first" instructions.

For every scenario: capture `debug.logQueries`/`debug.logBatches` output and the exact response
(status code + body, or exception + stack trace). If a scenario doesn't pass exactly as described,
that's a regression finding — write it up per `docs/test-plan/05-scoring-rubric-and-reporting.md`,
same as any other finding in this line of work.

## 00.1 — `kandra-codegen` Set/Map columns compile without the patch task

**Root cause (fixed)**: `KandraProcessor.kt`'s `resolveTypeName` (formerly a one-line
`declaration.qualifiedName` lookup) now recurses into `KSType.arguments`, so a `Set<String>` column
generates `KandraColumnRef<kotlin.collections.Set<kotlin.String>>` — valid Kotlin with both required
type arguments — instead of a raw `KandraColumnRef<kotlin.collections.Set>`.

1. Remove the `patchBrokenCodegenCollectionRefs` Gradle task and its `dependsOn`/ordering wiring from
   `sample-app/build.gradle.kts` entirely (not just disable it).
2. `./gradlew build`. **Expected**: green, with no post-processing step touching the generated
   `UserTable.kt`/`PostTable.kt`.
3. Inspect the actual generated file (`build/generated/ksp/main/kotlin/.../UserTable.kt`) and confirm
   `tags`/`metadata` now show fully-parameterized `KandraColumnRef<kotlin.collections.Set<kotlin.String>>`
   / `KandraColumnRef<kotlin.collections.Map<kotlin.String, kotlin.String>>` — not just "it compiled,"
   actually read the generated source, since a different silent fallback could in principle also
   produce compiling-but-wrong code (e.g. erasing to `Any`).
4. Exercise `UserTable.status.eq(...)`-style generated-DSL usage in at least one route to confirm the
   generated object isn't just syntactically valid but semantically usable (e.g. a `findAll` predicate
   against a generated column ref for a non-collection column, to confirm nothing else in codegen
   regressed).

## 00.2 — `@CacheResult` with real Caffeine no longer crashes

**Root cause (fixed)**: `KandraCache.resolveMethod` now resolves `getIfPresent`/`put`/`invalidate`
against Caffeine's public `Cache` interface (`Class.forName("com.github.benmanes.caffeine.cache.Cache")`)
instead of the cache instance's own concrete runtime class, which is package-private. The `Method`
object's declaring class is now public, so `Method.invoke` no longer throws `IllegalAccessException`.

1. Restore `@CacheResult(ttlSeconds = ..., maxSize = ...)` on `User` in `model/User.kt`.
2. `POST /users` (create). **Expected**: 201, no `IllegalAccessException` anywhere in the stack —
   this used to be an unconditional 500 on the very first cached write.
3. `GET /users/{id}` twice in a row. **Expected**: both 200 with identical bodies; second call should
   be a cache hit (confirm via a log line, a debug counter, or by stopping the cluster between calls
   and confirming the second call still succeeds — if it's genuinely served from cache, it shouldn't
   need the network).
4. Re-attempt C25/C26 and D5/§4.7 from `docs/test-plan/03-functional-coverage-matrix.md` and
   `04-edge-cases-and-adversarial-tests.md` — these were all FAIL/BLOCKED by this bug in v1.0 and
   should now be independently testable for the first time.

## 00.3 — Empty `Set`/`Map` columns are readable immediately after create

**Root cause (fixed)**: `KandraCodec.decode` now exempts `List`/`Set`/`Map` classifiers from the
blanket "NULL + non-nullable → throw" check, since Cassandra cannot represent an empty collection any
other way than NULL at the storage layer — the collection branches (`row.getSet`/`getMap`/`getList`)
already return empty rather than null for a NULL column, matching the DataStax driver's own design;
the codec just needed to actually reach them instead of throwing first.

1. `POST /users` with a request body that leaves `tags`/`metadata` unset (so they take the `emptySet()`/
   `emptyMap()` Kotlin defaults) and no `append`/`put` call afterward.
2. `GET /users/{id}` immediately. **Expected**: 200, `tags: []`, `metadata: {}` — this used to be an
   unconditional 500 (`KandraQueryException: Column 'tags' is NULL in Scylla but property 'tags' is
   non-nullable...`) the instant you read back an untouched row.
3. Confirm directly via `cqlsh` that the stored row genuinely has `tags = null, metadata = null` at
   the CQL layer (this part was always correct/expected Cassandra behavior — the bug was purely in
   the Kotlin-side decode, not the write path).
4. Re-run C29/F1 from file 3 and confirm they now score PASS instead of FAIL for the empty-collection
   case specifically (they may already show PASS for the non-empty case from other rows).

## 00.4 — Clustering-key WHERE clauses (the largest fix — ~8 call sites)

**Root cause (fixed)**: `StatementBuilder`'s `selectById`/`deleteById`/`appendToCollection`/
`removeFromCollection`/`counterUpdate`, and `BatchEngine`'s versioned-update and soft-delete-rewrite
statements, all now build their WHERE clause (and bind the corresponding values) from
`schema.partitionKeys + schema.clusteringKeys`, not `schema.partitionKeys` alone. `findById`/`deleteById`
now also **fail loudly** (`KandraSchemaException` naming the missing columns) if fewer key values are
supplied than the full key requires, instead of silently truncating via `.zip()`.

This is the highest-value re-verification in this file, since it's the fix with the most call sites
and the most different failure modes per call site. Test against `User` (partition key `userId`,
clustering key `bucketedAt`) — restore the full-key `findById(userId, bucketedAt)` /
`deleteById(userId, bucketedAt)` calls in `UserRoutes.kt` first (see README).

| Sub-case | Steps | Expected |
|---|---|---|
| `findById`, partial key | `GET /users/{id}` using a route that (deliberately, for this one test) calls `findById(userId)` with no `bucketedAt` | 4xx/5xx surfacing `KandraSchemaException` naming `bucketed_at` as missing — **not** an arbitrary row from the partition, and not a silent 200 |
| `findById`, full key | `GET /users/{id}/{bucketedAt}` (or equivalent route) | 200, the exact row for that `(userId, bucketedAt)` pair, confirmed by creating 2+ rows with the same `userId` and different `bucketedAt` first and checking the right one comes back |
| `deleteById`, full key | Create 2 rows same `userId`, different `bucketedAt`; `DELETE` one via its full key | Only the targeted row is gone; the sibling row (same partition) still exists — this is the data-loss scenario from Finding #9/#2, confirm it's actually fixed, not just that no exception was thrown |
| `append`/`remove`/`put` | `POST /users/{id}/tags` (or equivalent) on a clustering-keyed `User` row | 200, tag added — this used to hard-crash with `InvalidQueryException: Missing mandatory PRIMARY KEY part bucketed_at` on every call |
| `update` (`@Version`) | `PUT /users/{id}` triggering the LWT update path | 200, LWT applies correctly — re-run U1/U2 from file 3, which were FAIL in v1.0 for this exact reason |
| `updateForce` | Same entity, force-update path | 200, no `InvalidQueryException` |
| `increment`/`decrement` on a clustering-keyed counter table | If the sample app has (or file 01 adds) a counter table with a clustering key, confirm the `partitionKeys: Map<String, Any>` argument now also needs a clustering-key entry, and that omitting it throws `KandraSchemaException` naming the missing column rather than crashing at the driver level | Matches the new fail-fast contract |

**Scope gaps found and fixed in `0.4.4` (not present when this section was first written):** this fix
alone was not sufficient — cache invalidation and `@LookupIndex` resolution both derived a *different*,
narrower key shape than `findById`'s new full-key contract, so both silently broke for a
clustering-keyed entity even after this fix shipped. See [§00.7](#007--cache-invalidation-key-shape-fix-iss-028)
and [§00.8](#008--lookupindex-resolution-on-a-clustering-keyed-entity-iss-029) below — if you're
verifying `0.4.4` (not `0.4.3`) for the first time, treat §00.4 through §00.8 as one connected story
about the same underlying "full key, not partition key" contract change, not five independent bugs.

## 00.5 — `KandraBatchScope` naming fix (`saveInBatch`/`deleteInBatch`)

**Root cause (fixed)**: `save`/`delete` inside `KandraBatchScope` were member-extensions on
`KandraRepository`/`KandraSuspendRepository` with the **same names** as those repositories' own real
methods — Kotlin's member-always-wins-over-extension resolution rule made them structurally
unreachable through any normal call syntax. Renamed to `saveInBatch`/`deleteInBatch` (and the
always-throwing LWT guard to `saveIfNotExistsInBatch`, which had the identical problem and was
**also** silently unreachable — discovered while fixing #10, not part of the original v1.0 report).

1. **First, confirm the old shape is still broken** (methodology check, same spirit as file 4 §4.1's
   "fail this test on purpose first" instruction): in `PostRoutes.kt`, temporarily restore the
   originally-documented `application.kandra.batchBlocking { with(postRepo) { save(post) } }` (or
   `postRepo.save(post)` directly inside the block, matching the KDoc example that was also broken —
   see `docs/report:1.0/PROGRESS.md` Finding #10) shape. `POST /posts` with 2 tags, `debug.logBatches`
   on. **Expected**: still 3 separate `LOGGED BATCH` executions, not 1 — confirming this call shape is
   *still* silently non-atomic (the fix doesn't retroactively make the old, colliding names work; it
   makes the new names the only reachable ones).
2. Switch to `postRepo.saveInBatch(post)` / `postByTagRepo.saveInBatch(...)` inside the
   `batchBlocking { }` block. `POST /posts` with 2 tags again, `debug.logBatches` on. **Expected**:
   exactly **one** `LOGGED BATCH` execution covering all statements (`Post`'s insert + its `@LookupIndex`
   insert + both `PostByTag` inserts) — this is the actual atomicity guarantee finally holding.
3. Re-run capstone Flow 1 (`docs/test-plan/07-realistic-workload-capstone.md` §7.2) end to end with the
   fixed call shape — see [02-capstone-reverification.md](02-capstone-reverification.md).
4. Confirm `saveIfNotExistsInBatch` actually throws `KandraQueryException` when called inside
   `batch { }`/`batchBlocking { }` (it's meant to — LWT can't share a `LOGGED BATCH`). This specifically
   checks the newly-discovered sixth bug: before the rename, calling `saveIfNotExists` inside a batch
   block silently executed a real, immediate LWT write instead of throwing — confirm that failure mode
   is gone by checking no row was written before the exception (or, if testing the old name is
   informative, confirm it reproduces the silent-execution bug one more time for the record).

## 00.6 — Documentation accuracy

Not a code bug, but worth a pass now that the call shapes changed: confirm `README.md`'s "Caller-
Controlled Batches" section and the `kandra-runtime`/`kandra-codegen` Claude Code skill docs
(`.claude/skills/kandra-runtime/SKILL.md`, `.claude/skills/kandra-codegen/SKILL.md`) match the actual
fixed behavior — they were updated as part of this fix, but a fresh pair of eyes re-reading them
against the real running app (not just the source) is worth doing once, the same way file 6 of v1.0
existed to catch doc/reality drift.

## 00.7 — Cache invalidation key shape (fix: ISS-028)

**Root cause (fixed)**: `KandraRepository`/`KandraSuspendRepository` invalidated the `@CacheResult`
cache via `partitionKeyOf(entity)` — partition-key-only — while `findById`'s real cache key (since
ISS-025) is the full key (partition + clustering). For a clustering-keyed entity these are different
shapes (`userId` vs. `[userId, bucketedAt]`), so invalidation silently missed the real cache entry.
Fixed by replacing `partitionKeyOf` with `cacheKeyOf`, which reuses the same `keyValuesOf` helper
`append`/`remove`/`put` already used.

1. Restore `@CacheResult` on `User` if not already restored per §00.2. `User` is clustering-keyed
   (`bucketedAt`), so this scenario specifically needs a clustering-keyed cached entity — `User`
   qualifies, `IntegrationCached` (partition-key-only) in `kandra-test` does not exercise this bug.
2. `POST /users` to create a user, then `GET /users/{id}/{bucketedAt}` (full key) to populate the
   cache.
3. `PUT /users/{id}/{bucketedAt}` to update a field.
4. `GET /users/{id}/{bucketedAt}` again immediately. **Expected**: the fresh, updated value — this used
   to silently return the stale pre-update value (cache never invalidated) until the TTL expired on its
   own, with no error anywhere to indicate the write "didn't take."

## 00.8 — `@LookupIndex` resolution on a clustering-keyed entity (fix: ISS-029)

**Root cause (fixed)**: `find`/`findAll`/`findPage`/`exists`/`deleteBy` via a `@LookupIndex` predicate
reconstruct the primary table's key from the lookup row — but `LookupTableSchema` never stored the
primary table's clustering-key columns, only its partition key. Once `selectById` started requiring
the full key (ISS-025), every lookup-based query on a clustering-keyed entity broke with
`KandraSchemaException`. Fixed by adding `clusteringKeyColumns` to `LookupTableSchema`, flowed through
the lookup table's own DDL, `insertLookup`, `selectByLookup`, and all four `QueryExecutor`
lookup-resolution call sites.

**This is a schema/DDL change** — see the README's environment note: use a fresh keyspace, not one
left over from a `0.4.3` run, or run `AUTO_MIGRATE` against the existing lookup tables first.

1. `GET /users/by-email/{email}` (or equivalent lookup-predicate route) on a clustering-keyed `User`.
   **Expected**: 200 with the correct user — this used to be an unconditional `KandraSchemaException`.
2. Capstone-specific: `GET /posts/{postId}` and `DELETE /posts/{postId}` both resolve `Post` via its
   `@LookupIndex(postId)` — `Post` is also clustering-keyed (`createdAt`). Both routes used to fail
   identically; confirm both now succeed. This directly unblocks capstone Flow 5 (see
   [02-capstone-reverification.md](02-capstone-reverification.md), which should be re-read with this
   fix in mind — Flow 5's original failure was attributed to ISS-025 alone, but ISS-029 was silently
   blocking the same flow underneath it and wasn't separable until ISS-025's fix was in place).
3. Create 2+ posts with the same lookup value's owner but different `createdAt` to confirm `findPage`
   via a lookup predicate resolves the *one* matching row, not every clustering row in that partition
   (the second bug this same fix addressed).

## 00.9 — Soft-delete must not remove lookup rows (fix: ISS-030)

**Root cause (fixed)**: `BatchEngine`'s soft-delete path unconditionally deleted every `@LookupIndex`
row for the entity at the end of the sequence, contradicting the documented "soft delete does not
remove lookup rows" behavior — a soft-deleted row still "exists" (queryable, non-key columns not yet
TTL'd) until its TTL expires, so it should stay resolvable via its lookup index too, the same as
`findById` still finds it. This was a pre-existing bug, unrelated to ISS-025/028/029, but only became
independently observable once ISS-029 made lookup resolution on a clustering-keyed entity work at all
— `Post` (which combines `@LookupIndex(postId)` with `@SoftDelete`) couldn't reach this code path
through a lookup-driven request before that.

1. `POST /posts` → `DELETE /posts/{id}` (soft delete, since `Post` has `@SoftDelete`) → `GET /posts/{id}`
   (resolves via the same `@LookupIndex(postId)` lookup, not a direct key `findById`). **Expected**:
   200, the post returned with `isDeleted: true` — this used to fail lookup resolution entirely (the
   lookup row was gone), not return a soft-deleted-but-still-findable post.
2. This directly completes capstone Flow 5's soft-delete-via-lookup path — cross-reference with
   [02-capstone-reverification.md](02-capstone-reverification.md).
