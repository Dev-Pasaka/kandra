# ISS-092: kandra-ktor driver-config hardening gaps (TLS silent downgrade, reverse-DNS hostname verification, unvalidated pool sizes)

**Status:** Open

## Problem

Filed as GH #105.

Three assorted hardening gaps in `kandra-ktor`'s driver-config wiring, found during a post-fix audit of the recent SSL/pool fixes (#78/#80):

**1. `minimumTlsVersion` silently downgrades to the JVM's unrestricted defaults when unsupported.** In `CqlSessionBuilder.kt`'s `KandraSslEngineFactory.newSslEngine`, if the intersection of supported protocols and "at least `minimumTlsVersion`" is empty (e.g. `minimumTlsVersion = "TLSv1.3"` configured against a JVM/provider that only supports up to TLSv1.2), `engine.enabledProtocols` is left completely untouched — i.e., whatever the JSSE provider's own default set is, which can include weaker protocols than the configured floor was meant to exclude. Nothing is logged when this happens. This is a silent security-posture downgrade in exactly the scenario a compliance-motivated `minimumTlsVersion` setting exists to guard against. Not covered by any test (`CqlSessionBuilderTest` only exercises `minimumTlsVersion = "TLSv1.2"`, universally supported).

**2. Hostname verification triggers a blocking reverse-DNS lookup for IP-only gossip-discovered peers.** `KandraSslEngineFactory.newSslEngine` calls `sslContext.createSSLEngine(remoteAddress.hostName, remoteAddress.port)`. `InetSocketAddress.getHostName()` performs a reverse-DNS lookup when the address was constructed from a raw IP — exactly how the driver builds `EndPoint`s for peers discovered via `system.peers`/gossip (no hostname, just the IP). The driver's own `DefaultSslEngineFactory` uses `getHostString()` instead (no reverse lookup). Consequences: (a) blocking reverse-DNS calls on the driver's I/O threads for every new connection to a newly-discovered peer; (b) if reverse DNS isn't configured for cluster nodes (very common on cloud/multi-DC clusters), `getHostName()` falls back to the IP string, which then fails `endpointIdentificationAlgorithm = "HTTPS"` verification against the server cert's SANs unless the cert carries IP SANs for every node — most DNS-named certs don't have these. No test exercises a resolved, IP-only `EndPoint` (the shape a real discovered peer actually has); `SslRoundTripIntegrationTest` explicitly disables hostname verification, sidestepping this exact risk.

**3. `PoolConfig.localPoolSize`/`remotePoolSize` accept 0 or negative values with no validation.** Nothing rejects `localPoolSize = 0` (or negative). A pool size of 0 to local-DC nodes means the driver opens no connections to serve local traffic — every request against the local DC would fail once the session is live, but this doesn't surface at config-build time; it surfaces later as request-level `NoNodeAvailableException`, disguising a config typo as a cluster-health problem.

## Impact

Medium — each is a real hardening gap but requires a specific misconfiguration or environment (unsupported TLS floor, no reverse DNS, typo'd pool size) to actually bite; none is an outright break for a default/common configuration.

## Suggested fix

- #1: when the intersection is empty, throw (fail closed, matching `requireEncryption`'s own philosophy) or at minimum log a WARN naming the requested vs. actually-supported protocols.
- #2: use `remoteAddress.hostString` instead of `.hostName` (matches the driver's own convention); add a test using a resolved IP-only `InetSocketAddress`.
- #3: validate `localPoolSize >= 1` and `remotePoolSize >= 1` (or document `0` as a legitimate "never connect to remote DC" signal if that's intended) at the same point `dcAwareFailover`/`requireEncryption` are validated in `buildCqlSession`.

## Files

`kandra-ktor/src/main/kotlin/io/kandra/ktor/CqlSessionBuilder.kt`, `kandra-ktor/src/main/kotlin/io/kandra/ktor/KandraConfig.kt`

Filed from a critical post-fix audit (2026-09-09) ahead of experimental multi-DC cluster testing, re-reviewing the brand-new #78/#80 fixes.
