package io.kandra.kodein

import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.exception.KandraSchemaException
import io.kandra.kodein.admin.User as AdminUser
import io.kandra.kodein.billing.User as BillingUser
import io.kandra.ktor.KandraRuntimeKey
import io.kandra.ktor.KandraSessionKey
import io.kandra.runtime.BatchEngine
import io.kandra.runtime.KandraRuntime
import io.kandra.runtime.StatementBuilder
import io.kandra.runtime.codec.KandraCodec
import io.kandra.runtime.repository.KandraRepository
import io.kandra.runtime.repository.KandraSuspendRepository
import io.kandra.test.FakeKandraSession
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.kodein.di.direct
import org.kodein.di.instance
import org.kodein.di.ktor.closestDI
import java.util.UUID

@ScyllaTable("kodein_normal_widgets")
data class KodeinNormalWidget(@PartitionKey val id: UUID, val name: String)

/**
 * Unit tests for GH-35: `kandraKodein()` must detect same-simple-name entity collisions across
 * different packages/namespaces *before* building any Kodein bindings, and throw a clear
 * [KandraSchemaException] naming both fully-qualified classes — instead of letting Kodein's own
 * binding-override error surface a confusing generic message.
 */
class KandraKodeinQualifierCollisionTest {

    @AfterEach
    fun cleanup() {
        SchemaRegistry.clear()
    }

    @Test
    fun `kandraKodein throws KandraSchemaException when two entities share a simple name`() {
        SchemaRegistry.register(BillingUser::class)
        SchemaRegistry.register(AdminUser::class)

        testApplication {
            application {
                // No install(Kandra) needed — the collision check runs before kandraKodein() ever
                // touches the plugin runtime, session, or opens a Kodein di { } block.
                kandraKodein()
            }

            val thrown = assertThrows(Throwable::class.java) {
                startApplication()
            }

            val schemaException = generateSequence(thrown) { it.cause }
                .filterIsInstance<KandraSchemaException>()
                .firstOrNull()

            assertNotNull(schemaException, "Expected a KandraSchemaException in the cause chain, got: $thrown")
            val message = schemaException!!.message!!
            assertTrue(message.contains("User"))
            assertTrue(message.contains("io.kandra.kodein.billing.User"))
            assertTrue(message.contains("io.kandra.kodein.admin.User"))
        }
    }

    @Test
    fun `kandraKodein binds normally when no entities collide`() {
        SchemaRegistry.register(KodeinNormalWidget::class)
        lateinit var app: Application

        testApplication {
            application {
                app = this
                // Wire up the plugin's Application attributes by hand with a FakeKandraSession —
                // no real ScyllaDB connection needed to prove the non-colliding path still binds.
                val fakeSession = FakeKandraSession()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val batchEngine = BatchEngine(fakeSession, StatementBuilder(fakeSession), scope)
                val runtime = KandraRuntime(fakeSession, batchEngine, KandraCodec.default)
                attributes.put(KandraSessionKey, fakeSession)
                attributes.put(KandraRuntimeKey, runtime)

                kandraKodein()
            }

            startApplication()

            val di = app.closestDI()
            val repo = di.direct.instance<KandraRepository<*>>(tag = "KodeinNormalWidget")
            val suspendRepo = di.direct.instance<KandraSuspendRepository<*>>(tag = "KodeinNormalWidgetSuspend")
            assertNotNull(repo)
            assertNotNull(suspendRepo)
        }
    }
}
