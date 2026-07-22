# 4. Edge Cases and Adversarial Tests

This is the harsh-critic pass. Everything in file 3 checks "does the documented behavior happen at
all." This file checks "does it hold up under the conditions most likely to break it" — boundaries,
races, malformed input, partial failure, and scale. Several scenarios below test fixes made in
0.4.1/0.4.2 (`docs/issues/`) that have **never been run against a real cluster** — treat a clean pass
here as the first real evidence those fixes work, not a formality.

For every scenario: capture the exact CQL Kandra generated (`debug.logQueries = true`), the exact
exception (type + message + stack trace) if one occurred, and whether the outcome matched the
"expected" column. If it didn't, that's a finding — write it up per the
[reporting format](05-scoring-rubric-and-reporting.md), don't silently note it and move on.

## 4.1 Suspend read path concurrency

`docs/issues/ISS-014` claims `QueryExecutor` used to block the calling coroutine dispatcher on every
suspend read, and that 0.4.2 fixed it by adding true-async suspend variants. This is a concurrency
claim — the only way to actually verify it is under real concurrent load, not a single request.

- **Setup**: a route that runs `findById`/`findAll`/`findPage` inside a coroutine on a *constrained*
  dispatcher — e.g. `Dispatchers.IO.limitedParallelism(2)` — and fire 50 concurrent requests through
  it.
- **Expected if fixed**: total wall-clock time for 50 concurrent reads should be close to the network
  round-trip time × (50 / effective concurrency the driver allows), NOT 50 × (round-trip time), which
  is what you'd see if each read were secretly blocking one of only 2 available threads for its full
  duration.
- **How to fail this test on purpose first** (to prove your measurement methodology actually detects
  the bug): temporarily revert to the blocking equivalent (call `KandraRepository` instead of
  `KandraSuspendRepository` from inside the same constrained-dispatcher route) and confirm you *do*
  see the linear-blocking pattern. If you can't reproduce the "bad" pattern with the known-blocking
  API, your test harness isn't sensitive enough to trust a "good" result from the suspend API either.
- Also specifically re-check `findPage`'s suspend variant — it's the most complex of the new suspend
  methods (paging-state handling via `AsyncResultSet.currentPage()`/`hasMorePages()`); confirm paging
  actually terminates correctly (`hasMore == false` on the last page) under the suspend path, not just
  that it doesn't block.

## 4.2 `saveAll` batch chunking — atomicity boundary

Per docs, `batchAutoChunk` splits a `saveAll` exceeding `batchMaxChunkSize` into multiple independent
`LOGGED BATCH`es — **cross-chunk atomicity is explicitly not guaranteed**.

- Set `batchMaxChunkSize = 50` (already done in file 2) and call `saveAll` with 120 entities where the
  entity at index 75 (partway through chunk 2) is deliberately invalid in a way that only fails at
  the database level, not at construction time — e.g. a `String` value that's valid Kotlin but
  exceeds a constrained column somehow, or more reliably: kill the cluster connection (stop a node)
  mid-call if you can time it, or inject a value that trips `KandraQueryException` from the codec
  (a non-nullable field populated with something that later 404s — harder to construct; simplest
  reliable trigger is a mid-run node/network failure).
  - **Expected**: chunk 1 (entities 0-49) committed and durable even though the overall `saveAll`
    call threw partway through chunk 2 or later. Verify by querying for entities 0-49 *after* the
    failed call — they should exist despite the exception.
- Separately, confirm the exact chunk **count and boundaries**: 120 entities ÷ 50 = 3 chunks (50, 50,
  20) — confirm via `debug.logBatches` that you see exactly 3 separate `LOGGED BATCH` log lines, not
  1 or 2.
