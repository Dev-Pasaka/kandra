package io.kandra.runtime.cache

import io.github.oshai.kotlinlogging.KotlinLogging
import io.kandra.core.schema.CacheResultConfig
import java.lang.reflect.Method

private val logger = KotlinLogging.logger {}

/** Thin wrapper around an optional Caffeine cache. Falls back to no-op when Caffeine is not on the classpath. */
internal class KandraCache<K : Any, V : Any>(config: CacheResultConfig?) {
    private val inner: Any? = buildCache(config)

    // Resolved once at construction time — avoids per-call Method lookup overhead
    private val getIfPresentMethod: Method? = inner?.let { resolveMethod(it, "getIfPresent", Any::class.java) }
    private val putMethod: Method? = inner?.let { resolveMethod(it, "put", Any::class.java, Any::class.java) }
    private val invalidateMethod: Method? = inner?.let { resolveMethod(it, "invalidate", Any::class.java) }

    private fun buildCache(config: CacheResultConfig?): Any? {
        if (config == null) return null
        return try {
            val caffeine = Class.forName("com.github.benmanes.caffeine.cache.Caffeine")
            val builder = caffeine.getMethod("newBuilder").invoke(null)
            val builderClass = builder::class.java
            builderClass.getMethod("expireAfterWrite", Long::class.javaPrimitiveType, java.util.concurrent.TimeUnit::class.java)
                .invoke(builder, config.ttlSeconds.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            builderClass.getMethod("maximumSize", Long::class.javaPrimitiveType)
                .invoke(builder, config.maxSize)
            builderClass.getMethod("build").invoke(builder)
        } catch (_: ClassNotFoundException) {
            logger.warn { "Kandra: @CacheResult is configured but Caffeine is not on the classpath — caching disabled. Add 'com.github.ben-manes.caffeine:caffeine' to your dependencies." }
            null
        } catch (e: Exception) {
            logger.warn(e) { "Kandra: @CacheResult cache initialization failed — caching disabled." }
            null
        }
    }

    /**
     * Resolves [name] against Caffeine's public `Cache` interface, not `target`'s concrete
     * runtime class — Caffeine's cache implementations are package-private, and a [Method]
     * obtained via [Class.getMethod] on a non-public declaring class throws [IllegalAccessException]
     * on [Method.invoke] even though the method itself is public (it's declared on the public
     * `Cache` interface). Resolving against the interface keeps the declaring class public, so
     * the JVM's access check passes without needing [Method.setAccessible].
     */
    private fun resolveMethod(target: Any, name: String, vararg paramTypes: Class<*>): Method? =
        try {
            val cacheInterface = Class.forName("com.github.benmanes.caffeine.cache.Cache")
            cacheInterface.getMethod(name, *paramTypes)
        } catch (e: NoSuchMethodException) {
            logger.warn { "Kandra: could not resolve cache method '$name' — caching disabled for this operation." }
            null
        }

    @Suppress("UNCHECKED_CAST")
    fun getIfPresent(key: K): V? = getIfPresentMethod?.invoke(inner, key) as? V

    fun put(key: K, value: V) { putMethod?.invoke(inner, key, value) }

    fun invalidate(key: K) { invalidateMethod?.invoke(inner, key) }

    val isEnabled: Boolean get() = inner != null
}
