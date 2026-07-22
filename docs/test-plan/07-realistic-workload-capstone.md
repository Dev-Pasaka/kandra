# 7. Realistic Workload Capstone

Files 3 and 4 test Kandra one feature at a time. That's necessary but not sufficient — a library can
pass every isolated check and still not compose into something that handles real, medium-to-complex
Cassandra query patterns the way an actual day-to-day application needs. This file is the final
deliverable: extend the sample app from [file 2](02-sample-application-build-plan.md) into one
cohesive, running **social feed** service — a deliberately classic Cassandra workload (time-series
per-partition data, fan-out reads, counters, tagging) — and prove the whole thing works end to end
against the real cluster.

If you get to the end of this file with a server that actually serves these flows correctly, that is
the strongest possible evidence Kandra does what it claims. If you get stuck, **where** and **why**
you got stuck is itself one of the most valuable findings this entire plan can produce — Cassandra
data modeling has real, inherent constraints (no arbitrary `WHERE`, no joins) that no ORM can wish
away, and part of this capstone is honestly distinguishing "Kandra has a bug" from "this query
pattern genuinely requires different data modeling, and Kandra gave me the primitives to do it, but I
still had to do it."

## 7.1 Domain: a social feed

Add these two entities to the sample app alongside the ones from file 2:

```kotlin
package com.example.kandratest.model

import io.kandra.core.annotations.*
import java.time.Instant
import java.util.UUID

@ScyllaTable(name = "posts")
@SoftDelete(ttlSeconds = 86_400, markerProperty = "isDeleted")
data class Post(
    @PartitionKey val authorId: UUID,
    @ClusteringKey(order = ClusteringOrder.DESC) val createdAt: Instant,
    @LookupIndex(tableSuffix = "by_id", consistency = LookupConsistency.BATCH) val postId: UUID,
    val content: String,
    val tags: Set<String> = emptySet(),
    val isDeleted: Boolean = false
)

@ScyllaTable(name = "post_like_counters")
data class PostLikeCounter(
    @PartitionKey val postId: UUID,
    @Counter val likes: Long = 0L
)
```

Note the shape deliberately: `Post` is partitioned by `authorId` and clustered by `createdAt DESC` —
the correct Cassandra model for "give me this author's posts, newest first" (a partition-scoped range
query, the cheapest possible read pattern on this database). `postId` is a `@LookupIndex` purely so a
permalink URL (`GET /posts/{postId}`) can resolve a single post without knowing its author — a
textbook denormalized-lookup use case. Likes live in a **separate counter table** keyed by `postId`,
because counter columns can't coexist with regular columns in the same CQL table (per
`SchemaRegistry`'s counter-table validation) — this mirrors how you'd actually model "likes" in
production Cassandra, not a workaround specific to Kandra.

## 7.2 Flow 1 — create a post (the multi-write problem)

A realistic "create post" needs to: insert the post row, and — if you want tag-based discovery (7.4)
— also write into a tag-lookup structure. `@LookupIndex` only supports a **single-valued** property
per lookup table; `tags` is a `Set<String>`, so there is no annotation that automatically maintains a
"posts by tag" table for you. This is expected, not a gap to work around by inventing something —
Cassandra itself doesn't have multi-valued secondary indexing that performs well at scale either;
real applications hand-roll a `posts_by_tag(tag, post_id, author_id, created_at)` table and maintain
it themselves, same as Kandra's own generated `@LookupIndex` tables do internally, just without the
codegen.

Implement this explicitly:

```kotlin
@ScyllaTable(name = "posts_by_tag")
data class PostByTag(
    @PartitionKey val tag: String,
    @ClusteringKey(order = ClusteringOrder.DESC) val createdAt: Instant,
    val postId: UUID,
    val authorId: UUID
)
```

Register `PostByTag::class` alongside the others. On `POST /posts`, write the primary `Post` row and
one `PostByTag` row per tag **atomically**, using `KandraBatchScope`
(`runtime.batchBlocking { }`/`runtime.batch { }`, `@ExperimentalKandraApi`) rather than two
independent `save()` calls — a partial failure between two unrelated `save()` calls would leave a
post visible in the feed but invisible to tag search, or vice versa.

