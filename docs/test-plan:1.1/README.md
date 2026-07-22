# Kandra 0.4.3 — Real-World Test Plan v1.1

## What this is, and how it relates to v1.0

`docs/test-plan/` (v1.0) specified a from-scratch, adversarial real-cluster test of Kandra 0.4.2.
It was executed — see `docs/report:1.0/SUMMARY.md` for the full account — and got through roughly
43% of file 3's rows and 6 of 18 file 4 sections before time/scope ran out. That partial run still
found **5 Critical/High bugs**, all confirmed with live repros against a real 3-node ScyllaDB
cluster:

| # | Bug | File(s) |
|---|---|---|
| 6 | `kandra-codegen` can't generate valid Kotlin for any `Set`/`Map` column — compile-time failure | `KandraProcessor.kt` |
| 7 | `KandraCache` throws `IllegalAccessException` on every real-Caffeine cache call | `KandraCache.kt` |
| 8 | Non-nullable empty `Set`/`Map` columns become permanently unreadable after save | `KandraCodec.kt` |
| 9 | Every key-based repository op omits clustering keys from its WHERE clause (~8 call sites) | `StatementBuilder.kt`, `BatchEngine.kt` |
| 10 | `KandraBatchScope`'s `with(repo) { save(entity) }` silently does not batch anything | `KandraBatchScope.kt` |

**All five have since been fixed directly in this source tree, and shipped as `0.4.3`** (not worked
around at the sample-app level, unlike during the v1.0 run). See `docs/changelog/0.4.3.md` and each
linked `docs/issues/ISS-0NN-*.md` (ISS-022 through ISS-027) for the full root-cause writeup per fix.
A sixth, closely-related bug was found and fixed while fixing #10:
`KandraBatchScope`'s `saveIfNotExists` guard was **also** unreachable via its documented calling
convention, for the identical member-vs-extension-resolution reason as `save`/`delete` — it never
actually threw, it silently called the real `saveIfNotExists()` instead. See the "Root cause" line in
each section of [00-fix-verification.md](00-fix-verification.md) for the exact diff behind each fix,
and the doc comments in `KandraBatchScope.kt`/`KandraCache.kt`/`KandraCodec.kt`/`StatementBuilder.kt`
for the in-source explanation.

**Every fix has already been proven twice** before this plan was written:
1. Structurally, via new permanent regression tests in `kandra-test/src/test/kotlin/io/kandra/test/KandraIntegrationTest.kt` (Testcontainers-based, same pattern as the pre-existing suite).
2. Live, via a one-off smoke test connecting directly to the same docker-compose 3-node cluster the v1.0 report used (`docker-compose.scylla.yml`, still running at the time of writing) — all 7 scenarios passed against real ScyllaDB, not a fake session.

**So why does this plan exist, if the fixes are already proven?** Two reasons:

1. The proof above is at the **direct-repository level** (JUnit tests calling `KandraRepository`/
   `KandraSuspendRepository` methods directly). The v1.0 report's most convincing findings came from
   the **HTTP-route sample app** (a real Ktor server) and the **capstone** (a full social-feed app) —
   neither has been re-run against the fix yet. A bug fixed at the unit level can still be exposed
   differently through a real request/response cycle, real DI wiring, or a real multi-flow app. This
   plan's [file 00](00-fix-verification.md) closes that gap.
