# Kandra 0.4.2 — Real-World Test Plan

This folder is a self-contained assignment for a Claude Code agent (or any engineer) with **no prior
context on this conversation or codebase**. It specifies everything needed to build a real Ktor
application against the **published** Kandra artifact (not this source tree) and a **real ScyllaDB
cluster** (not a fake session), then exercise every documented feature and every known edge case,
and report back a scored result.

Nothing here has been executed yet. Treat every claim about "should" behavior as a hypothesis to be
verified against the real database, not a known-good fact — that is the entire point of this plan.

## Background: what Kandra is, and the problem it solves

Kandra is a lightweight Kotlin-first ORM for ScyllaDB/Cassandra, shipped as a Ktor server plugin
(`ke.co.coinx.kandra`, Apache 2.0, source at `https://github.com/Dev-Pasaka/kandra`). It exists
because the standard "ORM" mental model — one row = one object, foreign keys, joins, `WHERE` on
any column — actively fights how Cassandra/ScyllaDB actually work. Cassandra is a wide-column,
partition-oriented store: queries must be servable by a partition key (or a narrow set of
Cassandra-native mechanisms — secondary indexes, materialized views), there are no joins, and
denormalization (storing the same data shaped differently for each query pattern) is the normal,
correct way to model data, not a workaround. Existing Kotlin/JVM ORMs (Hibernate, Exposed, etc.)
model the relational world and don't have first-class concepts for the patterns Cassandra actually
needs day to day:

- **Denormalized lookup tables** — e.g. a `users` table keyed by `user_id`, plus a
  `users_by_email` table that exists purely so "find a user by email" doesn't require a full-cluster
  scan. Keeping these in sync by hand, in every write path, is exactly the kind of repetitive,
  error-prone glue code an ORM should own.
- **Composite partition keys and clustering keys** — e.g. time-series data partitioned by
  `(tenant_id, day)` and clustered by `timestamp DESC` for efficient "give me the latest N events for
  this tenant" queries — a completely different indexing model from a relational primary key.
- **TTL and tombstone-aware deletes** — Cassandra deletes are tombstones, not row removal; a naive
  "just call DELETE a lot" pattern degrades cluster performance over time in a way a relational
  developer wouldn't expect.
- **Lightweight Transactions (LWT)** for the narrow cases that need compare-and-set semantics
  (`INSERT ... IF NOT EXISTS`, optimistic-locked updates), which Cassandra supports but at a real
  performance cost — an ORM should make the *safe* default path cheap and make you opt in
  deliberately to the expensive compare-and-set path, not the other way around.
- **Counter tables**, which are a distinct CQL table type with their own write semantics
  (`increment`/`decrement` only, no plain `INSERT`).

Kandra grew through four build phases (`docs/history/0.1.0-build-spec.md` through
`0.4.0-build-spec.md`, and the `docs/changelog/` entries that track what actually shipped in each),
starting from core annotations/DDL generation and repositories, then adding schema validation modes,
pagination, counters, and collections (0.2.0), retry/idempotency/multi-DC/auth/SSL (0.3.0), and
optimistic locking, soft delete, health checks, graceful shutdown, schema migrations, and query
caching (0.4.0). It was built for and is used in production by CoinX, a crypto social-finance
platform built on Ktor + ScyllaDB — the annotation set and repository API reflect patterns that
came from a real application's day-to-day query needs, not a speculative feature list.

**This test plan's job** is to confirm that story holds up: that a fresh consumer, pulling only the
published artifact (not this source tree) against a real cluster, gets an ORM that actually handles
those Cassandra-native patterns correctly for realistic, medium-to-complex queries — not just the
individually-simple CRUD operations a toy example would exercise. See
[file 7](07-realistic-workload-capstone.md) for the concrete "does this actually work as a real
application" acceptance test that the whole plan builds toward.

## Why this exists

Kandra has 43 unit tests (all against `FakeKandraSession`, which cannot execute real prepared
statements — see [ISS-020](../issues/ISS-020-fake-session-lwt-semantics.md)) plus a handful of
Testcontainers integration tests added in 0.4.2 that cover a narrow slice of the surface (basic CRUD,
one LWT conflict, one soft-delete case, one shutdown case). The vast majority of Kandra's real
behavior — every annotation combination, every config knob, every documented gotcha in
`.claude/skills/kandra-*/SKILL.md` — has **never been run against a live cluster**. This plan exists
to close that gap by building one deliberately adversarial sample application that touches
everything, then hunting for where reality diverges from the docs.

## How to use this folder

Read the files **in order**. Each assumes the previous ones are done.