```kotlin
post("/posts") {
    val req = call.receive<CreatePostRequest>()
    val post = Post(authorId = req.authorId, createdAt = Instant.now(), postId = UUID.randomUUID(),
                     content = req.content, tags = req.tags)
    application.kandra.batchBlocking {
        with(postRepo) { save(post) }
        req.tags.forEach { tag ->
            with(postByTagRepo) { save(PostByTag(tag, post.createdAt, post.postId, post.authorId)) }
        }
    }
    call.respond(HttpStatusCode.Created, post)
}
```

**Verify**: kill a node mid-request (as in file 4 §4.11) during this batch and confirm it's genuinely
all-or-nothing (a `LOGGED BATCH` across these statements) — if you find the post row committed with
some tag rows missing, or vice versa, that's a real finding, not expected per `KandraBatchScope`'s
documented semantics.

## 7.3 Flow 2 — paginated author feed (the core time-series query)

```kotlin
get("/users/{authorId}/feed") {
    val authorId = UUID.fromString(call.parameters["authorId"]!!)
    val pageSize = call.request.queryParameters["size"]?.toIntOrNull() ?: 20
    val token = call.request.queryParameters["token"]
    val page = postRepo.findPage(pageSize, token) { +PostTable.authorId.eq(authorId) }
    call.respond(page)
}
```

This is the cheapest, most idiomatic Cassandra read there is — a single-partition range scan with
driver-native paging. Confirm real pagination end to end: create 50+ posts for one author, walk every
page via `nextPageToken` until `hasMore == false`, and confirm the total row count across all pages
matches exactly (no duplicates, no gaps — a real risk if paging-state handling in the new suspend
`findPage` from `docs/issues/ISS-014` has any off-by-one).

### The soft-delete-in-a-feed problem (expect to hit this, and document it honestly)

You will likely find that **`findActive()` cannot be used here as-is**: as built in file 2/3,
`findActive()` takes no predicate — it always runs `SELECT * FROM <table> WHERE <marker> = false
ALLOW FILTERING` against the **entire table**, with no partition scope. For a `Post` table
partitioned by `authorId`, that means `findActive()` would scan **every author's posts**, not just
this one's — both semantically wrong for a per-author feed and a real scalability problem (a
full-table `ALLOW FILTERING` scan) on anything but a toy dataset.

The two realistic ways to actually build "this author's active posts, paginated" with what Kandra
gives you:

1. **Filter client-side after `findPage`.** Fetch the page via the partition-scoped query above (no
   `ALLOW FILTERING`, cheap), then drop `isDeleted == true` entries in application code before
   returning. Downside: a page can come back with fewer items than `pageSize` after filtering
   (possibly zero), so pagination logic needs to keep fetching pages until it has enough live items or
   runs out — implement this and confirm it terminates correctly rather than looping forever on a
   heavily-soft-deleted feed.
2. **Model soft-delete into the clustering key instead of a marker column** (a more idiomatic
   Cassandra pattern than Kandra's current `@SoftDelete` marker-column design provides out of the
   box) — e.g. don't delete at all, just stop returning items past a "deleted" flag stored alongside,
   and accept option 1's filtering, or maintain a *second* denormalized "active feed" table that a
   delete removes the entry from directly. This is more work but avoids any filtering-after-fetch
   waste.

