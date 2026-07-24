# Landing Page Correction — scope the `ALLOW FILTERING` claim (GH #12)

**This is not a build or update prompt for the doc site's content pages.** It's a standing
correction note for one specific claim on the **landing page** (the marketing/hero page, not a
`docs/features/*.md`-sourced page) — hand it to whoever next touches the landing page copy,
alongside whatever build/update prompt they're already working from.

## The claim as currently written

The landing page states, as an unscoped headline claim:

> Kandra's query DSL structurally cannot express `ALLOW FILTERING` at all. The failure mode is an
> exception at query-build time... not "works fine in dev, pages someone at 3am."

## Why this needs correcting

This is true for the **predicate query DSL** — `repo.find { ... }` / `findAll { ... }` /
`QueryContext`, backed by `QueryExecutor.resolveRows()` — which has never had, and still does not
have, any way to emit `ALLOW FILTERING`; every predicate must resolve through a partition key, a
single-column partition-key `IN`, a `@LookupIndex` column, or a `@SecondaryIndex` column, or it
throws `KandraQueryException` at query-build time. That part of the claim is accurate and should
stay.

It is **not** true library-wide. `KandraRepository.findActive()` / `KandraSuspendRepository
.findActive()` — used for any entity with `@SoftDelete(markerProperty = ...)` — build CQL directly,
bypassing the predicate DSL entirely, and (when the marker column has no `@SecondaryIndex`) do
still literally emit `ALLOW FILTERING`. Before GH #12 this was a silent default (just a `WARN` log)
— exactly the "works fine in dev, pages someone at 3am" failure mode the landing page claims
Kandra structurally avoids. GH #12 fixed the silent-default part (see
[`docs/issues/ISS-036-findactive-allow-filtering-scope.md`](../issues/ISS-036-findactive-allow-filtering-scope.md)
and [`docs/features/repositories.md`](../features/repositories.md#findactive-softdelete-entities-only)),
but the `ALLOW FILTERING` escape hatch itself is intentional and remains — it's now explicit and
opt-in rather than silent, not eliminated.

## The correction to make

Reword the landing page claim to scope it to the predicate DSL specifically, roughly:

> Kandra's *predicate query DSL* (`repo.find { ... }` / `findAll { ... }`) structurally cannot
> express `ALLOW FILTERING` at all — every predicate must resolve through a partition key,
> `@LookupIndex`, or `@SecondaryIndex`, or the query throws at build time.

And add a follow-on sentence (or a footnote/aside, matching whatever the site's existing tone
device is for caveats) covering the one deliberate exception:

> The one exception is `findActive()` (for `@SoftDelete` entities without a `@SecondaryIndex` on
> the marker column), which requires an explicit `allowFullScan = true` opt-in — never a silent
> default — to run with `ALLOW FILTERING`.

Do not weaken the DSL claim itself — it remains true and is a real differentiator. The fix here is
scope, not retraction.
