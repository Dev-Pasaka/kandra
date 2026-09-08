# Kandra — Pre-Multi-DC-Cluster-Testing Review

**Date:** 2026-09-08
**Scope:** Security, performance, distributed-systems consistency, scalability, developer
experience, and general production-readiness checks across `kandra-core`, `kandra-runtime`,
`kandra-ktor`, `kandra-migrate`, and `kandra-multidc`.
**Purpose:** Independent critical pass ahead of experimental testing against a real multi-cluster,
multi-DC ScyllaDB topology — not a re-statement of the existing `docs/issues/` tracker, though that
tracker (59/60 previously-filed issues fixed, 1 deliberately deferred) was checked against and
found accurate.
**Outcome:** 7 new issues filed (`ISS-070`–`ISS-076` / GH #78–#84) — see the checklist below.

---

## Overall: 7.5 / 10 — unusually mature for pre-1.0, not yet safe for multi-instance production traffic

This is a more rigorously engineered library than most at this stage: crash-safe migrations reasoned
about at the OOM/`SIGKILL` level, clock-skew-safe staleness checks (server clock only, never app
wall-clock), jittered retry backoff, LWT-idempotency-aware retry, PII-redacting debug logs. That
level of "what happens when this specific thing fails at 3am" thinking is rare, and the project's
own `docs/issues/` tracker (60 entries, clear status, clear reasoning) is itself evidence of
unusually disciplined engineering practice.

But this pass found concrete gaps that a critical pre-production review should not wave through —
most notably **dead SSL config that gives a false sense of security**, and **no backpressure or
connection-pool controls**, both of which matter specifically once real multi-instance, multi-DC
traffic starts flowing.

---

## Security — 8 / 10

**Strong:**
- Structured query DSL can't be CQL-injected: `KandraColumnRef.cqlName` is validated against
  CQL-identifier shape at construction (`QueryDsl.kt:33-41`), and predicates can only enter a query
  via that typed path — there is no way to splice an untrusted string into a `WHERE` clause through
  the normal API.
- `raw()`/`rawQuery()` have a heuristic injection detector with an opt-in fail-closed strict mode
  (`QueryExecutor.kt:315-325`).
- `KandraCredentials.toString()` redacts the password (GH #66, verified in this review); bound
  query parameters are never logged even in debug mode; a per-column `@Sensitive` annotation
  redacts specific fields in debug entity logs (`KandraEntityLogger.kt`) — genuinely above what
  most ORMs bother with.
- Full mTLS support (trust/key store, hostname verification), pluggable auth provider with live
  credential rotation without a session rebuild (GH #61).

**Gap (`ISS-070` / GH #78):** `SslConfig.requireEncryption`, `minimumTlsVersion`, and
`cipherSuites` (`KandraConfig.kt:71,79-80`) are declared but never read anywhere else in the
codebase — confirmed by grep across the whole tree. Only `hostnameVerification` actually reaches
the driver. Same bug class as the already-fixed `PoolConfig.localRequestsPerConnection`
(`ISS-068`/GH #69) — except this one is security-relevant: a team that sets `minimumTlsVersion =
"TLSv1.3"` or pins `cipherSuites` believing they've hardened the connection have done nothing.
There is also **zero test coverage on the SSL path**, which is exactly why this wasn't caught the
way `ISS-068` was.

**Minor:** the health check is unauthenticated by design (honestly documented in-code) — still
worth a deployment-checklist item if the route is reachable outside a private network.

## Performance — 7 / 10

**Strong:** fully suspend on the hot path (async prepare/execute, GH #27), bounded unpaged reads
(10k row cap, truncate + warn rather than OOM, `ISS-066`), cached reflection/column-map lookups per
entity class, batch auto-chunking with size/tombstone warnings, speculative execution support.

**Gap (`ISS-074` / GH #82):** `KandraMetrics.record()` is only ever called on the **success** path
in `BatchEngine.kt` (lines ~160, 200, 244, 266) — after a retry loop exhausts, or a non-retryable
error is hit, nothing is recorded. Anyone wiring this into Prometheus/Micrometer for SLOs gets
latency-of-successes only, with no failure rate, retry count, or timeout visibility through the
library's one metrics hook.

**Gap (`ISS-072` / GH #80):** no connection-pool-size configuration. `PoolConfig` sets
`CONNECTION_MAX_REQUESTS` (requests-per-connection) but never touches `CONNECTION_POOL_LOCAL_SIZE`/
`CONNECTION_POOL_REMOTE_SIZE` — pool size is left at the DataStax driver default, with no way to
tune it short of raw driver config outside Kandra. (Already flagged as a deferred architectural note
in `ISS-069` item 4; promoted to its own actionable issue here.)

## Consistency / Distributed-Systems Correctness — 8.5 / 10

The strongest part of the library. `KandraMigrationRunner`'s crash-safety story is genuinely well
engineered: LWT-claimed rows before running, staleness measured via `toTimestamp(now())` read from
the cluster coordinator on **both** sides of the comparison — never app wall-clock (`ISS-063`/GH
#64) — with an explicit distinction between "still running elsewhere" (warn + halt) and "actually
crashed" (throw). The `@Version` LWT update path deliberately skips retry to avoid masking a real
success as a false `KandraOptimisticLockException` (`ISS-032`) — a bug most retry wrappers get
wrong.

**Most important finding in the whole review (`ISS-075` / GH #83):** `ConsistencyConfig`'s own
docstring already admits the defaults (`LOCAL_ONE` read / `LOCAL_QUORUM` write) only guarantee
read-your-writes for `RF ≤ 3` (documented in `ISS-069` item 3). Strict Mode — the library's only
guard rail here — warns on `LOCAL_ONE`/`ONE` usage in a multi-DC topology, but **does not check R+W
against actual RF at all**. A team running `RF=5` (plausible for a larger multi-DC deployment) on
the defaults gets silent, undetected loss of read-your-writes — passes every RF=1 Testcontainers
test, breaks only under real topology. Documented but unguarded — the single most likely
"worked-in-staging, silently-wrong-in-prod" footgun today, and directly relevant to the upcoming
multi-DC test.

**Gap (`ISS-071` / GH #79):** schema bootstrap (`SchemaMode.AUTO_CREATE`/`AUTO_MIGRATE`'s `CREATE
TABLE`/`ALTER TABLE`, and `KandraMigrationRunner`'s own `kandra_migrations` init) runs
unconditionally from **every instance at every startup**, with no leader election or LWT guard.
Concurrent DDL from N replicas racing on a rolling deploy is a known schema-disagreement risk in
Cassandra/Scylla — the library already solved exactly this problem for versioned migrations (LWT
claim, `ISS-043`) but didn't extend that pattern to the initial bootstrap path.

## Scalability — 7 / 10

Batch chunking, row caps, and token-range pagination (never `ALLOW FILTERING` implicitly) are all
solid defaults, as is DC-aware failover / speculative execution / multi-DC consistency knobs.

**Gap (`ISS-073` / GH #81):** no backpressure or admission control. `inFlightCount` exists and is
tracked (used for graceful-shutdown drain), but nothing caps it. A slow cluster plus bursty callers
means unbounded concurrent `session.execute` calls with no throttle. Combined with the missing
pool-size config above, this is the pair of knobs to have in hand before any real load test.

## Developer Friendliness — 9 / 10

The best-documented internal codebase reviewed in this category recently. Every non-obvious
decision has a KDoc explaining *why*, not just what — exception messages tell you the exact next
action (e.g. `findActive()`'s `ALLOW FILTERING` guidance, the migration checksum-mismatch recovery
path). 1000+ line README, 11 topic docs under `docs/features/`, and a 60+-entry issue tracker with
status and reasoning is unusually transparent for pre-1.0.

**Gap (`ISS-076` / GH #84):** test coverage is uneven across modules: `kandra-runtime` is
thoroughly tested (24 files) but `kandra-ktor` (2 files) and `kandra-migrate` (2 files) are thin
relative to how much configuration surface / crash-safety logic they carry — and the SSL path has
zero tests, directly related to `ISS-070` above.

**Minor, not filed as a GH issue (too small to block anything):** `KandraValidationException`
lives in `io.kandra.core` while every other exception lives in `io.kandra.core.exception` —
inconsistent package placement. Retry exhaustion wraps the real driver exception in a generic
`KandraQueryException` (as `cause`) — reasonable, but the "unwrap `cause` for typed handling"
pattern isn't documented anywhere found in this pass.

---

## Pre-multi-DC-testing checklist

All items below are now tracked as GitHub issues with matching `docs/issues/ISS-0NN` entries,
following the project's existing convention.

| Priority | Issue | GH | Area | Title |
|---|---|---|---|---|
| **P0** | ISS-070 | [#78](https://github.com/Dev-Pasaka/kandra/issues/78) | Security | `SslConfig` fields (`requireEncryption`/`minimumTlsVersion`/`cipherSuites`) declared but never applied |
| **P0** | ISS-071 | [#79](https://github.com/Dev-Pasaka/kandra/issues/79) | Multi-instance safety | Schema DDL bootstrap has no coordination guard across concurrently-starting instances |
| **P1** | ISS-072 | [#80](https://github.com/Dev-Pasaka/kandra/issues/80) | Scalability | Connection-pool size (local/remote) has no Kandra-level configuration |
| **P1** | ISS-073 | [#81](https://github.com/Dev-Pasaka/kandra/issues/81) | Scalability | No backpressure/admission-control knob for in-flight requests |
| **P1** | ISS-074 | [#82](https://github.com/Dev-Pasaka/kandra/issues/82) | Observability | `KandraMetrics.record()` only ever called on the success path |
| **P2** | ISS-075 | [#83](https://github.com/Dev-Pasaka/kandra/issues/83) | Consistency | Strict Mode never checks RF vs. (R+W) — only LOCAL_ONE/ONE usage |
| **P2** | ISS-076 | [#84](https://github.com/Dev-Pasaka/kandra/issues/84) | Testing | `kandra-ktor`/`kandra-migrate` test coverage thin relative to risk surface |

**P0** — fix before anything touches TLS or runs multiple instances against the cluster.
**P1** — fix before load/scale testing against the multi-DC topology.
**P2** — fix before broader rollout beyond initial experimental testing.

Also filed prior to this pass and still open, relevant to the same testing phase:

| Issue | GH | Title |
|---|---|---|
| ISS-059 | [#60](https://github.com/Dev-Pasaka/kandra/issues/60) | `KandraBatchScope`'s statement collection still blocks the coroutine dispatcher on cache-miss prepare |

---

## Method

This review read the actual implementation (not just prior audit docs) across `kandra-core`,
`kandra-runtime`, `kandra-ktor`, `kandra-migrate`, and `kandra-multidc`: config surfaces
(`KandraConfig`, `PoolConfig`, `SslConfig`, `RetryConfig`, `ConsistencyConfig`), the read/write hot
paths (`QueryExecutor`, `BatchEngine`, `StatementBuilder`), the migration crash-safety logic
(`KandraMigrationRunner`), and cross-cutting concerns (logging, metrics, caching). Every finding
above was confirmed against the current source, not inferred from documentation or commit messages
alone — dead-config claims were verified by grepping for field usage across the whole tree, and the
DDL-bootstrap and backpressure findings were traced through the actual driver-config wiring in
`CqlSessionBuilder.kt`.
