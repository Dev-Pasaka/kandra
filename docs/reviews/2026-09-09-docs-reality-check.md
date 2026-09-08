# Kandra — Documentation Reality Check

**Date:** 2026-09-09
**Scope:** Every user-facing doc surface — `README.md`, `docs/USER_GUIDE.md`, `docs/production-example.md`,
all 10 files under `docs/features/`, and all 10 `.claude/skills/*/SKILL.md` files — checked line-by-line
against the actual current source in `kandra-core`, `kandra-runtime`, `kandra-ktor`, `kandra-migrate`,
`kandra-test`, `kandra-codegen`, `kandra-koin`, and `kandra-kodein`.
**Method:** No claim was taken on trust. Every annotation signature, config field, method signature, and
DSL example quoted below was checked against the actual source file it claims to describe before being
judged accurate or fixed. Where a doc claimed a behavior didn't exist or wasn't wired up, the corresponding
source was read to confirm whether that claim was still true or had since become stale.
**Outcome:** 14 files updated, correcting drift ranging from cosmetic to a completely non-existent DSL
syntax used throughout two major reference docs, a batch-scope footgun that silently defeats atomicity
with no compiler error, and a testing skill whose central claim was the exact bug that had already been
fixed in the code it was describing.

**Not audited in this pass** (flagging rather than silently skipping): `docs/test-plan/`,
`docs/test-plan:1.1/`, `docs/changelog/`, `docs/history/`, `docs/site/`, `docs/report:1.0/`. The first two
are large (7 files) step-by-step build/coverage-matrix docs that could reasonably contain the same class of
drift found everywhere else in this pass — recommended as a focused follow-up. The rest are explicitly
historical records (changelog entries, superseded build specs, website generation prompts) that document
what was true *at a point in time*, not current behavior, so a "reality check" against current source
doesn't apply to them the same way.

---

## Why this mattered enough to do a full pass

Every bug below was found by treating "the docs say X" as a claim to verify, not a fact to summarize —
reading the actual annotation class, the actual method signature, the actual config class, or the actual
runtime behavior each time, rather than cross-referencing one doc against another (which would have just
propagated the same mistake if two docs happened to agree). Several of the bugs below are things a new user
would hit in their first ten minutes with the library — a copy-pasted example that doesn't compile, or
worse, one that compiles and runs but silently does the wrong thing.

---

## Findings, most severe first

### 1. `@LookupTable` doesn't exist — the real annotation is `@LookupIndex`, and the shape is different
**Where:** `docs/USER_GUIDE.md`, `docs/features/core-annotations.md`
**Severity:** Compile-breaking, in the primary annotation reference of both docs.

Both docs' "Annotations Reference" sections documented a `@LookupTable(tableName, indexField, consistency)`
class-level annotation, applied *twice* on the class in the example. The real annotation is
`@LookupIndex(tableSuffix, consistency)`, applied *once per property*, directly on the field it indexes —
a structurally different usage pattern, not just a rename. `README.md` and `docs/production-example.md`
already had this right; only these two docs had drifted.

### 2. `application.kandra.batch { repo.save(...) }` — recreates a previously-fixed, silently-broken bug
**Where:** `docs/USER_GUIDE.md`, `docs/features/repositories.md`
**Severity:** Compiles and runs, but silently does the wrong thing — no exception, no warning.

Both docs showed `save()`/`delete()` called inside a `batch { }` block. The real API requires
`saveInBatch()`/`deleteInBatch()` — this naming is deliberate specifically *because* Kotlin always resolves
a repository's own real `save`/`delete` member over a same-named extension, even one declared inside the
batch scope itself. Calling `repo.save(entity)` inside `batch { }` compiles cleanly and executes
immediately, one write at a time — never joining the batch, defeating the entire point of atomicity, with
no error anywhere. This is exactly the bug `ISS-026` fixed in the code; these two docs had regressed to
describing the pre-fix API shape. `README.md` already used `saveInBatch` correctly.

