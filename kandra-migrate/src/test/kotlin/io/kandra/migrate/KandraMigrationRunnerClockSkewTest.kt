package io.kandra.migrate

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.config.DefaultDriverOption
import com.datastax.oss.driver.api.core.config.DriverConfigLoader
import com.datastax.oss.driver.api.core.context.DriverContext
import com.datastax.oss.driver.api.core.time.TimestampGenerator
import io.kandra.core.exception.KandraMigrationException
import io.kandra.test.KandraTestBackend
import io.kandra.test.KandraTestcontainers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import java.time.Duration

/** Client-side write timestamps 5 s behind the real clock: an app server whose clock lags the cluster. */
class LaggingTimestampGenerator(@Suppress("UNUSED_PARAMETER") context: DriverContext) : TimestampGenerator {
    override fun next(): Long = (System.currentTimeMillis() - 5_000) * 1_000
    override fun close() = Unit
}

/**
 * Regression coverage for a lost `markApplied`: the claim is an `IF NOT EXISTS` LWT (timestamped by
 * the coordinator's Paxos ballot) and `markApplied` was a plain UPDATE (timestamped by the client).
 * With the client clock behind the cluster's, the UPDATE carried an older timestamp and was
 * silently discarded, leaving the row CLAIMED. Seen once in a full run against a Docker container
 * whose clock had drifted ahead; reproduced here deterministically with a lagging client clock.
 */
class KandraMigrationRunnerClockSkewTest {

    private val keyspace = KandraTestBackend.keyspaceName("skew")
    private var session: CqlSession? = null

    private fun laggingSession(): CqlSession {
        assumeFalse(KandraTestBackend.isCloud, "Docker-only: uses the container directly")
        KandraTestBackend.createKeyspace(keyspace)
        val config = DriverConfigLoader.programmaticBuilder()
            .withClass(DefaultDriverOption.TIMESTAMP_GENERATOR_CLASS, LaggingTimestampGenerator::class.java)
            .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofSeconds(30))
            .withDuration(DefaultDriverOption.METADATA_SCHEMA_REQUEST_TIMEOUT, Duration.ofSeconds(30))
            .withDuration(DefaultDriverOption.CONTROL_CONNECTION_AGREEMENT_TIMEOUT, Duration.ofSeconds(30))
            .build()
        return CqlSession.builder()
            .addContactPoint(KandraTestcontainers.container.contactPoint)
            .withLocalDatacenter(KandraTestcontainers.container.localDatacenter)
            .withConfigLoader(config)
            .withKeyspace(keyspace)
            .build()
            .also { session = it }
    }

    @AfterEach
    fun cleanup() {
        runCatching { session?.close() }
        KandraTestBackend.dropKeyspace(keyspace)
    }

    private class Noop(version: Int) : KandraMigration(version, "noop-$version") {
        override fun up(session: CqlSession) = Unit
    }

    private class Failing(version: Int) : KandraMigration(version, "failing-$version") {
        override fun up(session: CqlSession) = throw IllegalStateException("boom")
    }

    @Test
    fun `migrations are recorded APPLIED even when the client clock lags the cluster`() {
        val runner = KandraMigrationRunner(laggingSession())
        runner.run(Noop(1), Noop(2), Noop(3))
        assertEquals(
            List(3) { MigrationRowStatus.APPLIED },
            runner.history().map { it.status },
            "markApplied must not lose to the claim's LWT timestamp"
        )
        runner.run(Noop(1), Noop(2), Noop(3)) // verifies and skips, no CLAIMED row to trip over
    }

    @Test
    fun `a failed migration's claim is released even when the client clock lags the cluster`() {
        val runner = KandraMigrationRunner(laggingSession())
        assertThrows(KandraMigrationException::class.java) { runner.run(Failing(1)) }
        assertEquals(emptyList<MigrationHistory>(), runner.history(), "the CLAIMED row must be gone so a retry can run")
    }
}
