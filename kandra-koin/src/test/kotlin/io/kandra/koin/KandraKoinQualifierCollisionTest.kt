package io.kandra.koin

import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.exception.KandraSchemaException
import io.kandra.koin.admin.User as AdminUser
import io.kandra.koin.billing.User as BillingUser
import io.kandra.ktor.KandraRuntimeKey
import io.kandra.ktor.KandraSessionKey
import io.kandra.runtime.BatchEngine
import io.kandra.runtime.KandraRuntime
import io.kandra.runtime.StatementBuilder
import io.kandra.runtime.codec.KandraCodec
import io.kandra.runtime.repository.KandraRepository
import io.kandra.runtime.repository.KandraSuspendRepository
import io.kandra.test.FakeKandraSession
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koin.core.component.KoinComponent
import org.koin.core.qualifier.named
import org.koin.ktor.plugin.Koin
import java.util.UUID

@ScyllaTable("koin_normal_widgets")
data class KoinNormalWidget(@PartitionKey val id: UUID, val name: String)

/**
 * Unit tests for GH-35: `kandraKoin()` must detect same-simple-name entity collisions across
 * different packages/namespaces *before* building any Koin bindings, and throw a clear
 * [KandraSchemaException] naming both fully-qualified classes — instead of letting Koin's own
 * `DefinitionOverrideException` surface a confusing generic error.
 */
class KandraKoinQualifierCollisionTest : KoinComponent {

    @AfterEach
    fun cleanup() {
        SchemaRegistry.clear()
    }

    @Test
    fun `kandraKoin throws KandraSchemaException when two entities share a simple name`() {
        SchemaRegistry.register(BillingUser::class)
        SchemaRegistry.register(AdminUser::class)

        testApplication {
            application {
                // No install(Kandra) / install(Koin) needed — the collision check runs before
                // kandraKoin() ever touches the plugin runtime, session, or Koin's own context.
                kandraKoin()
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
            assertTrue(message.contains("io.kandra.koin.billing.User"))
            assertTrue(message.contains("io.kandra.koin.admin.User"))
        }
    }

    @Test
    fun `kandraKoin binds normally when no entities collide`() {
        SchemaRegistry.register(KoinNormalWidget::class)

        testApplication {
            application {
                // Wire up the plugin's Application attributes by hand with a FakeKandraSession —
                // no real ScyllaDB connection needed to prove the non-colliding path still binds.
                val fakeSession = FakeKandraSession()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val batchEngine = BatchEngine(fakeSession, StatementBuilder(fakeSession), scope)
                val runtime = KandraRuntime(fakeSession, batchEngine, KandraCodec.default)
                attributes.put(KandraSessionKey, fakeSession)
                attributes.put(KandraRuntimeKey, runtime)

                install(Koin) {}
                kandraKoin()
            }

            startApplication()

            val repo = getKoin().get<KandraRepository<*>>(named("KoinNormalWidgetRepo"))
            val suspendRepo = getKoin().get<KandraSuspendRepository<*>>(named("KoinNormalWidgetSuspendRepo"))
            assertNotNull(repo)
            assertNotNull(suspendRepo)
        }
    }
}
