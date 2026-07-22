# ISS-026: `KandraBatchScope`'s `save`/`delete` were structurally unreachable — batches never batched

**Status:** Fixed, verified live

## Problem

Found during real-cluster test plan execution (`docs/report:1.0/`, Finding #10 — discovered via the
capstone's multi-write-atomicity flow). `KandraBatchScope` declared `save`/`delete` as member-extension
functions on `KandraRepository`/`KandraSuspendRepository`, intending `with(repo) { save(entity) }`
inside a `batch { }`/`batchBlocking { }` block to collect statements instead of executing them
immediately. But Kotlin's overload resolution always prefers a same-named member function of the
extension receiver over an extension function — even a member-extension of a closer implicit
receiver — unconditionally. Since every repository already has its own real `save`/`delete` member,
`with(repo) { save(entity) }` (and the plugin's own `KandraRuntime` KDoc example, which called
`repo.save(entity)` directly) always resolved to the repository's own immediately-executing method,
never `KandraBatchScope`'s intended one. For suspend repositories this failed to compile outright; for
blocking repositories it silently compiled, returned success, and provided **zero atomicity** —
confirmed via `debug.logBatches` showing N independent `LOGGED BATCH` executions instead of one
combined cross-table batch. This defeated the exact multi-table-write correctness guarantee
`KandraBatchScope` exists to provide, with no error, warning, or other signal short of reading debug
logs line by line.

## Fix

Renamed to `saveInBatch`/`deleteInBatch` — names that don't collide with any real repository member,
so there is no ambiguity for Kotlin to resolve incorrectly; if it compiles, it can only have resolved
to the batch-collecting extension.

**File:** `kandra-runtime/.../KandraBatchScope.kt` (also updated the KDoc example in `KandraRuntime.kt`,
`README.md`'s "Caller-Controlled Batches" section, and the `kandra-runtime` skill doc).

**Verification:** live, against a real 3-node ScyllaDB cluster — `batchBlocking { primaryRepo.saveInBatch(...); secondaryRepo.saveInBatch(...) }` across two independent repositories, confirmed both rows present after the block, with the API design now guaranteeing (not just happening to produce) a single atomic batch.

## Related: ISS-027

Fixing this surfaced an identical, independently-unreachable bug in `KandraBatchScope`'s
`saveIfNotExists` LWT guard — see [ISS-027](ISS-027-batch-scope-save-if-not-exists-guard-unreachable.md).
