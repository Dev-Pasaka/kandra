package io.kandra.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/** Live-cluster smoke test for the [KandraCloud] harness. Skipped when KANDRA_CLOUD_* is unset. */
class KandraCloudSmokeTest {

    companion object {
        @BeforeAll @JvmStatic fun gate() = KandraCloud.assumeConfigured()
    }

    @Test
    fun `local datacenter matches configured value`() {
        KandraCloud.newSession().use { s ->
            val dc = s.execute("SELECT data_center FROM system.local").one()!!.getString("data_center")
            assertEquals(KandraCloud.localDc, dc)
        }
    }

    @Test
    fun `create keyspace, table, insert, select, drop`() {
        lateinit var name: String
        KandraCloud.freshKeyspace("smoke").use { ks ->
            name = ks.name
            assertTrue(name.matches(Regex("kt_smoke_[0-9a-f]{8}")))
            ks.session.execute("CREATE TABLE t (id int PRIMARY KEY, v text)")
            ks.session.execute("INSERT INTO t (id, v) VALUES (1, 'hello')")
            assertEquals("hello", ks.session.execute("SELECT v FROM t WHERE id = 1").one()!!.getString("v"))
        }
        KandraCloud.newSession().use { s ->
            val n = s.execute("SELECT keyspace_name FROM system_schema.keyspaces WHERE keyspace_name = '$name'").all().size
            assertEquals(0, n, "keyspace should be dropped")
        }
    }
}
