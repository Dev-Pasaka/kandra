# ISS-062: `KandraMigration.checksum()`'s bytecode hash risked false-positive startup failures after cosmetic recompilation

**Status:** Fixed

## Problem

`kandra-migrate/src/main/kotlin/io/kandra/migrate/KandraMigration.kt`:

```kotlin
internal fun checksum(): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    digest.update("${version}:${name}:${this::class.qualifiedName}".toByteArray())
    val resourceName = "/" + this::class.java.name.replace('.', '/') + ".class"
    this::class.java.getResourceAsStream(resourceName)?.use { digest.update(it.readBytes()) }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
```

This hashed `version`/`name`/qualified class name plus the migration class's own compiled
`.class` bytecode — a deliberate fix (ISS-017) to catch behavioral edits to `up()` regardless of
source formatting. Its own writeup called it "an approximation" but only evaluated the
false-negative direction (a harmless source reformat doesn't trip it). The false-positive
direction wasn't evaluated: a JDK version bump, a Kotlin compiler upgrade, or a changed compiler
flag (debug info, line-number tables, target bytecode version) can alter a migration class's
compiled bytecode byte-for-byte even when the source of `up()` is completely unchanged.

## Impact

`KandraMigrationRunner.run()` throws `KandraMigrationException` when `checksum() != existing.checksum`
for an already-applied migration, at application startup. A purely cosmetic toolchain upgrade —
bumping the Kotlin or JDK version used to build the deployable artifact, changing build flags, or
building with a different compiler patch version — could flip a stored checksum's match to a
mismatch for a migration whose logic never changed, bricking every future deployment against any
environment where that migration already ran, until an operator manually inspected and patched
the stored checksum row.

## Fix

Added `BytecodeNormalizer` (`kandra-migrate/src/main/kotlin/io/kandra/migrate/BytecodeNormalizer.kt`),
a minimal, dependency-free, read-only walk of the JVM class file format (JVM Spec §4) that strips
exactly the toolchain-noise byte ranges before hashing:

- the classfile's `minor_version`/`major_version` field (flips with `-jvmTarget`)
- `LineNumberTable`, `LocalVariableTable`, `LocalVariableTypeTable` (debug info, nested inside
  every method's `Code` attribute)
- `SourceFile`, `SourceDebugExtension`

`checksum()` now hashes `BytecodeNormalizer.normalize(bytes)` instead of the raw `.class` bytes.
Any parse failure (an unrecognized/future classfile shape) falls back to hashing the raw,
unmodified bytes, so normalization can only reduce false positives, never widen what counts as
"unchanged". No ASM (or other bytecode-parsing library) existed anywhere in the codebase, so this
avoids adding a new dependency for a single call site — consistent with this codebase's existing
preference for lighter-weight fixes (e.g. `DebugConfig.rawQueryStrictMode` in `kandra-runtime`).

This remains an approximation, not a semantic diff — a large enough compiler upgrade (e.g. one
that changes the embedded `@kotlin.Metadata` version fields, or constant-pool layout beyond
debug/version data) can still, rarely, flip the checksum. `KandraMigration.checksum()`'s KDoc now
documents the residual risk and a recovery path: after confirming (by diffing source, not just
re-reading it) that a reported mismatch is exactly this kind of false positive, an operator can
update the stored `kandra_migrations.checksum` row directly (`UPDATE kandra_migrations SET
checksum = ? WHERE version = ?`) rather than hand-guessing or editing the migration.

`KandraMigrationRunner.kt` was intentionally left untouched — throwing vs. warning on a mismatch
is its call, and it's owned by a concurrent fix for GH #64 in the same module. This fix instead
reduces how often a mismatch is a false positive in the first place, which is fully containable
in `KandraMigration.kt`/`BytecodeNormalizer.kt`.

## Tests

New `BytecodeNormalizerTest` (`kandra-migrate/src/test/kotlin/io/kandra/migrate/BytecodeNormalizerTest.kt`),
exercised against this module's own real, already-compiled bytecode (a literal recompile can't be
simulated inside a single test JVM run):

- `excludedRanges()` locates the version field and a real debug attribute in an actual compiled class.
- Corrupting a byte **inside** an excluded range leaves the normalized bytes — and the resulting
  SHA-256 checksum — unchanged (stands in for "cosmetic recompilation noise no longer trips the checksum").
- Corrupting a byte **outside** every excluded range (including within `Code`'s actual
  instructions) still changes the normalized output (stands in for "a genuine behavioral edit
  still trips the checksum").
- `normalize()` falls back to the raw bytes on unparseable input.
- Two migrations with different `up()` bodies still produce different `checksum()` values end-to-end.

`./gradlew :kandra-migrate:test --no-daemon` — all tests pass (14 total: 6 new
`BytecodeNormalizerTest` + 8 existing `KandraMigrationRunnerTest`, unchanged, real ScyllaDB via
`KandraTestcontainers`).

## Files

`kandra-migrate/src/main/kotlin/io/kandra/migrate/KandraMigration.kt`,
`kandra-migrate/src/main/kotlin/io/kandra/migrate/BytecodeNormalizer.kt` (new),
`kandra-migrate/src/test/kotlin/io/kandra/migrate/BytecodeNormalizerTest.kt` (new).