Implement option 1 (simpler, and it's the one `Post`'s existing `@SoftDelete` annotation actually
supports) and write down, precisely, in your final report: **is this a Kandra limitation worth
raising as a documentation gap** (i.e., `findActive()`'s docs should say up front "this is a
whole-table scan with no partition scope — do not use it on a partitioned, time-series-style table
without understanding that," since the `Wallet` example in file 2 happens to be a small,
non-partitioned-for-scale table where this limitation doesn't bite) **or does it turn out there's a
scoped variant you missed**. Don't silently work around it and move on without recording which case
this is — this is exactly the kind of thing files 3/4's narrower, single-feature checks can't surface
on their own, and is precisely the value this capstone file adds.

## 7.4 Flow 3 — tag search

```kotlin
get("/tags/{tag}/posts") {
    val tag = call.parameters["tag"]!!
    val pageSize = call.request.queryParameters["size"]?.toIntOrNull() ?: 20
    val token = call.request.queryParameters["token"]
    val page = postByTagRepo.findPage(pageSize, token) { +PostByTagTable.tag.eq(tag) }
    call.respond(page)
}
```

Cheap, partition-scoped, no `ALLOW FILTERING` — because `PostByTag` was deliberately modeled with
`tag` as the partition key. Confirm this returns correctly and that a post with multiple tags
appears once per tag it was written under (i.e. `posts_by_tag` legitimately has one row per
`(tag, postId)` pair, by design, not a bug).

**Known consequence to confirm, not "fix":** deleting a post (soft or hard) does **not** clean up its
`PostByTag` rows — there is no lookup-table mechanism here at all (it's a hand-rolled table, not a
Kandra `@LookupIndex`), so cleanup is entirely the application's responsibility, same as it would be
in a hand-rolled Cassandra data model with no ORM at all. Confirm a soft-deleted post still shows up
in tag search results (it will, unless you write the cleanup code yourself) and record this as
expected behavior for the demonstrated design, not a bug report — then, if you have time, implement
the missing-column-cleanup delete path yourself (delete the `PostByTag` rows for each of the post's
tags when the post is deleted) inside the same `KandraBatchScope` used in 7.2, and confirm *that*
works atomically too.

## 7.5 Flow 4 — likes

```kotlin
post("/posts/{postId}/like") {
    val postId = UUID.fromString(call.parameters["postId"]!!)
    postLikeCounterRepo.increment(PostLikeCounter::likes, mapOf("postId" to postId))
    call.respond(HttpStatusCode.NoContent)
}
```

Fire this concurrently from many simulated clients (e.g. 50 concurrent "like" requests against the
same `postId`) and confirm the final counter value is exactly 50 — counters are Cassandra's
purpose-built mechanism for exactly this kind of high-contention increment, so this should hold up
cleanly; if the final count is off, that's a real and serious finding (data loss on the single
feature Cassandra counters exist to make safe).

## 7.6 Flow 5 — delete a post

```kotlin
delete("/posts/{postId}") {
    val postId = UUID.fromString(call.parameters["postId"]!!)
    val lookupRow = postRepo.find { +PostTable.postId.eq(postId) } ?: return@delete call.respond(HttpStatusCode.NotFound)
    postRepo.delete(lookupRow)   // soft delete — TTL on non-key columns, marker set immediately
    call.respond(HttpStatusCode.NoContent)
}
```

Confirm: immediately after this call, the post is excluded from the author's feed under the 7.3
client-side-filter approach, but (per 7.4) still appears in tag search unless you implemented the
optional cleanup. Confirm the `posts_by_id` lookup table (from `@LookupIndex`) still resolves the
post by `postId` — soft delete does not remove lookup rows the way a hard delete does (re-check this
specific claim against `BatchEngine`'s soft-delete implementation, since it's easy to assume soft
delete behaves identically to hard delete's lookup cleanup and it may not).

## 7.7 Acceptance criteria — this is the actual deliverable

The capstone is complete when every one of these is a real, observed, working request/response
against the live 3-node cluster — not a unit test, not a mock, an actual `curl` (or equivalent) call
that returns the right thing:

- [ ] Create 3 users, then 30+ posts spread across them with overlapping tags.
- [ ] `GET /users/{id}/feed` returns that author's posts, newest first, correctly paginated across
      multiple pages with no duplicates/gaps.
- [ ] Soft-deleting a post removes it from that feed (via the client-side filter from 7.3) within the
      same request cycle that issued the delete — no stale read.
- [ ] `GET /tags/{tag}/posts` returns the right posts for a tag, correctly paginated.
- [ ] 50 concurrent likes on one post land the counter on exactly 50.
- [ ] The whole server survives one node in the 3-node cluster being killed and restarted mid-run
      (file 4 §4.11) without the application crashing (individual requests during the outage window
      may legitimately fail/retry — the *process* should not die).
- [ ] A cold restart of the application (not the cluster) reconnects and serves all of the above
      correctly with zero manual intervention.

Write up the outcome of this file the same way as files 3/4 (per the
[scoring rubric](05-scoring-rubric-and-reporting.md)), plus a short narrative section: did Kandra's
primitives compose cleanly into this real workload, where (if anywhere) did you have to drop into
raw CQL or hand-rolled tables to get correct Cassandra data modeling, and does that match what the
[background section](README.md#background-what-kandra-is-and-the-problem-it-solves) claims Kandra is
for?
