package io.kandra.core

import java.security.SecureRandom
import java.util.UUID

/**
 * Generates [UUID] values for use as primary/clustering key columns.
 *
 * Cassandra/ScyllaDB writes are upserts on partition key + clustering key — there is no separate
 * insert-vs-overwrite path. Keys derived from `Instant.now()` collide silently if two rows land in the
 * same millisecond. [timeOrdered] avoids that by using UUIDv7, whose 48-bit millisecond timestamp sits
 * in the leading bytes of the value, so plain lexicographic comparison — exactly how Cassandra's
 * `UUIDType` comparator orders a generic `uuid` column — already sorts chronologically. No dedicated
 * `timeuuid` CQL type is required for this to work.
 */
object KandraUuid {

    private val random = SecureRandom()

    /** A UUIDv7 (RFC 9562) — sortable by creation time when stored as a plain CQL `uuid` column. */
    fun timeOrdered(): UUID {
        val randomBytes = ByteArray(10)
        random.nextBytes(randomBytes)

        val timestampMs = System.currentTimeMillis() and 0xFFFFFFFFFFFFL // 48 bits
        val randA = ((randomBytes[0].toLong() and 0xFF) shl 4) or
            ((randomBytes[1].toLong() and 0xFF) ushr 4)

        val mostSigBits = (timestampMs shl 16) or
            (0x7L shl 12) or // version 7
            (randA and 0xFFFL)

        var randB = 0L
        for (i in 2 until randomBytes.size) {
            randB = (randB shl 8) or (randomBytes[i].toLong() and 0xFF)
        }
        val leastSigBits = (randB and 0x3FFFFFFFFFFFFFFFL) or (0x2L shl 62) // variant 10

        return UUID(mostSigBits, leastSigBits)
    }

    /** A UUIDv4 — no timestamp component, for ids that must not leak their creation time. */
    fun random(): UUID = UUID.randomUUID()
}
