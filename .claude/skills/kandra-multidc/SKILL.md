---
name: kandra-multidc
description: Exhaustive reference for kandra-multidc and Kandra's multi-datacenter config surface — load balancing, DC failover, speculative execution, consistency level resolution. Load when configuring multi-DC ScyllaDB deployments or tuning per-operation/per-entity consistency.
---

# Kandra Multi-DC

**Reality check first:** `kandra-multidc` is a thin module. Its entire source
(`kandra-multidc/src/main/kotlin/io/kandra/multidc/KandraMultiDc.kt`) is one `object` with **one function**.
It contributes no config DSL, no load-balancing policy implementation, no failover logic — all of that
already lives in `kandra-ktor`'s `KandraConfig` and is active whether or not you depend on `kandra-multidc`
at all. What the module actually adds is a startup-logging helper, plus the doc comment that anchors this
skill. Don't expect classes like `MultiDcPolicy` or `DcAwareRouter` — they don't exist.

## `io.kandra.multidc.KandraMultiDc` — the entire module

```kotlin
object KandraMultiDc {
    fun describe(config: KandraConfig): String
}
```

| Declaration | Kind | Notes |
|---|---|---|
| `KandraMultiDc` | `object` (singleton) | No properties, no state. Pure namespace for `describe`. |
| `describe(config: KandraConfig): String` | Function | Builds a multi-line human-readable summary of the multi-DC-relevant fields of a `KandraConfig`. Intended to be logged once at startup. |

### `describe()` — exact behavior

Built with `buildString { appendLine(...) }`. Reads (never mutates) the passed `config`:

```kotlin
fun describe(config: KandraConfig): String = buildString {
    appendLine("Kandra Multi-DC Configuration:")
    appendLine("  Local DC: ${config.localDatacenter}")
    appendLine("  Read consistency: ${config.consistency.defaultRead}")
    appendLine("  Write consistency: ${config.consistency.defaultWrite}")
    appendLine("  Serial consistency: ${config.consistency.defaultSerialConsistency}")
    appendLine("  Token-aware LB: ${config.loadBalancing.tokenAware}")
    if (config.loadBalancing.dcAwareFailover) {
        appendLine("  DC failover: enabled → ${config.loadBalancing.allowedRemoteDcs}")
    }
    if (config.speculativeExecution.enabled) {
        appendLine("  Speculative execution: ${config.speculativeExecution.delayMillis}ms delay, ${config.speculativeExecution.maxAttempts} max attempts")
    }
    appendLine("  Failover policy: ${config.failover.onLocalDcUnavailable}")
}
```

Non-obvious points from the actual implementation:

- **Six lines always print**, unconditionally: header, Local DC, Read/Write/Serial consistency, Token-aware LB
  — and the trailing **Failover policy line, always**, even when `failover.onLocalDcUnavailable` is left at
  its default `FailoverPolicy.THROW` and no DC failover was ever configured. That last line is *not* gated
  the same way the two lines above it are — a minor inconsistency worth knowing before you parse this
  output programmatically.
- **Two lines are conditional**: "DC failover" only appears if `loadBalancing.dcAwareFailover == true`;
  "Speculative execution" only appears if `speculativeExecution.enabled == true`. If both are off, the
  output is exactly 6 lines.
- It does not validate anything — despite the KDoc calling it "Validates a multi-DC configuration," the
  function body performs **no validation** (no check that `allowedRemoteDcs` is non-empty when
  `dcAwareFailover = true`, no check that DCs are reachable, no exceptions thrown). It is pure formatting.
  Treat the KDoc's "Validates" claim as aspirational/inaccurate against the actual code.
- Pure function — no I/O, no logging side effect itself. You must call your own logger with the result.

### Example

```kotlin
import io.kandra.multidc.KandraMultiDc

fun main() {
    val app = embeddedServer(Netty, port = 8080) {
        install(Kandra) {
            localDatacenter = "us-east-1"
            keyspace = "coinx"

            consistency {
                defaultRead = KandraConsistency.LOCAL_QUORUM
                defaultWrite = KandraConsistency.EACH_QUORUM
            }

            loadBalancing {
                tokenAware = true
                dcAwareFailover = true
                allowedRemoteDcs = listOf("eu-west-1", "ap-southeast-1")
            }

            failover {
                onLocalDcUnavailable = FailoverPolicy.RETRY_REMOTE_DC
            }

            speculativeExecution {
                enabled = true
                delayMillis = 100
                maxAttempts = 2
            }
        }
    }
    // Somewhere during startup, after the KandraConfig is built:
    log.info(KandraMultiDc.describe(kandraConfig))
}
```

