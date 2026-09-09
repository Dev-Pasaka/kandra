# Post-fix critical audit — 2026-09-09

## Context

On 2026-09-08 a critical library-wide review (`docs/reviews/2026-09-08-pre-multidc-cluster-review.md`)
filed seven issues (GH #78–#84, ISS-070–ISS-076) covering security, performance, consistency,
scalability, and DX gaps found ahead of experimental multi-cluster DC testing, plus one carried-over
item (GH #60/ISS-059). All eight were fixed and merged the same day, across four PRs (#86, #87, #88,
#89), by agents working in parallel — each fix was tested against real Testcontainers-backed
Cassandra and passed CI, but none of that new code had been looked at by a second, independent
reviewer.

This audit is that second look. It was commissioned specifically because "the fix passed its own
tests" is a weak signal for correctness in distributed-coordination code — the interesting bugs in
this class of system are almost always in the interleavings the original author didn't think to
test, not in the happy path. The brief was explicit: be maximally critical, cover every angle a
distributed ORM needs to get right, and re-review the brand-new fix code as hard as the long-standing
code, since nobody else had yet.

## Methodology

Seven independent agents, each given full repository access and no knowledge of the others'
findings, audited distinct but overlapping areas against the current `origin/main`
(commit `57ca1ec`) in read-only mode:

1. `kandra-runtime` write/retry/consistency path (`BatchEngine`, `KandraBatchScope`,
   `StatementBuilder`, `ConsistencyConfig`, `KandraMetrics`)
2. `kandra-runtime` read path, caching, and codec layer (`QueryExecutor`, `KandraCache`,
   `KandraCodec`, `KandraRepository`/`KandraSuspendRepository`)
3. `kandra-ktor` driver/connection/security wiring (`CqlSessionBuilder`, `KandraConfig`, `Kandra.kt`)
4. `kandra-migrate` and `kandra-core`'s schema-definition side (`KandraMigrationRunner`,
   `SchemaRegistry`, `DdlGenerator`)
5. `kandra-codegen`, `kandra-koin`, `kandra-kodein`, `kandra-jakarta`
6. Test infrastructure, CI posture, and the brand-new multi-DC docker-compose fixture
7. Security, cross-cutting across every module

Two agents (audits 3 and 4) independently found and cross-validated the same bug — the
`updateLookups`/`updateLookupsSuspend` consistency gap, ISS-083 below — which is a useful signal:
when two reviewers working from different angles converge on the same file and line without
coordinating, that finding gets treated with extra confidence. One agent (audit 7) worked from a
stale local branch that predated all four fix PRs and consequently reported #78/ISS-070 as still
open; that specific claim was independently re-verified against `origin/main` (it is fixed — see
PR #87) and dropped. Every other Critical/High finding below was personally re-verified by reading
the actual current source before filing, not taken solely on an agent's word.

## Headline result

**Two of the four just-merged fixes have real, confirmed bugs in the fix itself** — most seriously,
the DDL bootstrap claim added for GH #79 never resets after its first successful run, which means
`SchemaMode.AUTO_CREATE`/`AUTO_MIGRATE` will silently stop applying any schema change — new tables,
new columns — after the very first successful application startup, for the lifetime of the
keyspace. This is a regression, not a pre-existing gap: the pre-#79 code ran (racily, but
correctly) on every startup. See ISS-077.

Beyond the new code, this audit also surfaced several **severe pre-existing bugs** that neither the
2026-09-08 review nor any prior review had caught, including a counter-column decode bug that will
throw on any counter table with more than one counter column the moment they're incremented at
different times (ISS-080), and three separate places where the library's own consistency-override
mechanism is silently ignored (ISS-081, ISS-082, ISS-083) — undermining the exact guarantee the
upcoming multi-DC test round exists to validate.

**Recommendation: do not begin experimental multi-DC cluster testing until at least the eight
Critical findings below are fixed.** None of them require new architecture — all are surgical,
well-scoped changes to code that already exists — but running the experiment before fixing them
means the experiment's own results (particularly anything involving schema changes, counter
columns, cache-backed reads, or lookup-index consistency) cannot be trusted.

