package io.kandra.runtime

import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

@ScyllaTable("cc_users")
private data class CcUser(
    @PartitionKey val id: UUID,
    val tags: Set<String> = emptySet(),
    val history: List<String> = emptyList()
)

/**
 * Regression coverage for GH #145: [StatementBuilder.appendToCollection]/[StatementBuilder.removeFromCollection]
 * (and their suspend counterparts) bound the caller's raw `Collection<V>` argument as-is, with no
 * coercion to the JVM collection type the driver's codec expects for the column's actual CQL
 * collection type. `KandraSuspendRepository.append`/`remove`'s own public signature
 * (`values: Collection<V>`) invites passing a `List` (e.g. `listOf(tag)`), which failed outright
 * against a `Set<T>`-typed column with `CodecNotFoundException: [Set(TEXT, not frozen) <->
 * java.util.List<java.lang.String>]` -- confirmed live against a real ScyllaDB Cloud cluster.
 */
class StatementBuilderCollectionCoercionTest {

    @AfterEach
    fun tearDown() {
        SchemaRegistry.clear()
    }

    @Test
    fun `appendToCollection on a Set column binds a Set, not the raw List argument`() {
        val schema = SchemaRegistry.register(CcUser::class)
        val session = ScriptedCqlSession()
        val builder = StatementBuilder(session)
        val id = UUID.randomUUID()

        builder.appendToCollection(schema, listOf(id), "tags", listOf("vip"))

        val bound = session.lastBoundValues?.first()
        assertTrue(bound is Set<*>, "Expected a java.util.Set bound for a Set<T> column, got: ${bound?.javaClass}")
        assertEquals(setOf("vip"), bound)
    }

    @Test
    fun `removeFromCollection on a Set column binds a Set, not the raw List argument`() {
        val schema = SchemaRegistry.register(CcUser::class)
        val session = ScriptedCqlSession()
        val builder = StatementBuilder(session)
        val id = UUID.randomUUID()

        builder.removeFromCollection(schema, listOf(id), "tags", listOf("vip"))

        val bound = session.lastBoundValues?.first()
        assertTrue(bound is Set<*>, "Expected a java.util.Set bound for a Set<T> column, got: ${bound?.javaClass}")
        assertEquals(setOf("vip"), bound)
    }

    @Test
    fun `appendToCollectionSuspend on a Set column binds a Set`() = runBlocking {
        val schema = SchemaRegistry.register(CcUser::class)
        val session = ScriptedCqlSession()
        val builder = StatementBuilder(session)
        val id = UUID.randomUUID()

        builder.appendToCollectionSuspend(schema, listOf(id), "tags", listOf("vip", "vip"))

        val bound = session.lastBoundValues?.first()
        assertTrue(bound is Set<*>, "Expected a java.util.Set bound for a Set<T> column, got: ${bound?.javaClass}")
        assertEquals(setOf("vip"), bound)
    }

    @Test
    fun `removeFromCollectionSuspend on a Set column binds a Set`() = runBlocking {
        val schema = SchemaRegistry.register(CcUser::class)
        val session = ScriptedCqlSession()
        val builder = StatementBuilder(session)
        val id = UUID.randomUUID()

        builder.removeFromCollectionSuspend(schema, listOf(id), "tags", listOf("vip"))

        val bound = session.lastBoundValues?.first()
        assertTrue(bound is Set<*>, "Expected a java.util.Set bound for a Set<T> column, got: ${bound?.javaClass}")
        assertEquals(setOf("vip"), bound)
    }

    @Test
    fun `appendToCollection on a List column leaves a List as a List`() {
        val schema = SchemaRegistry.register(CcUser::class)
        val session = ScriptedCqlSession()
        val builder = StatementBuilder(session)
        val id = UUID.randomUUID()

        builder.appendToCollection(schema, listOf(id), "history", listOf("event-1"))

        val bound = session.lastBoundValues?.first()
        assertTrue(bound is List<*>, "Expected a java.util.List for a List<T> column, got: ${bound?.javaClass}")
        assertEquals(listOf("event-1"), bound)
    }
}
