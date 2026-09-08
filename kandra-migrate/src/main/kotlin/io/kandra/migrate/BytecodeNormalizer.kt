package io.kandra.migrate

import java.io.ByteArrayOutputStream

/**
 * Strips toolchain-dependent, behaviorally-irrelevant bytes from a compiled `.class` file before
 * [KandraMigration.checksum] hashes it (GH #63 / ISS-062).
 *
 * A recompilation of *unchanged* Kotlin source can still change the emitted `.class` bytes:
 *  - The classfile's `minor_version`/`major_version` fields flip with the `-jvmTarget` compiler
 *    flag (e.g. a JDK/toolchain bump).
 *  - `LineNumberTable`, `LocalVariableTable` and `LocalVariableTypeTable` attributes (nested
 *    inside every method's `Code` attribute) encode debug info that shifts with unrelated edits
 *    elsewhere in the file, or disappears entirely with `-g:none`.
 *  - `SourceFile` and `SourceDebugExtension` attributes record the source file name / line
 *    mapping, not behavior.
 *
 * None of those describe what `up()` actually *does*. [normalize] removes exactly those byte
 * ranges so [KandraMigration.checksum] is insensitive to them, while remaining sensitive to any
 * other byte in the file -- including the actual instructions in `Code`'s `code[]` array, the
 * constant pool, method/field signatures, and annotations (e.g. `@kotlin.Metadata`).
 *
 * This is a deliberately minimal, read-only walk of the JVM class file format (JVM Spec §4) --
 * just enough of it to resolve constant-pool UTF8 entries (to identify attributes by name) and
 * enumerate class/field/method/`Code` attributes. It does not rewrite the file into a new valid
 * class file; it only records which byte ranges of the *original* file to exclude before
 * hashing. It intentionally is not a general-purpose class file library (no ASM/bytecode-parsing
 * dependency exists elsewhere in this codebase, and adding one for this alone was judged not
 * worth it -- see ISS-062).
 *
 * **This remains an approximation, not a semantic diff.** It cannot see through, for example, a
 * Kotlin compiler upgrade that changes the embedded `@kotlin.Metadata` version fields, or
 * constant-pool layout differences a different compiler introduces beyond debug/version data.
 * Any parse failure -- including an unrecognized/future classfile shape -- falls back to hashing
 * the raw, unmodified bytes, so normalization can only ever reduce false positives, never
 * silently widen what counts as "unchanged". See [KandraMigration.checksum] for the documented
 * operator recovery path when a mismatch is a false positive that survives normalization anyway.
 */
internal object BytecodeNormalizer {

    private val STRIPPED_ATTRIBUTES = setOf(
        "LineNumberTable",
        "LocalVariableTable",
        "LocalVariableTypeTable",
        "SourceFile",
        "SourceDebugExtension"
    )

    /**
     * Returns [bytes] with the toolchain-noise ranges from [excludedRanges] removed, or [bytes]
     * unchanged if it can't be parsed as a class file.
     */
    fun normalize(bytes: ByteArray): ByteArray {
        val ranges = runCatching { excludedRanges(bytes) }.getOrNull() ?: return bytes
        val out = ByteArrayOutputStream(bytes.size)
        var cursor = 0
        for (range in ranges) {
            if (range.first > cursor) out.write(bytes, cursor, range.first - cursor)
            cursor = maxOf(cursor, range.last + 1)
        }
        if (cursor < bytes.size) out.write(bytes, cursor, bytes.size - cursor)
        return out.toByteArray()
    }

