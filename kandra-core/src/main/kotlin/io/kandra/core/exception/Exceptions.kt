
package io.kandra.core.exception

import kotlin.reflect.KClass

open class KandraException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class KandraSchemaException(message: String) : KandraException(message)

class KandraQueryException(message: String, cause: Throwable? = null) : KandraException(message, cause)

/**
 * Thrown when the driver's backpressure throttle (`throttle.enabled = true`) rejects a request
 * because `maxQueueSize` is exceeded. Wraps the driver's raw
 * `com.datastax.oss.driver.api.core.RequestThrottlingException` so callers catching
 * Kandra's documented exception hierarchy see this instead of an undocumented DataStax type. Not a
 * transient network fault — retrying it immediately would just add another request on top of an
 * already-overloaded throttler, so this is never a member of `RetryConfig.retryOn`.
 */
class KandraThrottledException(message: String, cause: Throwable? = null) : KandraException(message, cause)

class KandraAuthException(message: String, cause: Throwable? = null) : KandraException(message, cause)

class KandraOptimisticLockException(
    message: String,
    val entityClass: KClass<*>,
    val partitionKey: Any
) : KandraException(message)

class KandraMigrationException(message: String, cause: Throwable? = null) : KandraException(message, cause)
