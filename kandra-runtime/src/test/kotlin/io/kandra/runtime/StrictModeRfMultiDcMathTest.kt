package io.kandra.runtime

import com.datastax.oss.driver.api.core.CqlIdentifier
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.config.DriverConfig
import com.datastax.oss.driver.api.core.config.DriverExecutionProfile
import com.datastax.oss.driver.api.core.context.DriverContext
import com.datastax.oss.driver.api.core.metadata.Metadata
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata
import io.kandra.core.InternalKandraApi
import io.kandra.core.KandraConsistency
import io.kandra.core.schema.EntityReflection
import io.kandra.core.schema.TableSchema
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Optional
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/** Placeholder entity solely so [TableSchema.entityClass] has something real to point at. */
private data class StrictModeMathEntity(val id: Int)

/**
 * Deterministic, no-cluster-needed coverage for GH #98 / ISS-085 —
 * `StatementBuilder.replicationFactorOrNull`'s `NetworkTopologyStrategy`/multi-DC math.
 *
 * A real multi-DC repro of the false-positive this issue describes needs actual multi-DC topology
 * (Cassandra's `CREATE KEYSPACE` rejects a `NetworkTopologyStrategy` replication map naming a DC
 * that doesn't exist in the cluster — confirmed empirically against the single-node Testcontainers
 * container used by `StrictModeRfIntegrationTest` in kandra-test). Rather than requiring the
 * `:kandra-multidc` docker-compose cluster for what is otherwise pure arithmetic over a replication
 * map, these tests drive [StatementBuilder] against a hand-scripted [CqlSession] whose driver-facing
 * metadata (`getMetadata().getKeyspace(...).getReplication()`) and configured local datacenter
 * (`getContext().getConfig().getDefaultProfile().getString(LOAD_BALANCING_LOCAL_DATACENTER, ...)`)
 * are fully controlled — exactly the two inputs `replicationFactorOrNull` reads.
 */
@OptIn(InternalKandraApi::class)
class StrictModeRfMultiDcMathTest {

    private fun schema(): TableSchema = TableSchema(
        entityClass = StrictModeMathEntity::class,
        tableName = "strict_mode_math_entities",
        partitionKeys = emptyList(),
        clusteringKeys = emptyList(),
        columns = emptyList(),
        lookupTables = emptyList(),
        reflection = EntityReflection(
            copyFunction = StrictModeMathEntity::class.memberFunctions.find { it.name == "copy" },
            copyParameters = StrictModeMathEntity::class.memberFunctions.find { it.name == "copy" }?.parameters ?: emptyList(),
            propertiesByName = StrictModeMathEntity::class.memberProperties.associateBy { it.name },
            primaryConstructor = StrictModeMathEntity::class.primaryConstructor,
            constructorParameters = StrictModeMathEntity::class.primaryConstructor?.parameters ?: emptyList(),
            columnsByProperty = emptyMap()
        )
    )

    /** `resolveWriteConsistency` is `internal`, so its compiled name is module-mangled — match by prefix. */
    private fun resolveWriteMethod(): Method =
        StatementBuilder::class.java.declaredMethods
            .single { it.name.startsWith("resolveWriteConsistency") }
            .apply { isAccessible = true }

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

