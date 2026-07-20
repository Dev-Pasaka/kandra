package io.kandra.core

import io.kandra.core.exception.KandraException

fun interface KandraValidator<T : Any> {
    fun validate(entity: T): List<KandraValidationError>
}

data class KandraValidationError(
    val field: String,
    val message: String
)

class KandraValidationException(
    val errors: List<KandraValidationError>
) : KandraException(
    "Validation failed: ${errors.joinToString("; ") { "${it.field}: ${it.message}" }}"
)