2. The v1.0 run left **~70 of 123 file-3 rows and 12 of 18 file-4 sections genuinely unattempted** —
   not because they were blocked by the bugs above, but because time ran out first. Those rows are
   still just as unattempted today. This plan's [file 01](01-remaining-coverage-plan.md) is the
   priority-ordered resume list for that work, and is the larger part of what "100% coverage" (per
   v1.0's own file 5.6 definition, reused here) actually requires.

## Non-negotiable ground rules (carried forward from v1.0, with one change)

Rules 2–6 from `docs/test-plan/README.md` apply unchanged: use a real cluster (not `FakeKandraSession`),
record what actually happened, capture a minimal repro for every failure, don't silently patch the
library while testing it, and stop-and-ask on anything ambiguous or destructive.

**Rule 1 is different for this round, but less different than it was.** `gradle.properties`' `version`
is now `0.4.3` and a Maven Central publish has been initiated for it (see `docs/changelog/0.4.3.md`).
Two cases, depending on where that publish actually landed by the time you run this:

- **If `0.4.3` is resolvable from Maven Central** (confirm the same way v1.0 file 1 §1.3 did — a clean
  `--gradle-user-home` resolve, don't trust a cached copy): v1.0's original rule 1 applies unchanged —
  point the sample app at published `0.4.3`, same as any real consumer would get. This is the
  preferred path; prefer it over `mavenLocal()` whenever it's available, since a local build and a
  published release are not guaranteed identical (packaging, shading, POM metadata can all differ)
  even when the source is.
- **If `0.4.3` hasn't propagated yet, or the Central Portal release step is still pending a manual
  click** (`publishToMavenCentral(automaticRelease = false)` in `buildSrc/.../publish.gradle.kts` means
  a human has to explicitly release it after upload/validation): fall back to a
  **`mavenLocal()`-published** build (`./gradlew publishToMavenLocal` from the repo root) with the
  sample app's Kandra dependency pinned to `0.4.3`, and note in the report which path was actually
  used — re-run file 00 once more against the real published artifact once it's confirmed resolvable,
  since that's the actual target rule 1 wants restored.
- Everything else about "don't test the wrong thing" from v1.0 rule 1 still applies: don't hand-copy
  source files into the sample app, don't `includeBuild` as a substitute for actually publishing.

## Environment

Same as v1.0 (`docs/test-plan/01-prerequisites-and-environment.md`): JDK 21, the primary 3-node
ScyllaDB 5.4 docker-compose cluster, keyspace `kandra_sample` RF=3. The v1.0 report's auth-enabled
(`docker-compose.scylla-auth.yml`) and two-DC (`docker-compose.scylla-twodc.yml`) compose variants
were written but never brought up — bringing them up is now in scope for this round (file 01, P4/P5
and X4 rows depend on them).

The existing `KandraTestingPlayground` sample app (built during the v1.0 run, at
`/Users/pasaka/Developer/KandraTestingPlayground/sample-app`) is the right starting point for file 00
— it already has all 6 kitchen-sink entities, every plugin config knob, and the capstone's `Post`/
`PostLikeCounter`/`PostByTag` wired up. It also has three workarounds specifically for the bugs this
plan verifies are now fixed, all of which need to be **removed**, not left in place, or file 00 will
be testing the workaround instead of the fix:

1. `patchBrokenCodegenCollectionRefs` Gradle task in `sample-app/build.gradle.kts` (Finding #6 workaround).
2. `@CacheResult` commented out on `User` (Finding #7 workaround) — restore it.
3. `findById(id)`/`deleteById(id)` calls simplified to single-arg on `User` (a clustering-keyed entity)
   in `routes/UserRoutes.kt` — restore the full-key calls (`findById(userId, bucketedAt)` etc.), since
   the whole point of Finding #9's fix is that the full-key form now works and the partial-key form
   now correctly throws instead of silently misbehaving.

Also switch `PostRoutes.kt`'s create-post flow back to the plan's originally-documented
`batchBlocking { with(postRepo) { save(post) } }` pattern **first** (confirm it now fails the way file
00 §00.5 describes — the whole point is that this call shape is *still* broken, just no longer silent
about it), then switch to the fixed shape (`batchBlocking { postRepo.saveInBatch(post) }`) and confirm
that one actually batches.

## How to use this folder

| # | File | What it covers |
|---|---|---|
| — | `README.md` (this file) | Scope, ground-rule changes, environment setup delta from v1.0. |
| 00 | [00-fix-verification.md](00-fix-verification.md) | Dedicated re-verification of all 6 findings above, at the HTTP-route/capstone level this time, not just direct-repository. This is new content, not in v1.0. |
| 01 | [01-remaining-coverage-plan.md](01-remaining-coverage-plan.md) | The priority-ordered resume list for v1.0's ~70 unattempted file-3 rows and 12 unattempted file-4 sections, including the auth-enabled and two-DC cluster variants. References `docs/test-plan/03-*`/`04-*` by row ID rather than re-stating ~100 rows of unchanged table content. |
| 02 | [02-capstone-reverification.md](02-capstone-reverification.md) | Re-run plan for the two capstone flows that failed in v1.0 (Flow 1: multi-write atomicity, Flow 5: delete a post) plus the two acceptance criteria left "not independently verified." |

## Definition of "done" for this round

Same shape as v1.0's file 5.6: every scenario in file 00 has a recorded result, every row referenced
in file 01's resume list has a recorded result (including BLOCKED/N/A, not silently skipped), and the
capstone re-verification in file 02 is a working end-to-end re-run, not isolated checks. A report
should be produced in the same format as `docs/report:1.0/` (`SUMMARY.md` + per-file results), named
`docs/report:1.1/` when this round is executed.
