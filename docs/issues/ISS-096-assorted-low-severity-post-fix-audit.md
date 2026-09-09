# ISS-096: Assorted low-severity findings from the 2026-09-09 post-fix critical audit

**Status:** Open

## Problem

Filed as GH #109.

Assorted low-severity findings from a post-fix critical audit (2026-09-09), grouped per the existing convention (see #69/ISS-069):

**1. `classDecl.containingFile!!` is an unguarded NPE crash path in codegen.** (`kandra-codegen/src/main/kotlin/io/kandra/codegen/KandraProcessor.kt`, three call sites) `containingFile` is null for a `@ScyllaTable`-annotated declaration with no attached source in the current KSP round (e.g. surfaced only from a compiled binary dependency). Low probability in ordinary single-module use, but a real, unhandled crash path with no Kandra context.

**2. Jakarta support-probe builds a redundant, throwaway `ValidatorFactory`.** (`kandra-jakarta/src/main/kotlin/io/kandra/jakarta/JakartaKandraValidator.kt`) `KandraJakartaSupport.isAvailable` calls `Validation.buildDefaultValidatorFactory().close()` purely to probe availability; the real `sharedValidatorFactory` then builds a second, separate factory on first use — classpath scanning + constraint metadata resolution happens twice on cold start. Not a correctness bug, pure one-time startup cost.

**3. No codegen test coverage for illegal/edge entity shapes.** (`kandra-codegen/src/test/kotlin/io/kandra/codegen/`) The #37/ISS-046 content-assertion test suite is strong for the happy path but has no test for `@ScyllaTable` on an `object`/`interface`/`enum class` (see the sibling file-collision/non-data-class issue), two entities with colliding simple names in the same package, or an entity with zero `@PartitionKey` properties.

**4. Cancellation/interruption during retry backoff is not recorded as a metrics failure.** (`kandra-runtime/src/main/kotlin/io/kandra/runtime/BatchEngine.kt`, `executeWithRetry`/`executeWithRetrySuspend`) The `delay(backoff)`/`Thread.sleep(backoff)` calls sit inside the `catch` block; if cancellation/interruption occurs during that sleep rather than during `execute`/`executeSuspend`, the resulting exception propagates past every `recordFailure` call site. `inFlightCount` is still correctly decremented, so this isn't a leak — just an observability gap. Flagged as plausible, not independently reproduced under live coroutine cancellation.

**5. RF metadata lookup (`StatementBuilder.replicationFactorOrNull`) is uncached per statement while Strict Mode is enabled.** No network I/O involved (in-memory driver metadata), so the per-call cost is likely small, but it's on the hot path for every read/write for as long as Strict Mode stays on — worth confirming under real load rather than assuming it's free, especially for teams planning to run Strict Mode continuously rather than as a one-time diagnostic.

**6. Strict Mode's RF check pairs a resolved consistency override with the *global default* for the other side.** (`StatementBuilder.resolveWriteConsistency`/`resolveReadConsistency`) Already documented as a known trade-off in the code's own KDoc; restated here since it means a caller using strong per-call overrides on both read and write paths for the same logical entity may still see (or fail to see) warnings based on the *other* operation's global default rather than its actual override. Informational, not a required fix.

## Impact

Low — none of these are blocking for experimental testing, but worth tracking so they don't get lost.

## Suggested fix

See each item above; most are small, targeted code or documentation changes.

## Files

`kandra-codegen/src/main/kotlin/io/kandra/codegen/KandraProcessor.kt`, `kandra-jakarta/src/main/kotlin/io/kandra/jakarta/JakartaKandraValidator.kt`, `kandra-runtime/src/main/kotlin/io/kandra/runtime/BatchEngine.kt`, `kandra-runtime/src/main/kotlin/io/kandra/runtime/StatementBuilder.kt`

Filed from a critical post-fix audit (2026-09-09) ahead of experimental multi-DC cluster testing.
