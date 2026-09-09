# ISS-095: Multi-DC test fixture hardening (fixed compose ports, pause vs real partition realism, missing hostname-mismatch SSL test, cleanup reliance)

**Status:** Open

## Problem

Filed as GH #108.

Four hardening gaps in the brand-new multi-DC/SSL test fixture added by #84/ISS-076:

**1. Fixed host ports (9042/9043) in `multidc-docker-compose.yml` risk collisions.** Port 9042 is the canonical default Cassandra port — a developer preparing for real multi-DC ScyllaDB testing is disproportionately likely to already have a local Cassandra/Scylla instance or another concurrent test run bound to it. `ComposeContainer` fails with a raw Docker bind error ("port is already allocated"), not an actionable Kandra-authored message.

**2. Docker `pause` overstates its realism as a network-partition simulation.** `KandraMultiDcTestcontainers`'s KDoc and `MultiDcFailoverTest`'s class doc describe `pause` as making a node stop "responding on the wire entirely." Docker `pause` freezes the container's userspace processes via the cgroup freezer; it does not stop the host kernel's network stack for that container's namespace — established TCP state remains live, only the application stops producing/consuming data. This is closer to "the app hung" than "the network is gone," a different failure mode than a real link-down/partition (which typically produces immediate RSTs or ICMP unreachable, not silent hangs behind a still-live TCP stack). The tests' actual assertions (bounded retry loops) are likely tolerant of this difference, but the gap isn't disclosed anywhere, and a reader could reasonably conclude this is a faithful WAN-outage reproduction when it's closer to "node hung."

**3. The SSL round-trip test doesn't verify hostname-mismatch rejection.** `hostnameVerification = false` is hardcoded in `SslRoundTripIntegrationTest` (for a documented, legitimate Docker-networking reason), which means there is no test anywhere — unit or integration — proving a certificate for the wrong hostname is actually rejected end-to-end. `CqlSessionBuilderTest`'s unit coverage only confirms the config flag is wired to `HTTPS` endpoint identification, not that a real mismatched-cert handshake fails.

**4. Cleanup relies entirely on `@AfterEach`/`try-finally`; no JVM-level fallback is documented.** If the JVM itself dies between `pause()` and either cleanup path (a crash, OOM, `System.exit`, build-daemon kill), `dc1` is left frozen with nothing left alive to unpause it. Testcontainers' Ryuk resource-reaper will eventually force-remove orphaned containers on JVM exit, mitigating "paused forever," but this reliance is implicit rather than stated.

## Impact

Medium — these are quality/trust gaps in test infrastructure rather than production-code bugs, but a shaky fixture giving false confidence right before real multi-DC testing is exactly the kind of risk worth closing before relying on it.

## Suggested fix

- #1: preflight-check port availability with a clear error before invoking Compose, or explicitly document the tradeoff as an operational risk in the fixture's KDoc.
- #2: soften the KDoc's claim ("simulates an unresponsive/hung node, not a severed network link — no RST or ICMP unreachable is produced").
- #3: add a follow-up test issuing a cert for `CN=wrong-host` and asserting the handshake fails.
- #4: add a comment acknowledging the crash-between-pause-and-cleanup window and that Ryuk is the actual backstop.

## Files

`kandra-test/src/main/resources/multidc-docker-compose.yml`, `kandra-test/src/main/kotlin/io/kandra/test/KandraMultiDcTestcontainers.kt`, `kandra-multidc/src/test/kotlin/io/kandra/multidc/MultiDcFailoverTest.kt`, `kandra-ktor/src/test/kotlin/io/kandra/ktor/SslRoundTripIntegrationTest.kt`

Filed from a critical post-fix audit (2026-09-09) ahead of experimental multi-DC cluster testing, re-reviewing the brand-new #84 fix.