Output for the config above:

```
Kandra Multi-DC Configuration:
  Local DC: us-east-1
  Read consistency: LOCAL_QUORUM
  Write consistency: EACH_QUORUM
  Serial consistency: LOCAL_SERIAL
  Token-aware LB: true
  DC failover: enabled → [eu-west-1, ap-southeast-1]
  Speculative execution: 100ms delay, 2 max attempts
  Failover policy: RETRY_REMOTE_DC
```

With a bare single-DC config (defaults, nothing multi-DC configured), `describe()` still prints 6 lines,
ending in `Failover policy: THROW` — because that last line isn't gated on anything being "multi-DC" at all.

## The real multi-DC config surface — it's all in `kandra-ktor`

Everything an app actually configures for multi-DC behavior is a block on `KandraConfig`
(`kandra-ktor/src/main/kotlin/io/kandra/ktor/KandraConfig.kt`), available with **just** `kandra-ktor` —
`kandra-multidc` is not required to use any of it:

```kotlin
install(Kandra) {
    localDatacenter = "us-east-1"          // top-level KandraConfig property, not a sub-block
    consistency { ... }                     // -> ConsistencyConfig
    loadBalancing { ... }                   // -> LoadBalancingConfig
    failover { ... }                        // -> FailoverConfig
    speculativeExecution { ... }            // -> SpeculativeExecutionConfig
}
```

### `LoadBalancingConfig`

```kotlin
class LoadBalancingConfig {
    var tokenAware: Boolean = true
    var dcAwareFailover: Boolean = false
    var allowedRemoteDcs: List<String> = emptyList()
    var maxRemoteNodesPerRemoteDc: Int = 1
}
```

| Property | Default | Meaning |
|---|---|---|
| `tokenAware` | `true` | Routes queries directly to the token owner, skipping a coordinator hop. Always recommended on. |
| `dcAwareFailover` | `false` | Allow the driver to fall back to replicas in remote DCs if the local DC is unavailable. |
| `allowedRemoteDcs` | `emptyList()` | Ordered priority list of DCs to fail over to. **Required** (non-empty) when `dcAwareFailover = true` — the source doesn't enforce this at the type level, so leaving it empty silently gives you nowhere to fail over to. |
| `maxRemoteNodesPerRemoteDc` | `1` | Cap on remote replicas used per remote DC during failover. |

### `FailoverPolicy` (enum) and `FailoverConfig`

```kotlin
enum class FailoverPolicy { THROW, RETRY_REMOTE_DC }

class FailoverConfig {
    var onLocalDcUnavailable: FailoverPolicy = FailoverPolicy.THROW
    var remoteRetryDelayMs: Long = 50
}
```

| Value / Property | Default | Meaning |
|---|---|---|
| `FailoverPolicy.THROW` | default policy | Throws `com.datastax.oss.driver.api.core.NoNodeAvailableException` immediately when the local DC is unavailable — no automatic cross-DC retry. |
| `FailoverPolicy.RETRY_REMOTE_DC` | — | Retries against `LoadBalancingConfig.allowedRemoteDcs`, in the order given. |
| `FailoverConfig.remoteRetryDelayMs` | `50` | Delay before the remote-DC retry fires. |

`FailoverPolicy` and `dcAwareFailover`/`allowedRemoteDcs` are two separate knobs that must be set together:
`dcAwareFailover = true` + `allowedRemoteDcs` on `LoadBalancingConfig` makes remote replicas *visible* to
the driver; `failover.onLocalDcUnavailable = RETRY_REMOTE_DC` is what actually triggers a retry into them
when the local DC goes down. Setting one without the other leaves failover half-configured.

### `SpeculativeExecutionConfig`

```kotlin
class SpeculativeExecutionConfig {
    var enabled: Boolean = false
    var delayMillis: Long = 100
    var maxAttempts: Int = 2
}
```

Fires a second (then third, ...) request against a different replica if the first hasn't returned within
`delayMillis`, up to `maxAttempts` total attempts — reduces p99 tail latency on reads.

**Only idempotent statements are eligible.** Per `KandraMultiDc.kt`'s module KDoc, Kandra automatically
marks `isIdempotent = false` on plain `INSERT`, collection mutations, and counter updates — the driver
skips speculative execution for those regardless of this config. This behavior is asserted in the doc
comment, not something visible in `KandraMultiDc.kt` itself (no idempotency logic lives in this file);
it lives in the query-building code elsewhere in `kandra-runtime`/`kandra-ktor`.

