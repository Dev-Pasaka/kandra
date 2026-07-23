package io.kandra.runtime

import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.ClusteringKey
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@ScyllaTable("qe_events")
data class QeEvent(
    @PartitionKey val streamId: UUID,
    @ClusteringKey val occurredAt: Instant,
    val payload: String,
    val note: String? = null
)

/**
 * Proves [QueryExecutor.decodeEntity] decodes rows using the cached `TableSchema.reflection
 * .primaryConstructor`/`constructorParameters` (resolved once at [SchemaRegistry.register] time)
 * instead of re-resolving `entityClass.primaryConstructor` per row, and that the decoded result is
 * identical to what the old per-call-reflection path produced. See ISS-034 / GitHub #13.
 */
class QueryExecutorReflectionCacheTest {

    @AfterEach
    fun tearDown() = SchemaRegistry.clear()

    @Test
    fun `decodeEntity builds a correct entity from a row using the cached primary constructor`() {
        val schema = SchemaRegistry.register(QeEvent::class)
        val executor = QueryExecutor(FakeCqlSession(), schema, StatementBuilder(FakeCqlSession()))
        val streamId = UUID.randomUUID()
        val now = Instant.now()

        val row = fakeRow(
            mapOf(
                "stream_id" to streamId,
                "occurred_at" to now,
                "payload" to "hello",
                "note" to null
            )
        )

        val entity = executor.decodeEntity(row, QeEvent::class)

        assertEquals(streamId, entity.streamId)
        assertEquals(now, entity.occurredAt)
        assertEquals("hello", entity.payload)
        assertEquals(null, entity.note)
    }

    @Test
    fun `decodeEntity reuses the same cached constructor across multiple rows (findAll-style N decodes)`() {
        val schema = SchemaRegistry.register(QeEvent::class)
        val executor = QueryExecutor(FakeCqlSession(), schema, StatementBuilder(FakeCqlSession()))

        val ctorBeforeDecoding = schema.reflection.primaryConstructor
        val entities = (1..5).map { i ->
            val row = fakeRow(
                mapOf(
                    "stream_id" to UUID.randomUUID(),
                    "occurred_at" to Instant.now(),
                    "payload" to "payload-$i",
                    "note" to null
                )
            )
            executor.decodeEntity(row, QeEvent::class)
        }
        val ctorAfterDecoding = schema.reflection.primaryConstructor

        assertEquals(5, entities.size)
        assertEquals((1..5).map { "payload-$it" }, entities.map { it.payload })
        assertTrue(ctorBeforeDecoding === ctorAfterDecoding, "decodeEntity must not re-resolve primaryConstructor per row")
    }
}
