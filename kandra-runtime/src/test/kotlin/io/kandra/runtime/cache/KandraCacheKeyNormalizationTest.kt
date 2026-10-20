package io.kandra.runtime.cache

import io.kandra.core.schema.CacheResultConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * GH #178: CQL `timestamp` stores milliseconds, but `findById` used to cache under the caller-supplied
 * key (e.g. `Instant.now()` with microsecond precision) while writes invalidated under the key rebuilt
 * from the entity read back from the DB (millisecond precision). The two never compared equal, so
 * invalidation silently missed and a stale entry (old `@Version`) was served until TTL expiry.
 */
class KandraCacheKeyNormalizationTest {

    private fun cache() = KandraCache<Any, String>(CacheResultConfig(ttlSeconds = 60, maxSize = 1000))

    private val micros: Instant = Instant.parse("2026-10-20T11:07:27.733837Z")
    private val millis: Instant = micros.truncatedTo(ChronoUnit.MILLIS)

    @Test
    fun `invalidating with the millisecond key evicts an entry cached under the microsecond key`() {
        val cache = cache()
        val owner = UUID.randomUUID()
        cache.put(listOf(owner, micros), "v1")

        cache.invalidate(listOf(owner, millis))

        assertNull(cache.getIfPresent(listOf(owner, micros)))
    }

    @Test
    fun `lookups with micro and milli keys address the same entry`() {
        val cache = cache()
        cache.put(micros, "v1")
        assertEquals("v1", cache.getIfPresent(millis))
        assertEquals("v1", cache.getIfPresent(micros))
    }

    @Test
    fun `the invalidate race guard also treats micro and milli keys as one key`() {
        val cache = cache()
        val stamp = cache.readStamp()
        cache.invalidate(millis)
        cache.put(micros, "stale", stamp)
        assertNull(cache.getIfPresent(micros))
    }

    @Test
    fun `non-temporal keys are untouched`() {
        val cache = cache()
        val id = UUID.randomUUID()
        cache.put(listOf(id, "a", 1L), "v")
        assertEquals("v", cache.getIfPresent(listOf(id, "a", 1L)))
        assertNull(cache.getIfPresent(listOf(id, "b", 1L)))
    }
}
