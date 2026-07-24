package io.kandra.ktor

import com.datastax.oss.driver.api.core.CqlSession
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kandra.core.DdlGenerator
import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.InternalKandraApi
import io.kandra.core.KandraEventListener
import io.kandra.core.SchemaRegistry
import io.kandra.core.exception.KandraAuthException
import io.kandra.core.exception.KandraSchemaException
import io.kandra.runtime.BatchEngine
import io.kandra.runtime.DebugConfig
import io.kandra.runtime.KandraRuntime
import io.kandra.runtime.RetryConfig
import io.kandra.runtime.StatementBuilder
import io.kandra.runtime.codec.KandraCodec
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val logger = KotlinLogging.logger {}

/**
 * Ktor plugin that wires ScyllaDB/Cassandra via the DataStax Java driver.
 *
 * ```kotlin
 * install(Kandra) {
 *     contactPoints = "localhost:9042"
 *     keyspace = "coinx"
 *     localDatacenter = "datacenter1"
 *     autoCreateKeyspace = true
 *     schemaMode = SchemaMode.AUTO_CREATE
 *     register(User::class, Wallet::class)
 *     pool { requestTimeoutMillis = 10_000 }
 *     auth { provider = KandraAuth.fromEnv() }
 *     retry { maxAttempts = 5 }
 *     debug { logQueries = true; logSlowQueriesMs = 500 }
 *     eventListener = object : KandraEventListener { ... }
 * }
 * ```
 */
