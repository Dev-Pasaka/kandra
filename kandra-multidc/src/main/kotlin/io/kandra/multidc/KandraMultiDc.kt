package io.kandra.multidc

import io.kandra.core.KandraConsistency
import io.kandra.ktor.FailoverConfig
import io.kandra.ktor.FailoverPolicy
import io.kandra.ktor.KandraConfig
import io.kandra.ktor.LoadBalancingConfig
import io.kandra.ktor.SpeculativeExecutionConfig

/**
 * Multi-datacenter extension for Kandra.
 *
 * Include `kandra-multidc` in your dependencies for multi-DC features:
 * - Token-aware load balancing with DC failover
 * - Per-operation consistency level control
 * - Speculative execution for latency-sensitive reads
 * - LWT serial consistency configuration for globally unique constraints
 *
 * All multi-DC configuration lives in [KandraConfig] blocks:
 *
 * ```kotlin
 * install(Kandra) {
 *     localDatacenter = "us-east-1"
 *
 *     consistency {
 *         defaultRead = KandraConsistency.LOCAL_ONE
 *         defaultWrite = KandraConsistency.LOCAL_QUORUM
 *     }
 *
 *     loadBalancing {
 *         tokenAware = true
 *         dcAwareFailover = true
 *         allowedRemoteDcs = listOf("eu-west-1", "ap-southeast-1")
 *     }
 *
 *     failover {
 *         onLocalDcUnavailable = FailoverPolicy.RETRY_REMOTE_DC
 *         remoteRetryDelayMs = 100
 *     }
 *
 *     speculativeExecution {
 *         enabled = true
 *         delayMillis = 100
 *         maxAttempts = 2
 *     }
 * }
 * ```
 *
 * ## LWT serial consistency
 *
 * Use `LOCAL_SERIAL` (default) for Paxos within the local DC only — sufficient
 * for region-scoped uniqueness (e.g. wallet creation per-region).
 *
 * Use `SERIAL` for globally unique constraints (e.g. usernames unique across all DCs):
 *
 * ```kotlin
 * // Global uniqueness — Paxos consensus across ALL DCs
 * val registered = userRepo.saveIfNotExists(user, serialConsistency = KandraConsistency.SERIAL)
 * ```
 *
 * ## Speculative execution
 *
 * Only idempotent statements are eligible for speculative execution.
 * Kandra automatically sets `isIdempotent = false` on plain INSERT, collection
 * mutations, and counter updates — the driver skips speculative execution for them.
 */
object KandraMultiDc {

    /**
     * Validates a multi-DC configuration and returns a description of the active policies.
     * Useful for logging at startup.
     */
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
}
