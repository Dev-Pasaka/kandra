package io.kandra.core

import io.kandra.core.exception.KandraAuthException

/**
 * ScyllaDB credentials returned by a [KandraAuthProvider].
 */
data class KandraCredentials(val username: String, val password: String)

/**
 * Supplies credentials to the Kandra session builder.
 *
 * Implementations must be thread-safe — [getCredentials] may be called
 * from multiple threads during credential rotation.
 */
@ExperimentalKandraApi
fun interface KandraAuthProvider {
    fun getCredentials(): KandraCredentials
}

/**
 * Factory for standard [KandraAuthProvider] implementations.
 *
 * The recommended default for production is [fromEnv] — no credentials ever appear in code.
 */
@ExperimentalKandraApi
object KandraAuth {

    /**
     * Reads credentials from environment variables.
     *
     * This is the **recommended default for production**. Set `SCYLLA_USERNAME` and
     * `SCYLLA_PASSWORD` in your deployment environment or secrets manager.
     */
    fun fromEnv(
        usernameVar: String = "SCYLLA_USERNAME",
        passwordVar: String = "SCYLLA_PASSWORD"
    ): KandraAuthProvider = KandraAuthProvider {
        val username = System.getenv(usernameVar)
            ?: throw KandraAuthException(
                "Environment variable '$usernameVar' is not set. " +
                "Set it or configure a different KandraAuthProvider."
            )
        val password = System.getenv(passwordVar)
            ?: throw KandraAuthException(
                "Environment variable '$passwordVar' is not set."
            )
        KandraCredentials(username, password)
    }

    /**
     * Reads credentials from files (Docker secrets / Kubernetes secrets mounted as files).
     */
    fun fromFile(
        usernamePath: String,
        passwordPath: String
    ): KandraAuthProvider = KandraAuthProvider {
        try {
            val username = java.io.File(usernamePath).readText().trim()
            val password = java.io.File(passwordPath).readText().trim()
            KandraCredentials(username, password)
        } catch (e: java.io.IOException) {
            throw KandraAuthException("Failed to read credentials from file: ${e.message}", e)
        }
    }

    /**
     * Returns static credentials.
     *
     * **For local development and tests only.** Never use in production — credentials
     * will appear in source code and logs.
     */
    fun static(username: String, password: String): KandraAuthProvider =
        KandraAuthProvider { KandraCredentials(username, password) }

    /**
     * Custom provider — fetch credentials from Vault, AWS Secrets Manager, etc.
     *
     * ```kotlin
     * auth {
     *     provider = KandraAuth.custom {
     *         val secret = awsSecretsClient.getSecretValue("coinx/scylla")
     *         KandraCredentials(secret.username, secret.password)
     *     }
     *     refreshIntervalSeconds = 3600
     * }
     * ```
     */
    fun custom(provider: () -> KandraCredentials): KandraAuthProvider =
        KandraAuthProvider { provider() }
}
