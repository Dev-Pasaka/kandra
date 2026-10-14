package io.kandra.core

import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.annotations.SoftDelete
import io.kandra.core.exception.KandraSchemaException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/**
 * Regression coverage for GH #153/#154 (ISS-097/ISS-098): the `@SoftDelete` marker column's TTL
 * resolution, and the registration-time guard against non-nullable, defaultless columns that
 * would throw on decode during the husk window (after `ttlSeconds`, before the marker's own TTL).
 */
class SchemaRegistrySoftDeleteTtlTest {

    @AfterEach
    fun tearDown() {
        SchemaRegistry.clear()
    }

    @ScyllaTable("sd_default_marker_ttl")
    @SoftDelete(ttlSeconds = 100, markerProperty = "deleted")
    private data class DefaultMarkerTtl(
        @PartitionKey val id: UUID,
        val deleted: Boolean = false,
        val note: String? = null
    )

    @Test
    fun `marker TTL defaults to ttlSeconds + gcGraceSeconds when left unset`() {
        val schema = SchemaRegistry.register(DefaultMarkerTtl::class)

        // gcGraceSeconds on @ScyllaTable was left at its own default (-1 -- "use cluster default"),
        // so this should fall back to Cassandra/Scylla's real default, 864000s (10 days).
        assertEquals(100, schema.softDeleteTtlSeconds)
        assertEquals(100 + 864_000, schema.softDeleteMarkerTtlSeconds)
    }

    @ScyllaTable("sd_explicit_marker_ttl", gcGraceSeconds = 3600)
    @SoftDelete(ttlSeconds = 100, markerProperty = "deleted", markerTtlSeconds = 500)
    private data class ExplicitMarkerTtl(
        @PartitionKey val id: UUID,
        val deleted: Boolean = false
    )

    @Test
    fun `explicit markerTtlSeconds overrides the ttlSeconds + gcGraceSeconds default`() {
        val schema = SchemaRegistry.register(ExplicitMarkerTtl::class)

        assertEquals(100, schema.softDeleteTtlSeconds)
        assertEquals(500, schema.softDeleteMarkerTtlSeconds)
    }

    @ScyllaTable("sd_invalid_marker_ttl")
    @SoftDelete(ttlSeconds = 100, markerProperty = "deleted", markerTtlSeconds = 50)
    private data class InvalidMarkerTtl(
        @PartitionKey val id: UUID,
        val deleted: Boolean = false
    )

    @Test
    fun `markerTtlSeconds not greater than ttlSeconds throws at registration`() {
        val ex = assertThrows<KandraSchemaException> { SchemaRegistry.register(InvalidMarkerTtl::class) }
        assertTrue(ex.message!!.contains("strictly greater"), "Expected the ordering requirement in the message, got: ${ex.message}")
    }

    @ScyllaTable("sd_non_nullable_no_default")
    @SoftDelete(ttlSeconds = 100, markerProperty = "deleted")
    private data class NonNullableNoDefault(
        @PartitionKey val id: UUID,
        val deleted: Boolean = false,
        val title: String // non-nullable, no default -- exactly the shape GH #153 is about
    )

    @Test
    fun `non-nullable column with no default on a @SoftDelete entity throws at registration`() {
        val ex = assertThrows<KandraSchemaException> { SchemaRegistry.register(NonNullableNoDefault::class) }
        assertTrue(
            ex.message!!.contains("title") && ex.message!!.contains("must be nullable"),
            "Expected the offending property named and the nullability requirement stated, got: ${ex.message}"
        )
    }

    @ScyllaTable("sd_non_nullable_with_default")
    @SoftDelete(ttlSeconds = 100, markerProperty = "deleted")
    private data class NonNullableWithDefault(
        @PartitionKey val id: UUID,
        val deleted: Boolean = false,
        val title: String = "untitled" // non-nullable, but has a constructor default -- allowed
    )

    @Test
    fun `non-nullable column with a constructor default is allowed`() {
        // Should not throw -- a constructor default gives decode something to fall back to.
        val schema = SchemaRegistry.register(NonNullableWithDefault::class)
        assertEquals("sd_non_nullable_with_default", schema.tableName)
    }

    @ScyllaTable("sd_no_marker_nullable")
    @SoftDelete(ttlSeconds = 100)
    private data class NoMarkerNullable(
        @PartitionKey val id: UUID,
        val title: String? = null
    )

    @Test
    fun `without markerProperty, softDeleteMarkerColumn and markerTtlSeconds are both null`() {
        // No markerProperty means findActive() can't be used on this entity at all, but the data
        // columns are still written with USING TTL ttlSeconds, so the nullability guard below still
        // applies to them regardless -- this test only confirms the marker-specific fields stay null.
        val schema = SchemaRegistry.register(NoMarkerNullable::class)
        assertEquals(null, schema.softDeleteMarkerColumn)
        assertEquals(null, schema.softDeleteMarkerTtlSeconds)
    }

    @ScyllaTable("sd_no_marker_non_nullable")
    @SoftDelete(ttlSeconds = 100)
    private data class NoMarkerNonNullable(
        @PartitionKey val id: UUID,
        val title: String // non-nullable, no default -- still trips the guard with no marker at all
    )

    @Test
    fun `the nullability guard applies even without markerProperty -- data TTL expiry alone causes it`() {
        val ex = assertThrows<KandraSchemaException> { SchemaRegistry.register(NoMarkerNonNullable::class) }
        assertTrue(ex.message!!.contains("title"), "Expected the offending property named, got: ${ex.message}")
    }
}
