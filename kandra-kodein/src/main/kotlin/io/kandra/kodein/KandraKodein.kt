
package io.kandra.kodein

import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.core.InternalKandraApi
import io.kandra.core.SchemaRegistry
import io.kandra.core.exception.KandraSchemaException
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
 *
 * Tags are derived purely from [Class.getSimpleName], so two `@ScyllaTable` entities that share a
 * simple name (e.g. `billing.User` and `admin.User`) would otherwise collide and cause a confusing
 * generic binding-override error from Kodein itself. To surface a clear cause instead, this checks
 * for simple-name collisions across every registered entity *before* building any bindings and
 * throws [KandraSchemaException] naming the colliding classes. See GH-35.
 */
@Suppress("OPT_IN_USAGE")
fun Application.kandraKodein() {
    val registry = SchemaRegistry

    registry.all()
        .groupBy { it.entityClass.simpleName }
        .filterValues { it.size > 1 }
        .forEach { (simpleName, schemas) ->
            val qualifiedNames = schemas.joinToString(", ") { it.entityClass.qualifiedName ?: it.entityClass.toString() }
            throw KandraSchemaException(
                "Multiple @ScyllaTable entities share the simple name '$simpleName', which would " +
                    "produce colliding Kodein tags (\"$simpleName\" / \"${simpleName}Suspend\"): " +
                    "$qualifiedNames. Rename one of these classes so each entity has a unique simple name."
            )
        }

    val runtime = kandra                     // the plugin-installed KandraRuntime
    val session = kandraSession

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
