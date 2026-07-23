# Kandra Documentation Website — Build Prompt (targets Kandra v0.4.5)

**Written against Kandra v0.4.5.** Module list, annotation set, and API shapes below reflect that
release. If the Kandra repo has moved past v0.4.5 by the time this is used, treat this file as a
starting draft, not gospel — verify every claim against current source per §4 and §9, and prefer
writing a new `docs/site/build-prompt-X.Y.Z.md` for the target version over silently editing this one
in place. See [`docs/site/README.md`](README.md) for the versioning convention this folder follows.

This file is a self-contained prompt for a Claude Code agent (or any engineer) with no prior context
on this conversation. Hand this file to a fresh session in a **new, separate repository** (not this
one) and it should have everything needed to build the site end to end. Copy/paste the whole thing as
the initial instruction, or point Claude Code at this file path and say "build this."

---

## 1. Mission

Build **the** documentation website for Kandra — a Kotlin-first ORM for ScyllaDB/Cassandra shipped as
a Ktor plugin (source at `https://github.com/Dev-Pasaka/kandra`, Apache 2.0). Not a marketing page,
not a thin wrapper around auto-generated API docs — a genuine one-stop-shop that a developer with zero
prior Cassandra/ScyllaDB experience can land on, understand *why* the library is shaped the way it is,
and leave knowing how to build a real, correct, production-grade application against a real cluster.

This is a **separate project** from the Kandra library itself. It consumes Kandra's source and docs as
raw material but is its own TypeScript/React codebase, deployed independently.

## 2. The problem this site exists to solve — lead with this, don't bury it

**Every page on this site should be written by someone who has personally felt the pain of building on
a distributed database and is done pretending it's "just SQL with extra steps."** Before writing any
module reference or API page, the site needs a prominent, early section — ideally the second thing a
visitor sees after the hero, not scroll-seven-screens-down — that names the actual pain points
developers hit when building on Cassandra/ScyllaDB without a library like Kandra, and shows,
concretely, how Kandra addresses each one head-on. Source this from Kandra's own README background
section and the `docs/issues/ISS-*.md` files (every one of those is a real bug found and fixed against
a real cluster — genuine production lessons, not hypotheticals), but go further than Kandra's own docs
currently do — this site's job is to make the *problem* visceral before it makes the *solution* look
clever. Concretely, cover at minimum:

- **"Where did my lookup table go out of sync?"** — Cassandra has no joins and no foreign keys, so any
  "find X by Y" query that isn't servable by partition key needs a denormalized lookup table, kept in
  sync by hand in every write path. This is the single most common source of silent data-integrity bugs
  in Cassandra apps written without Kandra — a `save()` that updates the primary table but forgets (or
  fails halfway through) updating the lookup table leaves a permanently invisible-by-email user, and
  nothing about the code *looks* wrong. Kandra's `@LookupIndex` makes this an annotation, not a manual
  discipline.
- **"Why is this DELETE eating my whole partition?"** — a relational developer's muscle memory for
  "delete by ID" doesn't account for clustering keys; a naive `DELETE WHERE partition_key = ?` on a
  clustering-keyed table deletes every row in that partition, not the one row intended. This is a real
  data-loss class of bug (Kandra's own `ISS-025` — see `docs/issues/ISS-025-clustering-key-where-clause-omitted.md`
  in the Kandra repo — is a first-party example of exactly this mistake, caught and fixed).
- **"My ORM says it's atomic, but is it really?"** — Cassandra's `LOGGED BATCH` is the only atomicity
  primitive across tables, and it's easy to write code that *looks* transactional (multiple `save()`
  calls in sequence) while actually executing as N independent, non-atomic writes. A partial failure
  between them leaves your data in an inconsistent, hard-to-detect state. Kandra's `KandraBatchScope`
  exists specifically for this, and its own naming (`saveInBatch`, not `save`) is a direct scar from
  almost shipping an API that silently didn't provide the atomicity it promised (`ISS-026`/`ISS-027`).
- **"Why does this query need `ALLOW FILTERING`, and why is everyone telling me not to use it?"** —
  Cassandra will *let* you write a query that requires a full-cluster scatter-gather scan, and it will
  degrade a production cluster badly under load. Most ORMs don't stop you. Kandra's query DSL
  structurally cannot express `ALLOW FILTERING` at all — the failure mode is "this doesn't compile /
  throws with a clear message pointing at `@SecondaryIndex`," not "this works fine in dev and pages
  someone at 3am in prod."
- **"Tombstones are eating my cluster and nobody told me."** — naive `DELETE`-heavy workloads generate
  tombstones that degrade read performance over `gc_grace_seconds` (10 days by default) until
  compaction catches up; most relational-background developers have never had to think about this at
  all. Kandra's `@SoftDelete` (TTL-based, not naive DELETE) and `deleteAll()`'s explicit tombstone-count
  WARN log exist because this is a real, easy-to-hit footgun, not a theoretical one.
