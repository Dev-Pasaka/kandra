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
 *
 * The default [Validator] comes from [sharedValidatorFactory] — a single lazily-built,
 * process-wide `ValidatorFactory` shared by every default-constructed instance (GH-36). Kandra's
 * `validate<T> { }` config block registers one `JakartaKandraValidator` per entity type, and
 * `Validation.buildDefaultValidatorFactory()` is relatively expensive (classpath scanning +
 * constraint metadata resolution) and returns a `Closeable` resource — building one per entity
 * type instead of sharing a single factory meant an app validating N entity types built and
 * leaked N separate factories. `Validator` instances obtained from a `ValidatorFactory` are
 * documented as thread-safe and stateless, so sharing one across every entity's validator is safe.
 */
class JakartaKandraValidator<T : Any>(
    private val validator: Validator = sharedValidatorFactory.validator
) : KandraValidator<T> {
    override fun validate(entity: T): List<KandraValidationError> =
        validator.validate(entity).map { violation ->
            KandraValidationError(violation.propertyPath.toString(), violation.message)
        }

    companion object {
        /**
         * One `ValidatorFactory` for the whole process, built on first use and never explicitly
         * closed — same lifetime as the JVM, which is the standard way to use Bean Validation's
         * default factory (closing it would invalidate every [Validator] handed out from it,
         * including ones other [JakartaKandraValidator] instances are still holding).
         */
        internal val sharedValidatorFactory by lazy { Validation.buildDefaultValidatorFactory() }
    }
}

/** Detects whether a usable Jakarta Bean Validation provider is resolvable at runtime. */
object KandraJakartaSupport {
    /**
     * GH-109 item 2: this used to probe availability by building and immediately closing its own
     * throwaway `ValidatorFactory` via `Validation.buildDefaultValidatorFactory().close()`. Since
     * [JakartaKandraValidator.sharedValidatorFactory] then builds a second, separate factory on
     * first real use, classpath scanning + constraint metadata resolution happened twice on cold
     * start. Probing through the shared factory instead means whichever of [isAvailable] or
     * [JakartaKandraValidator.sharedValidatorFactory] is touched first does the one-time build, and
     * the other reuses it.
     */
    val isAvailable: Boolean by lazy {
        try {
            JakartaKandraValidator.sharedValidatorFactory
            true
        } catch (_: Throwable) {
            false
        }
    }
}
