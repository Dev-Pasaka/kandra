package io.kandra.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KandraUuidTest {

    @Test
    fun `timeOrdered sets version nibble to 7`() {
        val id = KandraUuid.timeOrdered()
        assertEquals(7, id.version())
    }

    @Test
    fun `timeOrdered sets IETF variant`() {
        val id = KandraUuid.timeOrdered()
        assertEquals(2, id.variant())
    }

    @Test
    fun `timeOrdered values generated in sequence sort in creation order`() {
        val ids = (1..50).map {
            Thread.sleep(1)
            KandraUuid.timeOrdered()
        }
        val sorted = ids.sorted()
        assertEquals(ids, sorted)
    }

    @Test
    fun `timeOrdered values are distinct`() {
        val ids = (1..1000).map { KandraUuid.timeOrdered() }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `two timeOrdered calls never return the same value`() {
        val a = KandraUuid.timeOrdered()
        val b = KandraUuid.timeOrdered()
        assertNotEquals(a, b)
    }

    @Test
    fun `random sets version nibble to 4`() {
        val id = KandraUuid.random()
        assertEquals(4, id.version())
    }

    @Test
    fun `random values are distinct`() {
        val ids = (1..1000).map { KandraUuid.random() }
        assertTrue(ids.toSet().size == ids.size)
    }
}
