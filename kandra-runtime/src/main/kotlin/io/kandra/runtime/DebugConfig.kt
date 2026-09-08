package io.kandra.runtime

/**
 * Query debug and observability settings.
 *
 * Note: bound parameter values are **never** logged even when [logQueries] is enabled,
 * because they may contain PII. Only the CQL template is logged.
 */
class DebugConfig {
    /** Log every CQL statement template at DEBUG before execution. */
    var logQueries: Boolean = false

    /** Log a WARN for any query that takes longer than this many milliseconds. 0 = disabled. */
    var logSlowQueriesMs: Long = 0L

    /** Log full batch contents at DEBUG before execution. */
    var logBatches: Boolean = false

    /**
     * Fail closed instead of warning when [QueryExecutor.raw]/[QueryExecutor.rawSuspend]/
     * [QueryExecutor.rawQuery]/[QueryExecutor.rawQuerySuspend] detect a CQL string that looks like it
     * has a string literal spliced directly into it (see [QueryExecutor] for the exact heuristic).
     *
     * Default `false`: the heuristic only logs a WARN, matching pre-existing behavior. Set to `true`
     * for a strict/CI environment where any raw CQL that isn't fully parameterized should throw
     * [io.kandra.core.exception.KandraQueryException] instead of merely being logged.
     *
     * Note this is a heuristic, not a parser — it can neither catch every injection shape (e.g.
     * quote-less numeric-context or keyword injection) nor guarantee zero false positives on CQL that
     * legitimately embeds a literal (e.g. a fixed non-user-supplied constant). Enable with that in mind.
     *
     * The warn-only default is deliberate and non-breaking (see ISS-069 / GH #70 item 2) — any team
     * exposing `raw()`/`rawQuery()` to code that builds queries from less-trusted input should turn
     * this on rather than relying on the warn-only default.
     */
    var rawQueryStrictMode: Boolean = false
}