- **"I need compare-and-set semantics, but LWT is expensive — how do I *not* pay that cost everywhere?"**
  — Cassandra's Lightweight Transactions (Paxos-based) are real and useful for optimistic locking /
  `INSERT ... IF NOT EXISTS`, but they carry a genuine latency cost most relational developers have
  never had to reason about, since standard SQL transactions don't ask you to. Kandra makes the *cheap*
  path (plain writes) the default and the *expensive* path (`@Version`, `saveIfNotExists`) something
  you opt into deliberately and can see in the entity definition — the cost is visible, not hidden
  behind a `@Transactional` annotation that quietly does something very different depending on the
  isolation level.
- **"Why does my read work in dev but throw in production?"** — partition-scoped design (or its
  absence) is invisible in a small dev dataset and catastrophic at scale; Kandra's schema validation
  runs at *startup*, not first-request, specifically so a query that can't be served without
  `ALLOW FILTERING` or a full scan is caught before deploy, not discovered under load.

Frame all of this explicitly against the relational/Hibernate mental model, and say so directly: this
site should **not** teach Cassandra/ScyllaDB by analogy to SQL. State plainly, early, and more than
once: *a distributed, partition-oriented, leaderless database needs distributed thinking, not a
relational mental model wearing a Kotlin costume.* Denormalization is the normal, correct way to model
data here — not a workaround, not a code smell, not "giving up on normalization." If a visitor's first
instinct is "how do I do a JOIN," the site's job is to correct that instinct, not accommodate it.

## 3. Tech stack (opinionated — don't relitigate, just build)

- **Next.js** (App Router, latest stable) — TypeScript throughout, no JavaScript files.
- **MDX** for content authoring (`@next/mdx` or `next-mdx-remote`) — every content page is Markdown
  with embedded interactive React components where useful (tabs, callouts, runnable-looking code
  blocks with copy buttons, "gotcha" boxes — see §8).
- **Tailwind CSS** for styling. Use a component layer on top (shadcn/ui is a reasonable default —
  accessible, unstyled-by-default primitives you theme yourself) rather than a heavy pre-styled UI kit;
  this site should not look like a generic template.
- **Shiki** (or equivalent) for syntax highlighting, themed for both light and dark mode, Kotlin as a
  first-class supported language (verify Kotlin grammar support before committing to a highlighter).
- **Local search** — Pagefind (static-index, no backend, no third-party service dependency) or
  FlexSearch. Do not require an external SaaS (Algolia DocSearch) unless the maintainer explicitly
  wants that operational dependency; default to something that works entirely from the static build.
- **Static export** (`next build` with static output) where possible — this is a docs site, it should
  deploy to GitHub Pages, Vercel, Netlify, or similar with zero backend infrastructure.
- Dark mode and light mode, both fully designed (not an afterthought) — respect `prefers-color-scheme`
  by default with a manual toggle override.
- Fully responsive — a developer reading module reference docs on a phone during standup is a real use
  case, not an edge case.

## 4. Source material — mine the real thing, don't paraphrase secondhand

The Kandra GitHub repo (`https://github.com/Dev-Pasaka/kandra`) is the single source of truth. **Read
the actual Kotlin source for every claim this site makes about behavior** — do not trust any
intermediate summary (including this prompt) as gospel for exact method signatures, parameter names,
or edge-case behavior; source drifts, prompts don't get re-verified. Specifically:

