package io.kandra.runtime.cache

import io.github.oshai.kotlinlogging.KotlinLogging
import io.kandra.core.schema.CacheResultConfig
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Thin wrapper around an optional Caffeine cache. Falls back to no-op when Caffeine is not on the classpath.
 *
 * **Per-process only:** this cache lives entirely in this JVM's heap. A write on one instance never
 * invalidates another instance's cached copy of the same row — in a horizontally-scaled or multi-DC
 * deployment, other instances keep serving their own stale entries until their own TTL expires,
 * independent of any consistency level configured for the write. See GH #100 / ISS-087.
 */
internal class KandraCache<K : Any, V : Any>(config: CacheResultConfig?) {
    private val inner: Any? = buildCache(config)

    // Resolved once at construction time — avoids per-call Method lookup overhead
    private val getIfPresentMethod: Method? = inner?.let { resolveMethod(it, "getIfPresent", Any::class.java) }
    private val putMethod: Method? = inner?.let { resolveMethod(it, "put", Any::class.java, Any::class.java) }
    private val invalidateMethod: Method? = inner?.let { resolveMethod(it, "invalidate", Any::class.java) }

    // ── GH #100 / ISS-087: invalidate-after-write race guard ──────────────────────────────
    //
    // Classic cache-aside race: thread A's findById misses the cache and begins its DB read
    // (observing the OLD value) while thread B's write commits the NEW value and invalidates
    // (a no-op, since A hasn't cached anything yet); A's read then completes and calls put(old
    // value) *after* B's invalidate already ran. Without a guard, the cache would be left
    // holding a stale entry with nothing left to evict it until TTL expiry.
    //
    // Fix: remember, per key, the most recent invalidate() timestamp. put() takes the caller's
    // read-start timestamp (captured via readStamp() *before* the DB read began) and refuses to
    // cache a value if the key was invalidated after that read started -- such a value may
    // already be stale. Bounded by opportunistically sweeping entries older than the cache's own
    // TTL: a put() arriving that late would produce an entry that expires almost immediately
    // anyway, so forgetting the invalidation past that point is safe.
    private val ttlNanos: Long = ((config?.ttlSeconds ?: 60).toLong()) * 1_000_000_000L
    private val invalidationSweepThreshold: Long = ((config?.maxSize ?: 1000)) * 2
    private val lastInvalidatedAt = ConcurrentHashMap<K, Long>()

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

    /** A monotonic stamp to capture (via [readStamp]) before starting a read whose result will conditionally [put]. */
    fun readStamp(): Long = System.nanoTime()

    /**
     * Caches [value] under [key]. If [readStartedAt] (from [readStamp], captured before the read that
     * produced [value] began) is older than the most recent [invalidate] call for this key, the read
     * may have raced a concurrent write and observed a now-superseded value — the put is silently
     * skipped instead of pinning a stale entry. Pass `null` (the default) to cache unconditionally,
     * e.g. for a write's own post-commit cache population, where there's no read-vs-write race to guard.
     */
    fun put(key: K, value: V, readStartedAt: Long? = null) {
        if (readStartedAt != null) {
            val invalidatedAt = lastInvalidatedAt[key]
            if (invalidatedAt != null && invalidatedAt >= readStartedAt) return
        }
        putMethod?.invoke(inner, key, value)
    }

    fun invalidate(key: K) {
        if (inner != null) {
            lastInvalidatedAt[key] = System.nanoTime()
            if (lastInvalidatedAt.size > invalidationSweepThreshold) sweepStaleInvalidationStamps()
        }
        invalidateMethod?.invoke(inner, key)
    }

    private fun sweepStaleInvalidationStamps() {
        val cutoff = System.nanoTime() - ttlNanos
        lastInvalidatedAt.entries.removeIf { it.value < cutoff }
    }

    val isEnabled: Boolean get() = inner != null
}
