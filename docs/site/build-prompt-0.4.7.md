# Kandra Documentation Website — Update Prompt (0.4.6 → 0.4.7)

**This is an incremental UPDATE prompt, not a from-scratch build prompt.** It assumes the site already
exists, built per [`build-prompt-0.4.5.md`](build-prompt-0.4.5.md)'s full scope and updated per
[`build-prompt-0.4.6.md`](build-prompt-0.4.6.md). Do not rebuild or re-derive anything already covered
there — a fresh Claude Code session should be able to read *only* this file, make the specific edits
below, and stop.

Hand this file alone to a fresh session with no prior context on this conversation. This file
accumulates one dated section per fix/feature merged into `main` since 0.4.6, in merge order. Read
every section below — later sections don't repeat context from earlier ones in this same file.

---

## PR #19 — `update()`/`updateSuspend()` on `@Version` entities no longer auto-retry (behavior change)

### 1. What shipped

`BatchEngine.executeWithRetry`/`executeWithRetrySuspend` used to retry the entire `UPDATE ... IF
version = ?` LWT statement on any transient exception covered by the default `RetryConfig`
(`WriteTimeoutException`, `ReadTimeoutException`, `NoNodeAvailableException`). That's unsafe for a
conditional statement: the server may have already applied the write and advanced the version before
the client observed the error, so the retry then sees `[applied] = false` and throws a **spurious**
`KandraOptimisticLockException` for a write that actually succeeded — reporting "someone else changed
this row" when the truth is "your own prior attempt already succeeded."

`update()`/`updateSuspend()` on a `@Version`-annotated entity now execute the LWT statement **exactly
once**, via new `executeOnce`/`executeOnceSuspend` helpers. A transient exception now propagates to the
caller unmodified instead of being silently retried; a genuine `[applied] = false` still correctly
throws `KandraOptimisticLockException`. Every other write path (`save`, `saveAll`, `delete`,
non-versioned `update`, collection/counter ops) is unaffected and still retries per `RetryConfig`.

This is a **breaking behavior change** for any caller relying on Kandra to silently retry a versioned
`update()` through a transient network blip — they must now catch the transient exception themselves,
re-fetch the entity's current version, and reissue `update(freshOld, new)`.

### 2. Source of truth — read these, don't take this prompt's word for it

- `docs/USER_GUIDE.md` — search for "no automatic retry on transient errors" (around the `@Version`
  section, currently ~line 345). This is the canonical, already-human-reference-quality explanation
  including the correct re-fetch-and-retry recipe. Adapt this content directly rather than rewriting.
- `docs/features/core-annotations.md` — search for "exactly once" in the `@Version` section — a shorter
  version of the same explanation, good source for a page-summary blurb.
- `kandra-runtime/src/main/kotlin/io/kandra/runtime/BatchEngine.kt` — `executeOnce`/`executeOnceSuspend`
  and their call sites in the versioned-update path.
- `docs/issues/ISS-032-versioned-update-spurious-optimistic-lock.md` — the original bug writeup, useful
  battle-scar material (concrete before/after scenario).
- `kandra-runtime/src/test/kotlin/io/kandra/runtime/BatchEngineVersionedUpdateTest.kt` — real, passing
  test scenarios (scripted transient error propagates unmodified; genuine `[applied]=false` still
  throws) if a code example needs a guaranteed-accurate scenario to lift from.

### 3. Exact edits, page by page

- **Whichever page currently documents `@Version`/optimistic locking** (per 0.4.5 prompt's IA — likely
  under `/modules/kandra-core` or a dedicated concurrency/optimistic-locking recipe page): add the "no
  automatic retry" note verbatim-adapted from `docs/USER_GUIDE.md`, including the re-fetch-and-retry
  code recipe. This is the single most important edit in this update — silently changed retry semantics
  is exactly the kind of footgun this site's "battle scar" pages exist to warn about.
- **`/battle-scars`** — add one new entry for ISS-032: spurious `KandraOptimisticLockException` from
  blindly retrying a conditional write. Generalizable takeaway: *never blindly retry a conditional
  (LWT/CAS-style) write on a transient error without first re-reading current state — the write may
  have already landed server-side even though the client never saw the ack.*
