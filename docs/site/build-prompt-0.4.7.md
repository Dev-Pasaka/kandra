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
