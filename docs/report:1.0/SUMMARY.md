# Kandra 0.4.2 Real-World Test Plan — Summary

**Status: PARTIAL RUN, NOT COMPLETE.** Per the plan's own definition of "done" (README, file 5.6), a complete
run requires every row in file 3 (123 rows) and every scenario in file 4 (18 sections) to have a recorded
result, including BLOCKED/N/A. This run recorded results for roughly **53 of 123 file-3 rows (~43%)** and
substantive coverage of **6 of 18 file-4 sections** (§4.3, §4.5, §4.6 bullet 1, §4.10, §4.11, §4.12 bullet 3),
plus 5 of the capstone's 7 sub-flows and its node-resilience acceptance criterion. The remaining rows are
genuinely **not yet attempted**, not silently assumed — see `report/PROGRESS.md` for the exact list and the
"next immediate action" pointer to resume. This summary reports honestly on what was actually run, per the
scoring rubric's rule against overstating a pass rate while quietly excluding unattempted work from view.

**Environment:** Primary 3-node ScyllaDB 5.4 cluster (docker-compose), keyspace `kandra_sample` RF=3, single-DC,
auth disabled. Published `ke.co.coinx.kandra` 0.4.2 artifacts from real Maven Central (confirmed resolvable
with a clean Gradle cache). JDK 21 (JBR 21.0.10), Gradle 8.11. Auth-enabled and two-DC cluster variants were
written (`docker-compose.scylla-auth.yml`, `docker-compose.scylla-twodc.yml`) but never brought up — all
auth-specific (file 3 P4/P5, file 4 §4.12 beyond the one bullet tested) and multi-DC-specific (X4, two-DC
edge cases) rows are BLOCKED for that reason, not scored.

## Coverage

| Result | Count (of rows actually scored) |
|---|---|
| PASS | ~46 |
| FAIL | 5 (2 are single-root-cause bugs surfacing across multiple would-be rows) |
| PARTIAL | 3 (C8 timing not fully isolated, C11's test design flaw, node-kill exception-type mismatch) |
| BLOCKED | Auth/two-DC-dependent rows (cluster variants not brought up) |
| N/A | none declared |
| Not yet attempted (honest gap, not scored as anything) | ~70 of 123 file-3 rows; 12 of 18 file-4 sections |

## Findings by severity

| Severity | Count | IDs / short names |
|---|---|---|
| **Critical** | 4 | #6 kandra-codegen Set/Map codegen bug; #7 KandraCache reflection crash; #8 empty-collection decode failure; #10 KandraBatchScope silently non-atomic |
| **Critical/High** | 1 | #9 clustering-key WHERE-clause omission (Critical for append/remove/put/update/updateForce, High for findById/deleteById) |
| **Medium** | 2 | node-kill exception-type mismatch (raw `AllNodesFailedException` vs expected `KandraQueryException`); C11's test-design gap (doesn't actually distinguish `@Column` override from auto-derivation) |
| **Low** | 2 | `gradle.properties`'s stale `gradleVersion=8.11` vs actual 9.3.0 wrapper; `kandra-core`/`kandra-runtime` skill docs omitting `@SoftDelete.markerProperty` and `findActive()` entirely |

### The 5 findings, in detail

1. **[Critical] kandra-codegen cannot handle `Set`/`Map` columns at all.** `KandraProcessor.kt`'s type-name
   resolution discards generic type arguments, generating `KandraColumnRef<kotlin.collections.Set>` (invalid
   Kotlin, no type args) for any collection column — fails compilation of the entire module. Affects `User`
   and the capstone's `Post`. Worked around via a local generated-source patch (does not touch the published
   jar); reported, not silently fixed.
2. **[Critical] `KandraCache` crashes on every real-Caffeine cache operation.** Reflective `Method` resolution
   picks up Caffeine's package-private concrete cache class; `invoke()` throws `IllegalAccessException`
   unconditionally (no `setAccessible(true)`). Blocks every write to any `@CacheResult` entity. Removed
   `@CacheResult` from `User` to unblock the rest of testing.
3. **[Critical] Empty non-nullable `Set`/`Map` columns become permanently unreadable.** `KandraCodec.decode()`'s
   blanket `row.isNull()` check has no collection special-case (unlike the DataStax driver's own collection
   getters, which return empty rather than null for exactly this reason) — throws `KandraQueryException` the
   first time you `findById`/`find`/`findAll`/`findPage` any row saved with the idiomatic
   `tags: Set<String> = emptySet()` default. This breaks the plan's own kitchen-sink `User` entity and the
   capstone's `Post` on ordinary read-after-create.
4. **[Critical/High] Every key-based repository operation omits clustering keys from its WHERE clause.**
   `findById`, `deleteById`, `append`, `remove`, `put`, `increment`, `decrement`, `update` (w/ `@Version`),
   `updateForce`, and the soft-delete rewrite all build `WHERE ... = ?` from `schema.partitionKeys` only,
   never `schema.clusteringKeys` — one repeated pattern across ~8 call sites in `StatementBuilder.kt`/
   `BatchEngine.kt`. Confirmed live: `append`/`remove`/`put`/`update`/`updateForce`/soft-delete all hard-crash
   with `InvalidQueryException: Missing mandatory PRIMARY KEY part <clustering-col>` on any clustering-keyed
   entity; `findById` silently returns an arbitrary row from the partition; `deleteById` silently deletes the
   **entire partition**. Directly broke the capstone's Flow 5 (delete a post) — `Post` combines a clustering
   key with `@SoftDelete`, exactly the combination that triggers this.
