# Kandra — Documentation Reality Check (follow-up)

**Date:** 2026-10-11
**Scope:** `README.md`, `docs/USER_GUIDE.md`, `docs/production-example.md`, and all files under
`docs/features/` — re-checked against current source in `kandra-core`, `kandra-runtime`, and
`kandra-ktor`, specifically for drift introduced by everything merged since the last full pass
([`2026-09-09-docs-reality-check.md`](2026-09-09-docs-reality-check.md)): PRs #112–#150 (GH #90–#146,
~160 commits).
**Method:** Same as the prior pass — no claim taken on trust. Every finding below was confirmed by
reading the actual current implementation, not by cross-referencing one doc against another.
**Outcome:** The September pass's fixes held up — nothing it corrected had regressed. But a month of
feature/safety work since then had shipped with **no corresponding doc updates**: three new repository
methods, a new exception type, a new consistency-validation behavior, a new debug flag, and a new
AUTO_MIGRATE safety guard were all live in `main` and completely undocumented. One was silent-data-loss
shaped; one was runtime-crash shaped from following the docs' own example. **All 8 findings below were
fixed in the same pass** — see each finding for what changed and where. A 9th, smaller gap
(`throttle { }`'s admission-control block, the actual config behind finding #3's
`KandraThrottledException`, itself undocumented) surfaced while fixing #3 and was fixed alongside it.
Separately, [PR #149](https://github.com/Dev-Pasaka/kandra/pull/149) — the multi-DC
"not yet validated against a genuine multi-region topology" disclosure, written and CI-passing but
unmerged since 2026-09-11 — was merged as part of this same pass, and the same disclosure was added to
`docs/USER_GUIDE.md`'s Multi-DC Support section, which hadn't carried it.

---

## Findings, most severe first

### 1. `findAll()`'s implicit 10,000-row cap is undocumented — silent truncation, not an error — Fixed
**Where:** should be in `README.md`, `docs/USER_GUIDE.md`, `docs/features/repositories.md`
**Severity:** High — silently returns an incomplete result set with no exception.

`QueryExecutor.resolveRows()`/`resolveRowsSuspend()` cap any unpaged `findAll()`/`find()` query at
`maxUnpagedResultRows = 10_000`: past that, it logs a WARN and **truncates** rather than throwing
(`"Query on '<table>' returned more than 10000 rows without paging — truncating... Use findPage()
..."`). This was added by ISS-066/ISS-081 as a safety net against unbounded memory growth, but it's a
behavior change `findAll()`'s callers have no way to discover from the docs — every doc shows
`findAll()`/`findAll(limit = N)` with no mention that omitting `limit` on a result set over 10k rows
gets you a silently-truncated list, not the full table. A caller relying on `findAll()` returning
"everything" for, say, a nightly export job would get wrong data with nothing but a server log line to
notice it by.

### 2. `saveAndGet()`/`saveIfNotExistsAndGet()`/`updateAndGet()` — three new repository methods, zero mentions anywhere — Fixed
**Where:** should be in `README.md`, `docs/USER_GUIDE.md`, `docs/features/repositories.md`
**Severity:** High — discoverability, and it's the documented fix for a footgun the docs still show the hard way around.

`KandraRepository`/`KandraSuspendRepository` gained `saveAndGet`, `saveIfNotExistsAndGet`, and
`updateAndGet` (GH #132/#138/#136) — each the same write as its plain counterpart, but returning the
entity actually persisted (generated id, `@CreatedAt`/`@UpdatedAt`, and critically the post-write
`@Version`) instead of discarding it. `docs/USER_GUIDE.md`'s own `@Version` section still recommends
the workaround these methods exist to replace:

```kotlin
// USER_GUIDE.md, current text — works, but re-fetches via a separate findById() round-trip
try {
    repo.update(old, new)
} catch (e: WriteTimeoutException) {
    val current = repo.findById(old.id)!!
    repo.update(current, new.copy(version = current.version))
}
```

`updateAndGet(old, new, ...)`'s own KDoc states its purpose is exactly this: letting a subsequent
`update()` reuse the returned version "without risking a spurious `KandraOptimisticLockException` from
reusing the stale pre-update version" (GH #136) — the retry recipe in the guide should use it instead
of a second network round-trip through `findById()`.

### 3. `KandraThrottledException` — missing from the Exception Reference table and everywhere else — Fixed
**Where:** `docs/USER_GUIDE.md` §"Exception Reference" (and `README.md`)
**Severity:** Medium-High — an exception type callers can now actually catch, with no doc telling them it exists.

GH #103/ISS-090 wraps every backpressure/admission-control rejection from the driver
(`BatchEngine`'s save/update/delete paths) into `KandraThrottledException` instead of letting the raw
driver exception leak through unwrapped. The `USER_GUIDE.md` exception table (7 rows, otherwise
accurate) has no row for it, and nothing in either doc explains backpressure/throttling behavior at
all.

### 4. Consistency Levels section invites a runtime crash it doesn't warn about — Fixed
**Where:** `docs/USER_GUIDE.md` §"Consistency Levels" (`## Consistency Levels`)
**Severity:** High — compiles fine, throws at query time; the doc's own listing is what leads there.

> Available levels: `ANY`, `ONE`, `TWO`, `THREE`, `QUORUM`, `ALL`, `LOCAL_QUORUM`, `EACH_QUORUM`,
> `LOCAL_ONE`, `LOCAL_SERIAL`, `SERIAL`.

This lists all 11 `KandraConsistency` values as interchangeable options for `defaultRead`/
`defaultWrite` (and, by extension, `@ReadConsistency`/`@WriteConsistency`). As of GH #140/#141
(`StatementBuilder.resolveReadConsistency`/`resolveWriteConsistency`), three of them now throw
`KandraQueryException` **at query time** if used that way:
- `EACH_QUORUM` as a *read* consistency (`defaultRead`, `@ReadConsistency`, or a per-call override) —
  Cassandra/Scylla reject it server-side; it's write-only.
- `SERIAL`/`LOCAL_SERIAL` as a *write* consistency (`defaultWrite`, `@WriteConsistency`, or a per-call
  override) — those are only valid as the separate `serialConsistency` parameter of a conditional
  write (`saveIfNotExists()`/`update()`'s `IF` check), never as the table's regular write consistency.

Someone following the doc's own "`defaultRead = KandraConsistency.EACH_QUORUM`"-shaped example (the
doc shows `LOCAL_QUORUM` but gives no reason not to generalize) would get an exception the guide never
mentions is possible. The Exception Reference table's `KandraQueryException` row ("Query rejected
(shutting down, null partition key, counter table misuse) or all retries exhausted") should gain this
case too.

### 5. `DebugConfig.rawQueryStrictMode` is entirely undocumented, and the injection-guard description is stale — Fixed
**Where:** `docs/USER_GUIDE.md` §"Debug / Observability" (`debug { }` block) and
`docs/features/repositories.md` §"Raw queries (injection-safe)"
**Severity:** Medium.

`DebugConfig` has a 4th field, `rawQueryStrictMode` (default `false`) — when enabled, the
`raw()`/`rawQuery()` injection heuristic throws `KandraQueryException` instead of only logging a WARN.
Both doc examples of the `debug { }` block show only `logQueries`/`logBatches`/`logSlowQueriesMs`.

Separately, `docs/features/repositories.md`'s one-line description — "Calling `raw(cql)` with no bind
parameters and a CQL string containing literals logs a `WARN`" — describes the *pre*-GH-#32 behavior.
Current behavior (confirmed in `QueryExecutor.checkRawInjectionRisk`): the check fires regardless of
whether other parameters are bound, and now also catches a bare `;` statement terminator or a `--`/
`/* */` comment marker (GH #142, closing a gap where a payload against an unquoted numeric/UUID column
reached the driver undetected). Neither the widened trigger condition nor the `;`/comment coverage is
mentioned in the doc.

### 6. `AUTO_MIGRATE`'s refusal to add a key column is undocumented — Fixed
**Where:** `docs/features/schema-modes.md`
**Severity:** Medium — a real safety guard a reader could easily mistake for a bug report if they hit it unprepared.

The mode table's one-line description of `AUTO_MIGRATE` — `` `CREATE TABLE IF NOT EXISTS` + `ALTER
TABLE ADD` for new columns; warns on unmapped Scylla columns `` — doesn't mention that GH #92/ISS-079
added an explicit refusal: if a column missing from the live table is a `@PartitionKey`/
`@ClusteringKey` on the entity, Kandra now throws `KandraSchemaException` at startup instead of
silently running `ALTER TABLE ADD` (which would add it as a *plain* column while Kandra keeps treating
it as part of row identity — collapsing distinct logical rows onto one physical row). This is a
correctness-preserving behavior change worth a line in the mode table, not just the throw site's
in-code comment.

### 7. Migration checksum doc understates what it actually hashes — Fixed
**Where:** `docs/features/migrations.md`
**Severity:** Low — understates a safety guarantee rather than overselling one, but it's the doc's one job.

> Checksums (SHA-256, hashing version/name/class name **and the migration class's own compiled
> bytecode**) are stored in `kandra_migrations` and validated on re-run

`KandraMigration.checksum()`'s current implementation (GH #102/ISS-089) also hashes every *sibling*
synthetic class file the migration's `up()` body produces — object expressions, local/anonymous
classes, and (for the subset of lambdas that still compile to a real class file rather than
`invokedynamic` on Kotlin 2.x) SAM-conversion lambdas. The doc's "its own compiled bytecode" undersells
this: a reader could reasonably conclude that editing an anonymous class *inside* `up()` wouldn't be
caught by the checksum, when it now is.

### 8. Version-number inconsistency between README, `gradle.properties`, and the changelog — Fixed
**Where:** `README.md` (multiple `(0.4.X)` feature tags vs. its own install snippet)
**Severity:** Low/cosmetic, but concretely confusing for anyone trying to pin a dependency.

`README.md` tags "Strict Mode" as `(0.4.7)` (line 693), but:
- `gradle.properties` → `version=0.4.6`
- the README's own install snippet two sections up → `implementation(platform("...kandra-bom:0.4.6"))`
- `docs/changelog/` has no `0.4.7.md` — the latest entry is `0.4.6.md`, dated 2026-07-23

(`docs/site/build-prompt-0.4.7.md` does exist, so "0.4.7" is a real internal marker — just one that
never made it into `gradle.properties`, the changelog, or the install instructions.) Every other
`(0.4.X)` tag in the README is `0.4.2`, all consistent with each other and with features that did ship
in that version per the changelog — this is an isolated miss, not a systemic pattern.

---

## Verified clean (no action needed)

Re-checked rather than assumed, since these cover areas adjacent to the findings above:

- `@Transient`, `@Ttl`, `@GeneratedUuid`, `@SecondaryIndex`, `@Version` annotation docs — all still
  match `kandra-core`'s current annotation definitions.
- `docs/features/multidc.md`'s Strict Mode description — matches `ConsistencyConfig`/
  `StatementBuilder.warnIfStrictModeViolation` exactly, including the "WARN-only, never throws" claim.
- `saveInBatch`/`deleteInBatch` naming and the "`save()`/`delete()` inside `batch {}` silently skips
  the batch" warning (the September pass's #2 finding) — still correct, not regressed.
- `findActive(allowFullScan: Boolean = false)`'s documented behavior in `repositories.md` — matches
  `KandraRepository.findActive` exactly, including the `@SecondaryIndex`-vs-`ALLOW FILTERING` branch.
- Basic `save`/`update`/`saveIfNotExists` signatures (sans the new `*AndGet` siblings) — parameter
  lists, defaults, and ordering all still match.
- The DDL-bootstrap-claim / migration-bootstrap-claim concurrency write-ups in `schema-modes.md` and
  `migrations.md` — both match current `Kandra.kt`/`KandraMigrationRunner` source, including the
  2-minute staleness threshold and the one residual `kandra_ddl_locks`-creation race both docs already
  disclose.

---

## Suggested follow-up

- The September review's own suggested follow-up — audit `docs/test-plan/`/`docs/test-plan:1.1/` —
  is still outstanding and not addressed by this pass either.
- Same root cause as every finding above except #8: there's no process step that updates
  `README.md`/`USER_GUIDE.md`/`docs/features/` when a PR changes public API surface or observable
  behavior. Of the 8 findings here, 6 are "a new method/exception/config/guard shipped with tests and
  an in-code KDoc, but no doc update in the same PR." Worth either a docs checklist item on the PR
  template, or a lightweight CI grep for new `fun` signatures added to `KandraRepository`/
  `KandraSuspendRepository`/new `: KandraException` subclasses that don't appear in `docs/`.
