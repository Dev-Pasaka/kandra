
package io.kandra.kodein

import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.core.InternalKandraApi
import io.kandra.core.SchemaRegistry
import io.kandra.core.schema.TableSchema
import io.kandra.ktor.kandra
import io.kandra.ktor.kandraSession
import io.kandra.runtime.BatchEngine
import io.kandra.runtime.StatementBuilder
import io.kandra.runtime.repository.KandraRepository
import io.kandra.runtime.repository.KandraSuspendRepository
import io.ktor.server.application.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.singleton
import org.kodein.di.ktor.di

/**
 * Extends the application's Kodein DI container with a [KandraRepository] and
 * [KandraSuspendRepository] binding for every entity registered in [SchemaRegistry].
 *
 * Must be called after `install(Kandra)`. All repos share the plugin's [BatchEngine],
 * so shutdown protection and in-flight tracking apply to DI-managed repos too.
 *
 * Repositories are bound by tag:
 * - `KandraRepository<*>` tagged with `"${EntityName}"`
 * - `KandraSuspendRepository<*>` tagged with `"${EntityName}Suspend"`
 */
@Suppress("OPT_IN_USAGE")
fun Application.kandraKodein() {
    val runtime = kandra                     // the plugin-installed KandraRuntime
    val session = kandraSession
    val registry = SchemaRegistry

    di {
        registry.all().forEach { schema ->
            val entityClass = schema.entityClass
            val name = entityClass.simpleName
                ?: throw io.kandra.core.exception.KandraException("Anonymous entity classes are not supported.")

            // Share the plugin's batchEngine — same shutdown guard, same inFlightCount, same scope
            bind<KandraRepository<*>>(tag = name) with singleton {
                KandraRepository(session, schema, entityClass, runtime.batchEngine)
            }

            bind<KandraSuspendRepository<*>>(tag = "${name}Suspend") with singleton {
                KandraSuspendRepository(session, schema, entityClass, runtime.batchEngine)
            }
        }
    }
}

/**
 * Type-safe helper for registering a [KandraRepository] and [KandraSuspendRepository]
 * for a specific entity type [T] in a standalone Kodein DI module (outside Ktor).
 *
 * When used outside of the Kandra Ktor plugin, supply a [scope] whose lifetime matches
 * the owning component so eventual writes are properly cancelled on shutdown.
 */
@Suppress("OPT_IN_USAGE")
@OptIn(InternalKandraApi::class)
inline fun <reified T : Any> DI.MainBuilder.bindKandraRepository(
    session: CqlSession,
    schema: TableSchema,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    val engine = BatchEngine(session, StatementBuilder(session), scope)
    bind<KandraRepository<T>>() with singleton {
        KandraRepository(session, schema, T::class, engine)
    }
    bind<KandraSuspendRepository<T>>() with singleton {
        KandraSuspendRepository(session, schema, T::class, engine)
    }
}
