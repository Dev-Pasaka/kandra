package io.kandra.core

/**
 * Callback interface invoked for connection, authentication, and EVENTUAL write events.
 *
 * Wire it in `io.kandra.ktor.KandraConfig.eventListener` to route failures to alerting,
 * metrics, or a dead-letter queue.
 *
 * Existing implementations that only override [onEventualWriteFailed] continue to compile —
 * all other methods have no-op defaults.
 *
 * ```kotlin
 * install(Kandra) {
 *     eventListener = object : KandraEventListener {
 *         override fun onEventualWriteFailed(tableName, entity, error) {
 *             deadLetterQueue.publish(FailedLookupWrite(tableName, entity))
 *         }
 *         override fun onAuthFailed(contactPoint, error) {
 *             alerting.critical("ScyllaDB auth failed on $contactPoint", error)
 *         }
 *     }
 * }
 * ```
 */
@ExperimentalKandraApi
interface KandraEventListener {

    /**
     * Called after logging when a fire-and-forget lookup insert fails.
     * Must not throw — any exception thrown here is silently swallowed.
     */
    fun onEventualWriteFailed(tableName: String, entity: Any, error: Throwable)

    /** Called when authentication to a contact point fails. */
    fun onAuthFailed(contactPoint: String, error: Throwable) {}

    /** Called when a new CQL session connection is successfully established. */
    fun onConnectionEstablished(contactPoint: String) {}

    /** Called after each successful credential rotation (when `refreshIntervalSeconds` is set). */
    fun onCredentialRefreshed() {}

    /** Called when a TLS handshake fails. */
    fun onSslHandshakeFailed(contactPoint: String, error: Throwable) {}
}
