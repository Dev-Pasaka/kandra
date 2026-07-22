# ISS-027: `KandraBatchScope`'s `saveIfNotExists` guard was also unreachable

**Status:** Fixed, verified live

## Problem

Discovered while fixing [ISS-026](ISS-026-batch-scope-save-unreachable.md) — not part of the original
`docs/report:1.0/` findings, but the identical root cause. `KandraBatchScope` declared a `saveIfNotExists`
member-extension whose only job is to throw `KandraQueryException` (LWT can't share a `LOGGED BATCH`
with regular statements). Same-named as `KandraSuspendRepository`'s real `saveIfNotExists` member, it
was subject to the exact same member-over-extension resolution rule as ISS-026 — meaning the guard
could never actually fire. Calling `repo.saveIfNotExists(entity)` inside a batch block silently executed
a real, immediate LWT write instead of throwing the intended guard exception, with no warning that the
protection was never active.

## Fix

Renamed to `saveIfNotExistsInBatch`, for the same reason as ISS-026's rename — a distinct name makes
the extension the only possible resolution target.

**File:** `kandra-runtime/.../KandraBatchScope.kt`.

**Verification:** live — `saveIfNotExistsInBatch` inside `batch { }` now throws `KandraQueryException`
as documented.