- Confirm the WARN log for `batchWarnThresholdKb` — with `batchWarnThresholdKb = 5` (5KB) and the
  size heuristic being `statements.size * 512` bytes (a fixed per-statement estimate, not real
  serialized size per the runtime docs), work out how many entities cross that threshold
  (5120 / 512 = 10 statements) and confirm the WARN actually fires at that boundary, not some other
  count — this estimate is fixed and knowable in advance, so an off-by-one here is a real bug, not
  measurement noise.

## 4.3 `@SoftDelete` + `findActive()` timing window

This is the single most timing-sensitive new feature in 0.4.2 (`docs/issues/ISS-007`) — test the
actual window, not just "eventually consistent with expectations."

Using `Wallet` (`ttlSeconds = 10`, `markerProperty = "isDeleted"`):

1. `save()` a wallet. Confirm `findActive()` includes it (`isDeleted = false`).
2. `delete()` it (triggers soft delete). **Immediately** (same second):
   - `findById()` should still return the row — all non-key columns were rewritten with a fresh TTL,
     not cleared.
   - `findActive()` should **already** exclude it — the marker column write has no TTL, so it should
     be visible/queryable via `WHERE is_deleted = false ALLOW FILTERING` the instant the soft-delete
     `UPDATE` commits, well before the 10s TTL on the other columns expires. This is the entire point
     of the marker-column design — confirm it actually holds, not just conceptually but with real
     timing (query at t+0.5s, not t+15s where you can't tell which mechanism excluded it).
3. Wait past `ttlSeconds` (10s) plus a safety margin for ScyllaDB's TTL-expiry granularity (check
   real behavior — TTL expiry is not instantaneous to the millisecond). Confirm:
   - The non-key columns are now gone/null when read directly via `cqlsh` (`SELECT * FROM wallets
     WHERE wallet_id = ...` — most columns should show `null`).
   - The **partition key row still exists as a tombstone** until `gc_grace_seconds` passes (default
     864000s/10 days — don't wait for this in a test, just confirm the row's continued existence via
     `SELECT wallet_id FROM wallets WHERE wallet_id = ...` returning a row with all-null non-key
     columns, consistent with "tombstone, not gone").
   - `findActive()` still excludes it (the marker column, having no TTL, survived — confirm it did
     *not* also get swept away, which would be a real regression since the marker's whole purpose is
     to outlive the TTL'd columns).
4. **Race condition variant**: call `delete()` and, in the same instant from a concurrent
   coroutine/thread, call `findActive()` and `findById()` repeatedly in a tight loop for ~2 seconds.
   Confirm there is no window where `findActive()` still includes the row after `delete()` has
   returned (i.e. the marker-column write must have actually committed by the time `delete()`'s
   suspend/blocking call returns — check the implementation does the marker-column `UPDATE` as part
   of the same synchronous call chain, not fired-and-forgotten async).
5. **`findActive()` without a marker column configured**: call it on an entity with plain
   `@SoftDelete(ttlSeconds = ...)` and no `markerProperty` (if you add one for this specific test).
   Expected: `KandraSchemaException` at call time, not at registration time — confirm which point it
   actually fails at.

## 4.4 Optimistic locking under real contention

`update(old, new)`'s `@Version` LWT check has only ever been exercised by one sequential test case in
the 0.4.2 integration suite (`docs/issues/ISS-013`) — not real concurrent contention.

- Load the same `User` row into N (say, 10) concurrent coroutines/threads, each computing a different
  update from the *same* loaded `old` snapshot, and fire all N `update(old, new)` calls
  simultaneously.
  - **Expected**: exactly 1 succeeds, the other 9 throw `KandraOptimisticLockException`. If more than
    one appears to succeed, that's a serious correctness bug in the LWT path, not a minor issue.
- Confirm `KandraOptimisticLockException.entityClass`/`.partitionKey` are populated correctly and
  usable (e.g. `e.entityClass.simpleName == "User"`).
- Retry-after-conflict pattern: on catching the exception, re-`findById()` and retry `update()` with
  the fresh version — confirm this converges (doesn't loop forever under reasonable contention) and
  that the final state reflects exactly one of the attempted updates having "won" per attempt
  (standard optimistic-retry semantics, not a Kandra-specific behavior, but worth confirming the
  primitives compose correctly).
- **No-`@Version` entities**: run the identical concurrent-update experiment against an entity with
  no `@Version` column (e.g. `AuditLog` if you add an update path, or a throwaway entity). Per docs,
  `update()` without `@Version` does a blind full-row overwrite with **no concurrency check at all**.
  Confirm: all N concurrent updates "succeed" (no exception from any of them), and the final
  persisted state is simply whichever one physically committed last — i.e. confirm data loss under
  concurrent writes is real and silent here, as documented, not something Kandra secretly guards
  against some other way.

## 4.5 Migration locking under real concurrency

`docs/issues/ISS-018` claims 0.4.2 added an LWT claim step so two `KandraMigrationRunner` instances
racing the same version can't both execute `up()`. This has never been tested under actual
concurrency (the fix was verified only by compiling and reading the code).

- Define a migration whose `up()` has an observable, non-idempotent side effect distinguishable from
  a second execution — e.g. `INSERT INTO migration_side_effects (id, note) VALUES (uuid(), 'ran')`
  (a plain, unconditional INSERT — if `up()` runs twice, you'll see two rows).
- Construct two separate `CqlSession`s (simulating two app instances) and two separate
  `KandraMigrationRunner` instances, one per session, both pointed at the same fresh keyspace with
  this migration unapplied.
- Launch both `runner.run(migration)` calls **as close to simultaneously as you can** (e.g. from two
  threads released by the same `CountDownLatch`, or two separate JVM processes started at the same
  time).
- **Expected**: exactly one `migration_side_effects` row, regardless of which runner "won." The
  loser's log output should show the "claimed by another instance concurrently — skipping" message
  (per the 0.4.2 changelog entry), not a duplicate execution and not a crash.
- **Also test the failure-releases-claim path**: a migration whose `up()` throws after doing some
  work. Confirm the claim row is deleted (per the fix), so a subsequent `run()` call retries it rather
  than being permanently stuck as "claimed by nobody, but also recorded as existing."

## 4.6 Migration checksum — what actually changes it

`docs/issues/ISS-017` claims the checksum now also hashes the migration class's compiled bytecode,
not just `version:name:qualifiedClassName`.

- Apply a migration, then make each of these changes **one at a time**, recompile, and re-run,
  confirming which ones now trip `KandraMigrationException` and which don't:
  1. Change a string literal inside `up()`'s CQL (e.g. add a harmless extra column to a `CREATE
     TABLE ... IF NOT EXISTS` that wouldn't matter functionally). **Expected**: now throws (this is
     the specific case the fix targeated — confirm it's real, not just present in the diff).
  2. Add a no-op comment inside `up()`'s Kotlin source with no bytecode effect (if your compiler
     genuinely produces byte-identical output — verify this assumption isn't accidentally also
     tripping the checksum, since comments occasionally do end up encoded in debug info depending on
     compiler flags).
  3. Rename the migration `object`. **Expected**: still throws (this was already covered before the
     fix — confirm it's not now double-triggering or behaving differently).
  4. Change only `version` or only `name` while leaving `up()` identical. **Expected**: still throws
     (unchanged from before the fix).
  5. Recompile with a trivial, unrelated change elsewhere in the same module (a different file
     entirely) and confirm the checksum for *this* migration's class file does **not** change just
     because the module was rebuilt — the fix should be scoped to this specific class's bytecode, not
     accidentally sensitive to the whole build.

## 4.7 Cache invalidation gaps — confirm they're real, not accidentally fixed

Per the runtime docs, `deleteById`'s cache-invalidation gap was fixed in 0.4.2, but `deleteBy`,
`append`, `remove`, `put`, `increment`, `decrement` were **not** touched — they still don't invalidate
the `@CacheResult` cache.

- `findById(user)` (populates cache) → `deleteById(user.id)` → `findById(user.id)` again. **Expected
  (fixed)**: second call returns `null`/not-found, not the stale cached row.
- `findById(user)` (populates cache) → `deleteBy { +UserTable.email.eq(user.email) }` →
  `findById(user.id)` again. **Expected (still broken, per docs)**: second call may return the
  **stale cached entity** even though the row is gone/soft-deleted, until the cache TTL (30s per file
  2's config) expires. Confirm this is real — if it turns out to already be fixed, that's worth
  reporting too (a positive surprise is still a finding).
- Same experiment for `append`/`remove`/`put` on a cached entity's collection field, and
  `increment`/`decrement` (though counters typically aren't cached the same way — confirm whether
  `@CacheResult` even applies meaningfully to counter tables, or whether this is N/A).

## 4.8 Codec boundary values

- **`BigDecimal` precision**: save a `User.balance` with more decimal places than a `Double` could
  represent exactly (e.g. `BigDecimal("0.1")` repeated additions, or a very large scale like
  `BigDecimal("123456789012345678.123456789")`). Read it back and confirm exact round-trip — CQL
  `DECIMAL` should preserve arbitrary precision; if you observe any float-like rounding, that's a
  real bug.
- **Empty collections vs. null collections**: `User.tags = emptySet()` vs. a hypothetical
  nullable collection field left `null`. Confirm both encode/decode without throwing, and confirm
  whether ScyllaDB stores an empty collection as an actual empty collection or as effectively absent
  (CQL has historically treated empty collections specially — confirm current real behavior, don't
  assume).
- **Very large collections**: `append()` a few thousand entries onto `User.tags` one at a time (or in
  a few large batches) and confirm no silent truncation, and note at what size (if any) ScyllaDB
  itself starts warning about large partitions/collections in its logs — that's cluster-level
  behavior worth knowing about even though it's not a Kandra bug per se.
- **Null into a non-nullable field**: construct an entity via reflection or a raw CQL insert bypassing
  the type system so a non-nullable Kotlin field's column is actually `NULL` in Scylla, then
  `findById()`. **Expected**: `KandraQueryException` naming the column/property — confirm the actual
  message is useful enough to debug from.
- **Enum drift**: see C31 in file 3 — deliberately store a stale/renamed enum value and confirm the
  exact exception (`IllegalArgumentException`, unwrapped, not a `KandraQueryException`) and that nothing
  in Kandra masks or wraps it into something friendlier (per docs, it does not).

## 4.9 DDL naming edge cases

- **Acronym collision**: add a property like `val userURL: String` (or `parseHTML`) to a throwaway
  entity. Per the `camelToSnake` gotcha in both `kandra-core` and `kandra-codegen` skills, this should
  produce `user_u_r_l` (every capital gets its own underscore), not `user_url`. Confirm this actually
  happens and isn't a documentation artifact from an older version.
- **`@Column(name = "")`** (blank, not omitted): per the codegen skill, this silently falls back to
  auto-derivation rather than erroring or using a literal empty string. Confirm this in the *codegen*
  output (`*Table.kt`) specifically, since that's a different code path from `SchemaRegistry`'s own
  `@Column` handling in `kandra-core` — check whether both layers agree on this fallback or diverge.
- **`gcGraceSeconds = 0` vs. omitted (`-1` default)**: `@ScyllaTable(name = "x", gcGraceSeconds = 0)`
  should emit `WITH gc_grace_seconds = 0` in DDL (since `0 >= 0` is treated as "set" per the schema
  registry gotcha), which in real ScyllaDB terms disables tombstone GC entirely. Confirm the DDL
  really contains `= 0` and not that it was accidentally treated the same as "unset."
- **Duplicate CQL column names from two different Kotlin properties**: per the codegen skill, nothing
  detects e.g. one property with explicit `@Column(name = "user_id")` colliding with another
  property's auto-derived `user_id`. Construct this deliberately and see what actually happens — does
  `CREATE TABLE` fail with a CQL-level duplicate-column error, or does something else silently break
  first (e.g. the codec binding the wrong value to the wrong logical field)?

## 4.10 Deliberately broken entities — exhaustive negative registration tests

Run each of these as an isolated `SchemaRegistry.register(BrokenX::class)` call (a small standalone
test/script, not wired into the main app's `register(...)` call, since each is expected to throw and
would otherwise crash the whole application at startup). Confirm the **exact exception type and
message shape** against the table in file 3 §3.1 and the `kandra-core` skill's "validation order"
section — and specifically confirm the validation **order** when an entity violates more than one
rule at once (the first check in source order should win; construct an entity that's broken in two
ways simultaneously and confirm which error surfaces).

```kotlin
// No @ScyllaTable at all
data class BrokenNoTable(@PartitionKey val id: UUID)

// No @PartitionKey at all
@ScyllaTable("broken_no_pk")
data class BrokenNoPk(val id: UUID)

// Duplicate @PartitionKey(index)
@ScyllaTable("broken_dup_pk_index")
data class BrokenDupPkIndex(
    @PartitionKey(index = 0) val a: UUID,
    @PartitionKey(index = 0) val b: UUID
)

// Duplicate @LookupIndex table name
@ScyllaTable("broken_dup_lookup")
data class BrokenDupLookup(
    @PartitionKey val id: UUID,
    @LookupIndex(tableSuffix = "by_x") val x: String,
    @LookupIndex(tableSuffix = "by_x") val y: String
)

// @Counter mixed with non-@Counter
@ScyllaTable("broken_mixed_counter")
data class BrokenMixedCounter(
    @PartitionKey val id: UUID,
    @Counter val hits: Long = 0L,
    val label: String = ""
)

// @Counter entity with a non-counter @LookupIndex column (kandra-core skill gotcha — confirm this really throws)
@ScyllaTable("broken_counter_with_lookup")
data class BrokenCounterWithLookup(
    @PartitionKey val id: UUID,
    @LookupIndex(tableSuffix = "by_x") val x: String,
    @Counter val hits: Long = 0L
)

// @CreatedAt on a non-Instant field
@ScyllaTable("broken_created_at_type")
data class BrokenCreatedAtType(
    @PartitionKey val id: UUID,
    @CreatedAt val createdAt: String = ""
)

// Two @CreatedAt
@ScyllaTable("broken_double_created_at")
data class BrokenDoubleCreatedAt(
    @PartitionKey val id: UUID,
    @CreatedAt val a: Instant = Instant.EPOCH,
    @CreatedAt val b: Instant = Instant.EPOCH
)

// @Version on an invalid type
@ScyllaTable("broken_version_type")
data class BrokenVersionType(
    @PartitionKey val id: UUID,
    @Version val version: String = ""
)

// @SoftDelete markerProperty pointing at a nonexistent property
@ScyllaTable("broken_marker_missing")
@SoftDelete(markerProperty = "doesNotExist")
data class BrokenMarkerMissing(@PartitionKey val id: UUID)

// @SoftDelete markerProperty pointing at a non-Boolean field
@ScyllaTable("broken_marker_type")
@SoftDelete(markerProperty = "deletedAt")
data class BrokenMarkerType(
    @PartitionKey val id: UUID,
    val deletedAt: Instant = Instant.EPOCH
)

// Two rule violations at once — which error wins? (no @PartitionKey AND bad @Version type)
@ScyllaTable("broken_double_violation")
data class BrokenDoubleViolation(
    val id: UUID,
    @Version val version: String = ""
)
```

## 4.11 Network partition / node failure resilience

Using the 3-node cluster from file 1:

- **Kill one node mid-operation**: `docker stop scylla-node2` while a sustained stream of
  `save()`/`findById()` calls is running. Confirm: `LOCAL_QUORUM` operations continue succeeding
  (2 of 3 nodes still up = quorum intact for RF=3), retries (per `RetryConfig`) absorb any transient
  errors during the failure detection window, and no data is lost for writes that were acknowledged
  before the kill.
- **Kill two of three nodes**: confirm `LOCAL_QUORUM` operations now genuinely fail (quorum lost),
  and that the failure surfaces as a sensible exception (likely wrapped by `RetryConfig`'s exhaustion
  path into `KandraQueryException("Query failed after N attempts", ...)`) rather than hanging
  indefinitely — measure how long it actually takes to fail and confirm it's bounded by
  `maxAttempts` × backoff, not the driver's own much longer default timeouts.
- **Restart the killed node(s)**: confirm the driver reconnects automatically and operations resume
  succeeding without an application restart.
- **Speculative execution under real latency**: artificially delay one node's responses (e.g. via
  `tc qdisc` inside the container, or by pausing the container briefly with `docker pause`/`unpause`)
  and confirm a speculative retry actually fires against a different replica within
  `speculativeExecution.delayMillis`, measurable as the request completing close to that delay rather
  than the slow node's full response time.

## 4.12 Auth / SSL failure modes

Against the auth-enabled cluster variant (file 1 §1.2.2):

- Correct credentials → connects.
- Wrong password → `KandraAuthException` (confirm it's this type specifically, wrapping the driver's
  `AuthenticationException`, not a raw driver exception leaking through).
- `KandraAuth.fromEnv()` with the env vars unset → `KandraAuthException` with the documented message
  naming the missing variable — confirm the **asymmetric wording** noted in the `kandra-core` skill
  (only the username-missing message suggests an alternative provider; confirm the password-missing
  one really doesn't).
- Low-privilege role missing `SELECT`/`MODIFY` → `KandraAuthException` at install time (permission
  validation), before any entity operations are attempted.
- If you build the optional SSL-enabled variant: a deliberately wrong/missing trust store path should
  throw `KandraAuthException` naming the failing path, not a raw `SSLException`.

## 4.13 Codegen correctness against a real build

- Confirm every entity's generated `*Table.kt` (under
  `build/generated/ksp/main/kotlin/.../*Table.kt`) matches the worked example format in the
  `kandra-codegen` skill — spot-check `UserTable` in particular given how many annotations `User`
  carries: confirm `@Sensitive`/`@Version`/`@SecondaryIndex`/`@CreatedAt`/`@UpdatedAt` properties all
  generate perfectly ordinary `KandraColumnRef`s indistinguishable from a plain column (per the
  documented "codegen reads almost nothing" finding), and that only `email`/`phone`
  (`@LookupIndex`) come out with `isLookup = true`.
- Confirm `@Transient val sessionToken` produces **no** corresponding `val` on `UserTable` at all.
- Add a computed property (`val displayLabel: String get() = "$displayName ($email)"`, no backing
  column) to a throwaway entity **without** marking it `@Transient`, and confirm the generated
  `*Table.kt` still emits a `KandraColumnRef` for it pointing at a CQL column that doesn't exist —
  then confirm what actually happens if you try to use that generated ref in a query (`findAll { +
  BadTable.displayLabel.eq("x") }`) — expect a real CQL-level error at query time ("column does not
  exist"), not a clean Kandra-level rejection, since codegen doesn't detect this at all per the docs.

## 4.14 Volume / scale checks

- `tombstoneWarnThreshold` (500 in file 2's config): `deleteAll()` more than 500 entities in one call
  and confirm the WARN fires at the documented message, mentioning `gc_grace_seconds`.
- Save an entity with a `Map<String,String>` containing several thousand entries via repeated
  `put()` calls, then `findById()` it back — confirm no silent size limit is hit under Kandra itself
  (ScyllaDB has its own practical limits on collection/partition size; note where those start to bite
  even if Kandra itself has no explicit guard).
- Run `findPage()` over a table with several thousand rows and confirm pagination genuinely completes
  (terminates with `hasMore = false`) rather than looping — a real end-to-end pagination walk, not
  just two pages.

## 4.15 Consistency-level combination matrix

Systematically exercise the three-level resolution order (per-call > class annotation > config
default) with values that would produce **observably different CQL** if resolved incorrectly:

| Per-call `consistency` | Class `@WriteConsistency` | Config default | Expected effective level |
|---|---|---|---|
| unset | `EACH_QUORUM` | `LOCAL_QUORUM` | `EACH_QUORUM` (class wins over config) |
| `QUORUM` | `EACH_QUORUM` | `LOCAL_QUORUM` | `QUORUM` (per-call wins over both) |
| unset | unset | `LOCAL_QUORUM` | `LOCAL_QUORUM` (falls through to config) |

Confirm via `debug.logQueries`/driver tracing which consistency level was actually attached to the
statement — don't infer it purely from absence of an error, since many of these levels behave
identically against a healthy 3-node cluster with nothing failing; use `nodetool` node-kill
experiments (4.11) layered on top of specific consistency choices to make the difference observable
(e.g. confirm a `ONE`-consistency read still succeeds with 2 of 3 nodes down, while a
`LOCAL_QUORUM`-consistency read on the same data does not).

## 4.16 Idempotency and retry-induced duplication risk

- Force a `WriteTimeoutException` mid-`INSERT` (plain, non-`IF NOT EXISTS` `save()`) by killing a
  node right as the request is in flight, in a way that causes the driver to time out rather than
  cleanly fail. Per docs, a plain `INSERT` is marked **not idempotent**, so `RetryConfig` should
  **not** retry it on this exception type (confirm — `WriteTimeoutException` IS in the default
  `retryOn` set, so check whether idempotency is actually consulted before retrying, or whether the
  retry fires anyway based purely on exception type). This is a genuinely important correctness
  question: does Kandra's retry logic actually respect `setIdempotent(false)`, or could a retried
  non-idempotent `INSERT` under a timeout produce a duplicate side effect somewhere (e.g. an
  `EVENTUAL` lookup write firing twice)?
- Repeat with `saveIfNotExists` (idempotent, `IF NOT EXISTS`) under the same failure injection —
  confirm a retry here is actually safe (the second attempt should see `wasApplied() == false` and
  correctly report "already exists" rather than incorrectly reporting success or throwing).

## 4.17 Graceful shutdown under real load

- Start a sustained stream of `save()` calls (suspend, via a background coroutine) at a moderate
  rate, then trigger application shutdown (SIGTERM to the Ktor process, or the equivalent
  test-harness shutdown call) while writes are actively in-flight.
- Confirm: `isShuttingDown` flips true, new requests during the drain window throw
  `KandraQueryException`, in-flight requests already past that check are allowed to complete, and the
  process doesn't hang past `drainTimeoutMs` even if some requests are still outstanding at that
  point (confirm the WARN log fires and the process still exits/closes the session).
- Repeat with `shutdown.graceful = false` and confirm the session closes immediately regardless of
  in-flight work — this is a real risk of dropped/failed in-flight requests, confirm that's what
  actually happens (requests failing with a connection-closed error) rather than Kandra silently
  protecting you anyway.

## 4.18 Validator conflict — last-registration-wins, confirmed

- Register **both** `validate<User> { ... rule A ... }` and `validateJakarta<User>()` (rule B, from
  the Jakarta annotations already on `User`), in that order, then in the reverse order in a second
  build.
- Craft an entity that violates rule A but not rule B, and vice versa.
- **Expected**: only the *last-registered* validator's rules are actually enforced — confirm the
  entity that violates the first-registered (now-overwritten) rule saves successfully with no
  exception, while the entity violating the last-registered rule correctly throws. This directly
  confirms (or refutes) the "backed by a Map, not a list" claim from the `kandra-ktor` skill.

---

Once every scenario above has an attempted result, move to
[file 5](05-scoring-rubric-and-reporting.md) to score everything from files 3 and 4 and produce the
final report.
