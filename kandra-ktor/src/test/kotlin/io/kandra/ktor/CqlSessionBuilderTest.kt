package io.kandra.ktor

import io.kandra.core.exception.KandraSchemaException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [CqlSessionBuilder.kt] internals that don't require a live cluster:
 *  - [AllowedDcNodeDistanceEvaluator]'s pure decision logic (GH #58)
 *  - [keyspaceDdl]'s identifier validation and DDL rendering (GH #65)
 */
class CqlSessionBuilderTest {

    // ── AllowedDcNodeDistanceEvaluator (GH #58) ──────────────────────────────

    @Test
    fun `local DC always defers to the policy's own default distance`() {
        val evaluator = AllowedDcNodeDistanceEvaluator("us-east-1", setOf("eu-west-1"))
        assertEquals(null, evaluator.evaluate("us-east-1"))
    }

    @Test
    fun `an allowed remote DC defers to the policy's own default distance`() {
        val evaluator = AllowedDcNodeDistanceEvaluator("us-east-1", setOf("eu-west-1", "ap-southeast-1"))
        assertEquals(null, evaluator.evaluate("eu-west-1"))
        assertEquals(null, evaluator.evaluate("ap-southeast-1"))
    }

    @Test
    fun `a remote DC not in the allow-list is forced IGNORED`() {
        val evaluator = AllowedDcNodeDistanceEvaluator("us-east-1", setOf("eu-west-1"))
        assertEquals(
            com.datastax.oss.driver.api.core.loadbalancing.NodeDistance.IGNORED,
            evaluator.evaluate("ap-southeast-1")
        )
    }

    @Test
    fun `an empty allow-list ignores every remote DC`() {
        val evaluator = AllowedDcNodeDistanceEvaluator("us-east-1", emptySet())
        assertEquals(
            com.datastax.oss.driver.api.core.loadbalancing.NodeDistance.IGNORED,
            evaluator.evaluate("eu-west-1")
        )
        assertEquals(null, evaluator.evaluate("us-east-1"))
    }

    @Test
    fun `a null datacenter defers to the policy's own default distance`() {
        val evaluator = AllowedDcNodeDistanceEvaluator("us-east-1", setOf("eu-west-1"))
        assertEquals(null, evaluator.evaluate(null))
    }

    // ── keyspaceDdl identifier validation (GH #65) ───────────────────────────

    @Test
    fun `keyspaceDdl renders SimpleStrategy DDL for a valid keyspace`() {
        val ddl = keyspaceDdl("coinx", ReplicationStrategy.SimpleStrategy(replicationFactor = 3))
        assertEquals(
            "CREATE KEYSPACE IF NOT EXISTS coinx WITH replication = " +
                "{'class': 'SimpleStrategy', 'replication_factor': 3}",
            ddl
        )
    }

    @Test
    fun `keyspaceDdl renders NetworkTopologyStrategy DDL for valid DC names`() {
        val ddl = keyspaceDdl(
            "coinx",
            ReplicationStrategy.NetworkTopologyStrategy(mapOf("us_east" to 3, "eu_west" to 2))
        )
        assertTrue(ddl.startsWith("CREATE KEYSPACE IF NOT EXISTS coinx WITH replication = "))
        assertTrue(ddl.contains("'class': 'NetworkTopologyStrategy'"))
        assertTrue(ddl.contains("'us_east': 3"))
        assertTrue(ddl.contains("'eu_west': 2"))
    }

    @Test
    fun `keyspaceDdl rejects a keyspace name that is not a valid CQL identifier`() {
        assertThrows(KandraSchemaException::class.java) {
            keyspaceDdl("coinx'; DROP KEYSPACE other; --", ReplicationStrategy.SimpleStrategy())
        }
    }

    @Test
    fun `keyspaceDdl rejects a keyspace name with whitespace`() {
        assertThrows(KandraSchemaException::class.java) {
            keyspaceDdl("my keyspace", ReplicationStrategy.SimpleStrategy())
        }
    }

    @Test
    fun `keyspaceDdl rejects a DC name in dcReplicationMap that is not a valid CQL identifier`() {
        assertThrows(KandraSchemaException::class.java) {
            keyspaceDdl(
                "coinx",
                ReplicationStrategy.NetworkTopologyStrategy(mapOf("us-east' } ; --" to 3))
            )
        }
    }

    @Test
    fun `keyspaceDdl accepts a keyspace name starting with an underscore`() {
        val ddl = keyspaceDdl("_coinx", ReplicationStrategy.SimpleStrategy())
        assertTrue(ddl.contains("_coinx"))
    }
}
