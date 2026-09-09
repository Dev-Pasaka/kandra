package io.kandra.runtime.cache

import io.kandra.core.schema.CacheResultConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * GH #100 / ISS-087: a `findById` cache-aside `put` that started reading before a concurrent
 * write's `invalidate()` must not clobber the cache with the (possibly stale) value it read --
 * otherwise the stale entry sits pinned until TTL expiry with nothing left to evict it.
 *
 * [KandraCache.readStamp]/[KandraCache.put]'s `readStartedAt` parameter guard against exactly
 * this: a `put` is silently skipped if the key was invalidated after the read that produced its
 * value began.
 */
class KandraCacheInvalidateRaceTest {

    private fun cache() = KandraCache<String, String>(CacheResultConfig(ttlSeconds = 60, maxSize = 1000))

    @Test
    fun `put unconditionally caches when no readStartedAt is given`() {
        val cache = cache()
        cache.put("k", "v")
        assertEquals("v", cache.getIfPresent("k"))
    }

    @Test
    fun `put is skipped when the key was invalidated after the read started -- the race`() {
        val cache = cache()
        val readStamp = cache.readStamp()
        // Simulates a concurrent write committing and invalidating *after* our read began but
        // *before* our (already in-flight, now-stale) read result reaches put().
        cache.invalidate("k")

        cache.put("k", "stale-value", readStamp)

        assertNull(cache.getIfPresent("k"), "a read that started before a concurrent invalidate must not be cached")
    }

    @Test
    fun `put succeeds when no invalidate happened after the read started`() {
        val cache = cache()
        val readStamp = cache.readStamp()

        cache.put("k", "fresh-value", readStamp)

        assertEquals("fresh-value", cache.getIfPresent("k"))
    }

    @Test
    fun `a fresh read after the invalidate can cache normally`() {
        val cache = cache()
        val staleReadStamp = cache.readStamp()
        cache.invalidate("k")
        cache.put("k", "stale-value", staleReadStamp)
        assertNull(cache.getIfPresent("k"))

        // A brand new read, started after the invalidate, is unaffected.
        val freshReadStamp = cache.readStamp()
        cache.put("k", "fresh-value", freshReadStamp)

        assertEquals("fresh-value", cache.getIfPresent("k"))
    }
}