### What `kandra-multidc` does NOT add

No new config block, no new annotation, no new repository method. `loadBalancing { }`, `failover { }`,
`speculativeExecution { }`, and `consistency { }` are all `KandraConfig` methods that work identically
whether or not `kandra-multidc` is on the classpath. Adding the `kandra-multidc` dependency gets you
exactly one thing: `KandraMultiDc.describe()`.

## Consistency levels — `KandraConsistency` (kandra-core)

`kandra-core/src/main/kotlin/io/kandra/core/KandraConsistency.kt`:

```kotlin
enum class KandraConsistency {
    ONE, TWO, THREE,
    QUORUM,
    ALL,
    LOCAL_ONE,
    LOCAL_QUORUM,
    EACH_QUORUM,
    LOCAL_SERIAL,
    SERIAL;

    val isSerial: Boolean get() = this == LOCAL_SERIAL || this == SERIAL
}
```

| Value | Meaning |
|---|---|
| `ONE` / `TWO` / `THREE` | Acknowledgment from exactly N replicas cluster-wide, no DC awareness. |
| `QUORUM` | Majority of replicas across **all** DCs. |
| `ALL` | Every replica cluster-wide — no availability if any replica is down. |
| `LOCAL_ONE` | Single replica in the local DC. Fastest read, weakest guarantee. **Default read consistency.** |
| `LOCAL_QUORUM` | Majority of replicas in the local DC only. **Default write consistency.** |
| `EACH_QUORUM` | Majority of replicas in **every** DC. Strongest multi-DC write guarantee. |
| `LOCAL_SERIAL` | Paxos (LWT) consensus within the local DC only. **Default serial consistency**, used by `saveIfNotExists`. |
| `SERIAL` | Paxos (LWT) consensus across **all** DCs. Required for constraints that must be globally unique. |
| `isSerial` (property) | `true` for `LOCAL_SERIAL`/`SERIAL`, `false` for everything else. Not a consistency level itself — a helper predicate. |

