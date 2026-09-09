package io.kandra.migrate

import com.datastax.oss.driver.api.core.CqlSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Regression coverage for GH #102 / ISS-089: [KandraMigration.checksum] hashed only the
 * migration class's own `.class` resource. Any anonymous/nested class Kotlin compiles to a
 * separate synthetic class file alongside it -- an anonymous object expression, a local class,
 * or similar -- lives in its own top-level `.class` file (`Outer$up$action$1.class`) that the
 * outer class's bytecode references only by a `new Outer$up$action$1()` call site, never by
 * content. Editing only that class's body left the outer class byte-identical, so the pre-fix
 * checksum (single own-file hash) never noticed. (Note: a plain `{ }`/SAM-literal lambda, e.g.
 * `Runnable { ... }`, no longer produces a separate class file at all on Kotlin 2.x -- it
 * compiles via `invokedynamic`, with the body inlined as a synthetic method *inside* the outer
 * class, so it was never actually affected by this bug. [LambdaMigration] below deliberately
 * uses an anonymous object expression instead, which still does compile to a real sibling file.)
 *
 * As with [BytecodeNormalizerTest] (see its own KDoc), a real "edit the source and recompile"
 * can't happen inside a single test JVM run -- there's only one compiled version of each class
 * on the classpath. Instead this directly mutates the *sibling* class's already-compiled bytes
 * on disk (the same technique [BytecodeNormalizerTest] uses on a class's own bytes), leaving the
 * outer class file completely untouched, and calls the real, unmodified [KandraMigration.checksum]
 * again -- it re-reads from disk every call, so this is a faithful stand-in for "someone edited
 * the nested class's body and recompiled".
 */
class KandraMigrationChecksumSiblingClassesTest {

    /**
     * A migration whose [up] body creates an anonymous `Runnable` -- compiles to a sibling
     * `.class` file. Written as an object expression (`object : Runnable { ... }`), not a `{ }`
     * SAM-literal lambda: Kotlin 2.x compiles ordinary lambdas (including SAM-literal ones like
     * `Runnable { ... }`) via `invokedynamic` by default, so they no longer produce a separate
     * class file at all -- it's specifically an anonymous *object expression* (or any other
     * genuine anonymous/local/nested class Kotlin can't reduce to an `invokedynamic` call site)
     * that still does, which is exactly the "any anonymous/lambda class it captures as a
     * top-level member" case [KandraMigration.checksum]'s KDoc already called out before this
     * fix, and precisely the shape GH #102/ISS-089 is about.
     */
    private object LambdaMigration : KandraMigration(1, "lambda-migration") {
        override fun up(session: CqlSession) {
            val action = object : Runnable {
                override fun run() {
                    session.execute("CREATE TABLE lambda_test (id UUID PRIMARY KEY)")
                }
            }
            action.run()
        }
    }

    /** A migration with no nested/anonymous class at all -- should have exactly one compiled `.class` file. */
    private object PlainMigration : KandraMigration(2, "plain-migration") {
        override fun up(session: CqlSession) {
            session.execute("CREATE TABLE plain_test (id UUID PRIMARY KEY)")
        }
    }

    /**
     * Every `.class` [File] on disk belonging to [clazz]: its own file, plus any sibling whose
     * name (minus `.class`) starts with the owner's name followed by `$`. Deliberately
     * independent of [KandraMigration]'s own (private) discovery logic -- this exists purely to
     * locate real files on disk to mutate, mirroring [BytecodeNormalizerTest]'s `classBytesOf`.
     */
    private fun compiledClassFilesOnDisk(clazz: Class<*>): List<File> {
        val root = File(clazz.protectionDomain.codeSource.location.toURI())
        val packagePath = clazz.name.substringBeforeLast('.', "").replace('.', '/')
        // The binary simple name (e.g. "KandraMigrationChecksumSiblingClassesTest$LambdaMigration"
        // for a nested object) -- NOT java.lang.Class.getSimpleName(), which strips the enclosing
        // class prefix and wouldn't match the actual file name on disk.
        val binarySimpleName = clazz.name.substringAfterLast('.')
        val packageDir = if (packagePath.isEmpty()) root else File(root, packagePath)
        return packageDir.listFiles { f -> f.isFile && f.name.endsWith(".class") }
            ?.filter { it.name == "$binarySimpleName.class" || it.name.startsWith("$binarySimpleName$") }
            ?: emptyList()
    }

    private fun binarySimpleNameOf(clazz: Class<*>): String = clazz.name.substringAfterLast('.')

    // ── Sibling discovery: a migration with an anonymous class compiles to more than one file ──

    @Test
    fun `a migration with an anonymous object expression in up() compiles to more than one class file`() {
        val files = compiledClassFilesOnDisk(LambdaMigration::class.java)
        assertTrue(files.size > 1, "expected the anonymous object expression to produce at least one sibling .class file, found: $files")
        assertTrue(
            files.any { it.name != "${binarySimpleNameOf(LambdaMigration::class.java)}.class" },
            "expected at least one file other than the migration's own top-level class"
        )
    }

    @Test
    fun `a migration with no anonymous or nested class in up() compiles to exactly its own class file`() {
        val files = compiledClassFilesOnDisk(PlainMigration::class.java)
        assertEquals(1, files.size, "a migration with no anonymous/nested class shouldn't have sibling class files: $files")
    }

    // ── The core regression: editing only the sibling class must change checksum() ──

    @Test
    fun `checksum() changes when only the sibling anonymous class file is edited`() {
        val files = compiledClassFilesOnDisk(LambdaMigration::class.java)
        val ownFile = files.first { it.name == "${binarySimpleNameOf(LambdaMigration::class.java)}.class" }
        val siblingFile = files.first { it != ownFile }

        val ownBytesBefore = ownFile.readBytes()
        val siblingBytesBefore = siblingFile.readBytes()
        val checksumBefore = LambdaMigration.checksum()

        // Find a byte outside every BytecodeNormalizer-excluded range, so this is guaranteed to
        // be a "real" change and not cosmetic debug-info noise the normalizer would strip anyway
        // (mirrors BytecodeNormalizerTest's "outside every excluded range" technique).
        val excluded = BytecodeNormalizer.excludedRanges(siblingBytesBefore)
        val safeOffset = (0 until siblingBytesBefore.size).first { offset -> excluded.none { offset in it } }
        val mutatedSibling = siblingBytesBefore.copyOf()
        mutatedSibling[safeOffset] = (mutatedSibling[safeOffset] + 1).toByte()

        try {
            siblingFile.writeBytes(mutatedSibling)

            // Sanity check on the exact bug this issue describes: the outer class's own bytes
            // are completely untouched by editing only the nested class -- this is precisely why
            // the pre-fix single-file checksum missed it.
            assertEquals(ownBytesBefore.toList(), ownFile.readBytes().toList())

            val checksumAfter = LambdaMigration.checksum()
            assertNotEquals(
                checksumBefore, checksumAfter,
                "editing only the sibling anonymous class must change checksum() even though " +
                "the migration's own outer class file is byte-identical"
            )
        } finally {
            siblingFile.writeBytes(siblingBytesBefore)
        }

        // Restored to the original bytes -- checksum() must be deterministic and match again.
        assertEquals(checksumBefore, LambdaMigration.checksum())
    }

    @Test
    fun `checksum() is stable across repeated calls`() {
        assertEquals(LambdaMigration.checksum(), LambdaMigration.checksum())
        assertEquals(PlainMigration.checksum(), PlainMigration.checksum())
    }
}
