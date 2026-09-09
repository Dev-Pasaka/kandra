package io.kandra.ktor

import com.datastax.oss.driver.api.core.CqlSession
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
        // GH #65: config.keyspace was previously only blank-checked before being spliced into CQL
        // as both an unquoted identifier (`USE <keyspace>`) and a string literal
        // (`... WHERE keyspace_name = '<keyspace>'`). Validate it as a CQL identifier up front, the
        // same way SchemaRegistry validates table/column names, so a malformed or malicious value
        // fails fast here rather than reaching interpolated CQL below.
        if (!CqlNaming.isValidIdentifier(config.keyspace)) throw KandraSchemaException(
            "Kandra: keyspace '${config.keyspace}' is not a valid CQL identifier. Identifiers must " +
            "start with a letter or underscore and contain only letters, digits, and underscores."
        )
        // GH #65: validate NetworkTopologyStrategy's DC-name keys up front too, before any
        // connection is attempted -- they get spliced into the CREATE KEYSPACE replication map
        // literal (keyspaceDdl, CqlSessionBuilder.kt) once autoCreateKeyspace runs below.
        val strategy = config.replicationStrategy
        if (config.autoCreateKeyspace && strategy is ReplicationStrategy.NetworkTopologyStrategy) {
            strategy.dcReplicationMap.keys.forEach { dc ->
                if (!CqlNaming.isValidIdentifier(dc)) throw KandraSchemaException(
                    "Kandra: replicationStrategy datacenter name '$dc' is not a valid CQL identifier. " +
                    "Identifiers must start with a letter or underscore and contain only letters, " +
                    "digits, and underscores."
                )
            }
        }

        val sessionHandle = if (config.autoCreateKeyspace) {
            val bootstrapHandle = buildCqlSession(config, withKeyspace = false)
            // keyspaceDdl (CqlSessionBuilder.kt) also validates dcReplicationMap's DC-name keys
            // before splicing them into the CREATE KEYSPACE literal (GH #65).
            bootstrapHandle.session.execute(keyspaceDdl(config.keyspace, config.replicationStrategy))
            bootstrapHandle.session.execute("USE ${config.keyspace}")
            logger.info { "Kandra: keyspace '${config.keyspace}' ensured." }
            bootstrapHandle
        } else {
            buildCqlSession(config)
        }
        val session = sessionHandle.session

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
                claimAndRunDdlBootstrap(session, ddlBootstrapLockName(SchemaRegistry.all())) {
                    SchemaRegistry.all().forEach { schema ->
                        DdlGenerator.allStatements(schema).forEach { ddl ->
                            session.execute(ddl)
                            logger.debug { "Kandra: DDL executed: $ddl" }
                        }
                    }
                }
            }
            SchemaMode.AUTO_MIGRATE -> {
                // Same claim guard and fingerprinted lock name as AUTO_CREATE above (GH #79, GH #90)
                // -- this branch also runs ALTER TABLE ADD, which is exactly the multi-statement,
                // multi-table DDL the guard exists to serialize.
                claimAndRunDdlBootstrap(session, ddlBootstrapLockName(SchemaRegistry.all())) {
                    SchemaRegistry.all().forEach { schema ->
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
                            config.keyspace, schema.tableName
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
            }
            SchemaMode.VALIDATE -> {
                SchemaRegistry.all().forEach { schema ->
                    // GH #65: bound params instead of a string-interpolated literal.
                    val rs = session.execute(
                        "SELECT column_name FROM system_schema.columns WHERE keyspace_name = ? AND table_name = ?",
                        config.keyspace, schema.tableName
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
        // this hook in Ktor 2.3.13's monitor API to restructure onto instead.
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
internal fun ddlBootstrapLockName(schemas: List<TableSchema>): String =
    "$DDL_BOOTSTRAP_SCHEMA_LOCK-${schemaFingerprint(schemas)}"

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
internal fun claimAndRunDdlBootstrap(session: CqlSession, lockName: String, action: () -> Unit) {
    ensureDdlLockTable(session)
    val holder = UUID.randomUUID().toString()

    if (tryClaimDdlLock(session, lockName, holder)) {
        logger.info { "Kandra: claimed DDL bootstrap lock '$lockName' -- running schema DDL." }
        runClaimedDdlAction(session, lockName, action)
        return
    }

    logger.info {
        "Kandra: another instance holds the DDL bootstrap lock '$lockName' -- waiting for it to " +
        "finish rather than racing the same DDL concurrently."
    }
    while (true) {
        val row = session.execute(
            session.prepare("SELECT holder, status, claimed_at FROM kandra_ddl_locks WHERE lock_name = ?")
                .bind(lockName)
        ).one()

        if (row == null) {
            // The row vanished between our failed claim and this read (shouldn't normally happen
            // outside of a manual operator intervention) -- treat it like nobody has claimed it.
            if (tryClaimDdlLock(session, lockName, holder)) {
                runClaimedDdlAction(session, lockName, action)
                return
            }
            continue
        }

        if (row.getString("status") == "DONE") {
            logger.info { "Kandra: DDL bootstrap lock '$lockName' was completed by another instance -- skipping DDL here." }
            return
        }

        val claimedAt = row.getInstant("claimed_at") ?: Instant.EPOCH
        val age = Duration.between(claimedAt, ddlLockServerNow(session, lockName))
        if (age > DDL_CLAIM_STALE_THRESHOLD) {
            logger.warn {
                "Kandra: DDL bootstrap lock '$lockName' has been CLAIMED for ${age.seconds}s, " +
                "exceeding the staleness threshold of ${DDL_CLAIM_STALE_THRESHOLD.seconds}s -- the " +
                "previous claimant is presumed crashed. Reclaiming and running the DDL here."
            }
            if (reclaimStaleDdlLock(session, lockName, holder, claimedAt)) {
                runClaimedDdlAction(session, lockName, action)
                return
            }
            // Someone else reclaimed (or finished) it between our staleness check and the reclaim
            // attempt -- loop and re-check rather than assume anything about the outcome.
            continue
        }

        Thread.sleep(DDL_CLAIM_POLL_MS)
    }
}

/** Runs [action] for the instance that just won (or reclaimed) [lockName], releasing the claim on failure so a later attempt can retry, or marking it DONE on success. */
private fun runClaimedDdlAction(session: CqlSession, lockName: String, action: () -> Unit) {
    try {
        action()
    } catch (e: Exception) {
        releaseDdlLock(session, lockName)
        throw e
    }
    markDdlLockDone(session, lockName)
    logger.info { "Kandra: DDL bootstrap lock '$lockName' released (done)." }
}

/**
 * Coordination table backing [claimAndRunDdlBootstrap]. Deliberately minimal and schema-stable
 * (no column is ever added after creation) to keep its own bootstrap -- the one race this
 * mechanism cannot itself guard, see [claimAndRunDdlBootstrap]'s KDoc -- as narrow as possible.
 */
private fun ensureDdlLockTable(session: CqlSession) {
    session.execute(
        """
        CREATE TABLE IF NOT EXISTS kandra_ddl_locks (
            lock_name   TEXT PRIMARY KEY,
            holder      TEXT,
            status      TEXT,
            claimed_at  TIMESTAMP
        )
        """.trimIndent()
    )
}

/** Returns `true` if this call won the claim on [lockName]. */
private fun tryClaimDdlLock(session: CqlSession, lockName: String, holder: String): Boolean {
    val prepared = session.prepare(
        "INSERT INTO kandra_ddl_locks (lock_name, holder, status, claimed_at) " +
        "VALUES (?, ?, 'CLAIMED', toTimestamp(now())) IF NOT EXISTS"
    )
    return session.execute(prepared.bind(lockName, holder)).wasApplied()
}

/**
 * Reclaims a stale claim via compare-and-set on its previously-observed `claimed_at`, so that if
 * several waiters independently decide the same claim is stale, only one of them wins.
 */
private fun reclaimStaleDdlLock(session: CqlSession, lockName: String, holder: String, previousClaimedAt: Instant): Boolean {
    val prepared = session.prepare(
        "UPDATE kandra_ddl_locks SET holder = ?, status = 'CLAIMED', claimed_at = toTimestamp(now()) " +
        "WHERE lock_name = ? IF claimed_at = ?"
    )
    return session.execute(prepared.bind(holder, lockName, previousClaimedAt)).wasApplied()
}

private fun markDdlLockDone(session: CqlSession, lockName: String) {
    session.execute(
        session.prepare("UPDATE kandra_ddl_locks SET status = 'DONE' WHERE lock_name = ?").bind(lockName)
    )
}

/** Releases a claim (deletes its row) so a later attempt can retry cleanly after [action] fails. */
private fun releaseDdlLock(session: CqlSession, lockName: String) {
    session.execute(session.prepare("DELETE FROM kandra_ddl_locks WHERE lock_name = ?").bind(lockName))
}

/**
 * The current time as seen by the ScyllaDB/Cassandra coordinator serving this query -- *not* this
 * JVM's [Instant.now]. See [claimAndRunDdlBootstrap]'s KDoc, "Staleness" (GH #64's approach reused
 * here for the same reason: never compare timestamps across independently-clocked app instances).
 */
private fun ddlLockServerNow(session: CqlSession, lockName: String): Instant {
    val row = session.execute(
        session.prepare("SELECT toTimestamp(now()) AS server_now FROM kandra_ddl_locks WHERE lock_name = ?")
            .bind(lockName)
    ).one()
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
