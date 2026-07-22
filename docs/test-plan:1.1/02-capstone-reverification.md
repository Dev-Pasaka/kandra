# 02. Capstone Re-Verification

v1.0's capstone (`docs/test-plan/07-realistic-workload-capstone.md`, results in
`docs/report:1.0/07-capstone-results.md`) built a social-feed app (`Post`, `PostLikeCounter`,
`PostByTag`) and ran 5 flows plus a node-resilience check. 3 flows and node-resilience passed
cleanly; 2 flows failed, both traced to bugs fixed in this round. This file is the targeted re-run —
not a full capstone rebuild, since Flows 2-4 and node-resilience already have clean, unrelated-to-
these-fixes passes on record and don't need repeating unless something else about the environment
changed.

## Flow 1 — create a post (multi-write atomicity) — was FAIL (Finding #10)

Original failure: `application.kandra.batchBlocking { with(postRepo) { save(post) } }` compiled and
returned 200, but `debug.logBatches` showed 3 independent `LOGGED BATCH` executions (`Post`'s own
insert+lookup, then each `PostByTag` insert separately) instead of one combined batch — the exact
"partial failure leaves a post visible in the feed but invisible to tag search" scenario the plan's
own background section warns about, silently unprotected.

**Re-run**:
1. Update `PostRoutes.kt`'s create-post handler to the fixed call shape:
   `postRepo.saveInBatch(post)` / `postByTagRepo.saveInBatch(...)` inside `batchBlocking { }` (see
   [00-fix-verification.md §00.5](00-fix-verification.md#005--kandrabatchscope-naming-fix-saveinbatchdeleteinbatch)
   for the "prove the old shape is still broken first" step — do that once here too, in the actual
   capstone app, not just in isolation).
2. `POST /posts` with 2+ tags, `debug.logBatches = true`. **Expected**: exactly one `LOGGED BATCH` log
   line covering all statements for this request (`Post` insert + its `@LookupIndex` insert + one
   `PostByTag` insert per tag) — not 3 separate lines.
3. **Partial-failure proof, not just a statement-count check**: this is the scenario the plan's
   background section actually cares about. Simulate a partial-failure window — e.g. kill a node
   mid-request if timing allows, or (more reliably) temporarily set `batchMaxChunkSize` low enough
   that this request's statement count would force a chunk split (confirm the batch scope's
   `execute()` does **not** chunk — check `KandraBatchScope.execute()` in source; if it truly always
   sends one `BatchStatement` regardless of size, note that as a separate finding if the statement
   count for a many-tags post could exceed Cassandra's real batch-size limit, since `saveAll`'s
   chunking logic doesn't apply here). At minimum, confirm via `nodetool`/`cqlsh` immediately after
   killing one node mid-batch (if reproducible) that you never observe a `Post` row without its
   matching `PostByTag` rows, or vice versa.
4. Record as PASS only if both the statement-count check and the partial-failure reasoning hold up —
   a single passing "it batched" log line is necessary but not sufficient to call this fixed, given
   the plan's own framing of why atomicity matters here.

## Flow 5 — delete a post — was FAIL (Finding #9)

Original failure: `DELETE /posts/{postId}` → 500, `InvalidQueryException: Missing mandatory PRIMARY
KEY part created_at`. `Post` combines a clustering key (`createdAt`) with `@SoftDelete` — exactly the
combination the clustering-key WHERE-clause bug broke.

**Re-run**:
1. `DELETE /posts/{postId}` on an existing post. **Expected**: 200/204, no `InvalidQueryException`.
2. Confirm the soft-delete semantics actually applied correctly, not just "no exception": `GET
   /posts/{postId}` immediately after should still return the post (non-key columns not yet TTL'd,
   matching `@SoftDelete`'s design — same pattern already proven correct for `Wallet` in v1.0's §4.3
   run), but the post should be excluded from `findActive()`-backed views.
3. **This was the acceptance-criterion gap from v1.0** ("soft-deleting a post removes it from the feed
   within the same request cycle" — recorded as "not independently verified" because deleting a post
   at all failed): now confirm it directly. `GET /users/{authorId}/feed` (or equivalent paginated-feed
   route) immediately after the delete. Since `Post`'s feed uses client-side filtering after
   `findPage` (per the plan's own documented design — `findActive()` can't be partition-scoped),
   confirm the client-side filter correctly excludes the just-deleted post in the very next feed
   fetch, not just via a direct `findById`.
4. Confirm `PostByTag` rows are **still** not cleaned up (this was explicitly "expected, not a bug" in
   v1.0 — `PostByTag` is a hand-rolled table, not a `@LookupIndex`, no automatic cleanup exists) — this
   part of the v1.0 finding was never independently re-verified because Flow 5 itself was blocked;
   confirm it now that deletes actually work.

## Acceptance criteria — re-check the two that weren't independently verified

From `docs/report:1.0/07-capstone-results.md` §7.7:

- **"Soft-deleting a post removes it from the feed within the same request cycle"** — covered by Flow
  5 re-run step 3 above.
- **"Cold restart of the app reconnects and serves correctly"** — genuinely never tested in v1.0 (the
  app was never restarted during that run, unrelated to any of the 6 fixes). Restart the sample app
  process against the still-running cluster and confirm: `KandraMultiDc.describe()` logs correctly on
  startup, `GET /kandra/health` returns 200, and a request against each of `User`/`Wallet`/`Post`
  succeeds without needing a fresh keyspace or any manual intervention.

## What doesn't need re-running

Flow 2 (paginated feed), Flow 3 (tag search), Flow 4 (concurrent likes), and the node-resilience checks
(1-of-3 and 2-of-3 node kills) all passed cleanly in v1.0 for reasons unrelated to any of the 6 fixed
bugs. Don't re-run these as part of this pass unless something else in the environment changed (e.g.
if file 01's auth/two-DC work reuses this same cluster) — re-running unrelated passing tests just to
pad coverage numbers is exactly the kind of thing v1.0's scoring rubric warns against.
