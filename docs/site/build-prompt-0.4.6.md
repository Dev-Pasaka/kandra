# Kandra Documentation Website — Update Prompt (0.4.5 → 0.4.6): `@GeneratedUuid`

**This is an incremental UPDATE prompt, not a from-scratch build prompt.** It assumes the site already
exists, built per [`build-prompt-0.4.5.md`](build-prompt-0.4.5.md)'s full scope. Do not rebuild or
re-derive anything already covered there — a fresh Claude Code session should be able to read *only*
this file, make the specific edits below, and stop. If you find yourself re-reading the whole 0.4.5
prompt or re-planning the site's information architecture, you've scoped this too broadly.

Hand this file alone to a fresh session with no prior context on this conversation.

---

## 1. What shipped since 0.4.5

One new feature, entirely inside `kandra-core` (plus one call site in `kandra-runtime`): the
`@GeneratedUuid` annotation, its `UuidStrategy` enum, and a new public `KandraUuid` utility object.
Nothing else changed. Do not touch pages unrelated to this feature.

## 2. The problem this solves — lead with this on every page you touch

Cassandra/ScyllaDB writes are upserts on partition key + clustering key — there is no separate
insert-vs-overwrite path. A clustering key (or partition key) derived from `Instant.now()` collides
silently if two rows in the same partition land in the same millisecond: the second `save()` just
overwrites the first row, no exception, no warning. This is exactly the site's existing "battle scar"
tone (see 0.4.5 prompt §2, §6.3) — a real, silent-data-loss-shaped footgun, not a hypothetical.

`@GeneratedUuid` has Kandra generate the key value itself instead of trusting a caller-supplied
timestamp:

- `TIME_ORDERED` (default) — a UUIDv7 via `KandraUuid.timeOrdered()`. UUIDv7's 48-bit millisecond
  timestamp sits in the leading bytes, so plain lexicographic comparison — exactly how Cassandra's
  `UUIDType` comparator orders a generic `uuid` column — already sorts chronologically. No dependency
  on Cassandra's separate `timeuuid` CQL type, which Kandra doesn't map to at all.
- `RANDOM` — a UUIDv4, for ids that must not leak their creation time.

Auto-populated on every INSERT the same way `@CreatedAt` already is; never touches `update()`/
`updateForce()` since primary key components are immutable in Cassandra. Validated at schema
registration like `@Version` — wrong field type throws `KandraSchemaException` immediately, not at
first insert.

## 3. Source of truth — read these, don't take this prompt's word for signatures

- `kandra-core/src/main/kotlin/io/kandra/core/KandraUuid.kt` — the utility object itself.
- `kandra-core/src/main/kotlin/io/kandra/core/annotations/Annotations.kt` — `@GeneratedUuid` and
  `UuidStrategy` definitions (search for `GeneratedUuid`).
- `kandra-core/src/main/kotlin/io/kandra/core/SchemaRegistry.kt` — the type-validation rule (must be
  `UUID`, mirrors the `@Version` check right above it in the same file).
- `kandra-runtime/src/main/kotlin/io/kandra/runtime/BatchEngine.kt` — `injectTimestamps` is where
  generation actually happens; note in the source comment/structure that it reuses the same
  `@CreatedAt`-style `copy()`-reflection mechanism rather than a new code path.
- **`docs/features/core-annotations.md`**, the `@GeneratedUuid` section — this is already
  human-reference quality (problem statement, mechanism, code example, Dokka link placeholder). Per the
  0.4.5 prompt's own sourcing rule (§4), this is the highest-value single source for the module-page
  content below — adapt and expand it, don't rewrite from scratch.
- `kandra-test/src/test/kotlin/io/kandra/test/KandraIntegrationTest.kt` — `IntegrationGeneratedUuidEvent`
  and its two `@GeneratedUuid`-named test cases are real, currently-passing, real-cluster-verified code.
  Prefer lifting the entity shape and scenario from here over inventing a new example, since this one is
  guaranteed to compile and behave as described.

## 4. Exact edits, page by page

Only these pages. Follow each existing page's established structure — don't introduce a new layout
pattern for this one feature.

- **`/modules/kandra-core`** — add `@GeneratedUuid` and `KandraUuid` entries following the existing
  per-item structure (0.4.5 prompt §6.2: signature, one-paragraph description, real compiling example,
  a `Gotcha` callout for "always overwritten on insert — don't rely on a caller-supplied value
  surviving," Dokka link). Place `@GeneratedUuid` alongside `@Version`/`@CreatedAt` (same
  "auto-populated column" family); `KandraUuid` as its own small entry near the annotation, cross-linked
  both ways.
- **`/reference`** — add curated entries for `@GeneratedUuid`, `UuidStrategy`, and `KandraUuid` (signature
  + short why/when + Dokka link, per 0.4.5 prompt §6.2 item 4 — not a duplicate of Dokka).
- **`/battle-scars`** — add one new entry (or fold into an existing clustering-key-themed page if the
  site grouped ISS-025/028/029/030 together per 0.4.5 prompt §6.3): the same-millisecond collision
  problem from §2 above, told as a real lesson. Generalizable takeaway for the closing line: *never
  derive a value that needs to be unique from wall-clock time at millisecond resolution — use a
  purpose-built id scheme (UUIDv7, a snowflake id, a database sequence) instead of a timestamp whenever
  "unique" and "sortable" both matter.* This is not tied to an `ISS-NNN` file in `docs/issues/` — it's a
  fix that shipped proactively, not a bug found after the fact; note that distinction rather than
  implying it was a discovered defect.
- **`/recipes/time-series`** (existing page, per 0.4.5 prompt's IA) — add a short section or callout:
  "for a clustering key that needs to sort by creation time, prefer `@GeneratedUuid` over a raw
  `Instant` field" — cross-link to the `/battle-scars` entry above. Do not create a new standalone
  recipe page for this; it belongs as an addition to the existing time-series recipe.
- **Everywhere else (including `/tutorial`) — leave unchanged.** The tutorial's `Todo.createdAt`
  clustering key is a reasonable candidate for a future mention of `@GeneratedUuid`, but rewriting
  tutorial pages is explicitly out of scope for this update; don't do it unless separately asked.

## 5. Code example accuracy bar

Same non-negotiable rule as the 0.4.5 prompt §6.4: every snippet must compile against current source.
The `docs/features/core-annotations.md` section and `KandraIntegrationTest.kt`'s
`IntegrationGeneratedUuidEvent` are both already-verified starting points — prefer adapting those over
writing new examples from memory.

## 6. Definition of done for this update

- `/modules/kandra-core` has `@GeneratedUuid` and `KandraUuid` entries.
- `/reference` has entries for `@GeneratedUuid`, `UuidStrategy`, `KandraUuid`, each linking to Dokka.
- One `/battle-scars` entry (new or folded in) covers the same-millisecond collision problem and its
  generalizable lesson.
- `/recipes/time-series` cross-links `@GeneratedUuid` as the recommended default for time-ordered
  clustering keys.
- No other page changed.
- Every new code example verified to compile against current source, not copied uncritically from this
  prompt.
