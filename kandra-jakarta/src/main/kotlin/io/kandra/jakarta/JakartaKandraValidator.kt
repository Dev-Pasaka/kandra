package io.kandra.jakarta

import io.kandra.core.KandraValidationError
import io.kandra.core.KandraValidator
import jakarta.validation.Validation
import jakarta.validation.Validator

/**
 * Adapts a [jakarta.validation.Validator] into a [KandraValidator], running Jakarta Bean
 * Validation constraints (`@NotNull`, `@Size`, etc.) declared on an entity's fields before
 * every save/update.
 *
 * Requires a Bean Validation implementation (e.g. Hibernate Validator) on the runtime
 * classpath — `jakarta.validation-api` is a `compileOnly` dependency of this module, so bring
 * your own implementation alongside it.
 */
class JakartaKandraValidator<T : Any>(
    private val validator: Validator = Validation.buildDefaultValidatorFactory().validator
) : KandraValidator<T> {
    override fun validate(entity: T): List<KandraValidationError> =
        validator.validate(entity).map { violation ->
            KandraValidationError(violation.propertyPath.toString(), violation.message)
        }
}

/** Detects whether a usable Jakarta Bean Validation provider is resolvable at runtime. */
object KandraJakartaSupport {
    val isAvailable: Boolean by lazy {
        try {
            Validation.buildDefaultValidatorFactory().close()
            true
        } catch (_: Throwable) {
            false
        }
    }
}
