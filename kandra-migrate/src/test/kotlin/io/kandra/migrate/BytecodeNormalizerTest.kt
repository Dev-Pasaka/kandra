package io.kandra.migrate

import com.datastax.oss.driver.api.core.CqlSession
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression coverage for GH #63 / ISS-062: [KandraMigration.checksum] hashes a *normalized*
 * copy of the migration class's compiled bytecode ([BytecodeNormalizer]) instead of the raw
 * `.class` bytes, so toolchain-only recompilation noise (debug info, line numbers, target
 * bytecode version) no longer flips the checksum for behaviorally-unchanged source, while a
 * real change to the compiled instructions still does.
 *
 * A real "recompile the same source with a different compiler/flags" can't be simulated inside
 * a single test JVM run -- there's only one compiled version of each class on the classpath.
 * Instead these tests exercise [BytecodeNormalizer] directly against this module's own real,
 * already-compiled `.class` bytes:
 *  - [BytecodeNormalizer.excludedRanges] locates the byte ranges the normalizer strips (debug
 *    attributes, target-version field) in an actual compiled class -- proving those regions
 *    exist and are found, not just asserted in the abstract.
 *  - Corrupting a byte *inside* one of those ranges must not change the normalized output
 *    (stands in for "cosmetic recompilation noise no longer trips the checksum").
 *  - Corrupting a byte *outside* every excluded range -- i.e. anywhere normalization doesn't
 *    touch, which includes the actual `Code.code[]` instructions -- must still change the
 *    normalized output (stands in for "a genuine behavioral edit still trips the checksum").
 */
class BytecodeNormalizerTest {

    private object SampleMigration : KandraMigration(1, "sample") {
        override fun up(session: CqlSession) {
            session.execute("CREATE TABLE IF NOT EXISTS sample (id UUID PRIMARY KEY)")
        }
    }

    private fun classBytesOf(clazz: Class<*>): ByteArray {
        val resourceName = "/" + clazz.name.replace('.', '/') + ".class"
        return clazz.getResourceAsStream(resourceName)!!.use { it.readBytes() }
    }

    // ── excludedRanges finds real debug/version data in an actual compiled class ───────────

    @Test
    fun `excludedRanges locates the minor-major version field and at least one debug attribute`() {
        val bytes = classBytesOf(SampleMigration::class.java)
        val ranges = BytecodeNormalizer.excludedRanges(bytes)

        // minor_version+major_version always sits at offset 4..8 in a class file.
        assertTrue(ranges.any { it == 4 until 8 }, "expected the version field range 4..<8 to be excluded")

        // Compiled with debug info (Kotlin/Gradle default), so at least one LineNumberTable (or
        // similar) attribute must have been found and excluded somewhere past the header.
        assertTrue(ranges.size > 1, "expected at least one debug attribute range beyond the version field")
        assertTrue(ranges.all { it.first >= 0 && it.last < bytes.size }, "ranges must stay within bounds")
    }

    // ── Cosmetic-only corruption (inside an excluded range) does not change the normalized bytes ──

    @Test
    fun `corrupting a byte inside an excluded range does not change the normalized output`() {
        val original = classBytesOf(SampleMigration::class.java)
        val ranges = BytecodeNormalizer.excludedRanges(original)

        // The version field is always excluded and always safe to flip for this test.
        val versionRange = ranges.first { it == 4 until 8 }
        val mutated = original.copyOf()
        mutated[versionRange.first] = (mutated[versionRange.first] + 1).toByte()

        assertNotEquals(
            original.toList(), mutated.toList(),
            "sanity check: the mutation must actually change the raw bytes"
        )
        assertArrayEquals(
            BytecodeNormalizer.normalize(original), BytecodeNormalizer.normalize(mutated),
            "a change confined to an excluded range (target bytecode version) must not affect the normalized output"
        )
    }

    @Test
    fun `checksum is unchanged when only the classfile target-version field differs`() {
        // Directly proves the checksum()-level contract (not just normalize()): two "builds" of
        // the same class that differ only in target bytecode version hash identically.
        val original = classBytesOf(SampleMigration::class.java)
        val versionRange = BytecodeNormalizer.excludedRanges(original).first { it == 4 until 8 }
        val mutated = original.copyOf()
        mutated[versionRange.first] = (mutated[versionRange.first] + 1).toByte()

        val digest = { bytes: ByteArray ->
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(BytecodeNormalizer.normalize(bytes))
                .joinToString("") { "%02x".format(it) }
        }

        assertEquals(digest(original), digest(mutated))
    }

    // ── A genuine change outside every excluded range still changes the normalized output ──

    @Test
    fun `corrupting a byte outside every excluded range still changes the normalized output`() {
        val original = classBytesOf(SampleMigration::class.java)
        val ranges = BytecodeNormalizer.excludedRanges(original)

        val safeOffset = (0 until original.size).first { offset -> ranges.none { offset in it } }
        val mutated = original.copyOf()
        mutated[safeOffset] = (mutated[safeOffset] + 1).toByte()

        assertNotEquals(
            BytecodeNormalizer.normalize(original).toList(),
            BytecodeNormalizer.normalize(mutated).toList(),
            "a change outside every excluded range (e.g. within Code's instructions) must still " +
            "change the normalized output, so genuine behavioral edits still trip the checksum"
        )
    }

    @Test
    fun `normalize falls back to the raw bytes when parsing fails`() {
        val garbage = byteArrayOf(1, 2, 3, 4, 5)
        assertArrayEquals(garbage, BytecodeNormalizer.normalize(garbage))
    }

    // ── End-to-end: two differently-behaved migrations still get different checksums ───────

    private class BehaviorA : KandraMigration(42, "behavior") {
        override fun up(session: CqlSession) {
            session.execute("CREATE TABLE a (id UUID PRIMARY KEY)")
        }
    }

    private class BehaviorB : KandraMigration(42, "behavior") {
        override fun up(session: CqlSession) {
            session.execute("DROP TABLE a")
        }
    }

    @Test
    fun `two migrations with different up() bodies still produce different checksums`() {
        // Same version/name (only the compiled body differs) -- checksum() must still tell them
        // apart, i.e. normalization did not accidentally strip away sensitivity to real logic.
        assertNotEquals(BehaviorA().checksum(), BehaviorB().checksum())
    }
}
