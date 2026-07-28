# ISS-046: `kandra-codegen`'s test suite never asserted on generated file content

**Status:** Fixed

## Category

Test coverage.

## Problem

Filed as the `kandra-codegen` half of GH #37. The module's entire job is generating correct Kotlin
source (`*Table` objects, `*KoinDi.kt`/`*KodeinDi.kt` accessors), but its tests only checked that a
file with the expected name was produced (e.g. `assertNotNull(generatedFileNamed("UserTable.kt"))`).
None of `resolveTypeName`'s generic handling, `resolveCqlName`'s blank-name fallback, or the
`isLookup` flag threading was verified against the actual generated source text — meaning bugs found
elsewhere in this same audit batch (the nullability-dropping gap tracked separately as part of GH #36,
and the blank-`@Column`-divergence bug fixed in [ISS-044](ISS-044-schema-registry-validation-gaps.md))
would not have been caught by this module's own test suite even though both are squarely in what it's
supposed to verify.

## Fix

Added `KandraProcessorTableContentTest`, using the same kotlin-compile-testing harness (KSP2,
real processor invocation) as the module's existing `KandraProcessorDiAccessorsTest`, asserting on the
actual generated `*Table.kt` source text against a fixture entity:

- Non-nullable simple type → exact `KandraColumnRef<kotlin.String>("id")` line.
- Nullable property → asserts it currently generates the **identical** form as non-nullable (the
  nullability gap is real and still open — tracked under GH #36, not fixed here — this test documents
  current behavior and is the regression guard that will need updating once that lands).
- Generic collections (`List<String>`, `Set<Int>`, `Map<String, Int>`) → exact recursively-resolved
  parameterized type strings.
- Explicit `@Column("custom_col")` → exact `cqlName` in the generated line.
- Blank `@Column("")` → falls back to `camelToSnake` of the property name — the regression test for
  the [ISS-044](ISS-044-schema-registry-validation-gaps.md) fix, confirming `resolveCqlName` now goes
  through the shared `CqlNaming.resolveColumnName` correctly.
- `@LookupIndex` column → asserts `isLookup = true` is threaded into the generated ref, plus a
  negative assertion that a plain column does not carry it.

## Files

- `kandra-codegen/src/test/kotlin/io/kandra/codegen/KandraProcessorTableContentTest.kt` (new)
