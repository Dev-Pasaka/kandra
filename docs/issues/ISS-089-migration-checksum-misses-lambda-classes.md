# ISS-089: Migration checksum only hashes the migration's own class file, missing sibling lambda/anonymous class files

**Status:** Open

## Problem

Filed as GH #102.

`KandraMigration.checksum()` (`kandra-migrate/src/main/kotlin/io/kandra/migrate/KandraMigration.kt`) reads exactly one `.class` resource — the migration object's own file (`this::class.java.name.replace('.', '/') + ".class"`). Its own KDoc claims the checksum covers "the body of `up` (or any anonymous/lambda class it captures as a top-level member)."

Any lambda inside `up()` that Kotlin compiles to a separate synthetic class file (any non-`inline` higher-order function call — e.g. `Runnable {}`, `CompletableFuture.thenApply {}`, a `Comparator`, an executor callback, anything that isn't one of the `inline` stdlib scope functions) lives in a different `.class` file (e.g. `V1_CreateUsers$up$1.class`) that `checksum()` never reads. Editing only the body of such a lambda after the migration has been applied leaves the outer class's bytecode byte-identical (the call site just does `new V1_CreateUsers$up$1()`), so the checksum is unaffected — a real behavior change goes undetected by the one safety net whose entire purpose is to catch this (see #63/ISS-062, which hardened the checksum against cosmetic-only false positives but didn't address this false-negative gap).

## Impact

High — undermines the crash-safety/change-detection guarantee the checksum exists to provide, specifically for a category of migration body that's easy to write without realizing it triggers separate class-file compilation.

## Suggested fix

Enumerate and hash every `.class` resource matching `${OuterClassSimpleName}$*.class` under the same package (or more precisely, every class file reachable from the compiled unit), not just the migration's own top-level file — or explicitly narrow the KDoc's claim to match current behavior and document the gap as a known limitation, the way `BytecodeNormalizer`'s residual limitations already are.

## Files

`kandra-migrate/src/main/kotlin/io/kandra/migrate/KandraMigration.kt`

Filed from a critical post-fix audit (2026-09-09) ahead of experimental multi-DC cluster testing. Related: #63 (ISS-062).
