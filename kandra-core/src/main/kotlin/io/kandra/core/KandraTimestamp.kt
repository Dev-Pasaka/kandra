package io.kandra.core

import java.time.Instant

/**
 * Utilities for generating explicit write timestamps in microseconds.
 *
 * Use [KandraTimestamp] when processing events out of order — passing an explicit
 * timestamp ensures last-event-wins conflict resolution instead of last-server-write-wins.
 *
 * ```kotlin
 * // Idempotent webhook handler — use event timestamp, not server time
 * walletRepo.save(wallet, timestampMicros = KandraTimestamp.fromInstant(event.occurredAt))
 * ```
 */
object KandraTimestamp {
    /** Current time in microseconds (Scylla's write timestamp unit). */
    fun now(): Long = System.currentTimeMillis() * 1000L

    /** Converts an [Instant] to microseconds for use as a write timestamp. */
    fun fromInstant(instant: Instant): Long = instant.toEpochMilli() * 1000L
}