- **Any page/table listing `RetryConfig` behavior per operation** (if the site has one, per 0.4.5
  prompt's reference/config docs) — note the `@Version` `update()`/`updateSuspend()` exception to the
  default retry-on-timeout behavior.
- **Everywhere else — leave unchanged.**

### 4. Definition of done for this section

- The optimistic-locking page states plainly that versioned `update()` never auto-retries, why, and
  shows the correct manual re-fetch-and-retry pattern.
- One `/battle-scars` entry covers ISS-032.
- Any per-operation retry-behavior reference table reflects the exception.
- No other page changed for this section.

---

## PR #20 — `EVENTUAL` lookup writes now share retry/`inFlightCount`/shutdown-gate safeguards

### 1. What shipped

`LookupConsistency.EVENTUAL` lookup writes (from `save`/`update`/`saveAll`, fired asynchronously via
`scope.launch` after the primary batch commits) used to call `session.execute(...)`/`executeSuspend(...)`
directly, bypassing `executeWithRetry()`/`executeWithRetrySuspend()`. Practical effect: an `EVENTUAL`
lookup write got no retry-on-transient-error, wasn't counted in `inFlightCount` (so graceful shutdown
didn't wait for it), and wasn't rejected once `isShuttingDown` was set — a `save()`/`update()` racing
`ApplicationStopping` could fire a query against an already-closed `CqlSession`.

Now routed through the same `executeWithRetry()`/`executeWithRetrySuspend()` path as every other write:
`EVENTUAL` writes retry per `RetryConfig`, are tracked in `inFlightCount` so the graceful-shutdown drain
waits for them, and are rejected with `KandraQueryException` (never reaching the driver) once shutdown
has started. This is a reliability fix, not an API or behavior contract change — no caller-visible
signature changed, `EVENTUAL` still means "fire-and-forget from the caller's perspective," it's just now
provably safe under shutdown and transient failures the way `BATCH` lookups already were.

### 2. Source of truth

- `docs/USER_GUIDE.md` and `docs/features/operations.md` — both already updated with the "EVENTUAL
  writes share the same safeguards" explanation (search for `inFlightCount` in each). Adapt directly.
- `docs/features/core-annotations.md` — the `@LookupIndex`/`LookupConsistency.EVENTUAL` section has the
  matching short-form note.
- `docs/issues/ISS-033-eventual-lookup-bypasses-safeguards.md` — original bug writeup.
- `kandra-runtime/src/main/kotlin/io/kandra/runtime/BatchEngine.kt` — `fireEventual`/`fireEventualSuspend`/
  `fireEventualStatements`.

### 3. Exact edits, page by page

- **Whichever page documents `@LookupIndex`/`LookupConsistency`** (per 0.4.5 prompt's IA): update the
  description of `EVENTUAL` to state it shares retry, `inFlightCount`, and shutdown-gate behavior with
  every other write — pull the exact wording from `docs/USER_GUIDE.md`'s `LookupConsistency.EVENTUAL`
  section, it's already precise.
- **Whichever page documents graceful shutdown** (per 0.4.5 prompt's IA, likely under `/modules/kandra-ktor`
  or an operations/production-readiness page): note that the shutdown drain now correctly covers
  in-flight `EVENTUAL` lookup writes, not just the primary batch.
- **`/battle-scars`** — optional, lower priority than the ISS-032 entry above: a short entry on
  ISS-033 if the site is tracking every real production-shaped bug found this way; skip if the page is
  getting crowded, this one is less generally-applicable than ISS-032's lesson.
- **Everywhere else — leave unchanged.**

### 4. Definition of done for this section

- The `LookupConsistency`/`@LookupIndex` page states `EVENTUAL` writes retry, count toward
  `inFlightCount`, and are rejected (not silently attempted) once shutdown begins.
- The graceful-shutdown page/section reflects that the drain covers `EVENTUAL` writes.
- No other page changed for this section.

---

## PR #22 — `@LookupIndex` + `@SoftDelete` storage-growth is now documented (docs-only, no code change)

### 1. What shipped

Since the ISS-030 fix (0.4.4), soft-deleting an entity deliberately leaves its `@LookupIndex` row(s)
alone until the entity's own soft-delete TTL expires — correct behavior, since a soft-deleted row
still "exists" (queryable, non-key columns not yet expired) and must remain resolvable via its
`@LookupIndex`, same as `findById` still finding it. The undocumented consequence: for high-churn
tables combining both annotations, the lookup table ends up holding significantly more live rows than
the primary table at any given time, since the primary table's non-key columns TTL/tombstone quickly
on soft-delete but the lookup row survives until the *entity's* full soft-delete TTL expires. This is
a real, non-obvious storage-cost implication — not a bug, not a behavior change, purely a
documentation gap. `docs/features/core-annotations.md`'s `@SoftDelete` section previously said "Lookup
rows are hard-deleted," which directly contradicted the actual (correct) ISS-030 behavior.

### 2. Source of truth

- `docs/features/core-annotations.md` — corrected `@SoftDelete` section plus new "Storage cost with
  `@LookupIndex`" notes in both the `@SoftDelete` and `@LookupTable` sections, cross-linked. Adapt
  directly.
- `docs/issues/ISS-035-lookupindex-softdelete-storage-growth.md` — original writeup.
- `docs/issues/ISS-030-soft-delete-removes-lookup-rows.md` — the underlying behavior this documents.

### 3. Exact edits, page by page

- **Whichever page documents `@SoftDelete`** (per 0.4.5 prompt's IA): if the site's copy says or
  implies lookup rows are removed on soft-delete, correct it — they are deliberately kept until the
  entity's own TTL expires.
- **Whichever page documents `@LookupIndex`/`@LookupTable`**: add a short storage-cost callout —
  combining `@LookupIndex` + `@SoftDelete` on the same entity means the lookup table will hold more
  live rows than the primary table on high-churn data, and this is expected.
- **`/battle-scars`** — optional: this is a documentation gap, not a discovered bug, so it's a weaker
  fit than ISS-032/ISS-033's entries. Skip unless the site has a lower-severity "gotchas/considerations"
  category distinct from battle-scars.
- **Everywhere else — leave unchanged.**

### 4. Definition of done for this section

- The `@SoftDelete` page no longer claims lookup rows are hard-deleted.
- The `@LookupIndex`/`@LookupTable` page notes the storage-growth interaction with `@SoftDelete`.
- No other page changed for this section.

---

## PR #23 — `findActive()`'s `ALLOW FILTERING` is now an explicit opt-in (⚠️ BREAKING CHANGE)

### 1. What shipped

`findActive()`/`findActiveSuspend()` (for `@SoftDelete(markerProperty = "...")` entities) build CQL
directly and, when the marker column has no `@SecondaryIndex`, used to silently emit
`ALLOW FILTERING` (just a `WARN` log). This contradicted the site's own landing-page claim that
Kandra's query layer structurally cannot express `ALLOW FILTERING` — that claim is true for the
*predicate* DSL (`repo.find { ... }`) but was never true for `findActive()`, which bypasses the DSL
entirely.

`findActive(allowFullScan: Boolean = false)` now:
- Queries the marker column's `@SecondaryIndex` directly if present — no `ALLOW FILTERING`,
  `allowFullScan` irrelevant.
- **Throws `KandraQueryException` at call time** if there's no `@SecondaryIndex` and `allowFullScan`
  is left at its default `false` — instead of silently scanning.
- Runs the old `ALLOW FILTERING` + WARN behavior only if the caller explicitly passes
  `allowFullScan = true`.

**This breaks existing callers**: any `findActive()` call on an entity whose marker column has no
`@SecondaryIndex` now throws instead of silently succeeding. Callers must add `@SecondaryIndex` to
the marker column or pass `allowFullScan = true`.

### 2. Source of truth

- **[`landing-page-correction-gh-12.md`](landing-page-correction-gh-12.md)** — a self-contained,
  ready-to-hand-off correction note for the landing page's `ALLOW FILTERING` claim specifically,
  written by the PR itself. Use it verbatim for the landing-page edit; don't re-derive that wording.
- `docs/features/repositories.md` — the `findActive()` section (search "GH #12") has the full,
  already-accurate behavior breakdown (indexed vs. non-indexed, default vs. `allowFullScan = true`).
  Adapt directly for any module/API-reference page.
- `docs/issues/ISS-036-findactive-allow-filtering-scope.md` — original writeup, good battle-scar
  material: "a library that markets 'no silent ALLOW FILTERING' had exactly one silent ALLOW
  FILTERING path, in the one method that bypasses the DSL enforcing that guarantee."
- `docs/USER_GUIDE.md` — `@SoftDelete` section (search "allowFullScan") now has the corrected,
  accurate explanation including the lookup-row-retention note from PR #22.

### 3. Exact edits, page by page

- **Landing page** — apply the correction from `landing-page-correction-gh-12.md` exactly as written
  there. This is the highest-priority edit in this whole 0.4.7 batch — it's a live, publicly-visible
  inaccuracy on the marketing page, not just an internal reference page.
- **Whichever page documents `findActive()`/`@SoftDelete`** (per 0.4.5 prompt's IA, likely
  `/modules/kandra-runtime` repository API section): update the signature to
  `findActive(allowFullScan: Boolean = false)`, document the throws-by-default behavior, and mark it
  clearly as a breaking change versus prior site content (if the site documented the old silent
  behavior anywhere).
- **`/battle-scars`** — add an entry for ISS-036. This one has an unusually sharp narrative hook (see
  ISS-036 source above) — worth a real entry, higher priority than ISS-035's.
- **Any "breaking changes" / migration-notes page** (if the site has one, per 0.4.5 prompt's IA) — add
  an entry: upgrading past this fix requires either adding `@SecondaryIndex` to soft-delete marker
  columns or passing `allowFullScan = true` at every `findActive()` call site, or those calls will
  start throwing.
- **Everywhere else — leave unchanged.**

### 4. Definition of done for this section

- Landing page's `ALLOW FILTERING` claim is scoped to the predicate DSL, per
  `landing-page-correction-gh-12.md`.
- The `findActive()` reference shows the new signature and throws-by-default behavior, called out as
  breaking.
- One `/battle-scars` entry covers ISS-036.
- Any migration/breaking-changes page lists this fix with the upgrade path.
- No other page changed for this section.

---

## PR #24 — Consistency Strict Mode: warn on `LOCAL_ONE`/`ONE` in multi-DC deployments (new opt-in feature)

### 1. What shipped

New opt-in `consistency { strictMode = true }` on `ConsistencyConfig` (default `false`, backward
compatible, WARN-only — never throws). When `strictMode` is on **and** the deployment is multi-DC
(auto-derived by the `Kandra` Ktor plugin from `loadBalancing.allowedRemoteDcs.isNotEmpty()` — not a
separate flag the user sets), every query whose resolved consistency level is `LOCAL_ONE` or `ONE`
logs a WARN. Rationale: `LOCAL_ONE`/`ONE` are easy defaults that silently read/write against a single
node's local view — fine for single-DC, but in a multi-DC deployment that single node might not have
seen the latest write from another DC yet, so a single-replica read can return stale data without any
error. This is a common footgun for teams that provision multi-DC infrastructure and never revisit
their consistency-level defaults. The warning is unconditional (no "warn once" dedup) and never
changes query behavior — purely observability.

### 2. Source of truth

- `docs/features/multidc.md` — "Strict Mode" section (search "GH #5"), already accurate and complete:
  config example, exactly when it fires, and the reasoning. Adapt directly.
- `docs/USER_GUIDE.md` — has a shorter version of the same, cross-referenced.
- `.claude/skills/kandra-multidc/SKILL.md` and `.claude/skills/kandra-runtime/SKILL.md` — both updated
  with the config surface and warning-trigger details.
- `docs/issues/ISS-037-consistency-strict-mode.md` — original writeup.
- `kandra-runtime/src/main/kotlin/io/kandra/runtime/ConsistencyConfig.kt` — `strictMode` and the
  `@InternalKandraApi multiDcTopology` field (plugin-derived, not user-set).
- `kandra-runtime/src/main/kotlin/io/kandra/runtime/StatementBuilder.kt` — `warnIfStrictModeViolation`
  for the exact trigger condition.

### 3. Exact edits, page by page

- **Whichever page documents multi-DC configuration** (per 0.4.5 prompt's IA, likely
  `/modules/kandra-multidc` or a dedicated multi-DC guide page): add a "Strict Mode" subsection —
  config snippet, what triggers the warning, and the reasoning (stale-read risk from `LOCAL_ONE`/`ONE`
  in a multi-DC topology). Note it's observability-only, never throws, and `multiDcTopology` is
  auto-derived, not a separate setting.
- **Whichever page documents consistency-level configuration generally** (per 0.4.5 prompt's IA,
  likely under `/modules/kandra-runtime` or a config reference page): add `strictMode` to the
  `ConsistencyConfig` field list.
- **Everywhere else — leave unchanged.** This is a small, self-contained opt-in feature; don't expand
  scope into a general "choosing consistency levels" tutorial unless one already exists to extend.

### 4. Definition of done for this section

- The multi-DC page documents `strictMode`, its config surface, and exactly when it warns.
- The consistency-config reference (if the site has one) lists `strictMode` as a field.
- No other page changed for this section.

---

## PR #25 — `kandra-codegen` generates typed Koin/Kodein DI accessors (new opt-in feature, this is the last PR in the 0.4.7 batch)

### 1. What shipped

Both `kandra-koin` and `kandra-kodein` bind repositories under **star-projected** types
(`KandraRepository<*>`) with string-based qualifiers (`named("FooRepo")` / `tag = "Foo"`) — JVM type
erasure means neither DI framework can recover the entity type parameter, so every hand-written lookup
site needed a hand-typed qualifier string plus an unchecked cast to `KandraRepository<Foo>`. A typo in
the qualifier string, or an entity rename that isn't caught everywhere, fails at runtime
(`NoDefinitionFoundException`/`DI.NotFoundException` on first resolution), not at compile time.

`kandra-codegen`'s KSP processor now conditionally generates, per `@ScyllaTable` entity: `fooRepo()`/
`fooSuspendRepo()` extension functions on `KoinComponent` (if `koin-core` is on the compiling module's
classpath) and on `DIAware` (if `kodein-di` is) — each wrapping the exact `named()`/`tag` lookup
`kandraKoin()`/`kandraKodein()` already bind under, with the cast done once inside the generated
function. Detection is a KSP classpath probe, not a compile dependency of `kandra-codegen` itself;
absence of either DI framework on the classpath is silent (the common case for most modules).

### 2. Source of truth

- `docs/features/di-integrations.md` — "Typed accessors (since 0.4.7)" section, already accurate and
  complete: generated function signatures, detection mechanism, and the concrete win over hand-typed
  lookups. Adapt directly.
- `.claude/skills/kandra-codegen/SKILL.md` — "Typed Koin/Kodein DI accessors" section has the exact
  detection/generation mechanics (KSP `Resolver.getClassDeclarationByName` probe) in more depth than
  the features doc.
- `.claude/skills/kandra-koin/SKILL.md` / `.claude/skills/kandra-kodein/SKILL.md` — each updated with
  usage from that framework's side.
- `docs/issues/ISS-038-typed-di-codegen-accessors.md` — original writeup.
- `kandra-codegen/src/main/kotlin/io/kandra/codegen/KandraProcessor.kt` — the generation logic itself,
  if a page needs to show real generated output for a specific entity shape.

### 3. Exact edits, page by page

- **`/modules/kandra-codegen`** (per 0.4.5 prompt's IA): add a "Typed DI accessors" subsection —
  what's generated, the classpath-probe detection mechanism (opt-in by presence, not a flag), and the
  exact generated signatures for both frameworks.
- **`/modules/kandra-koin`** and **`/modules/kandra-kodein`**: each gets a short note that
  `kandra-codegen` (if present on the classpath) generates `fooRepo()`/`fooSuspendRepo()` accessors as
  the preferred alternative to the hand-typed `named()`/`tag` lookups those pages otherwise document —
  cross-link to `/modules/kandra-codegen`'s fuller treatment rather than duplicating it.
- **Everywhere else — leave unchanged.**

### 4. Definition of done for this section

- `/modules/kandra-codegen` documents the typed DI accessor generation, detection mechanism, and
  signatures for both frameworks.
- `/modules/kandra-koin` and `/modules/kandra-kodein` each note the generated accessors as the
  preferred alternative, cross-linked to the fuller `kandra-codegen` treatment.
- No other page changed for this section.

---

## Batch complete

This file now covers every PR merged into `main` between 0.4.6 and this point (#18–#25). Once this
prompt has been executed against the live site, this file's job is done — start a fresh
`build-prompt-<next-version>.md` for whatever ships next, per the process in
[`docs/site/README.md`](README.md#adding-a-prompt-for-a-new-kandra-release). Don't keep appending to
this file after it's been handed off and acted on.