    private fun <T> proxy(clazz: Class<T>, respond: (Method) -> Any?): T {
        val handler = InvocationHandler { _, method, _ -> respond(method) }
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz), handler) as T
    }

    /**
     * A [CqlSession] whose current keyspace reports [replication] (a raw driver replication map,
     * `class` entry included) and whose configured local datacenter is [localDatacenter] (`null`
     * simulates it being undeterminable — no local DC configured, or a session type whose context
     * isn't inspectable).
     */
    private fun fakeSession(replication: Map<String, String>, localDatacenter: String?): CqlSession {
        val keyspaceId = CqlIdentifier.fromCql("ks")
        val keyspaceMetadata = proxy(KeyspaceMetadata::class.java) { method ->
            if (method.name == "getReplication") replication else null
        }
        val metadata = proxy(Metadata::class.java) { method ->
            if (method.name == "getKeyspace") Optional.of(keyspaceMetadata) else null
        }
        val executionProfile = proxy(DriverExecutionProfile::class.java) { method ->
            if (method.name == "getString") localDatacenter else null
        }
        val driverConfig = proxy(DriverConfig::class.java) { method ->
            if (method.name == "getDefaultProfile") executionProfile else null
        }
        val driverContext = proxy(DriverContext::class.java) { method ->
            if (method.name == "getConfig") driverConfig else null
        }
        return proxy(CqlSession::class.java) { method ->
            when (method.name) {
                "getKeyspace" -> Optional.of(keyspaceId)
                "getMetadata" -> metadata
                "getContext" -> driverContext
                else -> null
            }
        }
    }

    private fun ntsReplication(vararg perDc: Pair<String, Int>): Map<String, String> =
        mapOf("class" to "NetworkTopologyStrategy") + perDc.associate { (dc, rf) -> dc to rf.toString() }

    @Test
    fun `LOCAL_ level RF check uses the local DC's own RF, not the cluster-wide sum, when the local DC is known`() {
        // Worked example from GH #98 / ISS-085: 3 DCs at RF=1 each (sum=3) is a legitimate low-latency
        // config -- LOCAL_ONE + LOCAL_QUORUM is only ever satisfied within one DC's RF=1 replicas, so
        // R=1 + W=1 = 2 > 1 is genuinely safe. The old sum-based math used RF=3 here and false-positived.
        val session = fakeSession(ntsReplication("dc1" to 1, "dc2" to 1, "dc3" to 1), localDatacenter = "dc1")
        val builder = StatementBuilder(session, consistencyConfig = ConsistencyConfig().apply { strictMode = true })

        val output = captureStderr { resolveWriteMethod().invoke(builder, schema(), null) }

        assertFalse(output.contains("strictMode"), "expected no false-positive WARN, got: $output")
    }

    @Test
    fun `LOCAL_ level RF check falls back to the per-DC average when the local DC can't be determined`() {
        val session = fakeSession(ntsReplication("dc1" to 1, "dc2" to 1, "dc3" to 1), localDatacenter = null)
        val builder = StatementBuilder(session, consistencyConfig = ConsistencyConfig().apply { strictMode = true })

        val output = captureStderr { resolveWriteMethod().invoke(builder, schema(), null) }

        // ceil(3 / 3 DCs) = 1, same as the exact-match result here (RF is uniform across DCs) --
        // still safe (R=1 + W=1 = 2 > 1), unlike the old cluster-wide-sum (RF=3) behavior.
        assertFalse(output.contains("strictMode"), "expected no false-positive WARN via the fallback, got: $output")
    }

    @Test
    fun `LOCAL_ level RF check still warns when the local DC's own RF makes R plus W insufficient`() {
        // Local DC RF=3 (the other DCs' RF is irrelevant to a LOCAL_* level): R=1 (LOCAL_ONE) +
        // W=2 (LOCAL_QUORUM of 3) = 3, not > 3 -- genuinely unsafe, must still warn.
        val session = fakeSession(ntsReplication("dc1" to 3, "dc2" to 1, "dc3" to 1), localDatacenter = "dc1")
        val builder = StatementBuilder(session, consistencyConfig = ConsistencyConfig().apply { strictMode = true })

        val output = captureStderr { resolveWriteMethod().invoke(builder, schema(), null) }

        assertTrue(output.contains("strictMode"), "expected an RF strict-mode WARN, got: $output")
        assertTrue(output.contains("RF=3"), "expected the WARN to cite the local DC's RF=3, got: $output")
    }

    @Test
    fun `global consistency levels still use the cluster-wide RF sum, not a per-DC value`() {
        val session = fakeSession(ntsReplication("dc1" to 1, "dc2" to 1, "dc3" to 1), localDatacenter = "dc1")
        val builder = StatementBuilder(
            session,
            consistencyConfig = ConsistencyConfig().apply {
                strictMode = true
                defaultRead = KandraConsistency.ONE
                defaultWrite = KandraConsistency.QUORUM
            }
        )

        val output = captureStderr { resolveWriteMethod().invoke(builder, schema(), null) }

        // Cluster-wide RF=3 (sum across all 3 DCs): R=1 (ONE) + W=2 (QUORUM of 3) = 3, not > 3 -- warns.
        // A per-DC value (1) would also warn here by coincidence, so the message's RF is asserted too.
        assertTrue(output.contains("strictMode"), "expected an RF strict-mode WARN, got: $output")
        assertTrue(output.contains("replication factor 3"), "expected the cluster-wide sum RF=3, got: $output")
    }
}