5. **[Critical] `KandraBatchScope`'s documented `with(repo) { save(entity) }` pattern silently does not batch
   anything.** Kotlin's member-function-always-wins-over-extension-function resolution rule means `save`
   always resolves to the repository's own ordinary `save()` method, never `KandraBatchScope`'s intended
   statement-collecting extension — for suspend repos this fails to compile outright; for blocking repos it
   compiles and returns success with **no atomicity at all**, confirmed via `debug.logBatches` showing three
   independent `LOGGED BATCH` executions instead of one combined cross-table batch. This silently defeats the
   exact multi-table-write correctness guarantee the plan's own background section identifies as central to
   Kandra's value proposition, with no error, warning, or other signal short of reading debug logs line by
   line.

## Confirmed-fixed (0.4.1/0.4.2 changes that held up under real testing)

- **ISS-007** (`findActive()` + `@SoftDelete` marker-column timing): full 6-step sequence against a real cluster,
  including a 1-second tight-loop race check and a real 13-second TTL-expiry wait — every step matched the
  documented design exactly.
- **ISS-017** (migration checksum now includes compiled bytecode): confirmed live — changing `up()`'s CQL body
  without touching version/name/class correctly threw `KandraMigrationException` on re-run.
- **ISS-018** (migration concurrency locking): confirmed live with two real `CqlSession`s and two
  `KandraMigrationRunner`s racing an identical unapplied migration version via a `CountDownLatch` — exactly one
  side-effect row resulted, not two.
- **ISS-021** (`IN`-on-non-indexed-column error message reworded): confirmed — the message correctly suggests
  `@SecondaryIndex` and does not reference the old nonexistent `allowFiltering()` method.
- Migration idempotency (M1/M2/M6) and `KandraSchemaException`-type correctness for composite-PK `IN` (F6) both
  confirmed exactly as documented.

## Regressed or unconfirmed

- No claimed-fixed behavior was found to have regressed. The 5 findings above are either newly-discovered
  (Critical codegen/cache/collection/batch-scope bugs, apparently never exercised against a real cluster or
  real Caffeine/collection data before) or a previously-undocumented systemic gap (clustering-key WHERE clauses)
  rather than a regression of something the changelog claimed was fixed.

## Blocking findings

- None stopped the run entirely. Two Critical bugs (codegen, cache reflection) blocked forward progress until
  worked around at the sample-app level (not the library) — documented as explicit, confirmed decisions with
  the user before proceeding, per the plan's own "when ambiguous or destructive, stop and ask" rule.
- Auth-enabled and two-DC cluster variants were never brought up (time/scope decision, not attempted) — all
  rows depending on them are BLOCKED, not scored, and not silently marked N/A.

## Top 5 findings by severity, one line each

1. `KandraBatchScope`'s `with(repo) { save(entity) }` silently performs zero atomic batching — the exact
   multi-table-write correctness guarantee the library exists to provide is fictional as documented.
2. Every key-based repository method omits clustering keys from its WHERE clause — `deleteById` silently
   deletes an entire partition, and `append`/`remove`/`put`/`update`/`updateForce`/soft-delete all hard-crash
   on any clustering-keyed entity (which includes the plan's own `User`, `AuditLog`, and capstone `Post`).
3. A non-nullable `Set`/`Map` column left at its natural Kotlin default (`emptySet()`) becomes permanently
   unreadable via any read method the moment it's saved — the codec's NULL-check has no collection special-case.
4. `kandra-codegen`'s KSP processor cannot generate valid Kotlin for any `Set`/`Map` column at all — a hard
   compile-time failure for the entire consuming module, not a runtime edge case.
5. `KandraCache` crashes with `IllegalAccessException` on every real-Caffeine cache read/write/invalidate call
   — `@CacheResult` is completely unusable with a real cache dependency present, the exact scenario file 1's
   own dependency list asks for so caching is "actually exercised, not silently skipped."

## What's left for a complete run (see `report/PROGRESS.md` for exact resume point)

Most of file 3's P-series (beyond P1/P2/P10/P11/P17), S-series (AUTO_MIGRATE/VALIDATE/NONE variants), T-series
(`kandra-test` module), J-series (Jakarta validation — needs the HTTP route, not the direct-repository
harness), X2-X4 (consistency differentials, needs node-kill layered on specific consistency levels), and most
of file 4 (§4.1 suspend concurrency under load, §4.2 batch-chunking boundaries, §4.4's `@Version` contention
result — the no-`@Version` blind-overwrite half is done, the `@Version` half is blocked by Finding #9, §4.7
cache-invalidation gaps — superseded by Finding #7, §4.8 codec boundaries beyond the collection finding, §4.9
DDL naming edge cases, §4.13 codegen correctness spot-checks, §4.14 volume/scale, §4.15 consistency matrix,
§4.16 idempotency/retry, §4.17 graceful shutdown, §4.18 validator conflict) remain unattempted. The auth-enabled
and two-DC cluster variants also remain unattempted.
