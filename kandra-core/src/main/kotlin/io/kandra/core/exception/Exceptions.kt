
package io.kandra.core.exception

import kotlin.reflect.KClass

open class KandraException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class KandraSchemaException(message: String) : KandraException(message)

class KandraQueryException(message: String, cause: Throwable? = null) : KandraException(message, cause)

class KandraAuthException(message: String, cause: Throwable? = null) : KandraException(message, cause)

class KandraOptimisticLockException(
    message: String,
    val entityClass: KClass<*>,
    val partitionKey: Any
) : KandraException(message)

class KandraMigrationException(message: String, cause: Throwable? = null) : KandraException(message, cause)
