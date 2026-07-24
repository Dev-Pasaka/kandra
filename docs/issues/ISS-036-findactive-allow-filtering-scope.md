# ISS-036: `findActive()`'s `ALLOW FILTERING` was a silent default, contradicting the DSL's documented design

**Status:** Fixed

## Problem

Filed as GH #12. The separate documentation-website landing page states, as a headline claim:
"Kandra's query DSL structurally cannot express `ALLOW FILTERING` at all. The failure mode is an
exception at query-build time... not 'works fine in dev, pages someone at 3am.'" This is true for
the predicate query DSL (`repo.find { ... }` / `QueryContext`, backed by
`QueryExecutor.resolveRows()`, which throws `KandraQueryException` for anything needing
`ALLOW FILTERING` — see [ISS-021](ISS-021-allow-filtering-error-message.md)) but was **not** true
library-wide: `QueryExecutor.findActive()` / `findActiveSuspend()` build CQL directly, bypassing
the predicate DSL entirely, and literally emitted `ALLOW FILTERING` whenever the `@SoftDelete`
marker column had no `@SecondaryIndex`. Reachable from the public `KandraRepository.findActive()`
/ `KandraSuspendRepository.findActive()`. This only logged a `WARN` (`activeMarkerWarning()`) —
silent by default, exactly the "works fine in dev, pages someone at 3am" failure mode the landing
page claims Kandra structurally avoids.

## Fix

`findActive()` / `findActiveSuspend()` on both `KandraRepository` and `KandraSuspendRepository`
gained a new `allowFullScan: Boolean = false` parameter, threaded through to
`QueryExecutor.findActive()` / `findActiveSuspend()`:

- If the `@SoftDelete` marker column has `@SecondaryIndex`, the query is answered by the index —
  `WHERE <marker> = ?`, no `ALLOW FILTERING`, `allowFullScan` is irrelevant either way.
- If the marker column has **no** `@SecondaryIndex` and `allowFullScan` is left at its default
  `false`, `findActive()` now throws `KandraQueryException` at call time — matching the predicate
  DSL's own error tone (see `QueryExecutor`'s `IN`-requires-partition-key-or-`@SecondaryIndex`
  message): it explains that answering the query requires `ALLOW FILTERING`, that Kandra does not
  emit it implicitly, and that the caller should add `@SecondaryIndex` to the marker column or pass
  `allowFullScan = true`.
- If the marker column has no `@SecondaryIndex` and the caller explicitly passes
  `allowFullScan = true`, behavior is unchanged from before this fix: `WHERE <marker> = ? ALLOW
  FILTERING` plus a `WARN` log.

**Breaking change.** Existing callers of `findActive()` on an entity whose marker column has no
`@SecondaryIndex` will now get `KandraQueryException` at call time instead of a silent
`ALLOW FILTERING` scan — they must either add `@SecondaryIndex` to the marker column, or pass
`allowFullScan = true` to keep the previous behavior.

`docs/features/repositories.md`'s `findActive()` section was updated to document the new parameter
and default-throws behavior. Since the landing page itself lives in a separate repository this
repo cannot edit directly, a standing correction note was added at
`docs/site/landing-page-correction-gh-12.md` (referenced from `docs/site/README.md`) for whoever
next updates that site — it should scope the claim to "Kandra's *predicate* query DSL" and mention
`findActive()`'s new explicit `allowFullScan = true` opt-in as the one deliberate exception.

Verified against a real Testcontainers-backed cluster in `KandraIntegrationTest`:
`findActive without a secondary index throws unless allowFullScan is true`,
`findActive with allowFullScan=true succeeds and still emits ALLOW FILTERING`, and
`findActive on an entity with a secondary index succeeds without allowFullScan` (the last against a
new `IntegrationIndexedWidget` entity with `@SecondaryIndex` on its soft-delete marker column).

**Files:** `kandra-runtime/.../QueryExecutor.kt`,
`kandra-runtime/.../repository/KandraRepository.kt`,
`kandra-runtime/.../repository/KandraSuspendRepository.kt`,
`kandra-test/src/test/kotlin/io/kandra/test/KandraIntegrationTest.kt`,
`docs/features/repositories.md`, `docs/site/README.md`,
`docs/site/landing-page-correction-gh-12.md`.