@OptIn(InternalKandraApi::class, ExperimentalKandraApi::class)
val Kandra: ApplicationPlugin<KandraConfig> =
    createApplicationPlugin(name = "Kandra", createConfiguration = ::KandraConfig) {
        val config = pluginConfig

        if (config.keyspace.isBlank()) throw KandraSchemaException(
            "Kandra: 'keyspace' must be set in the plugin configuration."
        )

        val session = if (config.autoCreateKeyspace) {
            val bootstrapSession = buildCqlSession(config, withKeyspace = false)
            bootstrapSession.execute(keyspaceDdl(config.keyspace, config.replicationStrategy))
            bootstrapSession.execute("USE ${config.keyspace}")
            logger.info { "Kandra: keyspace '${config.keyspace}' ensured." }
            bootstrapSession
        } else {
            buildCqlSession(config)
        }

        config.eventListener?.onConnectionEstablished(config.contactPoints)
        logger.info { "Kandra: connected to ${config.contactPoints}, keyspace=${config.keyspace}" }

        // ── Permission validation ────────────────────────────────────────────
        if (config.validatePermissions && config.schemaMode != SchemaMode.NONE) {
            validatePermissions(session, config.keyspace, config.schemaMode)
        }

        config.entities.forEach { klass ->
            SchemaRegistry.register(klass)
            logger.debug { "Kandra: registered entity ${klass.simpleName}" }
        }

        when (config.schemaMode) {
            SchemaMode.AUTO_CREATE -> {
                SchemaRegistry.all().forEach { schema ->
                    DdlGenerator.allStatements(schema).forEach { ddl ->
                        session.execute(ddl)
                        logger.debug { "Kandra: DDL executed: $ddl" }
                    }
                }
            }
            SchemaMode.AUTO_MIGRATE -> {
                SchemaRegistry.all().forEach { schema ->
                    // Step 1: CREATE TABLE IF NOT EXISTS
                    DdlGenerator.allStatements(schema).forEach { ddl ->
                        session.execute(ddl)
                        logger.debug { "Kandra: DDL executed: $ddl" }
                    }
                    // Step 2: Diff entity vs Scylla columns and ALTER TABLE ADD for new ones
                    val rs = session.execute(
                        "SELECT column_name, type FROM system_schema.columns WHERE keyspace_name = '${config.keyspace}' AND table_name = '${schema.tableName}'"
                    )
                    val existingColumns = rs.all().associate { row ->
                        row.getString("column_name")!! to row.getString("type")!!
                    }
                    val entityColumns = buildList {
                        addAll(schema.partitionKeys)
                        addAll(schema.clusteringKeys)
                        addAll(schema.columns)
                        addAll(schema.lookupTables.map { it.indexColumn })
                    }.distinctBy { it.cqlName }

                    entityColumns.forEach { col ->
                        if (col.cqlName !in existingColumns) {
                            val alterDdl = DdlGenerator.alterTableAddColumn(schema, col)
                            session.execute(alterDdl)
                            logger.info { "Kandra AUTO_MIGRATE: added column '${col.cqlName}' to '${schema.tableName}'" }
                        } else {
                            val scyllaType = existingColumns[col.cqlName]!!.lowercase()
                            val expectedType = DdlGenerator.cqlTypeString(col).lowercase()
                            if (scyllaType != expectedType) {
                                logger.error {
                                    "Kandra AUTO_MIGRATE: type mismatch on '${schema.tableName}.${col.cqlName}' — " +
                                    "ScyllaDB has '$scyllaType' but entity declares '$expectedType'. " +
                                    "This will cause codec errors at runtime. " +
                                    "Fix the entity type to match the DB, or run: " +
                                    "ALTER TABLE ${schema.tableName} DROP ${col.cqlName}; then re-add."
                                }
                            }
                        }
                    }
                    // Columns in Scylla but not in entity — warn only
                    existingColumns.keys.filter { col -> entityColumns.none { it.cqlName == col } }.forEach { col ->
                        logger.warn {
                            "Kandra: Column '$col' exists in Scylla table '${schema.tableName}' but is not mapped in ${schema.entityClass.simpleName} entity. " +
                            "The data is still stored in ScyllaDB but will not be readable via Kandra. " +
                            "To remove it permanently, run: ALTER TABLE ${schema.tableName} DROP $col; " +
                            "Never run DROP COLUMN on a column with active data without a migration plan."
                        }
                    }
                }
            }
            SchemaMode.VALIDATE -> {
                SchemaRegistry.all().forEach { schema ->
                    val rs = session.execute(
                        "SELECT column_name FROM system_schema.columns WHERE keyspace_name = '${config.keyspace}' AND table_name = '${schema.tableName}'"
                    )
                    val existingColumns = rs.all().map { row -> row.getString("column_name") }.toSet()
                    val entityColumns = buildList {
                        addAll(schema.partitionKeys)
                        addAll(schema.clusteringKeys)
                        addAll(schema.columns)
                        addAll(schema.lookupTables.map { it.indexColumn })
                    }.map { it.cqlName }.toSet()

                    entityColumns.forEach { col ->
                        if (col !in existingColumns) throw KandraSchemaException(
                            "Column '$col' missing from table '${schema.tableName}' in Scylla. " +
                                "Run migration or set schemaMode = SchemaMode.AUTO_MIGRATE."
                        )
                    }
                    existingColumns.filter { it !in entityColumns }.forEach { col ->
                        logger.warn { "Kandra: column '$col' exists in Scylla table '${schema.tableName}' but is not in entity — ignored." }
                    }
                }
            }
            SchemaMode.NONE -> logger.info { "Kandra: schemaMode=NONE — skipping all DDL." }
        }

        // ── Strict Mode (GH #5) multi-DC topology signal ─────────────────────
        // Not user-set — derived automatically from loadBalancing.allowedRemoteDcs so that setting
        // consistency { strictMode = true } combines with the loadBalancing config a multi-DC deployment
        // already sets for failover, with no separate flag for the user to remember.
        config.consistency.multiDcTopology = config.loadBalancing.allowedRemoteDcs.isNotEmpty()

        // ── Build runtime ────────────────────────────────────────────────────
        // Bounded scope: eventual writes and credential refresh are tied to application lifetime.
        // Cancelled in ApplicationStopped after session.close(), so no coroutine can fire on a closed session.
        val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val statementBuilder = StatementBuilder(
            session = session,
            codec = config.codec,
            debugConfig = config.debug,
            consistencyConfig = config.consistency,
            cacheSize = config.preparedStatementCacheSize
        )
        val batchEngine = BatchEngine(
            session = session,
            statementBuilder = statementBuilder,
            scope = pluginScope,
            eventListener = config.eventListener,
            retryConfig = config.retry,
            debugConfig = config.debug,
            codec = config.codec
        ).also { engine ->
            engine.configureBatchLimits(config.batchWarnThresholdKb, config.batchMaxChunkSize, config.batchAutoChunk, config.tombstoneWarnThreshold)
            if (config.metrics.enabled) {
                config.metrics.recorder?.let { engine.setMetrics(it) }
                    ?: logger.warn { "Kandra: metrics.enabled=true but no recorder was configured — metrics will not be recorded." }
            }
            @Suppress("UNCHECKED_CAST")
            config.validators.forEach { (klass, validator) ->
                engine.registerValidator(klass as kotlin.reflect.KClass<Any>, validator as io.kandra.core.KandraValidator<Any>)
            }
        }
        val runtime = KandraRuntime(session, batchEngine, config.codec)

        application.attributes.put(KandraSessionKey, session)
        application.attributes.put(KandraCodecKey, config.codec)
        application.attributes.put(KandraRuntimeKey, runtime)
        config.eventListener?.let { application.attributes.put(KandraEventListenerKey, it) }

        // ── Health check route ───────────────────────────────────────────────
        if (config.healthCheck) {
            application.routing {
                get("/kandra/health") {
                    if (runtime.isHealthy()) {
                        call.respondText("""{"status":"UP"}""", ContentType.Application.Json, HttpStatusCode.OK)
                    } else {
                        call.respondText("""{"status":"DOWN"}""", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                    }
                }
            }
            logger.info { "Kandra: health check route registered at GET /kandra/health" }
        }

        // ── Credential rotation ──────────────────────────────────────────────
        if (config.auth.refreshIntervalSeconds != null) {
            val intervalMs = config.auth.refreshIntervalSeconds!! * 1000
            pluginScope.launch {
                while (true) {
                    delay(intervalMs)
                    try {
                        config.auth.provider.getCredentials()
                        config.eventListener?.onCredentialRefreshed()
                        logger.info { "Kandra: credentials refreshed successfully." }
                    } catch (e: Exception) {
                        logger.error(e) { "Kandra: credential refresh failed." }
                        config.eventListener?.onAuthFailed(config.contactPoints, e)
                    }
                }
            }
        }

        // ── Graceful shutdown ────────────────────────────────────────────────
        application.environment.monitor.subscribe(ApplicationStopping) {
            if (config.shutdown.graceful) {
                runtime.isShuttingDown.set(true)
                logger.info { "Kandra: shutdown signalled — draining in-flight queries (timeout ${config.shutdown.drainTimeoutMs}ms)" }
                val deadline = System.currentTimeMillis() + config.shutdown.drainTimeoutMs
                while (runtime.inFlightCount.get() > 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50)
                }
                if (runtime.inFlightCount.get() > 0) {
                    logger.warn {
                        "${runtime.inFlightCount.get()} queries still in-flight after ${config.shutdown.drainTimeoutMs}ms drain timeout — forcing close"
                    }
                }
            }
        }

        application.environment.monitor.subscribe(ApplicationStopped) {
            logger.info { "Kandra: closing CqlSession." }
            session.close()
            // Cancel after session close so no in-flight eventual writes can start new work
            pluginScope.cancel("Kandra plugin stopped")
        }
    }

