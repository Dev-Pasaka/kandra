# ISS-076: `kandra-ktor` (SSL/pool/failover) and `kandra-migrate` have thin test coverage relative to their risk surface

**Status:** Open

## Problem

Filed as GH #84.

Filed from a critical library-wide review (security/performance/consistency/scalability/developer
experience) done ahead of experimental multi-cluster DC testing.

Test-file counts by module (main-source LOC in parens):

- `kandra-runtime`: 24 test files (~8,485 LOC) — thorough
- `kandra-ktor`: 2 test files (~1,754 LOC) — thin, and carries the entire SSL/mTLS,
  connection-pool, failover, speculative-execution, and health-check config surface
- `kandra-migrate`: 2 test files (~1,049 LOC) — thin, and carries the crash-safety-critical
  claim/staleness/checksum logic
- `kandra-core`: 4 test files (~2,013 LOC) — thin relative to schema validation logic

Concretely, `SslConfig`/`buildSslContext` (see `ISS-070`) has **zero** test coverage, which is very
likely why that dead-field bug went uncaught.

**Impact:** before multi-DC cluster testing, the modules carrying the most cluster-topology-
sensitive logic (SSL, failover, pool config) are the ones with the least test coverage — the
inverse of where coverage should be concentrated given what's about to be exercised for the first
time against a real cluster.

## Suggested fix direction

At minimum, add coverage for:
- `SslConfig`/`buildSslContext` (see `ISS-070`) — including a real self-signed-cert round trip if
  feasible.
- `LoadBalancingConfig`/`FailoverConfig`/`buildDriverConfig`'s DC-failover wiring beyond the
  existing config-validation tests, ideally exercised against a multi-node Testcontainers topology.
- `KandraMigrationRunner`'s claim/staleness paths under genuine concurrent claim races (not just
  sequential calls).

**Files:** `kandra-ktor/src/test`, `kandra-migrate/src/test`, `kandra-core/src/test`.
