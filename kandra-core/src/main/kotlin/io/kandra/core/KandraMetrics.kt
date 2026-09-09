package io.kandra.core

/**
 * Callback interface for recording query execution metrics.
 *
 * Implement this to bridge Kandra into any metrics backend (Micrometer, Dropwizard, etc.).
 * Wire it via [io.kandra.ktor.KandraConfig.metrics] in the Kandra Ktor plugin.
 *
 * ```kotlin
 * install(Kandra) {
 *     metrics {
 *         enabled = true
 *         recorder = KandraMetrics { table, op, durationMs ->
 *             meterRegistry.timer("kandra.query", "table", table, "operation", op)
 *                 .record(durationMs, TimeUnit.MILLISECONDS)
 *         }
 *     }
 * }
 * ```
 */
fun interface KandraMetrics {
    /**
     * Called after every *successful* query execution (both blocking and suspend paths).
     *
     * @param tableName  the primary table name the query targeted
     * @param operation  one of: "save", "update", "delete", "saveAll", "deleteAll", "batch"
     * @param durationMs wall-clock duration of the execute call in milliseconds
     */
    fun record(tableName: String, operation: String, durationMs: Long)

    /**
     * Same as [record], but also reports how many attempts the query took to succeed (a query that
     * only succeeded on its 3rd attempt still round-tripped 3 times — [record] alone can't distinguish
     * that from a query that succeeded on the first try). Defaults to delegating to [record] so existing
     * implementations (including SAM lambdas built against the single-abstract-method [record]) keep
     * compiling and behaving exactly as before; override this overload instead if you want attempt counts.
     *
     * @param attempts   total number of execution attempts made, including the successful one (always >= 1)
     */
    fun record(tableName: String, operation: String, durationMs: Long, attempts: Int) {
        record(tableName, operation, durationMs)
    }

    /**
     * Called when a query ultimately fails and no successful result was ever produced — covers retry
     * exhaustion, an immediate non-retryable exception (wrong type for [io.kandra.runtime.RetryConfig.retryOn],
     * or a non-idempotent statement), and rejection because Kandra is shutting down. Has a no-op default
     * so existing [KandraMetrics] implementations keep compiling unchanged — this interface is public and
     * pluggable, so adding this hook must not be a breaking change.
     *
     * @param tableName     the primary table name the query targeted
     * @param operation     same free-form operation string as [record]
     * @param durationMs    wall-clock duration from first attempt to final failure, in milliseconds
     *                      (`0` for a shutdown-rejection, which fails before any attempt is made)
     * @param attempts      total number of execution attempts made before giving up (`0` for a
     *                      shutdown-rejection that never attempted the query at all)
     * @param exceptionType the fully-qualified class name of the exception that caused the failure
     *                      (falls back to the simple name, or `"Unknown"`/`"Throwable"` if unavailable)
     */
    fun recordFailure(tableName: String, operation: String, durationMs: Long, attempts: Int, exceptionType: String) {}
}
