package io.kandra.runtime.codec

import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.Counter
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.exception.KandraQueryException
import io.kandra.runtime.fakeRow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

@ScyllaTable("codec_counter_widgets")
data class CodecCounterWidget(
    @PartitionKey val id: UUID,
    @Counter val views: Long = 0,
    @Counter val likes: Long = 0
)

/**
 * GH #93 / ISS-080: an untouched counter cell reads back as NULL, not 0, at the storage layer —
 * [KandraCodec.decode] must treat a NULL counter column as `0`, mirroring Cassandra/Scylla's own
 * counter semantics, instead of throwing the generic non-nullable-NULL exception.
 */
class KandraCodecCounterDecodeTest {

    @AfterEach
    fun tearDown() {
        SchemaRegistry.clear()
    }

    @Test
    fun `NULL counter cell decodes to 0 instead of throwing`() {
        val schema = SchemaRegistry.register(CodecCounterWidget::class)
        val viewsColumn = schema.columns.first { it.propertyName == "views" }
        val likesColumn = schema.columns.first { it.propertyName == "likes" }
        // "likes" was never incremented -- absent from the row entirely, exactly how a NULL
        // counter cell is represented by the driver.
        val row = fakeRow(mapOf("views" to 5L))

        assertEquals(5L, KandraCodec.default.decode(row, viewsColumn))
        assertEquals(0L, KandraCodec.default.decode(row, likesColumn))
    }

    @Test
    fun `non-counter non-nullable NULL column still throws`() {
        val schema = SchemaRegistry.register(CodecCounterWidget::class)
        val idColumn = schema.partitionKeys.first { it.propertyName == "id" }
        val row = fakeRow(emptyMap())

        assertThrows(KandraQueryException::class.java) {
            KandraCodec.default.decode(row, idColumn)
        }
    }
}
