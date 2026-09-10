package io.kandra.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression coverage for GH #140 — `KandraConsistency.isValidForRead`/`isValidForWrite`.
 *
 * Found live: `defaultRead = EACH_QUORUM` (a write-only CQL consistency level) was accepted with no
 * validation anywhere in Kandra, so every read then failed against a real cluster with an opaque
 * driver-level exception instead of a clear Kandra one. The audit that followed found the mirror-image
 * gap on the write side too: `SERIAL`/`LOCAL_SERIAL` are only valid as a conditional write's *serial*
 * consistency (`saveIfNotExists()`/`update()`'s `serialConsistency` param), never as a table's regular
 * write consistency — Cassandra/Scylla reject both shapes server-side. These two properties are the
 * single source of truth both enforcement points (`kandra-runtime`'s `StatementBuilder.
 * resolveReadConsistency`/`resolveWriteConsistency` and `kandra-ktor`'s `Kandra` plugin install-time
 * check) validate against, so this test is the exhaustive statement of exactly which levels are valid
 * where.
 */
class KandraConsistencyTest {

    @Test
    fun `EACH_QUORUM is the only level invalid for reads`() {
        val invalid = KandraConsistency.entries.filterNot { it.isValidForRead }
        assertTrue(invalid == listOf(KandraConsistency.EACH_QUORUM), "Expected only EACH_QUORUM invalid for reads, got: $invalid")
    }

    @Test
    fun `every other level is valid for reads, including SERIAL and LOCAL_SERIAL`() {
        val valid = listOf(
            KandraConsistency.ONE, KandraConsistency.TWO, KandraConsistency.THREE,
            KandraConsistency.QUORUM, KandraConsistency.ALL,
            KandraConsistency.LOCAL_ONE, KandraConsistency.LOCAL_QUORUM,
            KandraConsistency.LOCAL_SERIAL, KandraConsistency.SERIAL
        )
        valid.forEach { level -> assertTrue(level.isValidForRead, "Expected $level to be valid for reads") }
    }

    @Test
    fun `SERIAL and LOCAL_SERIAL are the only levels invalid for writes`() {
        val invalid = KandraConsistency.entries.filterNot { it.isValidForWrite }.toSet()
        assertTrue(
            invalid == setOf(KandraConsistency.SERIAL, KandraConsistency.LOCAL_SERIAL),
            "Expected only SERIAL/LOCAL_SERIAL invalid for writes, got: $invalid"
        )
    }

    @Test
    fun `every other level is valid for writes, including EACH_QUORUM`() {
        val valid = listOf(
            KandraConsistency.ONE, KandraConsistency.TWO, KandraConsistency.THREE,
            KandraConsistency.QUORUM, KandraConsistency.ALL,
            KandraConsistency.LOCAL_ONE, KandraConsistency.LOCAL_QUORUM, KandraConsistency.EACH_QUORUM
        )
        valid.forEach { level -> assertTrue(level.isValidForWrite, "Expected $level to be valid for writes") }
    }

    @Test
    fun `isValidForRead and isValidForWrite are independent — no level is invalid for both`() {
        KandraConsistency.entries.forEach { level ->
            assertFalse(
                !level.isValidForRead && !level.isValidForWrite,
                "$level is marked invalid for both reads and writes — every level must be usable somewhere"
            )
        }
    }
}