Every value above is confirmed present in source — no other `KandraConsistency` values exist (no `TWO_LOCAL`,
no `ANY`, etc. — don't invent them).

### `@ReadConsistency` / `@WriteConsistency` (kandra-core `Annotations.kt`)

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ReadConsistency(val level: KandraConsistency)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class WriteConsistency(val level: KandraConsistency)
```

Both target the entity **class**, not individual properties — one consistency override per table, not
per column.

```kotlin
@ReadConsistency(KandraConsistency.LOCAL_QUORUM)
@WriteConsistency(KandraConsistency.EACH_QUORUM)
@ScyllaTable("critical_balances")
data class Balance(
    @PartitionKey val walletId: UUID,
    val amount: BigDecimal
)
```

### `ConsistencyConfig` (kandra-runtime) — the plugin-level default

```kotlin
class ConsistencyConfig {
    var defaultRead: KandraConsistency = KandraConsistency.LOCAL_ONE
    var defaultWrite: KandraConsistency = KandraConsistency.LOCAL_QUORUM
    var defaultSerialConsistency: KandraConsistency = KandraConsistency.LOCAL_SERIAL
}
```

Set via `install(Kandra) { consistency { defaultRead = ...; defaultWrite = ...; defaultSerialConsistency = ... } }`
— this is the block `KandraMultiDc.describe()` reads from (`config.consistency.defaultRead` etc.).

### Resolution order (identical, stated three times in source — kandra-core, kandra-runtime, and repeated in the `kandra-multidc` module doc)

1. **Per-operation parameter** on the repository method call (e.g. `repo.save(entity, consistency = ...)`,
   `repo.saveIfNotExists(entity, serialConsistency = ...)`).
2. **`@ReadConsistency` / `@WriteConsistency`** annotation on the entity class.
3. **`ConsistencyConfig` default** (`consistency { defaultRead = ...; defaultWrite = ... }` in plugin install).

```kotlin
// Resolution in action — three different levels for the same table:
@WriteConsistency(KandraConsistency.EACH_QUORUM)   // priority 2: class-level default
@ScyllaTable("balances")
data class Balance(@PartitionKey val id: UUID, val amount: BigDecimal)

install(Kandra) {
    consistency { defaultWrite = KandraConsistency.LOCAL_QUORUM }   // priority 3: plugin default, overridden above
}

balanceRepo.save(balance)                                            // uses EACH_QUORUM (class annotation wins)
balanceRepo.save(balance, consistency = KandraConsistency.QUORUM)    // uses QUORUM (per-call wins over both)
```

## Consistency level decision guide

Verified against the actual `KandraConsistency` enum values above — the table in `README.md`'s
"Multi-Datacenter" section is accurate as far as it goes; expanded here with the serial-consistency column
it omits:

| Scenario | `defaultRead` | `defaultWrite` | `defaultSerialConsistency` |
|---|---|---|---|
| Single DC (default) | `LOCAL_ONE` | `LOCAL_QUORUM` | `LOCAL_SERIAL` |
| Multi-DC active-active | `LOCAL_QUORUM` | `EACH_QUORUM` | `LOCAL_SERIAL` (region-scoped uniqueness) or `SERIAL` (global uniqueness) |
| Strong global | `QUORUM` | `QUORUM` | `SERIAL` |

Guidance on the trade-offs, per the source doc comments:

- **Single DC**: `LOCAL_ONE`/`LOCAL_QUORUM` are the library defaults — fastest reads, majority-local writes.
  No cross-DC coordination exists, so `LOCAL_*` and cluster-wide levels behave identically; use the
  `LOCAL_*` forms for consistency with multi-DC-ready code.
- **Multi-DC active-active**: bump reads to `LOCAL_QUORUM` (avoids reading stale data from a lagging local
  replica) and writes to `EACH_QUORUM` (every DC must see the write — prevents a DC that hasn't caught up
  from serving stale reads under `LOCAL_QUORUM`). For LWT (`saveIfNotExists`), pick `LOCAL_SERIAL` when
  the uniqueness constraint is naturally scoped per-region (e.g. wallet creation validated only within
  its home DC) and `SERIAL` when it must be unique across every DC (e.g. usernames).
- **Strong global**: `QUORUM`/`QUORUM` for both — majority across the whole cluster regardless of DC,
  strongest available guarantee short of `ALL`, at the cost of cross-DC round-trip latency on every
  operation.

```kotlin
// Global username uniqueness — Paxos consensus across ALL DCs, not just local
val registered = userRepo.saveIfNotExists(user, serialConsistency = KandraConsistency.SERIAL)
```

## Gotchas worth double-checking in review

- `kandra-multidc` is not where multi-DC config lives — `loadBalancing { }`, `failover { }`,
  `speculativeExecution { }`, and `consistency { }` are `kandra-ktor` `KandraConfig` blocks, usable without
  the `kandra-multidc` dependency at all. Adding `kandra-multidc` buys you exactly `KandraMultiDc.describe()`.
- `KandraMultiDc.describe()`'s KDoc says "Validates a multi-DC configuration" — the implementation does no
  validation whatsoever. It's a pure string formatter for logging. Don't rely on it to catch a misconfigured
  `dcAwareFailover = true` with empty `allowedRemoteDcs`.
- `describe()`'s last line (`Failover policy: ...`) always prints, unlike the DC-failover and speculative-
  execution lines above it which are gated on their `enabled`/`dcAwareFailover` flags — don't assume every
  line in the output implies that feature is actively "on."
- `dcAwareFailover = true` on `LoadBalancingConfig` only makes remote DCs *visible*; you also need
  `failover { onLocalDcUnavailable = FailoverPolicy.RETRY_REMOTE_DC }` to actually retry into them. Left at
  the `THROW` default, a local-DC outage still throws `NoNodeAvailableException` even with remote DCs listed.
  `allowedRemoteDcs` left empty while `dcAwareFailover = true` is set is a similarly silent no-op — there's
  nowhere to fail over to.
- Speculative execution only helps idempotent statements — plain `INSERT`, collection mutations, and
  counter updates are forced non-idempotent by Kandra and never get a speculative retry, per the module's
  own doc comment (this logic itself is not in `KandraMultiDc.kt` — it's elsewhere in the query builder).
- `@ReadConsistency`/`@WriteConsistency` are class-level only — there's no per-column or per-query-type
  variant; if you need finer control than "reads for this whole table," use the per-call `consistency`
  parameter on the repository method instead.
- `LOCAL_SERIAL` vs `SERIAL` is the recurring footgun: `saveIfNotExists` defaults to `LOCAL_SERIAL`, which
  only guarantees uniqueness **within the local DC** — a second DC can independently accept a conflicting
  "unique" row unless you explicitly pass `serialConsistency = KandraConsistency.SERIAL`.
