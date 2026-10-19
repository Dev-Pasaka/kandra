package io.kandra.koin

import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.KandraAuth
import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.ktor.Kandra
import io.kandra.ktor.SchemaMode
import io.kandra.test.KandraTestBackend
import io.kandra.test.KandraTestcontainers
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.koin.core.component.KoinComponent
import org.koin.ktor.plugin.Koin
import java.util.UUID

@ScyllaTable("koin_di_widgets")
data class KoinDiWidget(
    @PartitionKey val id: UUID,
    val name: String
)

/**
 * Real, end-to-end proof for GH-17: `kandra-codegen`'s generated `KoinDiWidgetKoinDi.kt`
 * (typed `koinDiWidgetRepo()` / `koinDiWidgetSuspendRepo()` accessors) resolves the *exact same*
 * `named("KoinDiWidgetSuspendRepo")` binding that `Application.kandraKoin()` creates — against a
 * real ScyllaDB/Cassandra Testcontainers cluster and a real Koin container, not a simulation.
 *
 * `KoinDiWidgetKoinDi.kt` is generated automatically because `kandra-koin`'s own build already
 * depends on `koin-core` (see `kandra-koin/build.gradle.kts`'s `kspTest(project(":kandra-codegen"))`)
 * — this is the exact "both codegen and the DI framework are on the classpath together" scenario
 * the feature targets, exercised by the module that actually ships `kandraKoin()`.
 */
class KandraKoinDiAccessorsTest : KoinComponent {

    private var testKeyspace: String? = null

    @AfterEach
    fun cleanup() {
        SchemaRegistry.clear()
        testKeyspace?.let { ks ->
            KandraTestBackend.newSession().use { it.execute("DROP KEYSPACE IF EXISTS $ks") }
        }
        testKeyspace = null
    }

    private fun freshKeyspaceName(): String {
        // "kandra_koin_" (12 chars) + a 32-char dash-stripped UUID = 44 chars, safely under
        // Cassandra's 48-character keyspace name limit (see ISS-021-adjacent fix in GH-16).
        val ks = KandraTestBackend.keyspaceName("koin")
        testKeyspace = ks
        return ks
    }

    @OptIn(ExperimentalKandraApi::class)
    @Test
    fun `generated koinDiWidgetSuspendRepo resolves kandraKoin's binding against a real cluster`() {

        testApplication {
            application {
                install(Koin) {}
                install(Kandra) {
                    useTestBackend()
                    keyspace = freshKeyspaceName()
                    autoCreateKeyspace = true
                    schemaMode = SchemaMode.AUTO_CREATE
                    register(KoinDiWidget::class)
                }
                kandraKoin()
            }
            // installs configure lazily on first application access — force startup now so Koin's
            // global context and the kandraKoin() bindings are ready before we resolve from them.
            // Resolution and use of the repo must happen *inside* this block: testApplication
            // stops the application (and closes Koin's scope) as soon as it exits.
            startApplication()

            // The generated accessor — no hand-typed named("KoinDiWidgetSuspendRepo") string, no cast at the call site.
            val repo = koinDiWidgetSuspendRepo()
            val widget = KoinDiWidget(id = UUID.randomUUID(), name = "widget-under-test")

            repo.save(widget)

            val found = repo.findById(widget.id)
            assertNotNull(found)
            assertEquals(widget, found)
        }
    }
}
