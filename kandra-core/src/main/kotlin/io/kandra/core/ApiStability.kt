package io.kandra.core

/**
 * Marks an internal Kandra API. It may change or be removed without notice.
 * Do not use in your own code — it is only intended for use within Kandra modules.
 */
@RequiresOptIn(
    message = "This is an internal Kandra API. It may change or be removed without notice. " +
              "Do not use in your own code.",
    level = RequiresOptIn.Level.ERROR
)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class InternalKandraApi

/**
 * Marks an experimental Kandra API that may change in future releases.
 * Opt in with `@OptIn(ExperimentalKandraApi::class)` or accept the compiler warning.
 */
@RequiresOptIn(
    message = "This Kandra API is experimental and may change in future releases.",
    level = RequiresOptIn.Level.WARNING
)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class ExperimentalKandraApi
