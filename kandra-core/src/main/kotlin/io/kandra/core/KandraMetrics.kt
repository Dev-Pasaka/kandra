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
     * Called after every query execution (both blocking and suspend paths).
     *
     * @param tableName  the primary table name the query targeted
     * @param operation  one of: "save", "update", "delete", "saveAll", "deleteAll", "batch"
     * @param durationMs wall-clock duration of the execute call in milliseconds
     */
    fun record(tableName: String, operation: String, durationMs: Long)
}
