package io.kandra.runtime

import com.datastax.oss.driver.api.core.NoNodeAvailableException
import com.datastax.oss.driver.api.core.servererrors.ReadTimeoutException
import com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException
import kotlin.reflect.KClass

/**
 * Retry policy for transient ScyllaDB failures.
 *
 * On a [WriteTimeoutException], [ReadTimeoutException], or [NoNodeAvailableException],
 * Kandra retries with linear backoff up to [maxAttempts] times before throwing
 * [io.kandra.core.exception.KandraQueryException].
 *
 * Add retryable exceptions via [retryOn] — only exception types in this set trigger retry.
 */
class RetryConfig {
    var maxAttempts: Int = 3
    var backoffMillis: Long = 100
    var maxBackoffMillis: Long = 2000
    var retryOn: Set<KClass<out Throwable>> = setOf(
        WriteTimeoutException::class,
        ReadTimeoutException::class,
        NoNodeAvailableException::class
    )
}
