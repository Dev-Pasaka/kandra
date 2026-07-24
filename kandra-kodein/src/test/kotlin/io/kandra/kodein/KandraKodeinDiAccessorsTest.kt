package io.kandra.kodein

import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.KandraAuth
import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.ktor.Kandra
import io.kandra.ktor.SchemaMode
import io.kandra.test.KandraTestcontainers
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.kodein.di.ktor.closestDI
import java.util.UUID

@ScyllaTable("kodein_di_widgets")
data class KodeinDiWidget(
    @PartitionKey val id: UUID,
    val name: String
)

/**
 * Real, end-to-end proof for GH-17: `kandra-codegen`'s generated `KodeinDiWidgetKodeinDi.kt`
 * (typed `kodeinDiWidgetRepo()` / `kodeinDiWidgetSuspendRepo()` accessors) resolves the *exact
 * same* `tag = "KodeinDiWidgetSuspend"` binding that `Application.kandraKodein()` creates —
 * against a real ScyllaDB/Cassandra Testcontainers cluster and a real Kodein container, not a
 * simulation.
 *
 * `KodeinDiWidgetKodeinDi.kt` is generated automatically because `kandra-kodein`'s own build
 * already depends on `kodein-di` (see `kandra-kodein/build.gradle.kts`'s
 * `kspTest(project(":kandra-codegen"))`) — this is the exact "both codegen and the DI framework
 * are on the classpath together" scenario the feature targets, exercised by the module that
 * actually ships `kandraKodein()`.
 */
class KandraKodeinDiAccessorsTest {

    private var testKeyspace: String? = null

    @AfterEach
    fun cleanup() {
        SchemaRegistry.clear()
        testKeyspace?.let { ks ->
            CqlSession.builder()
                .addContactPoint(KandraTestcontainers.container.contactPoint)
                .withLocalDatacenter(KandraTestcontainers.container.localDatacenter)
                .build().use { it.execute("DROP KEYSPACE IF EXISTS $ks") }
        }
        testKeyspace = null
    }

    private fun freshKeyspaceName(): String {
        // "kandra_kodein_" (14 chars) + a 32-char dash-stripped UUID = 46 chars, safely under
        // Cassandra's 48-character keyspace name limit (see ISS-021-adjacent fix in GH-16).
        val ks = "kandra_kodein_${UUID.randomUUID().toString().replace("-", "")}"
        testKeyspace = ks
        return ks
    }

    @OptIn(ExperimentalKandraApi::class)
    @Test
    fun `generated kodeinDiWidgetSuspendRepo resolves kandraKodein's binding against a real cluster`() {
        val cp = KandraTestcontainers.container.contactPoint
        lateinit var app: Application

        testApplication {
            application {
                app = this
                install(Kandra) {
                    contactPoints = "${cp.hostString}:${cp.port}"
                    localDatacenter = KandraTestcontainers.container.localDatacenter
                    keyspace = freshKeyspaceName()
                    autoCreateKeyspace = true
                    schemaMode = SchemaMode.AUTO_CREATE
                    register(KodeinDiWidget::class)
                    // The Cassandra Testcontainers image runs with AllowAllAuthenticator (no auth
                    // required) — use a blank-credentials provider instead of the default
                    // KandraAuth.fromEnv(), which throws if SCYLLA_USERNAME/SCYLLA_PASSWORD aren't
                    // set in the environment (they never are in CI or local dev for this test).
                    auth { provider = KandraAuth.static("", "") }
                }
                kandraKodein() // installs the DIPlugin itself via `di { ... }` — no separate install(DIPlugin) needed
            }
            // installs configure lazily on first application access — force startup now so
            // kandraKodein()'s bindings are ready before we resolve from them. Resolution and use
            // of the repo must happen *inside* this block, using the captured `app` reference,
            // since testApplication stops the application as soon as it exits.
            startApplication()

            // The generated accessor — no hand-typed tag = "KodeinDiWidgetSuspend" string, no cast at the call site.
            val repo = app.closestDI().kodeinDiWidgetSuspendRepo()
            val widget = KodeinDiWidget(id = UUID.randomUUID(), name = "widget-under-test")

            repo.save(widget)

            val found = repo.findById(widget.id)
            assertNotNull(found)
            assertEquals(widget, found)
        }
    }
}
