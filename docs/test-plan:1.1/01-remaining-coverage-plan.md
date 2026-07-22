# 01. Remaining Coverage — Priority-Ordered Resume List

v1.0 scored roughly 53 of 123 file-3 rows and substantive coverage of 6 of 18 file-4 sections
(`docs/report:1.0/SUMMARY.md`). This file is the resume list for everything left, ordered by
priority. Row/section IDs refer to `docs/test-plan/03-functional-coverage-matrix.md` and
`docs/test-plan/04-edge-cases-and-adversarial-tests.md` — **read this file's ID together with the
matching row in those files**, don't re-derive the test steps from scratch; they're already fully
specified there and haven't changed. Record results in a new `docs/report:1.1/03-functional-coverage-results.md`
/ `04-edge-case-results.md`, same format as v1.0's.

Do not re-attempt anything covered by [00-fix-verification.md](00-fix-verification.md) here — that
file already re-scopes the rows that were FAIL/BLOCKED specifically because of the 6 fixed bugs
(C25/C26/D5/§4.7 for the cache fix; U1-U3/D4-adjacent/F1-adjacent/K1-K3 for the clustering-key fix;
C29/F1's empty-collection case for the codec fix; the capstone's Flow 1/Flow 5 for the batch-scope and
clustering-key fixes respectively — see [02](02-capstone-reverification.md) for those two).

## Priority 1 — No infrastructure blockers, never attempted, cheap

These just ran out of time in v1.0; nothing stops them from being run first.

- **C9** — `@LookupIndex` EVENTUAL consistency (timing counterpart to C8, which got structural-only
  coverage in v1.0).
- **C17, C18** — `@CreatedAt`/`@UpdatedAt` (need an update-then-recheck sequence, not just a save).
- **C24** — `@Sensitive` masking (confirm `KandraEntityLogger.safeToString` behavior directly; plain
  `toString()` is documented to NOT mask, per v1.0's source-reading note — verify that's actually true
  at runtime, not just true in the source).
- **C26** — `@CacheResult` with Caffeine absent from the classpath (the one C25-adjacent case that
  does *not* touch the now-fixed cache bug — confirm the graceful `ClassNotFoundException` → WARN →
  no-op path still works correctly).
