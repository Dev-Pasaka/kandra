# Realistic Workload Capstone — Results

Built the social-feed capstone (`Post`, `PostLikeCounter`, `PostByTag`) alongside the file-2 entities in the
same sample app, registered with `AUTO_CREATE`, tested against the live 3-node ScyllaDB cluster.

## 7.1 Domain entities — registered and DDL-verified successfully
`Post` (partition key `authorId`, clustering key `createdAt DESC`, `@LookupIndex(postId, "by_id", BATCH)`,
`tags: Set<String>`, `@SoftDelete`), `PostLikeCounter` (counter table), `PostByTag` (hand-rolled denormalized
lookup, partition key `tag`, clustering key `createdAt DESC`) all registered and created cleanly.
**Note**: `Post.tags` hit the same kandra-codegen Set/Map bug as `User.tags` (Finding #6) — patched via the
same `patchBrokenCodegenCollectionRefs` Gradle task workaround, extended to cover `PostTable.kt`.

## 7.2 Flow 1 — create a post (multi-write atomicity) — **FAIL, Critical (Finding #10)**
Implemented exactly per the plan's worked example (`application.kandra.batchBlocking { with(repo) { save(...) } }`).
**Result: does NOT provide atomicity.** Confirmed via `debug.logBatches` log inspection on a real request (2 tags):
```
Executing LOGGED BATCH with 2 statements for posts        <- Post's own save() + its own lookup insert
Executing LOGGED BATCH with 1 statements for posts_by_tag  <- tag 1, completely independent
Executing LOGGED BATCH with 1 statements for posts_by_tag  <- tag 2, completely independent
```
Three independent `BatchEngine` executions, not one combined cross-table batch. Root cause: Kotlin's
member-function-always-wins-over-extension-function resolution rule means `with(repo) { save(entity) }` inside
`batchBlocking { }` always resolves to the repository's own ordinary `save()` method, never `KandraBatchScope`'s
intended statement-collecting extension — for both suspend repos (doesn't even compile: "Suspension functions
can only be called within coroutine body") and blocking repos (compiles, silently non-atomic). See
`report/PROGRESS.md` Finding #10 for full detail. **This is the single highest-value finding from the capstone**
— it means the exact multi-table-write correctness problem the plan's own background section identifies as
central to Kandra's value proposition ("a partial failure... would leave a post visible in the feed but invisible
to tag search, or vice versa") is **not actually solved** by the documented API, silently.

## 7.3 Flow 2 — paginated author feed — **PASS**
Created 30 posts across 3 authors (10 each), walked every page (`size=3`) via `nextPageToken` until
`hasMore=false`: **10 unique posts returned per author, zero duplicates, zero gaps**, newest-first order
confirmed (clustering `DESC` honored). Cheap partition-scoped query, no `ALLOW FILTERING` — exactly the
idiomatic Cassandra pattern the plan describes.
**Soft-delete-in-a-feed problem**: implemented option 1 (client-side filter after `findPage`) as the plan
directs. Confirmed this is a real, correct characterization — `findActive()` is a whole-table scan with no
partition scope and genuinely cannot be used for a per-author feed; the client-side filter is the only
practical option `Post`'s `@SoftDelete` design supports. **This is a documentation gap worth raising**:
`findActive()`'s docs should say up front it's an unscoped, whole-table `ALLOW FILTERING` scan.

## 7.4 Flow 3 — tag search — **PASS**
`GET /tags/tech/posts` correctly returned all 13 posts tagged "tech" (partition-scoped by `tag`, no
`ALLOW FILTERING`, confirmed via the hand-rolled `PostByTag` table). Multi-tag posts confirmed to appear once
per tag, by design (each tag's `posts_by_tag` row is written independently in the create-post flow).
**Known consequence confirmed, not a bug**: no cleanup mechanism exists for `PostByTag` rows on post
deletion (this is a hand-rolled table, not a `@LookupIndex`) — expected per the plan's own framing, not
independently re-verified given Flow 5 itself is blocked (see below).

## 7.5 Flow 4 — likes (counter contention) — **PASS, clean**
Fired 50 concurrent `POST /posts/{id}/like` requests against one post. Final counter value: **exactly 50**.
Counters held up perfectly under real concurrency, as expected — the one feature Cassandra counters exist to
make safe, working correctly.

## 7.6 Flow 5 — delete a post — **FAIL, blocked by Finding #9 (clustering-key WHERE-clause omission)**
`DELETE /posts/{postId}` → 500, log confirms: `InvalidQueryException: Missing mandatory PRIMARY KEY part created_at`.
`Post`'s soft-delete rewrite `UPDATE` (triggered by `repo.delete()`) builds its WHERE clause from partition
keys only (`authorId`), omitting the clustering key (`createdAt`) — same root cause as Finding #9, now
concretely demonstrated breaking a real capstone flow, not just a synthetic direct-repository test. **Any
entity combining a clustering key with `@SoftDelete` is completely unable to delete individual rows** — the
capstone's own `Post` design (clustering key + soft delete, both individually documented, idiomatic features)
combines them in exactly the way that breaks.

## Node resilience (file 4 §4.11, capstone acceptance criterion)
- Killed 1 of 3 nodes (`docker stop scylla-node2`): app stayed healthy (`GET /kandra/health` → 200), successfully
  created a new post during the outage (`LOCAL_QUORUM` intact with 2/3 up). **PASS.**
- Restarted the killed node: rejoined cluster automatically (`nodetool status` → all 3 `UN` again), app
  continued serving without restart. **PASS.**
- Killed 2 of 3 nodes: a write correctly failed fast (~90ms, not hanging) once quorum was lost. **Divergence
  from expectation**: the observed exception was a raw, unwrapped `com.datastax.oss.driver.api.core.AllNodesFailedException`
  wrapping `UnavailableException: Not enough replicas available for query at consistency ONE (1 required but
  only 0 alive)` — **not** the `KandraQueryException` the plan expected ("likely wrapped by RetryConfig's
  exhaustion path"). Root cause: `UnavailableException` is not in `RetryConfig`'s default `retryOn` set
  (`WriteTimeoutException`, `ReadTimeoutException`, `NoNodeAvailableException` only), so `BatchEngine` never
  even attempts a retry or wrap for this specific exception type — it propagates completely raw. Separately
  notable: consistency `ONE`, not the configured `LOCAL_QUORUM` — consistent with the already-documented
  behavior that `insertLookup`/`deleteLookup` (this request touched `Post`'s `@LookupIndex` `posts_by_id`
  table) never apply any consistency override and fall back to the driver/session's own default. **Recorded
  as a PARTIAL for the exception-type expectation** (behavior itself — failing fast, not hanging — is correct;
  the specific exception type/wrapping is not what was documented as "likely").
- Restarted both nodes: cluster fully reconverged (all 3 `UN`), **app process never crashed or needed a
  restart across the entire outage window** — the capstone's own explicit acceptance criterion.

## 7.7 Acceptance criteria — final status

| Criterion | Status |
|---|---|
| Create 3 users, 30+ posts with overlapping tags | **DONE** |
| Paginated feed, newest first, no dup/gaps | **PASS** |
| Soft-deleting a post removes it from the feed within the same request cycle | **NOT INDEPENDENTLY VERIFIED** — blocked, since deleting a post at all fails (Flow 5 / Finding #9) |
| Tag search returns correct paginated results | **PASS** |
| 50 concurrent likes land exactly on 50 | **PASS** |
| Server survives one node killed+restarted mid-run | **PASS** |
| Cold restart of the app reconnects and serves correctly | **NOT YET RE-VERIFIED** (app was never restarted during this run — worth a final explicit restart test) |

## Capstone narrative — did Kandra's primitives compose into a working real app?

**Partially.** The read-side patterns (partition-scoped paginated feed, denormalized tag-search table, counter
likes) worked cleanly and are genuinely idiomatic, well-supported Cassandra patterns — this part of Kandra's
value proposition holds up. The write side did not: the documented atomic-multi-write primitive
(`KandraBatchScope`) silently fails to batch at all (Finding #10), and the combination of two individually
well-documented, idiomatic features — a clustering key and `@SoftDelete` — completely breaks row-level deletes
(Finding #9), a combination the capstone's own `Post` entity was designed around and immediately hit. Neither
gap is a "this needed different data modeling" situation the plan's introduction anticipates as an honest,
non-bug outcome — both are internal implementation bugs (a resolution-order/API-design defect and a
copy-pasted `partitionKeys`-only WHERE-clause helper, respectively) that a consumer following the documented
API in good faith would hit without any warning, not a data-modeling limitation inherent to Cassandra itself.
