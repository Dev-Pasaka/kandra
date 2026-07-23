# ISS-032: `@Version` LWT updates were blindly retried on transient errors, causing spurious `KandraOptimisticLockException`

**Status:** Fixed

## Problem

`BatchEngine.executeWithRetry()`/`executeWithRetrySuspend()` retry the *entire* statement on any
exception matching `RetryConfig.retryOn` (default: `WriteTimeoutException`, `ReadTimeoutException`,
`NoNodeAvailableException`), with no distinction between statement kinds. This applied uniformly to
plain idempotent `INSERT`s **and** to the `@Version` LWT path (`buildVersionedUpdateStatement`/
`buildVersionedUpdateStatementSuspend`, `UPDATE ... IF version = ?`), which is not idempotent in the
same sense.

Failure scenario: `update(old, new)` on a `@Version`-annotated entity sends
`UPDATE ... SET ... WHERE pk=? IF version = ?`. The server applies it and increments the version, but
the client only observes a timeout/transient error (matching the *default* `RetryConfig` — not an
opt-in setting) before the response arrives. `executeWithRetry` then retried the *same* LWT statement
with the *same* `oldVersion` bind value. Since the row's version had already advanced server-side, the
retry got `[applied] = false`, and `throwOptimisticLockException()` fired — reporting a "concurrent
modification" to a caller whose write had, in fact, succeeded on the first attempt.

This was correctness-adjacent: the README's flagship `@Version` example is a `balances` table, so this
sat directly under the kind of write that most needs to be trustworthy.

## Fix

The versioned-update statement is now executed exactly once — it never goes through
`executeWithRetry`/`executeWithRetrySuspend`. Two new methods, `executeOnce`/`executeOnceSuspend`,
provide the same `inFlightCount` tracking and `checkNotShuttingDown()` gating as the retry-wrapped
methods (so the versioned-update path still participates in graceful shutdown draining and in-flight
counting), but skip the catch-matching-exception/sleep/retry loop entirely. A transient exception now
propagates to the caller as-is instead of being silently retried into a false conflict, and a genuine
`[applied] = false` result (no exception) still correctly throws `KandraOptimisticLockException`.

This is a **behavior change**: versioned updates (`update()`/`updateSuspend()` on a `@Version` entity)
no longer auto-retry on transient errors under any `RetryConfig`. Callers that want retry-on-timeout
semantics for a versioned update must catch the transient exception themselves, re-fetch the entity's
current version, and reissue `update(old, new)` — only the caller can tell "my own prior attempt
already applied" apart from "someone else changed it." This is now documented in
`docs/features/core-annotations.md` and `docs/USER_GUIDE.md` alongside the `@Version` annotation.

A regression test (`BatchEngineVersionedUpdateTest`, `kandra-runtime/src/test/`) reproduces the exact
scenario — a scripted `WriteTimeoutException` followed by a scripted `[applied] = false` — and asserts
the original `WriteTimeoutException` surfaces (not `KandraOptimisticLockException`), that the driver is
invoked exactly once, and that a genuine `[applied] = false` (no exception) still throws
`KandraOptimisticLockException` correctly. It also asserts `inFlightCount`/shutdown gating still apply
to the versioned-update path.

**Files:** `kandra-runtime/.../BatchEngine.kt`, `docs/features/core-annotations.md`,
`docs/USER_GUIDE.md`, `.claude/skills/kandra-runtime/SKILL.md`,
`kandra-runtime/src/test/kotlin/io/kandra/runtime/BatchEngineVersionedUpdateTest.kt`.