@InternalKandraApi
private fun validatePermissions(session: CqlSession, keyspace: String, schemaMode: SchemaMode) {
    try {
        val role = session.execute("SELECT role FROM system.local").one()?.getString("role")
        if (role == null) {
            KotlinLogging.logger("io.kandra.ktor.Kandra").info {
                "Kandra: Permission validation skipped — system.local.role is not populated (common on ScyllaDB). " +
                "Ensure the service role has SELECT, MODIFY, and ALTER permissions on keyspace '$keyspace'."
            }
            return
        }

        val rs = session.execute(
            "SELECT permissions FROM system_auth.role_permissions WHERE role = ? AND resource = ?",
            role, "data/$keyspace"
        )
        val permissions = rs.one()?.getSet("permissions", String::class.java) ?: emptySet()

        if ("SELECT" !in permissions && "ALL" !in permissions) {
            throw KandraAuthException(
                "Role '$role' lacks SELECT permission on keyspace '$keyspace'. " +
                "Grant: GRANT SELECT ON KEYSPACE $keyspace TO $role"
            )
        }
        if ("MODIFY" !in permissions && "ALL" !in permissions) {
            throw KandraAuthException(
                "Role '$role' lacks MODIFY permission on keyspace '$keyspace'. " +
                "Grant: GRANT MODIFY ON KEYSPACE $keyspace TO $role"
            )
        }
        if (schemaMode != SchemaMode.NONE && "ALTER" !in permissions && "ALL" !in permissions) {
            KotlinLogging.logger("io.kandra.ktor.Kandra").warn {
                "Role '$role' lacks ALTER permission on keyspace '$keyspace'. " +
                "This is required for schemaMode = AUTO_CREATE. " +
                "Grant: GRANT ALTER ON KEYSPACE $keyspace TO $role"
            }
        }
    } catch (e: KandraAuthException) {
        throw e
    } catch (e: Exception) {
        // system_auth may not be accessible in some configurations; skip silently
        KotlinLogging.logger("io.kandra.ktor.Kandra").debug { "Permission check skipped: ${e.message}" }
    }
}

val KandraSessionKey: AttributeKey<CqlSession> = AttributeKey("KandraSession")
val KandraCodecKey: AttributeKey<KandraCodec> = AttributeKey("KandraCodec")
val KandraRuntimeKey: AttributeKey<KandraRuntime> = AttributeKey("KandraRuntime")
@OptIn(ExperimentalKandraApi::class)
val KandraEventListenerKey: AttributeKey<KandraEventListener> = AttributeKey("KandraEventListener")

val Application.kandraSession: CqlSession get() = attributes[KandraSessionKey]
val Application.kandraCodec: KandraCodec get() = attributes[KandraCodecKey]
val Application.kandra: KandraRuntime get() = attributes[KandraRuntimeKey]