### 3. `kandra-test` skill's central claim was the exact bug already fixed in the code it describes
**Where:** `.claude/skills/kandra-test/SKILL.md`
**Severity:** High — actively steers away from a working, recommended testing style.

The skill's comparison table, a dedicated "Known limitation" section, the Gotchas list, *and* one of its
two worked example tests all asserted that every `KandraRepository`/`KandraSuspendRepository` call throws
`UnsupportedOperationException` under `FakeKandraSession`, because `FakePreparedStatement.bind()`
unconditionally threw. That was true — and was fixed in `ISS-040`/GH #28, which added a real
`FakeBoundStatement` that records bound values so the whole repository surface runs end-to-end against the
fake session. The skill was describing the pre-fix state as current, complete with a test that literally
asserted the old exception. Rewrote the relevant sections, added the previously-undocumented
`FakeBoundStatement`/`FakeUnset`/`capturedStatements()`, and replaced the wrong example with one that
demonstrates inspecting bound values on a real `save()` call.

### 4. `KandraMigrationRunner`/`KandraMigration` skill described a pre-crash-safety, pre-bytecode-checksum version
**Where:** `.claude/skills/kandra-migrate/SKILL.md`
**Severity:** High — wrong operational guarantees for a schema-migration tool.

The skill claimed the checksum hashes only `version:name:qualifiedClassName` and explicitly stated "the
runner cannot detect" an edited `up()` body — this was true before `ISS-017`/`ISS-062`, but the current
`checksum()` also hashes the migration's own (toolchain-noise-normalized) compiled bytecode, and **does**
detect body edits. The skill also showed a `kandra_migrations` table schema missing the `status`/
`claimed_at` columns, a two-fewer-field `MigrationHistory`, a `MigrationStatus` enum that no longer exists
(replaced by the actively-used `MigrationRowStatus`, not "dead code" as claimed), and a constructor missing
its `staleClaimThreshold` parameter — describing a runner with none of the GH #26 crash-safety mechanism
(LWT claim-before-run, cluster-clock-based staleness) that the real class has had for some time. Rewrote the
file's `KandraMigration`/`KandraMigrationRunner`/`MigrationHistory`/`MigrationRowStatus` sections against
current source.

### 5. `kandra-ktor` skill described several config knobs as dead that have since been wired up
**Where:** `.claude/skills/kandra-ktor/SKILL.md`
**Severity:** High — the exact opposite of current behavior on security- and reliability-relevant config.

Three claims were stale in the direction that most matters — telling a reader a feature doesn't work when
it now does:
- **Credential rotation**: claimed refreshed credentials are "not currently re-applied to the live
  `CqlSession`'s auth." Fixed by GH #61 — the session retains a live `ProgrammaticPlainTextAuthProvider`
  and the rotation loop calls `setUsername`/`setPassword` on it directly.
- **Multi-DC failover**: claimed `maxRemoteNodesPerRemoteDc` is "not read in `CqlSessionBuilder`" and that
  failover mechanics live entirely in undocumented driver config. Fixed by GH #58 — the plugin now sets
  `LOAD_BALANCING_DC_FAILOVER_MAX_NODES_PER_REMOTE_DC` and
  `LOAD_BALANCING_DC_FAILOVER_ALLOW_FOR_LOCAL_CONSISTENCY_LEVELS`, and registers a `NodeDistanceEvaluator`
  enforcing `allowedRemoteDcs` as a real allow-list.