| # | File | What it covers |
|---|---|---|
| 1 | [01-prerequisites-and-environment.md](01-prerequisites-and-environment.md) | Exact infrastructure you need before writing any code: a real ScyllaDB cluster (Docker Compose spec included), JDK/Kotlin/Gradle versions, and — critically — how to verify the published `ke.co.coinx.kandra` artifacts are actually resolvable before you waste time debugging what looks like a Kandra bug but is actually a missing release. |
| 2 | [02-sample-application-build-plan.md](02-sample-application-build-plan.md) | Step-by-step Ktor project scaffold, entity definitions that deliberately exercise every annotation (including combinations the docs warn are dangerous), plugin install, both DI integrations, migrations, Jakarta validation, and a route per repository method. |
| 3 | [03-functional-coverage-matrix.md](03-functional-coverage-matrix.md) | The literal checklist of what "100% real functionality" means — one row per annotation/method/config knob, each mapped to a concrete verification step and an expected-per-docs outcome. |
| 4 | [04-edge-cases-and-adversarial-tests.md](04-edge-cases-and-adversarial-tests.md) | The harsh-critic pass: boundary values, race conditions, malformed input, and every gotcha already known from source inspection — written so a fresh agent doesn't have to rediscover them, only confirm or refute them against a real cluster. |
| 5 | [05-scoring-rubric-and-reporting.md](05-scoring-rubric-and-reporting.md) | How to score each test (pass/fail/partial/blocked), severity classification, and the exact report format to produce at the end — this is the "scoring pattern" referenced from the other files. |
| 6 | [06-known-pitfalls-and-what-to-watch-for.md](06-known-pitfalls-and-what-to-watch-for.md) | Consolidated list of things that **look** like they work but are documented as silent no-ops, config fields with zero effect, or footguns that won't show up as compile errors or test failures unless you specifically look for them. |
| 7 | [07-realistic-workload-capstone.md](07-realistic-workload-capstone.md) | The final deliverable: extend the sample app into one cohesive, working Ktor server modeling a realistic day-to-day Cassandra domain (a social feed) — time-series feeds, pagination, tag search, counters, soft delete, and multi-table writes — proving Kandra handles medium-to-complex real-world query patterns, not just isolated feature pokes. |

## Non-negotiable ground rules for whoever executes this plan

1. **Use the published artifact, not this source tree.** The whole point is testing what a real
   consumer gets from Maven Central. Do not `includeBuild`/composite-build against this repo, and do
   not copy source files into the sample app. If the published artifact doesn't resolve, stop and
   report that as a blocking finding (see file 1) — do not silently fall back to a local build to
   "make progress," since that would test the wrong thing.
2. **Use a real ScyllaDB cluster, not `FakeKandraSession`.** Per
   [ISS-020](../issues/ISS-020-fake-session-lwt-semantics.md) and the `kandra-test` skill doc,
   `FakeKandraSession` cannot execute prepared statements at all (`FakePreparedStatement.bind()`
   unconditionally throws) — it is structurally incapable of validating anything in this plan.
   `KandraTestcontainers` (real Cassandra via Testcontainers) is an acceptable substitute for "a real
   cluster" for individual test cases, but the primary build in file 2 should target a real
   docker-compose ScyllaDB cluster so DDL/consistency/multi-node behavior is genuinely exercised.
3. **Record what actually happened, not what you expected to happen.** Several "known gotchas" listed
   in file 4 and file 6 were fixed in 0.4.1/0.4.2 (see `docs/issues/`) but have never been verified
   against a real cluster — they might not actually be fixed correctly. Several others are
   long-standing, deliberate, or accepted-risk behaviors that are *not* bugs — don't "fix" the
   library while testing it; report findings, don't patch around them, unless explicitly asked.
4. **Every failure needs a minimal reproduction**, not just "test X failed." Capture: the exact
   entity/config code, the exact CQL Kandra generated (turn on `debug { logQueries = true }`), the
   exact exception with stack trace, and the ScyllaDB version/config in use.
5. **When something is ambiguous or destructive, stop and ask** rather than guessing — e.g. if a test
   would require modifying production-shaped infrastructure, or the published artifact version is
   ambiguous, pause for human input rather than inventing an assumption and building on it silently.
6. **Get Kandra's own context before you start** — clone the Kandra source repo (read-only, as
   reference material) so you have the per-module Claude Code skills, the `docs/` this plan lives in,
   and the historical build specs available while you work. See
   [file 1, §1.0](01-prerequisites-and-environment.md#10-get-kandras-source-and-claude-code-skills-reference-only)
   for the exact clone/copy steps. This is reference material only — rule 1 still applies: build the
   sample app against the *published* artifact, never against this cloned source.

## Definition of "100% real functionality" for this plan

Every row in the [functional coverage matrix](03-functional-coverage-matrix.md) has been executed
against a real cluster at least once, with an observed result recorded (not skipped, not assumed),
**and** every applicable edge case in [file 4](04-edge-cases-and-adversarial-tests.md) has been
attempted and scored per the [rubric](05-scoring-rubric-and-reporting.md), **and** the
[realistic-workload capstone](07-realistic-workload-capstone.md) is a working, runnable Ktor server —
not just individually-passing isolated feature checks. "100%" describes coverage of the attempt, not
a requirement that every test *passes* — a well-documented, correctly-scored failure in files 3/4 is
a complete, successful execution of that part of the plan. The capstone in file 7 is different: it is
meant to actually run end-to-end as a coherent application, so a broken capstone server **is** an
incomplete run, even if every individual matrix row passed in isolation — that gap (features work one
at a time but don't compose into a working app) would itself be an important finding. An incomplete
run (skipped rows, untried edge cases, or a non-functional capstone server) is the only thing that
counts as *not* done.