    /**
     * Parses [bytes] as a class file and returns the sorted, non-overlapping byte ranges
     * (inclusive `first`, inclusive `last`) that [normalize] excludes: the `minor_version`/
     * `major_version` fields, plus every [STRIPPED_ATTRIBUTES] occurrence at class, field,
     * method, and (recursively) `Code` attribute level. Exposed internally so tests can assert
     * directly against known-safe "inside an excluded range" vs. "outside every excluded range"
     * byte offsets without duplicating this parser.
     *
     * Throws on anything that doesn't parse as expected -- callers (namely [normalize]) treat
     * that as "give up, hash the raw bytes" rather than risk excluding the wrong bytes.
     */
    internal fun excludedRanges(bytes: ByteArray): List<IntRange> {
        val cursor = Cursor(bytes)
        val excluded = mutableListOf<IntRange>()

        cursor.skip(4) // magic (0xCAFEBABE) -- not validated, a parse failure below is enough
        val versionStart = cursor.pos
        cursor.skip(2) // minor_version
        cursor.skip(2) // major_version
        excluded += versionStart until cursor.pos

        val utf8ByIndex = readConstantPool(cursor)

        cursor.skip(2) // access_flags
        cursor.skip(2) // this_class
        cursor.skip(2) // super_class
        val interfaceCount = cursor.u2()
        cursor.skip(2 * interfaceCount)

        val fieldsCount = cursor.u2()
        repeat(fieldsCount) {
            cursor.skip(2); cursor.skip(2); cursor.skip(2) // access_flags, name_index, descriptor_index
            walkAttributes(cursor, utf8ByIndex, excluded)
        }

        val methodsCount = cursor.u2()
        repeat(methodsCount) {
            cursor.skip(2); cursor.skip(2); cursor.skip(2)
            walkAttributes(cursor, utf8ByIndex, excluded)
        }

        walkAttributes(cursor, utf8ByIndex, excluded) // class-level attributes (e.g. SourceFile)

        return excluded.sortedBy { it.first }
    }

    /** Reads the constant pool, resolving only Utf8 entries (needed to identify attribute names by index). */
    private fun readConstantPool(cursor: Cursor): Map<Int, String> {
        val count = cursor.u2()
        val utf8ByIndex = HashMap<Int, String>()
        var index = 1
        while (index < count) {
            when (val tag = cursor.u1()) {
                1 -> { // Utf8
                    val length = cursor.u2()
                    utf8ByIndex[index] = String(cursor.bytes(length), Charsets.UTF_8)
                }
                7, 8, 16, 19, 20 -> cursor.skip(2)       // Class, String, MethodType, Module, Package
                3, 4, 9, 10, 11, 12, 17, 18 -> cursor.skip(4) // Integer, Float, *ref, NameAndType, Dynamic, InvokeDynamic
                5, 6 -> { cursor.skip(8); index++ }      // Long, Double occupy two constant pool slots
                15 -> cursor.skip(3)                     // MethodHandle
                else -> error("unrecognized constant pool tag $tag at index $index")
            }
            index++
        }
        return utf8ByIndex
    }

    /**
     * Walks one `attributes[]` list (a class's, a field's, or a method's), recording the full
     * byte range (6-byte header included) of any [STRIPPED_ATTRIBUTES] member into [excluded].
     * A `Code` attribute is recursed into (its own nested `attributes[]` list is where
     * `LineNumberTable`/`LocalVariableTable`/`LocalVariableTypeTable` actually live) rather than
     * skipped as an opaque blob.
     */
    private fun walkAttributes(cursor: Cursor, utf8ByIndex: Map<Int, String>, excluded: MutableList<IntRange>) {
        val count = cursor.u2()
        repeat(count) {
            val nameIndex = cursor.u2()
            val length = cursor.u4()
            val headerStart = cursor.pos - 6
            val name = utf8ByIndex[nameIndex]
            when {
                name in STRIPPED_ATTRIBUTES -> {
                    excluded += headerStart until (cursor.pos + length)
                    cursor.skip(length)
                }
                name == "Code" -> {
                    val codeEnd = cursor.pos + length
                    cursor.skip(2) // max_stack
                    cursor.skip(2) // max_locals
                    val codeLength = cursor.u4()
                    cursor.skip(codeLength) // the actual instructions -- never excluded
                    val exceptionTableLength = cursor.u2()
                    cursor.skip(exceptionTableLength * 8)
                    walkAttributes(cursor, utf8ByIndex, excluded)
                    check(cursor.pos == codeEnd) { "Code attribute length mismatch" }
                }
                else -> cursor.skip(length)
            }
        }
    }

    private class Cursor(private val bytes: ByteArray) {
        var pos = 0
            private set

        fun u1(): Int = bytes[pos++].toInt() and 0xFF
        fun u2(): Int = (u1() shl 8) or u1()
        fun u4(): Int = (u2() shl 16) or u2()
        fun skip(n: Int) {
            pos += n
        }

        fun bytes(n: Int): ByteArray {
            val slice = bytes.copyOfRange(pos, pos + n)
            pos += n
            return slice
        }
    }
}
