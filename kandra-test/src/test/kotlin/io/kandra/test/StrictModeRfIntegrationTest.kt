package io.kandra.test

import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.core.InternalKandraApi
import io.kandra.core.schema.EntityReflection
import io.kandra.core.schema.TableSchema
import io.kandra.runtime.ConsistencyConfig
import io.kandra.runtime.StatementBuilder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.Method
import java.util.UUID
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/** Placeholder entity solely so [TableSchema.entityClass] has something real to point at. */
private data class RfCheckEntity(val id: Int)

/**
 * Real-cluster coverage for ISS-075 / GH #83 — Strict Mode's RF-vs-(R+W) check
 * (`StatementBuilder.warnIfRfConsistencyMismatch`).
 *
 * Unlike [KandraIntegrationTest] this never executes an actual query — `LOCAL_QUORUM` against an
 * `RF=3` keyspace on a single-node Testcontainers cluster would fail with `UnavailableException`
 * (only one real replica ever exists), which is irrelevant to what's under test here: the WARN fires
 * purely from *declared* replication factor in driver keyspace metadata (`session.getMetadata()
 * .getKeyspace(...)`), read the moment consistency is resolved — before any statement is sent to the
 * cluster. `SimpleStrategy` happily reports whatever `replication_factor` the keyspace was created
 * with regardless of how many nodes actually exist, which is exactly what this test needs. It invokes
 * `StatementBuilder`'s internal `resolveWriteConsistency` via reflection against a real [CqlSession]
 * (mirroring the fake-session unit coverage in `ConsistencyStrictModeTest` in kandra-runtime, which
 * can't exercise this check at all since its session never has real keyspace metadata).
 */
@OptIn(InternalKandraApi::class)
class StrictModeRfIntegrationTest {

    private var handle: KandraRuntimeHandle? = null

    @AfterEach
    fun cleanup() {
        handle?.close()
        handle = null
    }

    private fun captureStderr(block: () -> Unit): String {
        val original = System.err
        val buffer = ByteArrayOutputStream()
        System.setErr(PrintStream(buffer))
        try {
            block()
        } finally {
            System.setErr(original)
        }
        return buffer.toString()
    }

    private fun schema(): TableSchema = TableSchema(
        entityClass = RfCheckEntity::class,
        tableName = "rf_check_entities",
        partitionKeys = emptyList(),
        clusteringKeys = emptyList(),
        columns = emptyList(),
        lookupTables = emptyList(),
        reflection = EntityReflection(
            copyFunction = RfCheckEntity::class.memberFunctions.find { it.name == "copy" },
            copyParameters = RfCheckEntity::class.memberFunctions.find { it.name == "copy" }?.parameters ?: emptyList(),
            propertiesByName = RfCheckEntity::class.memberProperties.associateBy { it.name },
            primaryConstructor = RfCheckEntity::class.primaryConstructor,
            constructorParameters = RfCheckEntity::class.primaryConstructor?.parameters ?: emptyList(),
            columnsByProperty = emptyMap()
        )
    )

    /** `resolveWriteConsistency` is `internal`, so its compiled name is module-mangled — match by prefix. */
    private fun resolveWriteMethod(): Method =
        StatementBuilder::class.java.declaredMethods
            .single { it.name.startsWith("resolveWriteConsistency") }
            .apply { isAccessible = true }

    @Test
    fun `strict mode warns on RF=3 with the LOCAL_ONE-LOCAL_QUORUM defaults`() {
        val db = KandraTestcontainers.freshKeyspace(replicationFactor = 3)
        handle = db
        val builder = StatementBuilder(
            session = db.runtime.session,
            consistencyConfig = ConsistencyConfig().apply { strictMode = true }
        )

        val output = captureStderr {
            resolveWriteMethod().invoke(builder, schema(), null)
        }

        assertTrue(output.contains("strictMode"), "expected RF strict-mode WARN, got: $output")
        assertTrue(output.contains("replication factor 3"), "expected WARN to mention RF=3, got: $output")
    }

