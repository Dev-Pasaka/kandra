package io.kandra.test

import com.datastax.oss.driver.api.core.cql.BatchStatement
import com.datastax.oss.driver.api.core.cql.DefaultBatchType
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import io.kandra.core.InternalKandraApi
import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

@ScyllaTable("usability_notes")
data class UsabilityNote(
    @PartitionKey val id: UUID,
    val title: String,
    val body: String?
)

/** GH #180 (public reset helper) and GH #181 (public captured-statement view). */
@OptIn(InternalKandraApi::class)
class KandraTestUsabilityTest {

    @AfterEach
    fun tearDown() {
        KandraTestUtils.reset()
    }

    // ---- #180 ------------------------------------------------------------------------------

    @Test
    fun `reset clears the schema registry without touching SchemaRegistry directly`() {
        KandraTestUtils.inMemory(UsabilityNote::class).close()
        SchemaRegistry.get(UsabilityNote::class) // registered

        KandraTestUtils.reset()

        assertThrows(Exception::class.java) { SchemaRegistry.get(UsabilityNote::class) }
    }

    @Test
    fun `closeAndReset closes the runtime and clears the registry`() {
        val runtime = KandraTestUtils.inMemory(UsabilityNote::class)

        runtime.closeAndReset()

        assertThrows(Exception::class.java) { SchemaRegistry.get(UsabilityNote::class) }
    }

    @Test
    fun `plain close leaves the registry alone`() {
        val runtime = KandraTestUtils.inMemory(UsabilityNote::class)
        runtime.close()
        SchemaRegistry.get(UsabilityNote::class) // still registered, no exception
    }

    // ---- #181 ------------------------------------------------------------------------------

    @Test
    fun `capturedStatementViews exposes cql and bound values`() {
        val runtime = KandraTestUtils.inMemory(UsabilityNote::class)
        val session = runtime.session as FakeKandraSession
        val id = UUID.randomUUID()

        runtime.repository(UsabilityNote::class).save(UsabilityNote(id, "hello", "world"))

        val view = session.capturedStatementViews().single()
        assertTrue(view.cql.startsWith("INSERT INTO usability_notes"), view.cql)
        assertEquals(mapOf<String, Any?>("id" to id, "title" to "hello", "body" to "world"), byColumn(view))
        assertEquals(1, session.capturedBatchViews().single().size)
    }

    /** Maps each INSERT column name to its bound value (bind order follows the column list in the CQL). */
    private fun byColumn(view: CapturedStatement): Map<String, Any?> {
        val columns = view.cql.substringAfter("(").substringBefore(")").split(",").map { it.trim() }
        return columns.zip(view.values).toMap()
    }

    private fun indexOf(view: CapturedStatement, column: String): Int =
        view.cql.substringAfter("(").substringBefore(")").split(",").map { it.trim() }.indexOf(column)

    @Test
    fun `save of a null field reads back as unset, saveWithNulls as null`() {
        val runtime = KandraTestUtils.inMemory(UsabilityNote::class)
        val session = runtime.session as FakeKandraSession
        val repo = runtime.repository(UsabilityNote::class)
        val id = UUID.randomUUID()

        repo.save(UsabilityNote(id, "t", null))
        val plain = session.capturedStatementViews().single()
        val body = indexOf(plain, "body")
        assertSame(FakeUnset, plain.values[body])
        assertTrue(plain.isUnset(body))
        assertFalse(plain.isUnset(indexOf(plain, "title")))

        session.reset()
        repo.saveWithNulls(UsabilityNote(id, "t", null))
        val tombstone = session.capturedStatementViews().single()
        assertEquals(null, tombstone.values[indexOf(tombstone, "body")])
        assertFalse(tombstone.isUnset(indexOf(tombstone, "body")))
    }

    @Test
    fun `batch boundaries and simple statements are described`() {
        val session = FakeKandraSession()
        val batch = BatchStatement.newInstance(DefaultBatchType.LOGGED)
            .add(SimpleStatement.newInstance("INSERT INTO a (x) VALUES (?)", 1))
            .add(SimpleStatement.newInstance("INSERT INTO b (y) VALUES (?)", "two"))
        session.execute(batch)
        session.execute(SimpleStatement.newInstance("SELECT * FROM c"))

        assertEquals(
            listOf(
                CapturedStatement("INSERT INTO a (x) VALUES (?)", listOf(1)),
                CapturedStatement("INSERT INTO b (y) VALUES (?)", listOf("two")),
                CapturedStatement("SELECT * FROM c", emptyList())
            ),
            session.capturedStatementViews()
        )
        assertEquals(2, session.capturedBatchViews().single().size)
    }
}
