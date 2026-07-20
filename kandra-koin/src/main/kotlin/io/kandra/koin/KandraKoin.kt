package io.kandra.koin

import io.kandra.core.SchemaRegistry
import io.kandra.ktor.kandra
import io.kandra.ktor.kandraSession
import io.kandra.runtime.repository.KandraRepository
import io.kandra.runtime.repository.KandraSuspendRepository
import io.ktor.server.application.Application
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.ktor.ext.getKoin

/**
 * Registers a [KandraRepository] and [KandraSuspendRepository] in the Koin container
 * for every entity registered in [SchemaRegistry].
 *
 * Must be called after `install(Kandra)` and after Koin itself is installed.
 * All repos share the plugin's [io.kandra.runtime.BatchEngine], so shutdown protection
 * and in-flight tracking apply to Koin-managed repos too.
 *
 * Named qualifiers follow the pattern:
 * - `"${EntityName}Repo"` for [KandraRepository]
 * - `"${EntityName}SuspendRepo"` for [KandraSuspendRepository]
 */
@Suppress("OPT_IN_USAGE")
fun Application.kandraKoin() {
    val runtime = kandra                     // the plugin-installed KandraRuntime
    val session = kandraSession
    val registry = SchemaRegistry

    val repoModule = module {
        registry.all().forEach { schema ->
            val entityClass = schema.entityClass
            val name = entityClass.simpleName
                ?: throw io.kandra.core.exception.KandraException("Anonymous entity classes are not supported.")

            // Share the plugin's batchEngine — same shutdown guard, same inFlightCount, same scope
            single(named("${name}Repo")) {
                KandraRepository(session, schema, entityClass, runtime.batchEngine)
            }

            single(named("${name}SuspendRepo")) {
                KandraSuspendRepository(session, schema, entityClass, runtime.batchEngine)
            }
        }
    }

    getKoin().loadModules(listOf(repoModule))
}
