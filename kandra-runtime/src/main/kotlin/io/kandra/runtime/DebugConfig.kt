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
}