## Findings by severity

### Critical — fix before testing

| ID | GH | Title |
|---|---|---|
| [ISS-077](../issues/ISS-077-ddl-bootstrap-claim-never-resets.md) | [#90](https://github.com/Dev-Pasaka/kandra/issues/90) | Schema DDL bootstrap claim never resets — AUTO_CREATE/AUTO_MIGRATE runs at most once ever per keyspace |
| [ISS-079](../issues/ISS-079-auto-migrate-key-column-add-corrupts-data.md) | [#92](https://github.com/Dev-Pasaka/kandra/issues/92) | AUTO_MIGRATE can silently ALTER TABLE ADD a missing key column as a plain column, causing row collisions |
| [ISS-080](../issues/ISS-080-counter-column-null-decode-throws.md) | [#93](https://github.com/Dev-Pasaka/kandra/issues/93) | Counter columns throw on decode whenever any counter cell is untouched (NULL) |
| [ISS-081](../issues/ISS-081-findactive-no-row-cap.md) | [#94](https://github.com/Dev-Pasaka/kandra/issues/94) | findActive()/findActiveSuspend() have no row cap — OOM risk on ALLOW FILTERING |
| [ISS-082](../issues/ISS-082-findbyid-cache-hit-ignores-consistency.md) | [#95](https://github.com/Dev-Pasaka/kandra/issues/95) | findById() cache hits silently ignore the caller's consistency override |
| [ISS-083](../issues/ISS-083-lookup-index-bypasses-consistency.md) | [#96](https://github.com/Dev-Pasaka/kandra/issues/96) | Lookup-index reads and versioned-update lookup-table writes both bypass configured consistency |
| [ISS-084](../issues/ISS-084-multidc-tests-not-run-in-ci.md) | [#97](https://github.com/Dev-Pasaka/kandra/issues/97) | kandra-multidc's entire test suite is tagged "manual" with zero CI/scheduled execution |
| [ISS-078](../issues/ISS-078-ddl-claim-no-holder-fencing-clock-fallback.md) | [#91](https://github.com/Dev-Pasaka/kandra/issues/91) | DDL claim completion/release has no holder fencing; clock-skew check has a local-clock fallback bug |

### High — fix soon, before relying on the affected feature

| ID | GH | Title |
|---|---|---|
| [ISS-085](../issues/ISS-085-strict-mode-rf-math-wrong-multidc.md) | [#98](https://github.com/Dev-Pasaka/kandra/issues/98) | Strict Mode's RF-vs-consistency math is wrong for multi-DC NetworkTopologyStrategy (false positives) |
| [ISS-086](../issues/ISS-086-batch-suspend-split-bypassable.md) | [#99](https://github.com/Dev-Pasaka/kandra/issues/99) | Suspend/blocking batch-collection split is bypassable by mixing repository types |
| [ISS-087](../issues/ISS-087-cache-invalidate-race-per-process-undocumented.md) | [#100](https://github.com/Dev-Pasaka/kandra/issues/100) | Cache invalidate-after-write race can pin a stale value indefinitely; cache is undocumented per-process |
| [ISS-088](../issues/ISS-088-migration-claim-applied-vs-claimed-conflated.md) | [#101](https://github.com/Dev-Pasaka/kandra/issues/101) | Migration claim resolution conflates losing to an APPLIED row with losing to a CLAIMED row |
| [ISS-089](../issues/ISS-089-migration-checksum-misses-lambda-classes.md) | [#102](https://github.com/Dev-Pasaka/kandra/issues/102) | Migration checksum misses sibling lambda/anonymous class files |
| [ISS-090](../issues/ISS-090-throttle-exception-not-wrapped.md) | [#103](https://github.com/Dev-Pasaka/kandra/issues/103) | Backpressure throttle rejections leak as an unwrapped driver exception |
| [ISS-091](../issues/ISS-091-codegen-nested-class-collision-nondata-class.md) | [#104](https://github.com/Dev-Pasaka/kandra/issues/104) | Codegen can crash on same-simple-name nested entity classes; non-data-class entities fail late |

### Medium

| ID | GH | Title |
|---|---|---|
| [ISS-092](../issues/ISS-092-ktor-driver-config-hardening-gaps.md) | [#105](https://github.com/Dev-Pasaka/kandra/issues/105) | kandra-ktor driver-config hardening gaps (TLS silent downgrade, reverse-DNS hostname verification, pool size validation) |
| [ISS-093](../issues/ISS-093-runtime-read-path-metrics-polish.md) | [#106](https://github.com/Dev-Pasaka/kandra/issues/106) | kandra-runtime read-path/metrics polish (no implicit LIMIT 1, non-atomic getOrPut, no stampede protection, generic metrics labels) |
| [ISS-094](../issues/ISS-094-security-defense-in-depth-gaps.md) | [#107](https://github.com/Dev-Pasaka/kandra/issues/107) | Security defense-in-depth gaps (silent skip-auth, unguarded existsQuery, unvalidated KandraPredicate) |
| [ISS-095](../issues/ISS-095-multidc-fixture-hardening.md) | [#108](https://github.com/Dev-Pasaka/kandra/issues/108) | Multi-DC test fixture hardening (fixed ports, pause-vs-partition realism, missing hostname-mismatch test) |

### Low

| ID | GH | Title |
|---|---|---|
| [ISS-096](../issues/ISS-096-assorted-low-severity-post-fix-audit.md) | [#109](https://github.com/Dev-Pasaka/kandra/issues/109) | Assorted low-severity findings (codegen NPE risk, redundant Jakarta factory, missing edge-case tests, metrics/RF-cache minutiae) |

## What was checked and found solid

Not everything the audit looked at had a problem. Worth recording so it isn't re-litigated by a
future review:

- `SslConfig`/`KandraSslEngineFactory` (#78/ISS-070's fix): the core mechanism — applying
  `minimumTlsVersion`/`cipherSuites`/`requireEncryption` to the actual TLS handshake — is real and
  correct for the common case; only edge cases (unsupported TLS floor, IP-only peers) are gapped,
  see ISS-092.
- DI qualifier collision handling (`kandra-koin`/`kandra-kodein`, #35/ISS-041) correctly generalizes
  to nested classes and cross-package collisions; the equivalent codegen file-naming bug (ISS-091)
  is a separate, narrower issue.
- Credential redaction (`KandraCredentials.toString()`, #66/ISS-065), the raw-CQL injection guard on
  `raw()`/`rawQuery()` (#50/ISS-050), and identifier validation on `KandraColumnRef`/keyspace/DC
  names (#51/#64) all hold up under a fresh look — no new gaps found in their actual mechanisms.
- Credential rotation applies correctly to new/reconnecting connections without disrupting in-flight
  requests on already-established connections (the correct, intentional behavior).
- CI itself has a working Docker daemon and successfully runs every non-"manual"-tagged
  Testcontainers-backed test today — the gap in ISS-084 is specifically about the tests that opted
  out of that, not a Docker-availability problem in CI generally.

## Process note for future reviews

Two of the seven audit agents initially worked from a stale local git branch instead of fetching and
reading `origin/main`, leading to some early-draft findings describing code that didn't reflect the
merged state. Both self-corrected once told to use `git show origin/main:<path>`, and this review's
Critical/High findings were each independently re-verified against `origin/main` (commit `57ca1ec`)
before being filed. Anyone continuing this work should start with `git fetch origin main` and audit
`origin/main` directly, not whatever branch happens to be checked out locally.
