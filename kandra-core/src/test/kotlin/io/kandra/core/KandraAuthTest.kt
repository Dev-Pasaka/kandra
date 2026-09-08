package io.kandra.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class KandraAuthTest {

    @Test
    fun `toString does not contain the raw password`() {
        val creds = KandraCredentials(username = "scylla_user", password = "super-secret-p4ssw0rd")

        val rendered = creds.toString()

        assertFalse(
            rendered.contains("super-secret-p4ssw0rd"),
            "toString() must not leak the plaintext password, but was: $rendered"
        )
    }

    @Test
    fun `toString shows the username and a redaction marker`() {
        val creds = KandraCredentials(username = "scylla_user", password = "super-secret-p4ssw0rd")

        val rendered = creds.toString()

        assertTrue(rendered.contains("username=scylla_user"), "expected username in toString(): $rendered")
        assertTrue(rendered.contains("***"), "expected redaction marker in toString(): $rendered")
        assertFalse(rendered.contains("password=super-secret-p4ssw0rd"))
    }

    @Test
    fun `equals still compares password for legitimate equality checks`() {
        val a = KandraCredentials(username = "u", password = "p1")
        val b = KandraCredentials(username = "u", password = "p1")
        val c = KandraCredentials(username = "u", password = "different")

        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `hashCode is consistent with equals`() {
        val a = KandraCredentials(username = "u", password = "p1")
        val b = KandraCredentials(username = "u", password = "p1")

        assertEquals(a.hashCode(), b.hashCode())
    }
}
