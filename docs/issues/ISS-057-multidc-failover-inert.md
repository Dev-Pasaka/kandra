# ISS-057: Multi-DC failover and load-balancing configuration is validated and documented, but never wired into the driver — a complete no-op

**Status:** Fixed (GH #58, PR #75)

## Problem

Filed as GH #58.

Filed from a pre-cluster-testing deep review; independently identified by two separate review passes
(performance and distributed-systems), then confirmed directly against source.

`LoadBalancingConfig.tokenAware`, `.dcAwareFailover`, `.allowedRemoteDcs`
(`kandra-ktor/.../KandraConfig.kt:84-97`) and `FailoverConfig.onLocalDcUnavailable =
FailoverPolicy.RETRY_REMOTE_DC` are documented as real resilience features — `KandraMultiDc.kt`'s doc
block (lines 30-46) shows exactly this usage as "Token-aware load balancing with DC failover" — and are
validated at startup: `CqlSessionBuilder.kt:19-32` throws if `dcAwareFailover`/`RETRY_REMOTE_DC` is set
without a non-empty `allowedRemoteDcs`.

Grepping every consumer of these fields across `kandra-ktor`, `kandra-runtime`, and `kandra-multidc`
turns up exactly two uses, neither of which touches the driver or a request's routing/retry behavior:

1. Startup validation (throws on obviously-inconsistent config, never on valid config).
2. `Kandra.kt:183`: `config.consistency.multiDcTopology = config.loadBalancing.allowedRemoteDcs.isNotEmpty()`
   — used only to gate `StatementBuilder`'s ISS-037 Strict Mode warning log.

`CqlSessionBuilder.buildDriverConfig` (`CqlSessionBuilder.kt`) sets `CONNECTION_MAX_REQUESTS`,
`REQUEST_TIMEOUT`, `CONNECTION_CONNECT_TIMEOUT`, `HEARTBEAT_INTERVAL`, SSL options, and speculative-
execution options — it never calls `.withLoadBalancingPolicy(...)` or sets
`DefaultDriverOption.LOAD_BALANCING_POLICY_CLASS`. `FailoverPolicy.RETRY_REMOTE_DC` does not appear
anywhere outside config declaration, docs, and the startup-validation check above — there is no code
path that catches a local-DC-unavailable condition and reroutes to a remote DC. `tokenAware` is read
nowhere except the `KandraMultiDc.describe()` log string.

**Impact:** a user who configures

```kotlin
loadBalancing { dcAwareFailover = true; allowedRemoteDcs = listOf("eu-west-1", "ap-southeast-1") }
failover { onLocalDcUnavailable = FailoverPolicy.RETRY_REMOTE_DC }
```

believing they have cross-DC resilience gets byte-for-byte identical runtime behavior to never having
set any of it. On a real local-DC outage, requests fail exactly as they would with `FailoverPolicy.THROW`
(governed only by the DataStax driver's own default load-balancing policy and node ordering — not
`allowedRemoteDcs`'s configured priority order). This is worse than the feature not existing, because it
creates false confidence in a resilience posture that isn't there — and it is invisible to every unit
test (`FakeKandraSession` never simulates a DC outage or exposes DC topology at all), so it will only be
discovered the first time a real DC actually goes down in production, which is precisely the scenario
this feature exists to handle.

## Suggested fix direction

Either:
- **Implement it**: build a real `LoadBalancingPolicy` (or per-DC driver execution profiles) and wire
  it via `.withLoadBalancingPolicy(...)`/`LOAD_BALANCING_POLICY_CLASS`, with genuine catch-and-reroute
  logic keyed off `NoNodeAvailableException` / local-DC-exhausted signals, respecting
  `allowedRemoteDcs`'s priority order and `remoteRetryDelayMs`; or
- **Remove the claim**: strip `FailoverPolicy.RETRY_REMOTE_DC`/`allowedRemoteDcs`-driven failover from
  the public API and docs until implemented, keeping only the (accurate) Strict Mode consistency
  warning use of `allowedRemoteDcs` as a "multi-DC topology" signal.

Given this is exactly the kind of feature that can only be meaningfully tested on a real multi-DC
cluster, treat this as a **must-verify-or-fix-before** the planned experimental cluster testing if
multi-DC failover is in scope for that testing — testing it today would just reconfirm it's a no-op.

**Files:** `kandra-ktor/src/main/kotlin/io/kandra/ktor/CqlSessionBuilder.kt`,
`kandra-ktor/src/main/kotlin/io/kandra/ktor/KandraConfig.kt`,
`kandra-multidc/src/main/kotlin/io/kandra/multidc/KandraMultiDc.kt`.

## Resolution

Fixed in PR #75. `dcAwareFailover = true` together with `failover { onLocalDcUnavailable =
FailoverPolicy.RETRY_REMOTE_DC }` now genuinely enables the DataStax driver's own native cross-DC
failover: `CqlSessionBuilder.buildDriverConfig` sets
`advanced.load-balancing-policy.dc-failover.max-nodes-per-remote-dc` (from
`loadBalancing.maxRemoteNodesPerRemoteDc`) and forces `allow-for-local-consistency-levels = true` (since
Kandra's own consistency defaults are all `LOCAL_*`, without this the failover would be a no-op for the
vast majority of Kandra's traffic). `buildCqlSession` registers a `NodeDistanceEvaluator`
(`AllowedDcNodeDistanceEvaluator`) that restricts eligible remote DCs to exactly
`loadBalancing.allowedRemoteDcs` — the driver's dc-failover options alone would otherwise apply to every
remote DC uniformly, with no allow-list concept of their own.

Two intentional, documented limitations versus the original ask (both called out in the updated KDoc on
`LoadBalancingConfig`/`FailoverPolicy`):
- The driver has no priority-ordering mechanism across multiple allowed remote DCs — `allowedRemoteDcs`
  is an allow-list, not a strict retry order.
- `tokenAware` remains unwired: the driver's `DefaultLoadBalancingPolicy` is token-aware
  unconditionally by design, with no driver-level toggle to disable it short of swapping to a
  materially different (and not-recommended-for-production) policy class.

Verified with new unit tests (`CqlSessionBuilderTest` — `AllowedDcNodeDistanceEvaluator`'s decision
logic in isolation) and Testcontainers-backed integration tests (`KandraPluginTest`) that read the
driver's actual live execution profile config back (`session.context.config.defaultProfile`) to confirm
the options are genuinely set when both flags are on, and left at their inert defaults when only one is
— this is real coverage possible without a multi-DC cluster; true cross-DC failover behavior itself
still requires verification against a real multi-DC cluster (unchanged from the original filing).
