package io.kandra.ktor

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.Row
import com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kandra.core.CqlNaming
import io.kandra.core.DdlGenerator
import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.InternalKandraApi
import io.kandra.core.KandraEventListener
import io.kandra.core.SchemaRegistry
import io.kandra.core.exception.KandraAuthException
import io.kandra.core.exception.KandraSchemaException
import io.kandra.core.schema.TableSchema
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
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * How long a DDL bootstrap claim (see [claimAndRunDdlBootstrap]) is given the benefit of the doubt
 * before a waiting instance treats it as abandoned by a crashed claimant and reclaims it itself.
 * Deliberately much shorter than [io.kandra.migrate.KandraMigrationRunner]'s own
 * `staleClaimThreshold` for per-migration claims (default 10 minutes) -- `CREATE TABLE IF NOT
 * EXISTS`/`ALTER TABLE ADD` for a handful of registered entities is a matter of seconds, not the
 * potentially long-running, arbitrary CQL a hand-written migration's `up()` can execute. Not
 * user-configurable today (see ISS-071/GH #79) -- raise this if a deployment genuinely has enough
 * registered entities that AUTO_MIGRATE's column-diff pass takes longer than this on a slow cluster.
 */
private val DDL_CLAIM_STALE_THRESHOLD: Duration = Duration.ofMinutes(2)

/** How long to sleep between polls while waiting for another instance's DDL claim to resolve. */
private const val DDL_CLAIM_POLL_MS = 200L

/**
 * Ktor plugin that wires ScyllaDB/Cassandra via the DataStax Java driver.
 *
 * Built and tested against Ktor 3.6.x (`ktor-server-core` 3.6.0, see `gradle.properties`'
 * `ktorVersion`). Ktor 2.x is not supported; see GH #160.
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

        // ISS-105: validates the default keyspace (GH #65: blank / valid-identifier checks, unchanged
        // for the single-keyspace case) plus every extra keyspace declared via keyspace(name) { },
        // including NetworkTopologyStrategy DC names (GH #65), all before any connection is attempted.
        val keyspaceSpecs = resolveKeyspaceSpecs(config)
        val defaultKeyspace = config.keyspace
        val hasDefaultKeyspace = defaultKeyspace.isNotBlank()

        // GH #140: EACH_QUORUM is write-only (Cassandra/Scylla reject it server-side for reads) and
        // SERIAL/LOCAL_SERIAL are only valid as a conditional write's *serial* consistency, never as
        // a table's regular write consistency -- catch either misconfiguration here, before any
        // connection is attempted, rather than letting it surface as an opaque driver-level failure
        // the first time a query actually runs. `resolveReadConsistency`/`resolveWriteConsistency` in
        // kandra-runtime's StatementBuilder re-validate the fully-resolved value (per-call override /
        // @ReadConsistency|@WriteConsistency / this default) at query time too, so a bad
        // per-call override or class annotation is still caught even though it isn't visible here.
        if (!config.consistency.defaultRead.isValidForRead) throw KandraSchemaException(
            "Kandra: consistency.defaultRead = ${config.consistency.defaultRead} is not valid for a " +
            "read operation -- EACH_QUORUM is a write-only consistency level; Cassandra/Scylla reject " +
            "it for reads server-side. Use one of: ONE, TWO, THREE, QUORUM, ALL, LOCAL_ONE, " +
            "LOCAL_QUORUM, SERIAL, LOCAL_SERIAL."
        )
        if (!config.consistency.defaultWrite.isValidForWrite) throw KandraSchemaException(
            "Kandra: consistency.defaultWrite = ${config.consistency.defaultWrite} is not valid for a " +
            "write operation -- SERIAL/LOCAL_SERIAL are only valid as the serialConsistency parameter " +
            "of a conditional write (saveIfNotExists()/update()'s IF check), never as a table's " +
            "regular write consistency; Cassandra/Scylla reject it server-side. Use one of: ONE, TWO, " +
            "THREE, QUORUM, ALL, LOCAL_ONE, LOCAL_QUORUM, EACH_QUORUM."
        )

        // ISS-105: register entities before connecting so an entity naming an undeclared keyspace
        // (or lacking one when there is no default) fails fast, without leaking an open session.
        config.entities.forEach { klass ->
            SchemaRegistry.register(klass)
            logger.debug { "Kandra: registered entity ${klass.simpleName}" }
        }
        validateEntityKeyspaces(config, SchemaRegistry.all())

        // Exactly ONE CqlSession regardless of keyspace count. If any keyspace is auto-created, the
        // session is opened without a keyspace, the keyspaces are created, and then the default one
        // (if any) is USEd -- the single-keyspace flow is unchanged.
        val anyAutoCreate = keyspaceSpecs.any { it.autoCreate }
        val sessionHandle = if (anyAutoCreate) {
            val bootstrapHandle = buildCqlSession(config, withKeyspace = false)
            keyspaceSpecs.filter { it.autoCreate }.forEach { spec ->
                // keyspaceDdl (CqlSessionBuilder.kt) also validates dcReplicationMap's DC-name keys
                // before splicing them into the CREATE KEYSPACE literal (GH #65).
                bootstrapHandle.session.execute(keyspaceDdl(spec.name, spec.strategy))
                logger.info { "Kandra: keyspace '${spec.name}' ensured." }
            }
            if (hasDefaultKeyspace) bootstrapHandle.session.execute("USE $defaultKeyspace")
            bootstrapHandle
        } else {
            buildCqlSession(config)
        }
        val session = sessionHandle.session

        config.eventListener?.onConnectionEstablished(config.contactPoints)
        logger.info {
            if (keyspaceSpecs.size <= 1 && hasDefaultKeyspace) {
                "Kandra: connected to ${config.contactPoints}, keyspace=$defaultKeyspace"
            } else {
                "Kandra: connected to ${config.contactPoints}, defaultKeyspace=" +
                    "${defaultKeyspace.ifBlank { "<none>" }}, keyspaces=${keyspaceSpecs.map { it.name }}"
            }
        }

        // ── Permission validation (per keyspace) ─────────────────────────────
        if (config.validatePermissions && config.schemaMode != SchemaMode.NONE) {
            keyspaceSpecs.forEach { validatePermissions(session, it.name, config.schemaMode) }
        }

        val allSchemas = SchemaRegistry.all()
        when (config.schemaMode) {
            SchemaMode.AUTO_CREATE -> keyspaceSpecs.forEach { spec ->
                val schemas = schemasForKeyspace(allSchemas, spec, defaultKeyspace)
                if (!spec.isDefault && schemas.isEmpty()) return@forEach
                // GH #79/ISS-071: a rolling deploy of N replicas otherwise races N instances' worth
                // of `CREATE TABLE IF NOT EXISTS` against the same keyspace concurrently -- a known
                // schema-disagreement risk on Cassandra/Scylla. Guard the whole pass behind a single
                // cluster-wide LWT claim so exactly one instance runs it per startup wave; the rest
                // wait for it to finish rather than racing the same DDL. See claimAndRunDdlBootstrap.
                //
                // GH #90/ISS-077: the claim is keyed by a fingerprint of the currently-registered
                // schema, not a fixed constant -- a claim marked DONE for an earlier fingerprint must
                // never block a later deploy whose registered entities (or their columns) changed,
                // or AUTO_CREATE/AUTO_MIGRATE would only ever run once per keyspace's entire lifetime.
                // See schemaFingerprint's KDoc.
                //
                // ISS-105: one claim per keyspace, over that keyspace's entities only, with the
                // keyspace in the lock name (non-default keyspaces) and the lock table living in the
                // target keyspace itself.
                claimAndRunDdlBootstrap(
                    session, ddlBootstrapLockName(schemas, lockKeyspaceSuffix(spec)), lockTableKeyspace(spec)
                ) {
                    schemas.forEach { schema ->
                        DdlGenerator.allStatements(schema).forEach { ddl ->
                            session.execute(ddl)
                            logger.debug { "Kandra: DDL executed: $ddl" }
                        }
                    }
                }
            }
            SchemaMode.AUTO_MIGRATE -> keyspaceSpecs.forEach { spec ->
                val schemas = schemasForKeyspace(allSchemas, spec, defaultKeyspace)
                if (!spec.isDefault && schemas.isEmpty()) return@forEach
                // Same claim guard and fingerprinted lock name as AUTO_CREATE above (GH #79, GH #90)
                // -- this branch also runs ALTER TABLE ADD, which is exactly the multi-statement,
                // multi-table DDL the guard exists to serialize.
                claimAndRunDdlBootstrap(
                    session, ddlBootstrapLockName(schemas, lockKeyspaceSuffix(spec)), lockTableKeyspace(spec)
                ) {
                    schemas.forEach { schema -> autoMigrateTable(session, schema, spec.name) }
                }
            }
            SchemaMode.VALIDATE -> keyspaceSpecs.forEach { spec ->
                schemasForKeyspace(allSchemas, spec, defaultKeyspace).forEach { schema ->
                    validateTable(session, schema, spec.name)
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

        // ── Health check route (GH #36) ──────────────────────────────────────
        // Unauthenticated by design (see KandraConfig.healthCheck doc) and, absent the cache
        // below, ran a live `SELECT release_version FROM system.local` against the cluster on
        // every single hit — a misconfigured or abusive probe storm translated 1:1 into cluster
        // queries. healthCheckCache debounces that: within healthCheckCacheTtlMs of the last
        // real check, a request gets the cached result instead of hitting the cluster again.
        if (config.healthCheck) {
            val healthCheckCache = HealthCheckCache(config.healthCheckCacheTtlMs)
            application.attributes.put(KandraHealthCheckCacheKey, healthCheckCache)
            application.routing {
                get("/kandra/health") {
                    if (healthCheckCache.check { runtime.isHealthy() }) {
                        call.respondText("""{"status":"UP"}""", ContentType.Application.Json, HttpStatusCode.OK)
                    } else {
                        call.respondText("""{"status":"DOWN"}""", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                    }
                }
            }
            logger.info {
                "Kandra: health check route registered at GET /kandra/health " +
                    "(cached for ${config.healthCheckCacheTtlMs}ms)"
            }
        }

        // ── Credential rotation (GH #61) ─────────────────────────────────────
        // Previously this loop called config.auth.provider.getCredentials() and discarded the
        // result -- nothing pushed the refreshed username/password into the live CqlSession, so
        // onCredentialRefreshed()/the success log fired while the session kept using its
        // startup-time credentials indefinitely. sessionHandle.liveAuthProvider (set in
        // buildCqlSession, CqlSessionBuilder.kt) is the ProgrammaticPlainTextAuthProvider actually
        // backing the session's auth -- pushing new values into it via setUsername/setPassword is
        // picked up by the driver on every subsequent authentication (new connections, reconnects),
        // without a session rebuild.
        if (config.auth.refreshIntervalSeconds != null) {
            val intervalMs = config.auth.refreshIntervalSeconds!! * 1000
            val liveAuthProvider = sessionHandle.liveAuthProvider
            pluginScope.launch {
                while (true) {
                    delay(intervalMs)
                    try {
                        val creds = config.auth.provider.getCredentials()
                        if (liveAuthProvider != null) {
                            liveAuthProvider.setUsername(creds.username)
                            liveAuthProvider.setPassword(creds.password)
                            config.eventListener?.onCredentialRefreshed()
                            logger.info { "Kandra: credentials refreshed successfully." }
                        } else {
                            // The session was opened without an active auth provider (the provider
                            // returned a blank username at startup, e.g. AllowAllAuthenticator) --
                            // there is no live session auth to update, so refreshing here would be
                            // a no-op. Say so explicitly instead of firing a misleading success event.
                            logger.warn {
                                "Kandra: credential refresh fetched new credentials, but the session " +
                                "was opened without an active auth provider (initial credentials were " +
                                "blank) -- there is no live session auth to update. Restart with " +
                                "non-blank initial credentials for rotation to take effect."
                            }
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "Kandra: credential refresh failed." }
                        config.eventListener?.onAuthFailed(config.contactPoints, e)
                    }
                }
            }
        }

        // ── Graceful shutdown (GH #34) ────────────────────────────────────────
        // `monitor.subscribe(ApplicationStopping) { ... }` registers a plain, non-suspend
        // `(Application) -> Unit` handler (see io.ktor.events.Events — EventHandler<T> = (T) -> Unit)
        // that Ktor's `Events.raise` invokes synchronously, in subscription order, on the
        // shutdown-triggering thread. Because of that contract, this hook must still occupy the
        // calling thread until the drain finishes or times out — ApplicationStopped (which closes
        // the session) must not run until this returns, and there's no suspend-aware variant of
        // this hook in Ktor 3.6.0's monitor API to restructure onto instead.
        //
        // What we control is *how* we occupy the thread while waiting: rather than a raw
        // `Thread.sleep(50)` busy-wait loop with a manually computed deadline, poll with suspending
        // `delay` inside `withTimeoutOrNull` (a proper timeout construct — no manual deadline math)
        // and run it via `runBlocking` on `pluginScope`'s own context (SupervisorJob + Dispatchers.IO,
        // already scoped to application lifetime — not an ad-hoc GlobalScope) instead of spinning a
        // raw thread-sleep loop.
        application.environment.monitor.subscribe(ApplicationStopping) {
            if (config.shutdown.graceful) {
                runtime.isShuttingDown.set(true)
                logger.info { "Kandra: shutdown signalled — draining in-flight queries (timeout ${config.shutdown.drainTimeoutMs}ms)" }
                runBlocking(pluginScope.coroutineContext) {
                    withTimeoutOrNull(config.shutdown.drainTimeoutMs) {
                        while (runtime.inFlightCount.get() > 0) {
                            delay(50)
                        }
                    }
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

/** AUTO_MIGRATE's per-table pass: create-if-missing, then diff columns against `system_schema` and ALTER ADD. */
private fun autoMigrateTable(session: CqlSession, schema: TableSchema, keyspaceName: String) {
        // Step 1: CREATE TABLE IF NOT EXISTS
        DdlGenerator.allStatements(schema).forEach { ddl ->
            session.execute(ddl)
            logger.debug { "Kandra: DDL executed: $ddl" }
        }
        // Step 2: Diff entity vs Scylla columns and ALTER TABLE ADD for new ones
        // GH #65: bound params instead of a string-interpolated literal -- keyspace_name
        // was previously spliced directly into a single-quoted CQL string literal.
        val rs = session.execute(
            "SELECT column_name, type FROM system_schema.columns WHERE keyspace_name = ? AND table_name = ?",
            keyspaceName.lowercase(), schema.tableName
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
                // GH #92/ISS-079: CQL has no way to ALTER TABLE ADD a column into an
                // existing primary key. A missing partition/clustering key column
                // means the entity's key shape changed incompatibly since the table
                // was created (e.g. a new @ClusteringKey added for versioning) --
                // silently emitting a plain ALTER TABLE ADD would create it as a
                // regular column while Kandra keeps treating it as part of row
                // identity, so two logically-distinct rows collapse onto the same
                // physical row and silently overwrite each other's other columns.
                // This always needs a manual, explicit migration -- never a safe
                // auto-migration -- so refuse loudly instead of corrupting data.
                if (col.isPartitionKey || col.clusteringKey != null) {
                    throw KandraSchemaException(
                        "Kandra AUTO_MIGRATE: entity '${schema.entityClass.simpleName}' declares " +
                        "'${col.cqlName}' as a ${if (col.isPartitionKey) "partition" else "clustering"} " +
                        "key, but table '${schema.tableName}' does not have it as part of its " +
                        "primary key (or at all). CQL cannot ALTER TABLE ADD a column into an " +
                        "existing primary key -- ALTER TABLE ${schema.tableName} ADD " +
                        "${col.cqlName} would create it as a plain column, silently mapping " +
                        "distinct logical rows onto the same physical row. This requires a " +
                        "manual migration (e.g. via kandra-migrate: create a new table with the " +
                        "desired key and backfill, or recreate this table if the data can be " +
                        "discarded) -- refusing to auto-migrate."
                    )
                }
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

/** VALIDATE's per-table pass: every entity column must exist in the table (looked up in [keyspaceName]). */
private fun validateTable(session: CqlSession, schema: TableSchema, keyspaceName: String) {
        // GH #65: bound params instead of a string-interpolated literal.
        val rs = session.execute(
            "SELECT column_name FROM system_schema.columns WHERE keyspace_name = ? AND table_name = ?",
            keyspaceName.lowercase(), schema.tableName
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

/**
 * Lock key prefix used for the [SchemaMode.AUTO_CREATE]/[SchemaMode.AUTO_MIGRATE] DDL bootstrap
 * pass. One key covers every registered entity for a given install -- see
 * [claimAndRunDdlBootstrap]'s KDoc for why this is a single claim for the whole pass rather than
 * one per table. Always used together with [schemaFingerprint] via [ddlBootstrapLockName] -- see
 * that function's KDoc (GH #90/ISS-077) for why the fixed prefix alone is not a safe lock key.
 */
private const val DDL_BOOTSTRAP_SCHEMA_LOCK = "schema-bootstrap"

/**
 * Lock name for the [SchemaMode.AUTO_CREATE]/[SchemaMode.AUTO_MIGRATE] DDL bootstrap claim (GH
 * #90/ISS-077). Combines the fixed [DDL_BOOTSTRAP_SCHEMA_LOCK] prefix with a fingerprint of the
 * currently-registered [schemas] so that a claim marked `DONE` for one schema generation never
 * blocks a later deploy whose registered entities (or their columns) have changed.
 *
 * Before this existed, the lock was keyed by [DDL_BOOTSTRAP_SCHEMA_LOCK] alone: once any deploy
 * won the claim and finished, every later deploy -- including one that registered a brand new
 * entity, or added a column to an existing one under `AUTO_MIGRATE` -- found the row already
 * `DONE` and skipped running DDL entirely, silently. Fingerprinting the lock name means a changed
 * schema lands on a fresh, never-claimed row and runs its own DDL pass, while replicas within the
 * *same* deploy wave (identical registered schema, identical fingerprint) still correctly
 * serialize against each other exactly as before -- only one of them wins the claim, the rest wait
 * and skip. See [schemaFingerprint] for what participates in the fingerprint.
 */
internal fun ddlBootstrapLockName(schemas: List<TableSchema>, keyspace: String? = null): String =
    if (keyspace == null) "$DDL_BOOTSTRAP_SCHEMA_LOCK-${schemaFingerprint(schemas)}"
    else "$DDL_BOOTSTRAP_SCHEMA_LOCK-$keyspace-${schemaFingerprint(schemas)}"

/**
 * Fingerprints the registered schema by hashing the exact DDL each table would emit
 * ([DdlGenerator.allStatements]) -- so the fingerprint changes whenever the physical DDL that
 * `AUTO_CREATE`/`AUTO_MIGRATE` would run changes: a new table, a new column, a changed clustering
 * order, and so on. Hashing rendered DDL rather than, say, just table names keeps this in lockstep
 * with what the bootstrap pass actually does, without needing to independently reason about which
 * [TableSchema] fields matter.
 *
 * Order-independent: [schemas] is sorted by table name before hashing, so registering the same
 * entities in a different order (e.g. `register(B::class, A::class)` vs `register(A::class,
 * B::class)`) produces the same fingerprint and does not spuriously trigger a new claim.
 */
internal fun schemaFingerprint(schemas: List<TableSchema>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    schemas.sortedBy { it.tableName }.forEach { schema ->
        DdlGenerator.allStatements(schema).forEach { ddl ->
            digest.update(ddl.toByteArray(Charsets.UTF_8))
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Runs [action] under a cluster-wide LWT claim keyed by [lockName], so that when several
 * application instances start concurrently against the same keyspace -- a rolling deploy of N
 * replicas is the normal topology this guards against (GH #79/ISS-071) -- only one of them
 * actually executes [action] (the [SchemaMode.AUTO_CREATE]/[SchemaMode.AUTO_MIGRATE] DDL), while
 * the others detect the claim and wait for it to finish rather than racing the same `CREATE
 * TABLE`/`ALTER TABLE` statements against the cluster concurrently -- a known schema-disagreement
 * risk on Cassandra/Scylla.
 *
 * Mirrors the claim/staleness pattern [io.kandra.migrate.KandraMigrationRunner] already uses for
 * its own per-migration claims (GH #26/#64), backed by a small coordination table,
 * `kandra_ddl_locks`, holding one row per [lockName]:
 * - **Claim**: `INSERT ... IF NOT EXISTS` -- exactly one racing instance wins.
 * - **Losers wait**: poll the row every [DDL_CLAIM_POLL_MS] until it reads `DONE` (the winner
 *   finished -- skip running [action] here at all) or its claim goes stale.
 * - **Staleness**: measured entirely by the cluster's own clock (`toTimestamp(now())`, never this
 *   JVM's `Instant.now()`) against [DDL_CLAIM_STALE_THRESHOLD] -- the same clock-skew-proof
 *   approach as [io.kandra.migrate.KandraMigrationRunner] (see its KDoc, "Clock source for
 *   staleness", GH #64). A claim older than the threshold is presumed abandoned by a crashed
 *   claimant (OOM, `SIGKILL`, an uncaught `Error` mid-DDL); one waiter reclaims it via a
 *   compare-and-set `UPDATE ... IF claimed_at = ?` (so only one of several simultaneous waiters
 *   wins the reclaim) and runs [action] itself.
 *
 * Unlike [io.kandra.migrate.KandraMigrationRunner]'s migration claims -- which deliberately halt
 * (or throw) rather than let a caller barrel ahead of an unresolved claim, because later
 * migrations may depend on earlier DDL -- a waiting instance here always converges on running
 * (startup should not hang indefinitely, and `CREATE TABLE IF NOT EXISTS`/`ALTER TABLE ADD` are
 * idempotent, so there's no ordering hazard in retrying).
 *
 * The one race this cannot remove: creating `kandra_ddl_locks` itself, the first time it doesn't
 * yet exist. That's a single `CREATE TABLE IF NOT EXISTS` for one small, schema-stable table (no
 * column is ever added to it after creation) -- a far narrower and lower-impact race than the one
 * this guards against, which is N instances concurrently running `CREATE TABLE`/`ALTER TABLE`
 * across every registered entity. [io.kandra.migrate.KandraMigrationRunner]'s own bootstrap of its
 * `kandra_migrations` table carries the identical, already-accepted residual race (see its class
 * KDoc, "Multi-instance caveat").
 */
@InternalKandraApi
internal fun claimAndRunDdlBootstrap(
    session: CqlSession,
    lockName: String,
    lockKeyspace: String? = null,
    action: () -> Unit
) {
    val table = ddlLockTableName(lockKeyspace)
    ensureDdlLockTable(session, table)
    val holder = UUID.randomUUID().toString()

    if (tryClaimDdlLock(session, table, lockName, holder)) {
        logger.info { "Kandra: claimed DDL bootstrap lock '$lockName' -- running schema DDL." }
        runClaimedDdlAction(session, table, lockName, holder, action)
        return
    }

    logger.info {
        "Kandra: another instance holds the DDL bootstrap lock '$lockName' -- waiting for it to " +
        "finish rather than racing the same DDL concurrently."
    }
    while (true) {
        val row = session.execute(
            session.prepare("SELECT holder, status, claimed_at FROM $table WHERE lock_name = ?")
                .bind(lockName)
        ).one()

        if (row == null) {
            // The row vanished between our failed claim and this read (shouldn't normally happen
            // outside of a manual operator intervention) -- treat it like nobody has claimed it.
            if (tryClaimDdlLock(session, table, lockName, holder)) {
                runClaimedDdlAction(session, table, lockName, holder, action)
                return
            }
            continue
        }

        if (row.getString("holder") == holder && row.getString("status") == "CLAIMED") {
            // GH #205: this instance's own claim timed out on the wire but had committed.
            logger.info { "Kandra: DDL bootstrap lock '$lockName' is held by this instance after a timed-out claim -- running schema DDL." }
            runClaimedDdlAction(session, table, lockName, holder, action)
            return
        }

        if (row.getString("status") == "DONE") {
            logger.info { "Kandra: DDL bootstrap lock '$lockName' was completed by another instance -- skipping DDL here." }
            return
        }

        val claimedAt = row.getInstant("claimed_at") ?: Instant.EPOCH
        val age = Duration.between(claimedAt, ddlLockServerNow(session))
        if (age > DDL_CLAIM_STALE_THRESHOLD) {
            logger.warn {
                "Kandra: DDL bootstrap lock '$lockName' has been CLAIMED for ${age.seconds}s, " +
                "exceeding the staleness threshold of ${DDL_CLAIM_STALE_THRESHOLD.seconds}s -- the " +
                "previous claimant is presumed crashed. Reclaiming and running the DDL here."
            }
            if (reclaimStaleDdlLock(session, table, lockName, holder, claimedAt)) {
                runClaimedDdlAction(session, table, lockName, holder, action)
                return
            }
            // Someone else reclaimed (or finished) it between our staleness check and the reclaim
            // attempt -- loop and re-check rather than assume anything about the outcome.
            continue
        }

        Thread.sleep(DDL_CLAIM_POLL_MS)
    }
}

/**
 * Runs [action] for the instance that just won (or reclaimed) [lockName] as [holder], releasing
 * the claim on failure so a later attempt can retry, or marking it DONE on success.
 *
 * GH #91/ISS-078: both the release and the DONE write are fenced by `holder` (`IF holder = ?`),
 * not unconditional -- otherwise a stale-claim reclaim by another instance while [action] is still
 * genuinely running here (a real risk under multi-DC latency, since a claim's staleness threshold
 * is a heuristic, not a lease) would let this instance's completion clobber that other instance's
 * active claim, or its release delete the row out from under it. A lost CAS here means this
 * instance's ownership of [lockName] was taken over mid-run -- exactly the schema-disagreement
 * risk #79 exists to prevent -- so it's surfaced loudly rather than silently no-oping.
 */
private fun runClaimedDdlAction(session: CqlSession, table: String, lockName: String, holder: String, action: () -> Unit) {
    try {
        action()
    } catch (e: Exception) {
        if (!releaseDdlLock(session, table, lockName, holder)) {
            logger.warn {
                "Kandra: DDL bootstrap lock '$lockName' could not be released by holder '$holder' " +
                "after its action() failed -- the lock was already reclaimed by another instance " +
                "(its action() is presumably now running, or has already finished). The original " +
                "failure below is what this instance should still report; no further action is " +
                "needed here since another instance now owns the retry."
            }
        }
        throw e
    }
    if (!markDdlLockDone(session, table, lockName, holder)) {
        throw KandraSchemaException(
            "Kandra: DDL bootstrap lock '$lockName' was reclaimed by another instance while holder " +
            "'$holder' was still running its DDL action. The action completed successfully here, but " +
            "this instance lost ownership of the coordination lock before it could record that -- " +
            "meaning a second instance may have concurrently run (or is about to run) the same DDL, " +
            "the exact schema-disagreement risk this claim mechanism exists to prevent. This means the " +
            "DDL pass took longer than the ${DDL_CLAIM_STALE_THRESHOLD.seconds}s staleness threshold. " +
            "Verify the resulting schema in '$lockName' is consistent, and investigate why the DDL " +
            "pass (or cluster latency) exceeded the staleness threshold."
        )
    }
    logger.info { "Kandra: DDL bootstrap lock '$lockName' released (done)." }
}

/**
 * Coordination table backing [claimAndRunDdlBootstrap]. Deliberately minimal and schema-stable
 * (no column is ever added after creation) to keep its own bootstrap -- the one race this
 * mechanism cannot itself guard, see [claimAndRunDdlBootstrap]'s KDoc -- as narrow as possible.
 */
private fun ensureDdlLockTable(session: CqlSession, table: String) {
    session.execute(
        """
        CREATE TABLE IF NOT EXISTS $table (
            lock_name   TEXT PRIMARY KEY,
            holder      TEXT,
            status      TEXT,
            claimed_at  TIMESTAMP
        )
        """.trimIndent()
    )
}

/** Returns `true` if this call won the claim on [lockName]. */
private fun tryClaimDdlLock(session: CqlSession, table: String, lockName: String, holder: String): Boolean {
    val prepared = session.prepare(
        "INSERT INTO $table (lock_name, holder, status, claimed_at) " +
        "VALUES (?, ?, 'CLAIMED', toTimestamp(now())) IF NOT EXISTS"
    )
    return try {
        session.execute(prepared.bind(lockName, holder)).wasApplied()
    } catch (e: WriteTimeoutException) {
        // GH #205: a CAS timeout under Paxos contention (N replicas starting together) leaves the
        // outcome unknown, and the driver's default policy rethrows it. Report "not won"; the wait
        // loop in claimAndRunDdlBootstrap re-reads the row and recognises this holder if it did land.
        logger.warn { "Kandra: claiming DDL bootstrap lock '$lockName' timed out under LWT contention; re-reading the row." }
        false
    }
}

/** How many times a CAS write that timed out under contention is retried, each after re-reading the row (GH #205). */
private const val LWT_CONTENTION_RETRIES = 5

private fun lwtBackoffMillis(attempt: Int): Long =
    100L * attempt + java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 150)

/** The lock row for [lockName], or `null` when there is none. */
private fun readDdlLock(session: CqlSession, table: String, lockName: String): Row? =
    session.execute(session.prepare("SELECT holder, status, claimed_at FROM $table WHERE lock_name = ?").bind(lockName)).one()

/**
 * Reclaims a stale claim via compare-and-set on its previously-observed `claimed_at`, so that if
 * several waiters independently decide the same claim is stale, only one of them wins.
 */
private fun reclaimStaleDdlLock(session: CqlSession, table: String, lockName: String, holder: String, previousClaimedAt: Instant): Boolean {
    val prepared = session.prepare(
        "UPDATE $table SET holder = ?, status = 'CLAIMED', claimed_at = toTimestamp(now()) " +
        "WHERE lock_name = ? IF claimed_at = ?"
    )
    return try {
        session.execute(prepared.bind(holder, lockName, previousClaimedAt)).wasApplied()
    } catch (e: WriteTimeoutException) {
        false // GH #205: unknown outcome; the wait loop re-reads and recognises this holder if it won.
    }
}

/**
 * Marks [lockName] DONE, but only if [holder] is still its current holder (GH #91/ISS-078) --
 * fenced the same way [reclaimStaleDdlLock] already fences its own CAS, so a claim that was
 * reclaimed out from under [holder] (a stale-claim reclaim racing a still-alive, still-running
 * claimant) can't have its completion silently overwrite the new holder's active claim. Returns
 * `false` if the CAS lost -- see [runClaimedDdlAction] for how that's handled.
 */
private fun markDdlLockDone(session: CqlSession, table: String, lockName: String, holder: String): Boolean {
    val prepared = session.prepare(
        "UPDATE $table SET status = 'DONE' WHERE lock_name = ? IF holder = ?"
    )
    var attempt = 0
    while (true) {
        try {
            return session.execute(prepared.bind(lockName, holder)).wasApplied()
        } catch (e: WriteTimeoutException) {
            // GH #205: unknown outcome; the row tells. Only this holder writes DONE under its own name.
            val row = readDdlLock(session, table, lockName)
            val ours = row?.getString("holder") == holder
            if (ours && row?.getString("status") == "DONE") return true
            if (ours && ++attempt <= LWT_CONTENTION_RETRIES) { Thread.sleep(lwtBackoffMillis(attempt)); continue }
            return false
        }
    }
}

/**
 * Releases a claim (deletes its row) so a later attempt can retry cleanly after [action] fails --
 * but only if [holder] is still its current holder (GH #91/ISS-078), for the same reason
 * [markDdlLockDone] is fenced: an unconditional delete could remove another instance's active
 * claim out from under it, after this holder's claim was itself reclaimed as stale while it was
 * still (unbeknownst to the reclaimer) alive and running. Returns `false` if the CAS lost.
 */
private fun releaseDdlLock(session: CqlSession, table: String, lockName: String, holder: String): Boolean {
    val prepared = session.prepare("DELETE FROM $table WHERE lock_name = ? IF holder = ?")
    var attempt = 0
    while (true) {
        try {
            return session.execute(prepared.bind(lockName, holder)).wasApplied()
        } catch (e: WriteTimeoutException) {
            // GH #205: unknown outcome; the row tells.
            val row = readDdlLock(session, table, lockName)
            if (row == null) return true
            if (row.getString("holder") == holder && ++attempt <= LWT_CONTENTION_RETRIES) { Thread.sleep(lwtBackoffMillis(attempt)); continue }
            return false
        }
    }
}

/**
 * The current time as seen by the ScyllaDB/Cassandra coordinator serving this query -- *not* this
 * JVM's [Instant.now]. See [claimAndRunDdlBootstrap]'s KDoc, "Staleness" (GH #64's approach reused
 * here for the same reason: never compare timestamps across independently-clocked app instances).
 *
 * Reads from `system.local` (GH #91/ISS-078) rather than the `kandra_ddl_locks` row for [lockName]
 * itself: that row's existence is exactly what a staleness check is reasoning about, and it can be
 * deleted out from under a concurrent caller by [releaseDdlLock] -- falling back to this JVM's own
 * clock in that window is precisely the clock-skew risk this whole mechanism exists to avoid.
 * `system.local` always has exactly one row (the local node's own), on every Cassandra/ScyllaDB
 * node, so this has nothing to fall back to except a genuinely unreachable cluster.
 */
internal fun ddlLockServerNow(session: CqlSession): Instant {
    val row = session.execute("SELECT toTimestamp(now()) AS server_now FROM system.local").one()
    return row?.getInstant("server_now") ?: Instant.now()
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

/**
 * Debounces `/kandra/health`'s cluster probe (GH #36): a request within [ttlMillis] of the last
 * real check gets the cached result instead of triggering another
 * `SELECT release_version FROM system.local`, so a probe storm (misconfigured monitoring, or
 * deliberate abuse if the route is reachable beyond a private network) doesn't translate 1:1 into
 * cluster queries.
 *
 * [probeCount] is `internal` — it's not meant as public API, only so tests in this module can
 * assert on how many real probes actually ran, rather than inferring it indirectly from timing.
 *
 * Not linearizable under concurrent requests racing the exact TTL boundary — two overlapping
 * requests can, in the worst case, both see a stale cache and both probe. That's an acceptable
 * trade for a health check: correctness never suffers (the result is always either fresh or at
 * most [ttlMillis] old), only the debounce guarantee is best-effort rather than exact, and adding
 * a lock here would cost more than a rare extra probe is worth.
 */
internal class HealthCheckCache(private val ttlMillis: Long) {
    @Volatile private var cachedResult: Boolean = false
    @Volatile private var cachedAtMillis: Long = Long.MIN_VALUE
    val probeCount = java.util.concurrent.atomic.AtomicInteger(0)

    suspend fun check(probe: suspend () -> Boolean): Boolean {
        val now = System.currentTimeMillis()
        if (cachedAtMillis != Long.MIN_VALUE && now - cachedAtMillis < ttlMillis) {
            return cachedResult
        }
        val result = probe()
        probeCount.incrementAndGet()
        cachedResult = result
        cachedAtMillis = now
        return result
    }
}

val KandraSessionKey: AttributeKey<CqlSession> = AttributeKey("KandraSession")
val KandraCodecKey: AttributeKey<KandraCodec> = AttributeKey("KandraCodec")
val KandraRuntimeKey: AttributeKey<KandraRuntime> = AttributeKey("KandraRuntime")
@OptIn(ExperimentalKandraApi::class)
val KandraEventListenerKey: AttributeKey<KandraEventListener> = AttributeKey("KandraEventListener")
internal val KandraHealthCheckCacheKey: AttributeKey<HealthCheckCache> = AttributeKey("KandraHealthCheckCache")

val Application.kandraSession: CqlSession get() = attributes[KandraSessionKey]
val Application.kandraCodec: KandraCodec get() = attributes[KandraCodecKey]
val Application.kandra: KandraRuntime get() = attributes[KandraRuntimeKey]
