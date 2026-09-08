# ISS-070: `SslConfig.requireEncryption`/`minimumTlsVersion`/`cipherSuites` are declared but never applied

**Status:** Open

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