    @Test
    fun `strict mode does not warn on RF=1 with the LOCAL_ONE-LOCAL_QUORUM defaults`() {
        val db = KandraTestcontainers.freshKeyspace(replicationFactor = 1)
        handle = db
        val builder = StatementBuilder(
            session = db.runtime.session,
            consistencyConfig = ConsistencyConfig().apply { strictMode = true }
        )

        val output = captureStderr {
            resolveWriteMethod().invoke(builder, schema(), null)
        }

        assertFalse(output.contains("strictMode"), "expected no RF strict-mode WARN at RF=1, got: $output")
    }

    @Test
    fun `RF check never fires when strictMode is disabled, even at RF=3`() {
        val db = KandraTestcontainers.freshKeyspace(replicationFactor = 3)
        handle = db
        // strictMode left at its default (false).
        val builder = StatementBuilder(session = db.runtime.session)

        val output = captureStderr {
            resolveWriteMethod().invoke(builder, schema(), null)
        }

        assertFalse(output.contains("strictMode"), "expected no WARN when strictMode is disabled, got: $output")
    }

    // ── GH #98 / ISS-085: NetworkTopologyStrategy RF math for LOCAL_* levels ───
    //
    // A genuine multi-DC false-positive repro (RF=1 per DC across several real DCs) needs actual
    // multi-DC topology to test against -- Cassandra's CREATE KEYSPACE validates that every DC named
    // in a NetworkTopologyStrategy replication map actually exists in the cluster, which this
    // single-node Testcontainers container can't provide (confirmed empirically: a replication map
    // referencing a second, non-existent DC is rejected with InvalidConfigurationInQueryException).
    // The exact-vs-fallback RF math itself (StatementBuilder.replicationFactorOrNull) is covered by
    // fully deterministic unit tests against a scripted multi-DC replication map instead, in
    // kandra-runtime's StrictModeRfMultiDcMathTest -- no real cluster (single- or multi-DC) required
    // there. This test only proves NetworkTopologyStrategy in general (a single, real DC) resolves
    // correctly end-to-end through a real driver session, which SimpleStrategy alone didn't cover.

    @Test
    fun `strict mode resolves RF correctly on a single-DC NetworkTopologyStrategy keyspace`() {
        val keyspace = "kandra_test_nts_${UUID.randomUUID().toString().replace("-", "")}"
        val container = KandraTestcontainers.container
        val localDc = container.localDatacenter

        val bootstrapSession = CqlSession.builder()
            .addContactPoint(container.contactPoint)
            .withLocalDatacenter(localDc)
            .build()
        bootstrapSession.execute(
            "CREATE KEYSPACE IF NOT EXISTS $keyspace WITH replication = " +
            "{'class': 'NetworkTopologyStrategy', '$localDc': 3}"
        )
        bootstrapSession.close()

        val session = CqlSession.builder()
            .addContactPoint(container.contactPoint)
            .withLocalDatacenter(localDc)
            .withKeyspace(keyspace)
            .build()
        try {
            val builder = StatementBuilder(
                session = session,
                consistencyConfig = ConsistencyConfig().apply { strictMode = true }
            )

            val output = captureStderr {
                resolveWriteMethod().invoke(builder, schema(), null)
            }

            // Local RF=3: R=1 (LOCAL_ONE) + W=2 (LOCAL_QUORUM of 3) = 3, not > 3 -- warns, exactly
            // like the equivalent SimpleStrategy RF=3 case above, proving the exact-local-DC-match
            // branch of replicationFactorOrNull resolves the same real driver-reported RF correctly
            // for NetworkTopologyStrategy.
            assertTrue(output.contains("strictMode"), "expected RF strict-mode WARN, got: $output")
            assertTrue(output.contains("replication factor 3"), "expected WARN to mention RF=3, got: $output")
        } finally {
            runCatching { session.execute("DROP KEYSPACE IF EXISTS $keyspace") }
            runCatching { session.close() }
        }
    }
}