- **C28** — `@ReadConsistency`/`@WriteConsistency` class-level annotations (needs driver tracing or a
  node-kill differential — can piggyback on the node-kill infrastructure already proven in the
  capstone's node-resilience test).
- **C31** — Enum column with a stale stored value (write a raw string via `cqlsh` that isn't a valid
  enum constant, confirm the documented `IllegalArgumentException` — not a Kandra exception type —
  fires on read).
- **R2** — `save()` on a counter table (confirm it throws, per `BatchEngine.save`'s explicit guard).
- **D4** — `deleteById()` on the **blocking** repository with a `@SoftDelete` entity (v1.0 only tested
  suspend; the skill doc's own gotcha note says blocking `deleteById` ignores soft-delete entirely and
  always hard-deletes — confirm that's actually true, it's a real behavioral trap, not a hypothetical).
- **S1 nuance vs. C11 gap** — S1 (AUTO_CREATE) already passed, but re-check the C11 gap noted in v1.0's
  results (the test entity's `@Column` override coincidentally matched auto-derivation, so it never
  actually proved the override takes effect) — add one property where the explicit name differs from
  `camelToSnake` auto-derivation and confirm the override wins.
- **I3, I4, I5** — Koin/Kodein wrong-registration-order and standalone-binder variants (I1/I2 happy
  paths already passed).

## Priority 2 — `SchemaMode` variants (S-series), needs a second keyspace/mode toggle but no new infra

- **S2-S7** — `AUTO_MIGRATE` (new column, type change, removed column), `VALIDATE` (match, mismatch),
  `NONE`. These need the sample app restarted with different `SchemaMode` config between runs — not
  hard, just sequential rather than parallel with everything else. `VALIDATE` mismatch and `NONE` are
  the highest-value sub-cases (confirm `VALIDATE` actually throws on a real mismatch, and `NONE`
  genuinely does nothing to the schema).

## Priority 3 — Plugin config remainder (most of P-series)

P1/P2/P10/P11/P17 already passed in v1.0. The rest of the ~22-row P-series is unattempted — work
through `docs/test-plan/03-functional-coverage-matrix.md`'s P-series systematically. **P4/P5
specifically require the auth-enabled cluster** (Priority 5 below) — do the non-auth P-rows first.

## Priority 4 — `kandra-test` module (T-series) and Jakarta validation (J-series)

- **T1-T3** — exercises `FakeKandraSession`/`KandraTestUtils`/`KandraTestcontainers` themselves (the
  test-support module, not the runtime). Low risk, self-contained, can run any time.
- **J1-J4** — Jakarta validation. Per v1.0's harness notes, these need the **HTTP route**, not the
  direct-repository JUnit harness (`validate<T>`/`validateJakarta<T>` are `install(Kandra)`-level
  hooks). The sample app already has `hibernate-validator`/`jakarta.el` wired per file 1 §1.4 — this
  needs the actual validation-annotated fields and a route that triggers them, which per v1.0's
  progress log was never built. Build it as part of this pass. Also covers **P19/P20** (validator
  config), noted as needing the HTTP route for the same reason.

## Priority 5 — Auth-enabled cluster (needs `docker-compose.scylla-auth.yml` brought up)

Written in v1.0, never brought up. Bring it up (port 9043, `PasswordAuthenticator`+`CassandraAuthorizer`
per the v1.0 compose file), create the low-priority role P4/P5 need (also never done), then:

- **P4, P5** — auth config rows.
- **§4.12 remainder** — v1.0 only tested the "unset env var" bullet (§4.12 bullet 3); the rest of
  §4.12 (actual authenticated connection, wrong credentials, role-based access denial) needs the real
  auth cluster.

## Priority 6 — Two-DC cluster (needs `docker-compose.scylla-twodc.yml` brought up)

Also written, never brought up (ports 9044/9045, `GossipingPropertyFileSnitch`). Bring it up, then:

- **X4** — the two-DC-specific row in file 3's X-series (X1 already passed against the single-DC
  cluster's `KandraMultiDc.describe()` output — X4 needs an actual second DC to be meaningful).
- Multi-DC edge cases referenced in file 4 (DC-aware failover under a real DC outage, not just the
  config-validation-only P10/P11 checks that already passed).

## Priority 7 — Consistency differentials (X2, X3) and the rest of file 4

These are the most expensive/slowest to execute — save for last, after everything above has a
result.

- **X2, X3** — consistency-level differentials, need node-kill layered on top of specific consistency
  levels (LOCAL_QUORUM vs. LOCAL_ONE vs. ALL under partial node loss) — the capstone's node-kill
  infrastructure (already proven working in v1.0) is the right starting point, just parameterized by
  consistency level instead of a single fixed one.
- **§4.1** — suspend read-path concurrency under real load (needs a constrained-dispatcher route and
  the "fail this test on purpose first" methodology file 4 §4.1 specifies — don't trust a "good"
  result without first confirming the harness can detect a "bad" one).
- **§4.2** — `saveAll` batch-chunking atomicity boundary (needs a way to fail partway through a
  multi-chunk batch — file 4 §4.2 suggests a mid-run node/network failure as the most reliable
  trigger).
- **§4.4** — the `@Version` contention half specifically (the no-`@Version` blind-overwrite half
  already passed in v1.0 via the direct harness on `AuditLog`; the `@Version` half was blocked by
  Finding #9 and should now be retestable — this overlaps with [00.4](00-fix-verification.md#004--clustering-key-where-clauses-the-largest-fix--8-call-sites)'s
  U1/U2 re-run, cross-reference rather than duplicate).
- **§4.8** — codec boundaries beyond the empty-collection case (`00-fix-verification.md` §00.3 covers
  the collection-specific regression; §4.8 covers everything else in the codec's boundary behavior —
  e.g. `BigDecimal` precision, `Instant` sub-millisecond truncation, custom encoder/decoder edge cases).
- **§4.9** — DDL naming edge cases (reserved CQL keywords as column names, very long identifiers,
  case sensitivity).
- **§4.13** — codegen correctness spot-checks beyond the Set/Map fix already covered in
  [00.1](00-fix-verification.md#001--kandra-codegen-setmap-columns-compile-without-the-patch-task) —
  covers other type mappings (enums, `BigDecimal`, nested generics if any appear elsewhere in the
  entity set).
- **§4.14** — volume/scale (large partition, wide row, `findPage` behavior at real scale rather than
  the 30-post capstone feed).
- **§4.15** — the full consistency matrix (overlaps with X2/X3 above — do these together).
- **§4.16** — idempotency/retry under real transient failures (the capstone's node-kill test already
  showed a real failure mode — Finding re: `UnavailableException` not being in `RetryConfig`'s default
  `retryOn` set, recorded as a Medium finding in v1.0, not yet re-verified post-fix since it's
  unrelated to the 6 fixes — confirm it's still true, since nothing in this fix pass touched
  `RetryConfig`).
- **§4.17** — graceful shutdown under real in-flight load (v1.0 only tested the trivial "reject after
  flag set" case in the direct harness — §4.17 wants a real drain-in-progress scenario).
- **§4.18** — validator conflict (overlaps with J-series/P19-P20 above — do together).
