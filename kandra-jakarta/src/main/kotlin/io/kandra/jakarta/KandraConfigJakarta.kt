package io.kandra.jakarta

import io.github.oshai.kotlinlogging.KotlinLogging
import io.kandra.ktor.KandraConfig
import kotlin.reflect.KClass

private val logger = KotlinLogging.logger {}

/**
 * Registers a [JakartaKandraValidator] for [T] with `install(Kandra) { ... }`, running its
 * Jakarta Bean Validation constraints before every save/update.
 *
 * ```kotlin
 * install(Kandra) {
 *     register(User::class)
 *     validateJakarta<User>()
 * }
 * ```
 *
 * Logs a WARN and skips registration if no Bean Validation provider (e.g. Hibernate Validator)
 * is found on the runtime classpath, rather than failing plugin install.
 */
inline fun <reified T : Any> KandraConfig.validateJakarta(): Unit = validateJakarta(T::class)

fun <T : Any> KandraConfig.validateJakarta(klass: KClass<T>) {
    if (!KandraJakartaSupport.isAvailable) {
        logger.warn {
            "validateJakarta<${klass.simpleName}>() called but no Jakarta Bean Validation provider " +
            "is on the classpath — add e.g. org.hibernate.validator:hibernate-validator. Skipping registration."
        }
        return
    }
    validate(klass, JakartaKandraValidator())
}
