# Documentation Website — Build Prompts

The Kandra documentation website is a **separate project** (its own TypeScript/React codebase, deployed
independently) — this folder does not contain the site itself. It holds the self-contained prompt(s)
used to hand a fresh Claude Code session (or any engineer) everything needed to build or update that
site end to end, without any prior context from this repo's conversation history.

One file per Kandra version the prompt was authored against — newest first — the same convention as
[`docs/changelog/`](../changelog/README.md) and [`docs/history/`](../history/). The site's source
material (module list, annotation set, DSL syntax, skill docs) changes release to release, so a prompt
written against an older version can go stale in ways that are easy to miss if it's edited in place.
Keeping one file per version makes that drift visible instead of silent.

| Version | Kind | File |
|---|---|---|
| 0.4.6 | Incremental update | [build-prompt-0.4.6.md](build-prompt-0.4.6.md) — adds `@GeneratedUuid`/`UuidStrategy`/`KandraUuid` coverage to specific pages only |
| 0.4.5 | Full build | [build-prompt-0.4.5.md](build-prompt-0.4.5.md) — initial build prompt, full site scope, information architecture, and content requirements |

## Full build vs. incremental update

- **Full build** — for when the site doesn't exist yet, or is being substantially reworked. Covers
  scope, information architecture, and every page from zero (see `build-prompt-0.4.5.md`'s §1–§11).
- **Incremental update** — for when the site already exists and one release added a small, well-scoped
  feature. States only what's new, exactly which existing pages to touch, and explicitly tells the
  agent not to re-derive or rebuild anything already covered by the last full build (see
  `build-prompt-0.4.6.md`). Cheaper to write and cheaper for a fresh Claude Code session to execute
  correctly than handing over the entire accumulated build prompt again.

## Adding a prompt for a new Kandra release

Don't edit an existing `build-prompt-X.Y.Z.md` in place once it's been used. Instead:

1. Decide full build or incremental update (almost always the latter, once a full build exists — reach
   for a full build again only after a rework big enough that page-by-page deltas stop making sense).
2. For an incremental update: write a new, self-contained `build-prompt-<new-version>.md` stating what
   changed since the last release, the source files to verify it against, and the exact pages to touch
   — modeled on `build-prompt-0.4.6.md`'s structure (§1–§6). Don't copy the full prior prompt into it.
   For a full build: copy the latest full-build file, re-verify every claim in it against current source
   (annotation set, method signatures, DSL syntax, module list, `docs/issues/`), and correct anything
   stale — treat the previous prompt as a draft, not a source of truth.
3. Add a row to the table above, noting its kind.

Each prompt is the authority on how to hand itself off and what "done" looks like for its own scope —
see its own definition-of-done section rather than duplicating that here.

## Outstanding corrections to the live site

Unlike the build/update prompts above, this is a standing note (not tied to a specific version)
about a claim already live on the **landing page** that needs correcting next time someone touches
it — see [`landing-page-correction-gh-12.md`](landing-page-correction-gh-12.md). Short version: the
landing page's "Kandra's query DSL structurally cannot express `ALLOW FILTERING`" claim needs
scoping to the *predicate* DSL specifically — `findActive()` is a deliberate, now-explicit-opt-in
exception (`allowFullScan = true`, GH #12 / ISS-036), not a DSL loophole. Remove this section once
the correction has been applied.
