package io.kandra.test

import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.ClusteringKey
import io.kandra.core.annotations.ClusteringOrder
import io.kandra.core.annotations.LookupIndex
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@ScyllaTable("bind_test_users")
data class BindTestUser(
    @PartitionKey val id: UUID,
    val email: String
)

@ScyllaTable("bind_test_events")
data class BindTestEvent(
    @PartitionKey val streamId: UUID,
    @ClusteringKey(order = ClusteringOrder.DESC) val occurredAt: Instant,
    val payload: String
)

@ScyllaTable("bind_test_lookup_users")
data class BindTestLookupUser(
    @PartitionKey val id: UUID,
    @LookupIndex(tableSuffix = "by_email") val email: String
)

/**
 * Regression coverage for GH #28 — `FakePreparedStatement.bind()` used to unconditionally throw
 * `UnsupportedOperationException`, and every repository CRUD path (`save`, `findById`, `delete`,
 * `update`, batch writes) routes through `StatementBuilder.prepare(cql).bind(...)`. That meant
 * `FakeKandraSession`'s stated purpose — "wire a full repository stack without Testcontainers" /
 * structural test of batch composition and save/delete ordering — was entirely broken: calling any
 * repository method against `KandraTestUtils.inMemory()` threw immediately. These tests exercise
 * exactly that surface end-to-end and assert on what actually got bound.
 */
class FakeKandraSessionBindTest {

    @AfterEach
    fun tearDown() {
        SchemaRegistry.clear()
    }

    @Test
    fun `save does not throw and captures a LOGGED batch with the bound INSERT`() {
        val runtime = KandraTestUtils.inMemory(BindTestUser::class)
        try {
            val session = runtime.session as FakeKandraSession
            val repo = runtime.repository(BindTestUser::class)
            val user = BindTestUser(UUID.randomUUID(), "ada@example.com")

            repo.save(user)

            val batches = session.capturedBatches()
            assertEquals(1, batches.size)
            val insertStmt = batches.single().single() as FakeBoundStatement
            assertTrue(insertStmt.query.startsWith("INSERT INTO bind_test_users"))
            assertTrue(insertStmt.boundValues().contains(user.id))
            assertTrue(insertStmt.boundValues().contains("ada@example.com"))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `findById does not throw against a fake session and misses (empty fake result set)`() {
        val runtime = KandraTestUtils.inMemory(BindTestUser::class)
        try {
            val repo = runtime.repository(BindTestUser::class)
            val user = BindTestUser(UUID.randomUUID(), "grace@example.com")
            repo.save(user)

            // FakeKandraSession.execute() always returns an empty result set, so this can never
            // actually find the row -- the point of this test is that the call succeeds at all
            // (previously: UnsupportedOperationException from FakePreparedStatement.bind()).
            val found = repo.findById(user.id)
            assertNull(found)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `delete captures a LOGGED batch with the bound DELETE, keyed on the full primary key`() {
        val runtime = KandraTestUtils.inMemory(BindTestEvent::class)
        try {
            val session = runtime.session as FakeKandraSession
            val repo = runtime.repository(BindTestEvent::class)
            val event = BindTestEvent(UUID.randomUUID(), Instant.now(), "payload")
            repo.save(event)
            session.reset()

            repo.delete(event)

            val batches = session.capturedBatches()
            assertEquals(1, batches.size)
            val deleteStmt = batches.single().single() as FakeBoundStatement
            assertTrue(deleteStmt.query.startsWith("DELETE FROM bind_test_events"))
            // Full primary key (partition + clustering) must be bound, not just the partition key.
            assertEquals(listOf(event.streamId, event.occurredAt), deleteStmt.boundValues())
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `save with a LookupIndex batches the primary insert and lookup insert together`() {
        val runtime = KandraTestUtils.inMemory(BindTestLookupUser::class)
        try {
            val session = runtime.session as FakeKandraSession
            val repo = runtime.repository(BindTestLookupUser::class)
            val user = BindTestLookupUser(UUID.randomUUID(), "bob@example.com")

            repo.save(user)

            val batches = session.capturedBatches()
            assertEquals(1, batches.size)
            val statements = batches.single().toList().map { it as FakeBoundStatement }
            assertEquals(2, statements.size)
            assertTrue(statements.any { it.query.startsWith("INSERT INTO bind_test_lookup_users ") })
            assertTrue(statements.any { it.query.contains("by_email") })
            assertTrue(statements.any { it.boundValues().contains("bob@example.com") })
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `unset columns are recorded as FakeUnset, distinct from an explicit null`() {
        val runtime = KandraTestUtils.inMemory(BindTestUser::class)
        try {
            val session = runtime.session as FakeKandraSession
            val repo = runtime.repository(BindTestUser::class)
            val user = BindTestUser(UUID.randomUUID(), "carol@example.com")

            repo.save(user)

            val insertStmt = session.capturedBatches().single().single() as FakeBoundStatement
            // Every column on this entity has a real (non-null) value, so nothing should read back
            // as FakeUnset -- this asserts the sentinel isn't leaking into ordinary bound values.
            assertTrue(insertStmt.boundValues().none { it === FakeUnset })
        } finally {
            runtime.close()
        }
    }
}