- **`PoolConfig.localRequestsPerConnection`**: described as a currently-dead-but-existing field. It was
  removed from the class entirely (GH #69) — the skill still showed it in the class definition.

Also added: `keyspaceDdl`'s identifier validation (GH #65, not previously mentioned), `jitter` on
`RetryConfig`, and the RF-vs-(R+W) caveat on `ConsistencyConfig`.

### 6. Several config/annotation examples used a parameter name that doesn't exist
**Where:** `README.md`, `docs/USER_GUIDE.md`, `docs/production-example.md`, `docs/features/*.md` (multiple)
**Severity:** Compile-breaking.

- `@ScyllaTable(tableName = "...")` — the real parameter is `name`, not `tableName`, and it has **no
  default** (USER_GUIDE additionally claimed a nonexistent "defaults to snake_case class name" fallback).
  Found and fixed in `docs/USER_GUIDE.md`, `docs/production-example.md` (twice), and
  `docs/features/core-annotations.md`.
- `KandraAuth.plainText(...)` — doesn't exist; the real factory is `KandraAuth.static(...)`. Found and
  fixed in `docs/USER_GUIDE.md` and `docs/features/ktor-plugin.md`.
- `KandraAuth.fromFile("/single/path")` — the real signature takes **two** separate plain-text file paths
  (`usernamePath`, `passwordPath`), not one JSON file. Found and fixed in `README.md` (two call sites).
- `consistency { read = ...; write = ... }` — the real fields are `defaultRead`/`defaultWrite`. Found and
  fixed in `docs/USER_GUIDE.md` and `docs/features/ktor-plugin.md`.
- `@ClusteringKey`'s parameter table invented a `descending: Boolean` parameter that doesn't exist — the
  real parameter is `order: ClusteringOrder` (an enum), contradicting the correct code example directly
  above it in the same doc. Found and fixed in `docs/USER_GUIDE.md`.
- `@Ttl` was documented as a **property**-level annotation read "from the annotated field at insert time" —
  it's actually **class**-level, a static default, with no such per-row-from-a-field semantics. Found and
  fixed in `docs/USER_GUIDE.md`.
- A fabricated `batch { warnThresholdKb = ...; maxChunkSize = ...; autoChunk = ... }` DSL block — these are
  flat top-level `KandraConfig` properties (`batchWarnThresholdKb` etc.), not a nested block; no such
  `batch { }` function exists. Found and fixed in `docs/production-example.md`.
- `KandraTestcontainers.container()` called as a function, and a fabricated `KandraTestcontainers.setup(container, ...)`
  — the real API is a `val container` property and a single `freshKeyspace(vararg classes)` entry point with
  no container argument (it manages its own shared lazy container). Found and fixed in `docs/USER_GUIDE.md`.
- `codec.register(Type::class, MyCodec())` — the real API is two separate calls,
  `registerEncoder`/`registerDecoder`, each taking a plain lambda, not one call taking a codec object. Found
  and fixed in `docs/USER_GUIDE.md`.
- `import io.kandra.core.exception.KandraValidationException` — wrong package; the real class lives in
  `io.kandra.core`. Found and fixed in `docs/production-example.md`.
- `KandraMigration` subclassed with `: KandraMigration()` + `override val version`/`override suspend fun up`
  — `version`/`name` are constructor parameters (not overridable properties) and `up()` is not `suspend`.
  Found and fixed in `docs/USER_GUIDE.md` and `docs/features/migrations.md`.

### 7. `@GeneratedUuid` — a real, documented-in-code annotation, entirely absent from README, USER_GUIDE, and the `kandra-core` skill
**Where:** `README.md`, `docs/USER_GUIDE.md`, `.claude/skills/kandra-core/SKILL.md`
**Severity:** Discoverability — a real feature (collision-resistant `UUID` auto-population, added to avoid
the same-millisecond clustering-key collision risk of `Instant`-derived keys) with zero mentions in the
three most-read docs. Only `docs/features/core-annotations.md` had it. Added a full section to
`docs/USER_GUIDE.md` (with parameter table and example) and a Feature Index entry; README already links to
`docs/features/` where it's covered.

### 8. Several repository method signatures were missing parameters added in later fixes
**Where:** `.claude/skills/kandra-runtime/SKILL.md`, `.claude/skills/kandra/SKILL.md`
**Severity:** Medium — undersells real capability rather than describing something false, but easy to miss
in review.

`find`, `findAll`, `findPage`, and `exists` were shown without their `consistency` parameter (added by
`ISS-054` to close the gap where generic reads ignored configured read consistency); `saveWithNulls`,
`saveAll`, and `updateForce` were shown without `consistency`; `update(old, new)` was shown without either
`consistency` or `ttlSeconds` (the latter added by `ISS-058` — without it, an `UPDATE` with no `USING TTL`
silently clears a `@Ttl`-annotated row's TTL on its first update). Also missing: `RetryConfig.jitter`
(equal-jitter backoff randomization) in both the `kandra-runtime` and `kandra-ktor` skills.

### 9. `KandraCredentials` shown without its password-redacting `toString()`
**Where:** `.claude/skills/kandra-core/SKILL.md`
**Severity:** Low-medium — the skill's own code sample would leak a password if copied and printed/logged.

Fixed by GH #66 (reviewed as part of an earlier PR this session): `toString()` now redacts `password` as
`***`. The skill still showed the plain, pre-fix data class. Fixed, with a note on why (`equals`/`hashCode`
intentionally still consider `password`, only the string representation is redacted).

### 10. `@SoftDelete`'s own signature table omitted `markerProperty`
**Where:** `.claude/skills/kandra-core/SKILL.md`
**Severity:** Low — the parameter is used correctly elsewhere in the same file, just missing from its own
canonical signature row.

### 11. Assorted smaller corrections
- `docs/USER_GUIDE.md`'s `KandraPage<T>` example was missing the real `hasMore: Boolean` field.
- Permission-validation section claimed the "can't complete the check" fallback logs at `INFO`; it's
  actually `DEBUG`. Also clarified that only `SELECT`/`MODIFY` throw at startup — a missing `ALTER` grant
  only warns (schema DDL fails later, not at startup).
- Added the RF-vs-(R+W)-consistency caveat (already present in the real `ConsistencyConfig` KDoc, per
  `ISS-069`) to the `kandra-runtime`, `kandra-ktor`, and `kandra-multidc` skills, and to
  `docs/USER_GUIDE.md` — previously only in the code and the pre-multi-DC review from 2026-09-08.
  Cross-referenced against `ISS-075` (filed in that same review) throughout.
- `kandra-ktor`'s SSL section in `docs/USER_GUIDE.md` no longer implies `requireEncryption`/
  `minimumTlsVersion`/`cipherSuites` do anything — cross-referenced to `ISS-070`.
- Top-level `kandra` skill's claimed repository method count (18) corrected to 23 — the collection/counter
  family (`append`/`remove`/`put`/`increment`/`decrement`, 5 methods) was added after that count was
  written and never updated.

---

## Verified clean (no action needed)

Spent real time checking these rather than assuming — they held up:

- `docs/features/schema-modes.md`, `operations.md`, `di-integrations.md`, `multidc.md`, `testing.md`,
  `jakarta-validation.md` — all matched source exactly.
- `.claude/skills/kandra-codegen/SKILL.md` — spot-verified its strongest claims (zero `logger.warn`/`error`
  calls in the processor, `@PartitionKey` never read, `@ScyllaTable`'s arguments never read) directly
  against `KandraProcessor.kt`; all held.
- `.claude/skills/kandra-koin/SKILL.md`, `.claude/skills/kandra-kodein/SKILL.md` — consistent with the
  verified `kandra-codegen` DI-accessor generation and with each other; no discrepancies found.
- The "Kotlin → CQL type mapping" tables in both `README.md` and `docs/USER_GUIDE.md` — cross-checked
  against `DdlGenerator.kt`'s actual `when` branches; exact match.
- `KandraTimestamp`, `KandraEventListener`, `KandraMetrics`, exception hierarchy signatures — all matched.

---

## Suggested follow-up

- Audit `docs/test-plan/` and `docs/test-plan:1.1/` the same way — not done in this pass, flagged above.
- Consider a lightweight CI check (even a grep-based one) for the highest-recurrence pattern found here:
  `where {` inside a query block, `KandraAuth.plainText`, `tableName =` on `@ScyllaTable`, and `: KandraMigration()`
  all repeated across multiple files independently, suggesting doc examples get copy-pasted across files
  without re-verification against source.
