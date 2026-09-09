# ISS-070: `SslConfig.requireEncryption`/`minimumTlsVersion`/`cipherSuites` are declared but never applied

**Status:** Fixed

## Problem

Filed as GH #78.

Filed from a critical library-wide review (security/performance/consistency/scalability/developer
experience) done ahead of experimental multi-cluster DC testing.

`SslConfig` (`kandra-ktor/src/main/kotlin/io/kandra/ktor/KandraConfig.kt:69-81`) declares three
fields that are never read anywhere else in the codebase (confirmed by grep across the whole tree):

- `requireEncryption: Boolean = true`
- `minimumTlsVersion: String = "TLSv1.2"`
- `cipherSuites: List<String>? = null`

`CqlSessionBuilder.kt`'s `buildSslContext`/`buildDriverConfig` only ever reads `ssl.enabled`,
`ssl.hostnameVerification`, and the trust/key store fields. The three fields above have zero effect
on the actual `SSLContext`/driver config that gets built.

This is the same class of bug as the already-fixed `ISS-068`/GH #69
(`PoolConfig.localRequestsPerConnection`), except security-relevant: a team that sets
`minimumTlsVersion = "TLSv1.3"` or pins `cipherSuites` for compliance reasons believes the
connection is hardened and it is not.

Compounding factor: there is currently **zero test coverage** for the SSL path at all
(`kandra-ktor` has 2 test files total, neither touches `SslConfig`/`buildSslContext`) — which is
exactly why this wasn't caught the way ISS-068 was.

## Suggested fix direction

- Wire `minimumTlsVersion` into the `SSLContext`/`SSLParameters` (e.g. via
  `SSLEngine.setSSLParameters` through a custom `SslEngineFactory`, since
  `CqlSession.builder().withSslContext(...)` alone doesn't expose per-connection `SSLParameters`).
- Wire `cipherSuites` similarly.
- Either enforce `requireEncryption` (e.g. refuse to build a session with `ssl.enabled = false`
  when `requireEncryption = true` is set explicitly) or remove the field if it's redundant with
  `enabled`.
- Add a test suite exercising `SslConfig`/`buildSslContext` end to end (see `ISS-076`).

**Files:** `kandra-ktor/src/main/kotlin/io/kandra/ktor/KandraConfig.kt`,
`kandra-ktor/src/main/kotlin/io/kandra/ktor/CqlSessionBuilder.kt`.

## Fix

Added `KandraSslEngineFactory` (`CqlSessionBuilder.kt`), a custom
`com.datastax.oss.driver.api.core.ssl.SslEngineFactory` that replaces the driver's own
`ProgrammaticSslEngineFactory` (what `CqlSession.builder().withSslContext(sslContext)` wraps things
into internally, with no cipher-suite restriction and no hostname validation regardless of driver
config). `buildCqlSession` now calls `builder.withSslEngineFactory(KandraSslEngineFactory(...))`
instead of `withSslContext(...)` whenever `ssl.enabled = true`. On every `SSLEngine` the driver
creates, this factory:

- Restricts `enabledProtocols` to the intersection of the JVM's supported protocols and everything
  at or above `minimumTlsVersion` (ascending order `TLSv1` < `TLSv1.1` < `TLSv1.2` < `TLSv1.3`).
- Sets `enabledCipherSuites` verbatim from `cipherSuites` when non-null.
- Sets `SSLParameters.endpointIdentificationAlgorithm = "HTTPS"` when `hostnameVerification = true`.

**Bonus fix, same root cause:** `ssl.hostnameVerification` was *also* silently dead before this
change, for a different reason than `minimumTlsVersion`/`cipherSuites` — `buildDriverConfig` set
`DefaultDriverOption.SSL_HOSTNAME_VALIDATION`, but that option is only ever read by the driver's own
`DefaultSslEngineFactory` (activated via the `advanced.ssl-engine-factory` config section), which
Kandra never used — `withSslContext(...)` bypasses driver-config-based SSL entirely. Hostname
verification is now applied directly inside `KandraSslEngineFactory` instead, and the
now-permanently-ineffective `SSL_HOSTNAME_VALIDATION` option was removed from `buildDriverConfig`
(left as a comment explaining why, to stop it from being silently reintroduced).

`minimumTlsVersion` is validated eagerly in `buildCqlSession` (before any connection attempt) against
the four recognized values (`TLSv1`, `TLSv1.1`, `TLSv1.2`, `TLSv1.3`) — anything else throws
`KandraSchemaException`, matching the existing "validate config before touching the network" pattern
used for keyspace/DC identifiers (GH #65) and failover config.

**`requireEncryption` — judgment call:** rather than enforcing it at its old default (`true`), which
would have made every default `KandraConfig()` (SSL is opt-in, `ssl.enabled` defaults to `false`)
throw at startup — a breaking change for every non-SSL deployment — the field's **default was changed
to `false`**, and it is now a real, enforced hard gate: `buildCqlSession` throws
`KandraSchemaException` if `ssl.requireEncryption = true` while `ssl.enabled = false`, before any
connection attempt. This makes the field meaningful (an explicit opt-in guard against SSL being
accidentally left off) without breaking any deployment that never touched the `ssl { }` block. The
field's KDoc on `SslConfig.requireEncryption` documents this explicitly.

## Tests

`kandra-ktor/src/test/kotlin/io/kandra/ktor/CqlSessionBuilderTest.kt` (new sections, no existing
tests touched):

- `KandraSslEngineFactory.protocolAtLeast` — TLS version ordering and unrecognized-string handling.
- `KandraSslEngineFactory.newSslEngine` — asserts `enabledProtocols` is restricted to the
  JVM-supported floor above `minimumTlsVersion`, `enabledCipherSuites` reflects configured
  `cipherSuites` (or is left at the JSSE default when `null`), and
  `SSLParameters.endpointIdentificationAlgorithm` is set to `"HTTPS"` only when
  `hostnameVerification = true`. Uses a real trust-all `SSLContext` and a synthetic
  `DefaultEndPoint` (no network I/O, no live cluster needed).
- `buildCqlSession` — `requireEncryption = true` + `enabled = false` throws `KandraSchemaException`;
  an unrecognized `minimumTlsVersion` throws only when `ssl.enabled = true`; both gates confirmed to
  clear (i.e. not throw `KandraSchemaException`) once satisfied, using an unreachable
  `localhost:19999` contact point (same fail-fast-without-a-live-cluster pattern as the existing
  GH #65 keyspace-identifier tests in this file).

Did not attempt a self-signed-cert Testcontainers round trip (Cassandra image + custom
`cassandra.yaml` client-encryption options + mounted keystore/truststore) — judged too large and
flaky a lift relative to the coverage gain over the direct `SSLEngine`/`SSLParameters` assertions
above, which exercise the exact same code path (`KandraSslEngineFactory.newSslEngine`) that a live
handshake would invoke. Left as a natural follow-up for `ISS-076`
(`docs/issues/ISS-076-ktor-migrate-test-coverage-gaps.md`) if a real TLS handshake assertion is
wanted later.

`./gradlew :kandra-ktor:test --no-daemon` — all tests pass (26/26 in `CqlSessionBuilderTest`, no
regressions elsewhere in the module). `./gradlew test --no-daemon` — full multi-module suite passes.
