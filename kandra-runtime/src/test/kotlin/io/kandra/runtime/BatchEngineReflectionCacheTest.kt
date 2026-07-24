package io.kandra.runtime

import com.datastax.oss.driver.api.core.cql.BatchStatement
import com.datastax.oss.driver.api.core.cql.BoundStatement
import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.CreatedAt
import io.kandra.core.annotations.LookupIndex
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.annotations.UpdatedAt
import io.kandra.core.annotations.Version
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@ScyllaTable("be_stamped")
data class BeStamped(
    @PartitionKey val id: UUID,
    @CreatedAt val createdAt: Instant = Instant.EPOCH,
    @UpdatedAt val updatedAt: Instant = Instant.EPOCH,
    val value: String = ""
)

@ScyllaTable("be_versioned")
data class BeVersioned(
    @PartitionKey val id: UUID,
    @Version val version: Long = 0L,
    val value: String = ""
)

@ScyllaTable("be_with_lookup")
data class BeWithLookup(
    @PartitionKey val id: UUID,
    @LookupIndex(tableSuffix = "by_email") val email: String
)

/**
 * Proves [BatchEngine]'s timestamp/version injection and delete/lookup-key extraction read the
 * entity's `copy()` function and property values through the cached
 * `TableSchema.reflection` (resolved once at [SchemaRegistry.register] time) instead of
 * re-resolving `entity::class.memberFunctions`/`memberProperties` per call, and that the resulting
 * behavior is identical to the old per-call-reflection path. See ISS-034 / GitHub #13.
 */
class BatchEngineReflectionCacheTest {

    private fun newEngine(session: FakeCqlSession = FakeCqlSession()): BatchEngine =
        BatchEngine(session, StatementBuilder(session), CoroutineScope(Dispatchers.Unconfined))

    @AfterEach
    fun tearDown() = SchemaRegistry.clear()

    @Test
    fun `injectTimestamps stamps createdAt and updatedAt on insert via the cached copy function`() {
        val schema = SchemaRegistry.register(BeStamped::class)
        val engine = newEngine()
        val entity = BeStamped(UUID.randomUUID(), value = "v1")

        val stamped = engine.injectTimestamps(schema, entity, isInsert = true) as BeStamped

        assertNotEquals(Instant.EPOCH, stamped.createdAt)
        assertNotEquals(Instant.EPOCH, stamped.updatedAt)
        assertEquals(entity.id, stamped.id, "non-timestamp fields must be preserved by the copy() call")
        assertEquals("v1", stamped.value)
    }

    @Test
    fun `injectTimestamps only stamps updatedAt (not createdAt) on update`() {
        val schema = SchemaRegistry.register(BeStamped::class)
        val engine = newEngine()
        val original = BeStamped(UUID.randomUUID(), createdAt = Instant.EPOCH, value = "v1")

        val stamped = engine.injectTimestamps(schema, original, isInsert = false) as BeStamped

        assertEquals(Instant.EPOCH, stamped.createdAt, "update must not touch createdAt")
        assertNotEquals(Instant.EPOCH, stamped.updatedAt)
    }

    @Test
    fun `injectTimestamps reuses the same cached copy function across repeated calls`() {
        val schema = SchemaRegistry.register(BeStamped::class)
        val engine = newEngine()

        val copyFnBefore = schema.reflection.copyFunction
        engine.injectTimestamps(schema, BeStamped(UUID.randomUUID()), isInsert = true)
        engine.injectTimestamps(schema, BeStamped(UUID.randomUUID()), isInsert = true)
        val copyFnAfter = schema.reflection.copyFunction

        assertTrue(copyFnBefore === copyFnAfter, "copy() must be resolved once, not per injectTimestamps call")
    }

    @Test
    fun `save injects an initial Long version of 1 via the cached copy function`() {
        val schema = SchemaRegistry.register(BeVersioned::class)
        val session = FakeCqlSession()
        val engine = newEngine(session)

        engine.save(schema, BeVersioned(UUID.randomUUID(), value = "v1"))

        val batch = session.executedStatements().filterIsInstance<BatchStatement>().single()
        val primaryInsert = batch.first() as BoundStatement
        // version is the 3rd bound column in @PartitionKey, @Version, value declaration order
        val versionIdx = (schema.partitionKeys + schema.columns).distinctBy { it.cqlName }
            .indexOfFirst { it.propertyName == "version" }
        assertEquals(1L, primaryInsert.recorded().values[versionIdx])
    }

    @Test
    fun `delete extracts key and lookup column values via the cached property map`() {
        val schema = SchemaRegistry.register(BeWithLookup::class)
        val session = FakeCqlSession()
        val engine = newEngine(session)
        val id = UUID.randomUUID()

        engine.delete(schema, BeWithLookup(id, "a@b.com"))

        val batch = session.executedStatements().filterIsInstance<BatchStatement>().single()
        val statements = batch.toList().filterIsInstance<BoundStatement>()
        // [0] = DELETE FROM be_with_lookup WHERE id = ? ; [1] = DELETE FROM be_with_lookup_by_email WHERE email = ?
        assertEquals(id, statements[0].recorded().values[0])
        assertEquals("a@b.com", statements[1].recorded().values[0])
    }

    @Test
    fun `collectDelete (batch-scope path) also reads keys via the cached property map`() {
        val schema = SchemaRegistry.register(BeWithLookup::class)
        val session = FakeCqlSession()
        val engine = newEngine(session)
        val id = UUID.randomUUID()

        val statements = engine.collectDelete(schema, BeWithLookup(id, "a@b.com"))

        assertEquals(2, statements.size)
        assertEquals(id, (statements[0] as BoundStatement).recorded().values[0])
        assertEquals("a@b.com", (statements[1] as BoundStatement).recorded().values[0])
    }
}
