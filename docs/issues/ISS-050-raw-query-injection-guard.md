# ISS-050: `raw()`/`rawQuery()`'s CQL-injection guard only fired under a narrow condition and never blocked execution

**Status:** Fixed

## Problem

`QueryExecutor.raw()`/`rawSuspend()` logged a CQL-injection-risk warning only when the caller bound
**zero** parameters, and only via a narrow substring heuristic:

```kotlin
// QueryExecutor.kt (pre-fix)
fun raw(cql: String, vararg params: Any?): List<Row> {
    if (params.isEmpty() && (cql.contains("'") || cql.contains("\"="))) {
        logger.warn {
            "raw() called with no parameters but CQL contains string literals. " +
            "If any literal came from user input this is a CQL injection risk. " +
            "Use parameterised queries: raw(\"SELECT * FROM t WHERE col = ?\", value)"
        }
    }
    val rs = session.execute(session.prepare(cql).bind(*params))
    return rs.all()
}
```

(`rawSuspend` mirrored this exactly.) `rawQuery()`/`rawQuerySuspend()` had no check at all — they
executed `KandraRawQuery.cql`/`.params` unconditionally, on the unenforced assumption that
`KandraRawQuery` is always built fully parameterized.

Three concrete gaps (GitHub #32):

1. **The check only ran when `params.isEmpty()`.** Binding a single, entirely unrelated parameter
   anywhere in the call suppressed the warning for the *entire* CQL string — including any other,
   unparameterized value spliced directly into it:
   ```kotlin
   // someOtherValue is bound, but userInput is spliced in unparameterized — no warning fired.
   executor.raw("SELECT * FROM t WHERE x = ? AND y = '$userInput'", someOtherValue)
   ```
   Whether a string literal is embedded in the CQL text and whether some unrelated parameter happens
   to be bound are independent concerns; gating one on the other made the check trivially easy to
   defeat by accident.
2. **It was a heuristic string search, not a parser** — it only looked for a bare `'` or the literal
   substring `"="` anywhere in the string, so it both missed quote-less injection shapes (numeric-context
   tautologies, bare keyword injection) and could fire on any unrelated apostrophe in the CQL text.
3. **It was warn-only, unconditionally.** Even when the heuristic did fire, execution proceeded no
   matter what — there was no way to make a suspicious `raw()`/`rawQuery()` call fail closed.

## Impact

`raw()`/`rawQuery()` are the documented, public escape hatch for queries the DSL can't express. As
written, the injection guard gave a false sense of safety: it was easy to accidentally suppress (bind
any unrelated parameter anywhere in the call) and provided no way to actually prevent a dangerous call
from executing — only to log it after the fact, and only in the narrow case where no parameters were
bound at all.

## Fix

`QueryExecutor.kt` — scoped strictly to `raw`, `rawSuspend`, `rawQuery`, `rawQuerySuspend`:

1. **The literal-splice check now runs unconditionally**, independent of whether (or how many) other
   parameters are bound. It's implemented as a shared private helper,
   `checkRawInjectionRisk(cql, callerName)`, called from all four entry points before the CQL is
   prepared/executed:

   ```kotlin
   private val SUSPICIOUS_LITERAL_PATTERN = Regex("""'[^']*'|"[^"]*"\s*=""")

   private fun checkRawInjectionRisk(cql: String, callerName: String) {
       if (!SUSPICIOUS_LITERAL_PATTERN.containsMatchIn(cql)) return
       val message = "$callerName() CQL appears to contain a string literal spliced directly into the " +
           "query (independent of any other bound parameters). If any of it came from user input this " +
           "is a CQL injection risk. Use parameterised queries: raw(\"SELECT * FROM t WHERE col = ?\", value)"
       if (debugConfig.rawQueryStrictMode) {
           throw KandraQueryException(message)
       } else {
           logger.warn { message }
       }
   }
   ```

   The regex looks for an actual matched string literal (`'...'`) or a double-quoted identifier
   immediately followed by `=` (`"col"=`) — a slightly more precise successor to the old bare
   `contains("'")`/`contains("\"=")` checks, scanned across the *whole* CQL string regardless of
   `params`. This remains a heuristic, not a parser: quote-less injection shapes are still not
   detected, and it can still false-positive on CQL that legitimately embeds a fixed, non-user-supplied
   literal — the doc comment on `checkRawInjectionRisk` and on the new config flag says so explicitly,
   so absence of the warning is never treated as proof of safety.

2. **`rawQuery()`/`rawQuerySuspend()` now run the same check** against `query.cql`, closing the gap
   where `KandraRawQuery` was assumed-safe with nothing enforcing it.

3. **Added an opt-in strict mode**: `DebugConfig.rawQueryStrictMode: Boolean = false` (new field on
   the existing `kandra-runtime` `DebugConfig`, alongside `logQueries`/`logSlowQueriesMs`/`logBatches`).
   When enabled, a suspicious `raw()`/`rawSuspend()`/`rawQuery()`/`rawQuerySuspend()` call throws
   `KandraQueryException` **before** the statement is prepared or executed, instead of only logging —
   giving teams that want `raw()` to fail closed a way to do so. Default is `false`, so this is
   non-breaking for every existing caller.

   This flag required no additional plugin-side plumbing: `kandra-ktor`'s `Kandra.kt` already builds
   one `DebugConfig` (`config.debug`) and threads it into `BatchEngine`, and — since ISS-048 —
   `KandraRepository`/`KandraSuspendRepository` construct their `QueryExecutor` with
   `batchEngine.debugConfig` (not a fresh default-constructed one). So `install(Kandra) { debug {
   rawQueryStrictMode = true } }` reaches `QueryExecutor.raw`/`rawQuery`/etc. end-to-end with a
   one-field addition to `DebugConfig` and zero changes to `KandraConfig.kt`, `Kandra.kt`, or either
   repository class. This is the reason the optional strict-mode flag was implemented rather than
   left as a "consider" item — it fit the existing config-threading pattern with no new plumbing.

## Regression tests

`kandra-runtime/src/test/kotlin/io/kandra/runtime/QueryExecutorRawInjectionGuardTest.kt`, using the
existing `ScriptedCqlSession` test double (already relied on elsewhere in this module for
`prepareAsync`/suspend coverage):

- `raw()`/`rawSuspend()` now warn on an embedded literal **even when another, unrelated parameter is
  bound** — the exact false-negative the issue reported.
- `rawQuery()`/`rawQuerySuspend()` warn the same way, closing the "no check at all" gap.
- No false positive on a fully parameterized `raw()`/`rawQuery()` call.
- The bare double-quoted-identifier-`=` pattern (`"col"=`) still triggers the guard (parity with the
  pre-fix heuristic).
- With `DebugConfig.rawQueryStrictMode = true`, `raw()`/`rawQuery()`/`rawSuspend()` throw
  `KandraQueryException` instead of warning, and — asserted via `ScriptedCqlSession.executeCount` —
  the driver is never actually invoked, i.e. this genuinely fails closed rather than merely logging
  louder.
- With strict mode enabled but no suspicious literal present, the call still executes normally (no
  false-positive block).

Verified before/after: reverted just the `DebugConfig.kt`/`QueryExecutor.kt` fix (via `git stash`),
keeping the new tests in place, and confirmed the "warns even with other params bound" and
`rawQuery`/`rawQuerySuspend` tests fail against the pre-fix code (5 of a reduced 8-test run failed);
restored the fix and confirmed the full test file (12 tests) passes.

**Files:** `kandra-runtime/.../QueryExecutor.kt`, `kandra-runtime/.../DebugConfig.kt`,
`kandra-runtime/src/test/kotlin/io/kandra/runtime/QueryExecutorRawInjectionGuardTest.kt`.