- **`.claude/skills/kandra-*/SKILL.md`** (one per module) — these are exceptionally detailed,
  source-verified references already written for AI-assisted development against Kandra. They document
  every public class/function, exact behavior (including which retry/cache/consistency paths each
  method does and doesn't go through), and a "Gotchas" section per topic. **These are the highest-value
  single source for this site's module reference and pitfalls content** — do not write that content
  from scratch when a rigorously-verified version already exists; adapt and expand it for a web
  audience (the skill docs assume an AI pair-programmer context; the site needs to teach a human from
  zero).
- **`README.md`** — quick-start, philosophy, feature walkthrough with real code examples.
- **`docs/USER_GUIDE.md`** — the full narrative reference.
- **`docs/features/*.md`** — one file per feature area, already organized by topic.
- **`docs/changelog/*.md`** — one file per version; read every one, in order, to understand how the
  library actually evolved and why (this is real design history, not just a diff log).
- **`docs/issues/*.md`** (`ISS-NNN-*.md`) — **this is gold**. Every entry is a real bug, found either
  during development or (many of the higher-numbered ones) during actual real-cluster testing, with a
  full root-cause writeup: what broke, why, and the fix. Turn this into a prominent "Design Decisions &
  Lessons from Production" section — this is exactly the kind of content that separates a library site
  that feels battle-tested from one that feels like a README got expanded. Do not hide this in a
  changelog appendix; it's some of the most credible, differentiating content the site can have.
- **`docs/history/*-build-spec.md`** — the original build specs per version; useful for "why does this
  feature exist" narrative context.
- **`docs/test-plan/` and `docs/test-plan:1.1/`, `docs/report:1.0/`** — real-cluster test plans and
  results. Useful raw material for a "how thoroughly is this tested" trust-building page, and for
  concrete before/after examples of bugs caught by real-cluster testing vs. unit tests alone.
- **Actual module source** under each `kandra-*/src/main/kotlin/` — for exact current signatures,
  annotation parameters, exception types and messages, and default values. When the site shows a code
  example, it must compile against the current published version — verify against source, not memory.
- **The Dokka-generated API reference**, published at `https://dev-pasaka.github.io/kandra/` (built
  from source on every tag, multi-module). **Every class/function/annotation page on this site should
  deep-link to its corresponding Dokka page** as an "Full API reference ->" link. Don't guess the URL
  structure — fetch the live Dokka site during development and inspect its actual generated path scheme
  per module before wiring up links (Dokka's multi-module HTML output structure can vary by Dokka
  version/config; verify against the real, currently-published output, not an assumed convention).

**Ground rule**: every module in scope is `kandra-bom`, `kandra-core`, `kandra-runtime`, `kandra-ktor`,
`kandra-kodein`, `kandra-koin`, `kandra-codegen`, `kandra-test`, `kandra-multidc`, `kandra-migrate`,
`kandra-jakarta` (confirm this list against the repo's current `settings.gradle.kts` at build time —
modules may have been added since this prompt was written).

## 5. Information architecture

```
/                           Home — hero, the pain-points section (§2), quick "why Kandra" pitch,
                             not a wall of links
/why                        Expanded version of §2 — the full "distributed thinking, not relational
                             thinking" essay, with Kandra's concrete answer to each pain point
/foundations                Cassandra/ScyllaDB mental model for people coming from a relational
                             background — see §6.1, this is load-bearing, don't skip or shrink it
/getting-started             5-minute quick start — install, one entity, one query. Points at /tutorial
                             for anyone who wants the full, real-app-shaped walkthrough
/tutorial                    THE comprehensive tutorial — see §6.5. A multi-user todo-list Ktor server,
                             built up incrementally with every design decision explained as it's made
  /tutorial/01-modeling          Data modeling first, before any code
  /tutorial/02-project-setup      Ktor scaffold, every dependency explained
  /tutorial/03-entities            Entity definitions, one annotation choice at a time
  /tutorial/04-plugin-install        install(Kandra) walkthrough, every config block explained
  /tutorial/05-routes                CRUD routes, DI wiring
  /tutorial/06-denormalization         "todos assigned to me" — lookup tables in anger
  /tutorial/07-concurrency              Optimistic locking with @Version — why here, not everywhere
  /tutorial/08-soft-delete-and-trash     Undo/trash pattern with @SoftDelete + TTL
  /tutorial/09-counters                   A stats dashboard using @Counter
  /tutorial/10-caching                      @CacheResult wiring and its real invalidation gotchas
  /tutorial/11-observability                  Metrics (recorder hook) + logging (structured, debug flags)
  /tutorial/12-testing                          FakeKandraSession unit tests + Testcontainers integration
  /tutorial/13-migrations                        Adding a column later, safely, with kandra-migrate
  /tutorial/14-running-and-shipping                docker-compose, health checks, graceful shutdown
  /tutorial/15-what-we-didnt-do                      Explicit "why not X" — relational instincts avoided
/philosophy                  Design philosophy and the specific tradeoffs Kandra makes and why
  /philosophy/atomicity        Why LOGGED BATCH, why KandraBatchScope, the saveInBatch naming story
  /philosophy/consistency      Resolution order, per-call vs class vs global, LWT cost tradeoffs
  /philosophy/schema-safety    Fail-fast at startup, SchemaMode, why no runtime ALTER surprises
  /philosophy/denormalization  @LookupIndex as a first-class citizen, not a workaround
  /philosophy/testing           FakeKandraSession vs Testcontainers, and why the fake is deliberately
                                 limited (link out to ISS-020's reasoning)
/modules                     Index page listing all modules with a one-line "what and why" each
  /modules/kandra-core          Every annotation, the schema model, DDL generator, exceptions,
                                 validators, metrics, consistency types, timestamp handling
  /modules/kandra-runtime        Repositories (blocking + suspend), StatementBuilder, BatchEngine,
                                 QueryExecutor, codec, cache, the query DSL, driver extensions
  /modules/kandra-codegen         The KSP processor — what it generates, type resolution, gotchas
                                 (inheritance, computed properties, acronym-handling in name derivation)
  /modules/kandra-ktor             The ApplicationPlugin, every config block, install lifecycle
  /modules/kandra-koin             DI auto-binding, call-order requirements
  /modules/kandra-kodein            Same, for Kodein
  /modules/kandra-migrate            KandraMigration, KandraMigrationRunner, checksum + locking design
  /modules/kandra-multidc             Multi-DC config surface, load balancing, failover
  /modules/kandra-jakarta               Bean Validation adapter
  /modules/kandra-test                   FakeKandraSession, KandraTestUtils, KandraTestcontainers
/recipes                      Real-world worked examples, task-oriented not module-oriented
  /recipes/lookup-tables           @LookupIndex end to end, BATCH vs EVENTUAL consistency
  /recipes/time-series              Clustering keys, pagination, "give me the latest N" patterns
  /recipes/soft-delete                @SoftDelete + findActive(), the timing model, storage tradeoffs
  /recipes/counters                    @Counter tables, increment/decrement, concurrency guarantees
  /recipes/optimistic-locking           @Version, LWT cost, when to use it vs. blind overwrite
  /recipes/multi-table-writes            KandraBatchScope done correctly, the atomicity guarantee
  /recipes/caching                        @CacheResult, Caffeine, invalidation semantics and gotchas
  /recipes/migrations                      Versioned schema migrations, concurrent-runner safety
  /recipes/multi-dc-deployment               Real multi-DC config walkthrough
  /recipes/social-feed-capstone                The full worked "social feed" app (source this from
                                              docs/test-plan/07-realistic-workload-capstone.md and its
                                              results) — the single most complete "does this actually
                                              work as a real app" example available; feature it
/reference                    API reference shell — every public class/function with a short
                             description and a prominent "Full API reference (Dokka) ->" link out.
                             This is NOT a duplicate of Dokka; it's a curated, example-rich companion
                             that explains *why* and *when*, with Dokka as the exhaustive signature
                             source of truth
/battle-scars                 The docs/issues/ISS-*.md content, reframed for a web audience — real
                             bugs, root causes, fixes, and the lesson each one teaches. This is a
                             trust-building section: it proves the library has been tested against
                             a real cluster and problems get found and fixed, not swept under the rug
/changelog                    Rendered from docs/changelog/*.md, newest first, with migration notes
                             called out prominently for breaking changes
/faq                          Anticipate: "why not Hibernate/Exposed", "why not just use the driver
                             directly", "does this work with plain Cassandra or only ScyllaDB",
                             "what's the performance overhead", "how do I test this"
```

## 6. Section-by-section content requirements

### 6.1 `/foundations` — do not assume the reader knows anything

This is the single most important page for developer happiness on this site, and the easiest to
under-invest in. Assume the reader has built relational-database applications and has never touched a
wide-column, partition-oriented store. Cover, in plain language with diagrams (build these as React/SVG
components, not screenshotted images — they need to work in both light and dark mode and stay crisp at
any zoom level):

- What a partition key actually is, physically (data locality, which node(s) own a partition) — and
  why every query must be answerable by partition key or a small set of Cassandra-native mechanisms
  (secondary index, or a denormalized lookup table).
- Clustering keys: sort order *within* a partition, and why "give me the latest N events for this
  user" is a clustering-key pattern, not a `ORDER BY ... LIMIT N` full-table pattern.
- Why there are no joins, no foreign keys, and why that's a deliberate tradeoff for horizontal
  scalability and availability, not a missing feature.
- Tombstones: what a DELETE actually does at the storage layer, why `gc_grace_seconds` exists, why
  tombstone-heavy workloads degrade read performance over time.
- Consistency levels (`ONE`, `LOCAL_ONE`, `QUORUM`, `LOCAL_QUORUM`, `ALL`, etc.) in plain terms — what
  you're actually trading off, and why "LOCAL_QUORUM is usually right for a single-DC or when you want
  DC-local consistency" is a real, practical default, not an arbitrary one.
- Lightweight Transactions (LWT / Paxos-based compare-and-set) — what they cost, why they're not "just
  a transaction," why they should be opt-in rather than default.
- Counter columns as a genuinely distinct CQL type with distinct write semantics — not a plain integer
  column, not usable in a normal INSERT.
- `ALLOW FILTERING` — what it actually does (scatter-gather scan across the cluster), why it's
  dangerous at scale, and why Kandra's DSL structurally refuses to let you reach for it.

Each concept should end with an explicit "what this means for how you write Kandra code" bridge — this
page's whole job is to make the rest of the site make sense, not to be generic Cassandra theory
disconnected from the library.

### 6.2 Module reference pages

For each module, follow this exact structure (adapt from the corresponding `.claude/skills/kandra-*/SKILL.md`
as primary source, expanded and reframed for a human reader rather than an AI agent):

1. **What this module is, in one paragraph.**
2. **Why it exists as a separate module** — not every consumer needs `kandra-migrate` or
   `kandra-multidc`; explain the modularity decision (matches the BOM's `api` vs. `implementation`
   choices — check `kandra-bom/build.gradle.kts` for the real dependency shape).
3. **Every public annotation/class/function**, each with: signature, a short description, a real
   compiling code example, and a "Gotchas" callout box wherever the skill doc or source comments flag
   one (there are many, documented exhaustively in the skill docs — don't lose any of them in the
   adaptation).
4. **A "Full API Reference" link** to the module's Dokka page.
5. **Cross-links** to relevant `/recipes` and `/battle-scars` pages where this module's behavior was
   the subject of a real bug/fix.

### 6.3 `/battle-scars`

One page per meaningfully-grouped set of `ISS-NNN` entries (group by theme, e.g. "clustering keys and
lookup tables," "the batch-scope shadowing saga," "cache invalidation," "codegen collection types") —
not necessarily strictly 1:1 with each file, since several 0.4.4-era issues are directly connected
(ISS-025 -> ISS-028/029 -> ISS-030 is one continuous story about a single design change's ripple
effects, and reads far better told as one narrative than four disconnected entries). For each: what
broke, a concrete before/after code or behavior example, the fix, and — most importantly — the general
lesson a reader should take away that applies beyond Kandra itself (e.g., "Kotlin always resolves a
member function over an extension function, even a closer-scoped one — this will bite you anywhere you
try to intercept a method with an extension of the same name, not just in Kandra").

### 6.4 Code examples — non-negotiable accuracy bar

Every code snippet on this site must be **valid against the current published Kandra API**. Concretely,
as of this prompt being written: the query DSL does **not** use a `+` prefix (`UserTable.email eq "x"`,
not `+UserTable.email.eq("x")` — this was a breaking change made specifically to remove a footgun,
verify current syntax against source before publishing any example); `KandraBatchScope` methods are
`saveInBatch`/`deleteInBatch`/`saveIfNotExistsInBatch`, not `save`/`delete`/`saveIfNotExists`. **Verify
every example against actual current source at build time — do not trust this prompt's examples to
still be accurate by the time you build this**, library APIs evolve. If feasible, add a CI check that
extracts code blocks tagged `kotlin` from MDX content and at minimum type-checks them against a pinned
Kandra version (a lightweight Gradle/Kotlin-compiler-based snippet-checker script is reasonable scope
for this; full execution against a live cluster is not required for the CI check, just compilation).

### 6.5 `/tutorial` — the comprehensive walkthrough (this is the site's centerpiece, treat it that way)

`/getting-started` is a 5-minute taste. `/tutorial` is the real thing: build a complete, multi-user
Ktor server end to end, with **every design decision explained at the moment it's made**, not deferred
to a "see the reference for details" footnote. The worked example is a **multi-user todo-list app** —
deliberately mundane on the surface, chosen specifically because it's the smallest domain that naturally
forces every interesting Cassandra/ScyllaDB modeling decision to happen for a real reason, not a
contrived one: per-user data ownership (partition design), "show me my todos in order" (clustering
keys), "show me todos assigned to me by someone else" (denormalization — this is the load-bearing
lesson of the whole tutorial), concurrent edits (optimistic locking), soft-delete/undo (TTL design),
completion stats (counters), and a hot read path worth caching (query caching). Don't swap this for a
"more impressive-sounding" domain — the mundanity is the point; if a todo list needs this much
deliberate modeling, the reader internalizes that *no* real Cassandra app gets this for free by
accident.

Each numbered page below is a **checkpoint**, not just a topic — the reader should be able to follow
along and have a running, testable increment of the app at the end of every single page. Every page
that introduces a modeling or config choice must include an explicit **"Why this, and not the
relational-instinct alternative"** callout — this is the tutorial's whole reason for existing over just
reading the reference docs.

**01 — Modeling, before any code.** Start with pen-and-paper (or a diagram component) data modeling,
*before* writing a single entity class — this ordering is itself a lesson (query-first modeling is a
distributed-database discipline, not an afterthought layered on top of an ERD). Work through: who are
the actors (users, todos, assignments), what are the actual access patterns the app needs ("show me my
todos," "show me todos assigned to me," "mark this todo done," "show completion stats") — and derive
the partition/clustering key design *from those access patterns*, explicitly contrasting with "design
the schema from the entities' natural relationships" (the relational instinct). Land on: `Todo`
partitioned by `ownerId` (clustering key `createdAt DESC` — "show me my todos, newest first" is a cheap
partition-scoped query), a `Users` table keyed by `userId` with an `@LookupIndex` on `email` (for
login-by-email), and flag — don't yet solve — the "todos assigned to someone else" access pattern as
something the `Todo`'s own partition-by-owner design can't serve, to be picked up in page 06.

**02 — Project setup.** Full Ktor project scaffold. **Every single dependency in `build.gradle.kts`
gets its own explanation** — don't just show the block, annotate each line: `kandra-bom` (why a BOM at
all — version alignment across modules without repeating version strings), `kandra-ktor` (the plugin
itself), `kandra-koin` (the chosen DI approach for this tutorial — briefly note `kandra-kodein` as the
alternative and why the tutorial picked one rather than both), `kandra-codegen` via `ksp` (why type-safe
`*Table` refs are worth the KSP build-time cost — tie back to the DSL's compile-time safety story),
`kandra-test` (test-only), Caffeine (`compileOnly` upstream, `runtimeOnly`/`testImplementation` here —
explain the compileOnly-vs-runtime distinction concretely, since this is a real point of confusion flagged
in external review of the library), Micrometer (for the observability page later), a JSON serialization
dependency for Ktor request/response bodies, and `kotlin-logging` for structured logging. Also show and
explain the `docker-compose.yml` for a local single-node ScyllaDB dev instance — every port, every
environment variable, why single-node is fine for local dev but the reader should know real deployments
are multi-node (link to `/foundations`).

**03 — Entities.** Introduce `User` and `Todo` one annotation at a time, each with its own "why":
`@PartitionKey` (why `ownerId`, derived from page 01's access-pattern analysis, not "because it's the
primary key" — there is no single "the primary key" concept the way a relational reader expects),
`@ClusteringKey(order = ClusteringOrder.DESC)` on `createdAt` (why DESC — "newest first" is the actual
UX requirement, and getting the order right at the schema level means the query never needs an
in-memory sort), `@LookupIndex` on `User.email` (why: login needs "find by email," which isn't the
partition key), `@CreatedAt`/`@UpdatedAt` (automatic timestamp injection, why `Instant`-only is a
deliberate constraint), `@Column` where a CQL name needs to diverge from the Kotlin property name. Show
the actual generated DDL (via the DDL-preview widget from §7) right next to the entity so the
annotation-to-schema mapping is never abstract.

**04 — Installing the plugin.** Full `install(Kandra) { }` block, every config sub-block explained in
turn: `keyspace`/`autoCreateKeyspace` (and why `AUTO_CREATE` is right for this tutorial but
`schemaMode = VALIDATE` is what you'd actually want in a production deploy pipeline — cross-link to the
`SchemaMode` philosophy page), `contactPoints`/`localDatacenter`, `pool` (what `requestTimeoutMillis`
and `maxRequestsPerConnection` actually protect against), `retry` (`maxAttempts`/`backoffMillis` — what
transient failures this catches and what it deliberately doesn't), `auth` (`KandraAuth.fromEnv()` as
the production default, why env-var-based rather than hardcoded), and a first mention (expanded properly
in page 11) of `debug` and `metrics`. Wire up `kandraKoin()` and show the DI call-order requirement
explicitly (install Koin before configuring Kandra) as a real, easy-to-hit gotcha.

**05 — Routes.** Basic CRUD: create a todo, list "my todos" (partition-scoped `findPage`), mark done,
delete. Keep auth intentionally minimal (a `userId` path parameter or header is enough — full
authentication/session management is explicitly out of scope and the tutorial should say so plainly,
rather than pretending to solve it) so the focus stays on Kandra, not on unrelated Ktor concerns.

**06 — Denormalization: "todos assigned to me."** This is the tutorial's centerpiece lesson. The app
now needs "show me every todo assigned to me, regardless of who owns it" — and `Todo`'s
partition-by-`ownerId` design (correct for page 01's primary access pattern) cannot serve this query at
all without a full scan. Walk through the relational instinct first (a SQL reader would reach for
`SELECT * FROM todos WHERE assignee_id = ?`, an ordinary indexed column scan) and explain concretely why
that doesn't translate. Then introduce a **second, denormalized table** — either via `@LookupIndex` if
the shape fits, or (more likely, since this needs to return *multiple* rows per assignee, not resolve
one primary key) a **hand-rolled denormalized table** (`TodosByAssignee`, partitioned by `assigneeId`,
written to explicitly alongside the primary `Todo` write) — matching the pattern Kandra's own capstone
example (`docs/test-plan/07-realistic-workload-capstone.md`'s `PostByTag`) uses for exactly this
"denormalize for a second access pattern that doesn't fit `@LookupIndex`'s one-row-per-key shape"
scenario. This is also the natural place to introduce **`KandraBatchScope`/`saveInBatch`** for real:
writing the primary `Todo` and its `TodosByAssignee` denormalized row must be atomic (a partial failure
here is exactly the "post visible in the feed but invisible to tag search" scenario Kandra's own docs
warn about), so this page should show `batchBlocking { todoRepo.saveInBatch(todo);
todosByAssigneeRepo.saveInBatch(...) }` used for a real reason the reader has just been made to feel the
need for, not introduced cold as an API to memorize.

**07 — Concurrency: optimistic locking.** Two people (or two tabs) editing the same todo's title at the
same time — a real, relatable scenario. Introduce `@Version`, `update(old, new)`'s LWT-based
compare-and-set, and `KandraOptimisticLockException` handling in the route layer (what HTTP status/error
shape a client should see on conflict). Explicitly justify *why this table* gets `@Version` and the
`User`/other tables don't — tie back to the LWT-cost tradeoff from `/foundations`: don't reach for this
by default, reach for it where concurrent-edit conflicts are a real, expected scenario.

**08 — Soft delete and undo.** "Deleted" todos should be recoverable for a window (a real product
requirement, not a contrived one) — `@SoftDelete(ttlSeconds, markerProperty)`, `findActive()`'s
whole-table-scan caveat (and why it's *not* the right tool for "show my non-deleted todos" — that's
still the ordinary partition-scoped `findPage` with a client-side filter, exactly as Kandra's own
capstone documents), and the TTL-driven "exists but excluded, then truly gone" lifecycle walked through
with real timing, not hand-waved.

**09 — Counters: completion stats.** "How many todos has this user completed, ever" — introduce
`@Counter` tables as a genuinely distinct CQL type, `increment()`/`decrement()`, and why this is
*not* a `SELECT count(*)` (which doesn't scale and isn't idiomatic here) and not a plain integer column
mutated by a normal `UPDATE` (Cassandra counters exist specifically because naive read-modify-write
counters aren't safe under concurrent writes — this is the one place a relational instinct of "just add
1 in application code" would be a real concurrency bug).

**10 — Caching the hot path.** The user's own todo list is read far more than it's written — introduce
`@CacheResult`, the Caffeine dependency's `compileOnly`-vs-real-presence behavior from page 02 made
concrete, and **the real invalidation gotcha**: cache invalidation must match `findById`'s key shape
exactly (source this from the real `ISS-028` bug in `docs/issues/`) — walk through *why* this matters
with a concrete "stale read after update" scenario, not just "call `save()` and it's handled."

**11 — Observability: metrics and logging.** This page must be genuinely thorough, not a token
paragraph:
- **Logging**: `kotlin-logging` usage throughout the app (structured, leveled logs — not
  `println`), and Kandra's own `debug` config block in depth: `logQueries` (logs the CQL *template* at
  DEBUG — and the important, security-relevant detail that **bound parameter values are never logged,
  even with this on**, specifically because they may contain PII — this is a real, deliberate design
  choice worth calling out explicitly as a model for the reader's own logging practices),
  `logSlowQueriesMs` (a WARN-level slow-query tripwire, and what threshold is actually reasonable to
  set in production vs. local dev), `logBatches` (full batch contents at DEBUG — useful exactly for
  debugging the atomicity behavior introduced in page 06).
- **Metrics**: Kandra's `metrics { enabled = true; recorder = KandraMetrics { table, op, durationMs ->
  ... } }` hook — explain that Kandra does **not** hard-wire a specific metrics backend; `recorder` is a
  plain callback receiving table name, operation, and duration, and the tutorial should wire it into
  Micrometer explicitly (`meterRegistry.timer("kandra.query", "table", table, "operation",
  op).record(...)`) so the reader sees the real integration, not an abstraction they have to
  reverse-engineer. Cover what dashboards/alerts this unlocks in practice (p99 latency per
  table/operation, spotting a specific query pattern regressing).
- **Health and shutdown**: wire up `GET /kandra/health` and `ShutdownConfig` (`graceful`,
  `drainTimeoutMs`), and explain what "graceful" actually buys you operationally (in-flight queries
  finish before the process exits during a rolling deploy, instead of being cut off mid-request).

**12 — Testing.** Both halves, explained as genuinely different tools for genuinely different jobs (not
"use whichever"): `FakeKandraSession`-backed unit tests for structural assertions (did `saveInBatch`
actually collect the right statements, is the batch shape correct) with an explicit, honest statement of
its limits (it cannot simulate real data round-trips or real LWT applied/not-applied outcomes — source
this from `ISS-020`'s reasoning, don't understate it), and `KandraTestcontainers`-backed integration
tests for everything that actually touches data or LWT semantics (the optimistic-locking conflict from
page 07, the soft-delete timing from page 08, the denormalized-write atomicity from page 06). Show a
real test for each of those three scenarios, not a generic "here's how to set up Testcontainers"
placeholder.

**13 — Migrations.** The app ships, then a real requirement arrives: todos need a `priority` field.
Walk through writing a `KandraMigration`, running it via `KandraMigrationRunner`, and explain the
checksum + LWT-claim-locking design (why two runner instances racing on deploy can't double-apply it) —
and explicitly flag LWT support as an operational prerequisite (per GitHub issue tracking this exact doc
gap in the Kandra repo, if resolved by the time this site is built, link to the finished docs; if not,
state the prerequisite directly here).

**14 — Running it and shipping it.** Local dev via the docker-compose from page 02, a brief look at
what changes for a real multi-node/multi-DC deployment (cross-link to `/recipes/multi-dc-deployment`,
don't re-explain it here), and a final walkthrough of the health-check + graceful-shutdown behavior from
page 11 under a simulated rolling deploy.

**15 — What we didn't do, on purpose.** Close the tutorial with an explicit list of relational instincts
the reader might expect and why this app doesn't have them: no join table for assignments (page 06's
denormalized table instead), no `ORDER BY` at query time for the todo list (baked into the clustering
key at schema-design time instead), no naive `SELECT count(*)` for stats (counter table instead), no
`@Transactional` spanning arbitrary business logic (deliberately scoped `KandraBatchScope` around
exactly the two writes that need atomicity, and nothing else). This page should feel like the payoff of
`/foundations`' opening argument — the reader should be able to look back at the whole tutorial and see
distributed thinking applied consistently, not bolted on.

## 7. Interactive features (developer-happiness bar)

- **Copy-to-clipboard on every code block**, with a brief "Copied!" confirmation.
- **Callout components**: at minimum `Gotcha`, `Note`, `Tip`, `Danger` — visually distinct, used
  liberally wherever the source material has one (the skill docs already flag dozens of these
  explicitly — don't flatten them into plain prose).
- **A "generated DDL preview" widget** on the `kandra-core` / annotations pages: given a small set of
  pre-baked example entities (not free-form user input compiled server-side — this is a static site,
  no backend), show the entity source alongside its generated `CREATE TABLE` statement side by side, so
  the DDL generator's behavior is immediately visible rather than something the reader has to imagine.
- **Version switcher** if/when the site needs to show docs for multiple Kandra versions side by side;
  at minimum, every code example and behavior claim should be clearly tied to "as of version X.Y.Z" so
  a reader on an older pinned version isn't misled — pull the current version from the same source the
  library itself uses (`gradle.properties`' `version` key in the Kandra repo).
- **"Edit this page" link** on every content page, pointing at the corresponding MDX source file in
  this site's own GitHub repo (not the Kandra repo) — encourages community doc contributions.
- **Full-text search** (§3) surfaced via a prominent, keyboard-shortcut-accessible (`Cmd/Ctrl+K`) search
  palette, not just a sidebar search box.
- **A responsive, collapsible sidebar nav** reflecting the information architecture in §5, with the
  current page always visible/highlighted, and a persistent "on this page" table of contents for long
  reference pages.

## 8. Tone and voice — "the spirit of Kandra"

Write the way the `.claude/skills/*/SKILL.md` files and `docs/issues/*.md` files are written: direct,
specific, technically honest, willing to say "this is a footgun" or "this used to be broken, here's
why" in plain language rather than euphemism. No marketing copy, no "blazingly fast," no vague
superlatives — every claim should be backed by a concrete mechanism or a link to source/evidence. Trust
the reader's intelligence; they're a working engineer, not a prospect being sold to. Where the library
made a real tradeoff (e.g., LWT cost, `+`-free DSL breaking existing call sites, `FakeKandraSession`'s
deliberate limitations), say so plainly rather than hiding it — this is exactly the credibility-building
tone that made the two independent external code reviews (referenced in this repo's git history, if you
have access to it) rate the library highly specifically *because* of its honesty about its own rough
edges. The site should read like it was written by the same person who wrote those `ISS-NNN` postmortems
— someone who has actually run this against a real cluster and hit the real problems — not by a
generic technical-writing pass over a feature list.

## 9. Non-goals / guardrails

- Do not invent features, config options, or behavior that doesn't exist in the actual source. If
  something in this prompt turns out to be stale by the time you build it, trust the source over this
  prompt and flag the discrepancy rather than silently "fixing" the prompt's claim into the docs.
- Do not compare favorably to Hibernate/JPA/Exposed except to explicitly contrast mental models (§2) —
  this site isn't selling against a competitor, it's teaching a different way of thinking.
- Do not build a live, arbitrary-code-execution playground against a real cluster — this is a static
  site with no backend budget assumed; the DDL-preview widget in §7 is the right scope for
  "interactive," not a full sandboxed query runner.
- Do not require any paid third-party service (search, hosting, analytics) as a hard dependency — keep
  the whole stack deployable for free on GitHub Pages or an equivalent static host. Analytics, if added
  at all, should be privacy-respecting and optional.
- Do not duplicate Dokka's exhaustive signature-level API reference wholesale — link out to it. This
  site's reference section is a curated companion (why/when/gotchas + example), not a second copy of
  the same generated content.

## 10. Deployment

Static export, deployable to GitHub Pages (co-locate under a path or subdomain alongside the existing
Dokka output at `dev-pasaka.github.io/kandra/`, or a dedicated custom domain if the maintainer wants
one), Vercel, or Netlify — any of these should work with zero backend infrastructure. Wire up a CI
workflow (GitHub Actions) that builds and deploys on push to the site's main branch, and — if practical
— a separate check that flags when the pinned Kandra version this site documents falls behind the
latest tag in the Kandra repo, so documentation staleness is visible rather than silent.

## 11. Definition of done

- Every module in §4's list has a complete reference page per §6.2 — no module skipped, no "coming
  soon" placeholders.
- `/foundations` is complete per §6.1 — a relational-background developer with zero Cassandra
  experience can read it and understand *why* the rest of the site's guidance makes sense.
- `/tutorial` is complete per §6.5 — all 15 pages present, each ending in a working, testable increment
  of the todo-list app; every design decision named in §6.5 is actually explained on the page, not
  deferred to a reference-doc link; the observability page (11) covers both logging (including the
  bound-values-never-logged detail) and metrics (including a real Micrometer wiring example) in full,
  not as a token paragraph; the testing page (12) includes real tests for the three flagged scenarios
  (optimistic-lock conflict, soft-delete timing, denormalized-write atomicity), not a generic
  Testcontainers setup placeholder; page 15's "what we didn't do" list is present and specific.
- `/battle-scars` covers every `ISS-NNN` entry in the Kandra repo, grouped into coherent narratives.
- Every code example compiles against the current published Kandra API (verified, not assumed).
- Every class/function reference page links out to its Dokka page, and those links have been verified
  against the live, currently-published Dokka site (not guessed from an assumed URL scheme).
- Search works, keyboard shortcut included, and returns relevant results across the whole site (not
  just page titles).
- Full dark/light mode, fully responsive down to mobile width, no horizontal scroll anywhere except
  intentionally (wide code blocks/tables) with their own scroll containers.
- Site builds and deploys via a documented, automated CI pipeline with zero manual steps.
- Nothing on the site references or implies the `+`-prefixed query DSL syntax, `KandraBatchScope.save`/
  `delete` (pre-rename), or any other API shape that predates the current published version — a final
  full-text sweep for known-stale patterns before calling this done is cheap insurance.
