# ISS-091: Codegen can crash on same-simple-name nested entity classes; @ScyllaTable on a non-data-class fails late and confusingly

**Status:** Open

## Problem

Filed as GH #104.

Two gaps in `kandra-codegen`'s KSP processor (`kandra-codegen/src/main/kotlin/io/kandra/codegen/KandraProcessor.kt`):

**1. File-name collision for same-simple-name nested entity classes crashes the build.** `processClass` derives both the output package and file name purely from `classDecl.packageName.asString()` and `classDecl.simpleName.asString()`, never from the enclosing class. Two distinct `@ScyllaTable` entities nested under different outer types but declared in the same file/package (e.g. `Ns1.User` and `Ns2.User` both in `com.example.app`) both compute `objectName = "UserTable"` in package `com.example.app`, so both calls to `codeGenerator.createNewFile(...)` target the identical virtual file within the same KSP round. KSP's `CodeGenerator.createNewFile` throws `FileAlreadyExistsException` on the second call — a hard build break surfaced as a raw KSP-internal exception, not a Kandra diagnostic, with nothing in the file naming this a "duplicate simple name" problem. (Note: this is distinct from the already-fixed #35/ISS-041 DI-qualifier collision, which is keyed on `KClass.simpleName` and is nesting-agnostic — that fix does *not* generalize to this file-naming bug.)

**2. No structural validation that `@ScyllaTable` sits on a normal (data) class.** `process()`'s only shape filter is `it is KSClassDeclaration` — no check on `classKind`. Putting `@ScyllaTable` on an `object`, `interface`, `enum class`, or non-data `abstract class` is accepted silently and generates a `*Table` object referencing it. `SchemaRegistry.buildSchema` (the runtime side) doesn't catch this either — it never checks `klass.isData` or interface/enum-ness. The failure only surfaces much later and far more confusingly, inside entity-reflection code (`klass.primaryConstructor`, `copyFunction`) at the first actual save/update call, likely as an NPE or an opaque Kotlin-reflection `IllegalStateException`, never a message naming the real problem.

## Impact

High. Both are narrow, low-probability triggers, but when hit they surface as unexplained KSP/reflection crashes exactly when someone is debugging a real cluster deployment, not as a clear, actionable error at build/registration time.

## Suggested fix

- For #1: derive `objectName`/`fileName` from a nesting-qualified name (join enclosing simple names), or run a simple-name collision pre-check before calling `createNewFile` and fail with a clear `logger.error`.
- For #2: add an early `require(klass.isData) { ... }` (or equivalent classKind check) in `SchemaRegistry.buildSchema`, with a clear `KandraSchemaException`, consistent with its existing eager-validation philosophy.

## Files

`kandra-codegen/src/main/kotlin/io/kandra/codegen/KandraProcessor.kt`, `kandra-core/src/main/kotlin/io/kandra/core/SchemaRegistry.kt`

Filed from a critical post-fix audit (2026-09-09) ahead of experimental multi-DC cluster testing.
