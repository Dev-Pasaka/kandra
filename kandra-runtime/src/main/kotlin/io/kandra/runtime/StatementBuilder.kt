package io.kandra.runtime

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel
import com.datastax.oss.driver.api.core.cql.BoundStatement
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kandra.core.InternalKandraApi
import io.kandra.core.KandraConsistency
import io.kandra.core.annotations.ReadConsistency
import io.kandra.core.annotations.WriteConsistency
import io.kandra.core.exception.KandraQueryException
import io.kandra.core.exception.KandraSchemaException
import io.kandra.core.schema.LookupTableSchema
import io.kandra.core.schema.TableSchema
import io.kandra.runtime.codec.KandraCodec
import io.kandra.runtime.codec.KandraUnset
import io.kandra.runtime.driver.prepareSuspend
import java.util.Collections
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation

private val logger = KotlinLogging.logger {}

/**
 * Builds [BoundStatement] instances for all CRUD operations.
 *
 * Prepared statements are cached in a bounded LRU cache keyed by CQL string.
 * Idempotency is set per statement type — SELECT and DELETE are idempotent;
 * plain INSERT and collection mutations are not (to prevent duplicate writes on retry).
 */
@InternalKandraApi
class StatementBuilder(
    private val session: CqlSession,
    private val codec: KandraCodec = KandraCodec.default,
    private val debugConfig: DebugConfig = DebugConfig(),
    private val consistencyConfig: ConsistencyConfig = ConsistencyConfig(),
    cacheSize: Int = 1000
) {
    private val cache: MutableMap<String, PreparedStatement> = Collections.synchronizedMap(
        object : LinkedHashMap<String, PreparedStatement>(cacheSize + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, PreparedStatement>): Boolean {
                if (size > cacheSize) {
                    logger.warn { "Prepared statement cache eviction: '${eldest.key}'. Consider increasing preparedStatementCacheSize." }
                    return true
                }
                return false
            }
        }
    )

    private fun prepare(cql: String): PreparedStatement {
        if (debugConfig.logQueries) logger.debug { "Kandra CQL: $cql" }
        return cache.getOrPut(cql) { session.prepare(cql) }
    }

    /**
     * Suspend counterpart of [prepare] (GH #27 / ISS-049) — uses [CqlSession.prepareSuspend]
     * (`prepareAsync` under the hood) instead of the blocking `session.prepare`, so a cache miss
     * on the *first* call for a given CQL string (or after an LRU eviction) never blocks the
     * calling coroutine dispatcher thread for a full driver round-trip.
     *
     * The cache is checked up front (fast path on a hit — no suspension needed at all), and
     * `getOrPut` is used only to insert the freshly-prepared statement, so a race between two
     * coroutines preparing the same CQL concurrently still converges on a single cached
     * [PreparedStatement] instance (whichever `getOrPut` call wins) rather than each coroutine
     * keeping its own.
     */
    private suspend fun prepareSuspend(cql: String): PreparedStatement {
        if (debugConfig.logQueries) logger.debug { "Kandra CQL: $cql" }
        cache[cql]?.let { return it }
        val prepared = session.prepareSuspend(cql)
        return cache.getOrPut(cql) { prepared }
    }

    @Suppress("UNCHECKED_CAST")
    private fun BoundStatement.setEncoded(idx: Int, value: Any): BoundStatement =
        set(idx, value, value::class.java as Class<Any>)

    @InternalKandraApi
    internal fun resolveWriteConsistency(schema: TableSchema, override: KandraConsistency?): KandraConsistency {
        val resolved = override
            ?: schema.entityClass.findAnnotation<WriteConsistency>()?.level
            ?: consistencyConfig.defaultWrite
        warnIfStrictModeViolation(schema, resolved)
        // The write side of the pair is what we just resolved; pair it with the currently configured
        // read default, since a per-call read override (if any) isn't visible from here. See ISS-075.
        warnIfRfConsistencyMismatch(schema, readLevel = consistencyConfig.defaultRead, writeLevel = resolved)
        return resolved
    }

    @InternalKandraApi
    internal fun resolveReadConsistency(schema: TableSchema, override: KandraConsistency?): KandraConsistency {
        val resolved = override
            ?: schema.entityClass.findAnnotation<ReadConsistency>()?.level
            ?: consistencyConfig.defaultRead
        warnIfStrictModeViolation(schema, resolved)
        warnIfRfConsistencyMismatch(schema, readLevel = resolved, writeLevel = consistencyConfig.defaultWrite)
        return resolved
    }

    /**
     * Strict Mode (GH #5): unconditional WARN — matches the existing
     * [QueryExecutor.activeMarkerWarning]-style precedent of warning on every call rather than tracking
     * "warn once" state — logged when a query resolves to `LOCAL_ONE`/`ONE` while both
     * [ConsistencyConfig.strictMode] and [ConsistencyConfig.multiDcTopology] are true. Never throws.
     */
    private fun warnIfStrictModeViolation(schema: TableSchema, resolved: KandraConsistency) {
        if (!consistencyConfig.strictMode || !consistencyConfig.multiDcTopology) return
        if (resolved != KandraConsistency.LOCAL_ONE && resolved != KandraConsistency.ONE) return
        logger.warn {
            "Kandra strictMode: query on '${schema.tableName}' resolved to $resolved consistency in a " +
            "multi-DC deployment (loadBalancing.allowedRemoteDcs is non-empty). LOCAL_QUORUM is usually " +
            "the intended default for multi-DC deployments so writes/reads are acknowledged across " +
            "datacenters, not just one local replica. Set an explicit consistency level, a " +
            "@ReadConsistency/@WriteConsistency annotation, or consistency { defaultRead/defaultWrite = " +
            "... } if $resolved is intentional here."
        }
    }

    /**
     * Strict Mode RF-vs-(R+W) check (ISS-075 / GH #83) — opt-in via [ConsistencyConfig.strictMode]
     * (deliberately *not* gated on [ConsistencyConfig.multiDcTopology] like [warnIfStrictModeViolation]:
     * an RF/consistency mismatch is just as real on a single-DC cluster with RF > 3 as it is in a
     * multi-DC deployment). Reads the resolved table's actual replication factor from live driver
     * metadata (`session.getMetadata().getKeyspace(...)`) — not from any static config — and warns
     * when the resolved read/write consistency pair can no longer guarantee read-your-writes, i.e.
     * when `R + W < RF`. Never throws; RF is looked up on a best-effort basis and the check is
     * silently skipped (not "assumed safe") if the session has no current keyspace or the driver has
     * no metadata for it yet (e.g. a bare CqlSession in a unit test).
     *
     * Each call only knows *one* side of the pair for certain (the value it just resolved); the other
     * side is taken from the currently configured default, since a per-call override on that other
     * side isn't visible from here. This can under- or over-report relative to what a given caller
     * actually mixes at runtime, but it's the same "best information available at this call site"
     * trade-off [warnIfStrictModeViolation] already makes for its own check.
     *
     * GH-109 item 6 restates this same trade-off: a caller using strong per-call overrides on both
     * the read and write paths for the same logical entity may still see (or fail to see) a warning
     * based on the *other* operation's global default rather than its actual override. Informational
     * — not a required fix, since resolving it would mean threading each call's sibling override
     * through to the other side, which the current per-call API surface doesn't expose.
     */
    private fun warnIfRfConsistencyMismatch(schema: TableSchema, readLevel: KandraConsistency, writeLevel: KandraConsistency) {
        if (!consistencyConfig.strictMode) return
        val rf = replicationFactorOrNull() ?: return
        val readWeight = quorumWeight(readLevel, rf)
        val writeWeight = quorumWeight(writeLevel, rf)
        // Cassandra's own documented rule is the *strict* inequality R + W > RF, not R + W >= RF: with
        // R + W == RF, an adversarial replica placement can still make the read set and write set fully
        // disjoint (e.g. RF=3, W=2 lands on {A,B}, R=1 reads only {C} -- no overlap). Only R + W > RF
        // guarantees overlap by pigeonhole. See ISS-075 / GH #83.
        if (readWeight + writeWeight > rf) return
        logger.warn {
            "Kandra strictMode: table '${schema.tableName}' has replication factor $rf, but the resolved " +
            "read=$readLevel (effective $readWeight) + write=$writeLevel (effective $writeWeight) = " +
            "${readWeight + writeWeight}, which does not exceed RF ($rf). Read-your-writes requires " +
            "R + W > RF (not just >=) -- with R + W == RF, an unlucky replica placement can still make " +
            "the read and write sets disjoint. Raise consistency { defaultRead/defaultWrite = ... }, add a " +
            "@ReadConsistency/@WriteConsistency annotation, or pass a stronger per-call override so " +
            "R + W > RF."
        }
    }

    /**
     * Best-effort replication factor for the session's current keyspace, read from live driver
     * metadata. Returns `null` (never throws) if the session has no current keyspace, the driver has
     * no metadata for it, or the replication map can't be parsed — any of which simply skips the
     * RF check above rather than treating it as "safe" or raising an error.
     *
     * Sums every non-`class` entry in [com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata.getReplication]
     * — correct for `SimpleStrategy` (a single `replication_factor` entry) and a reasonable proxy for
     * `NetworkTopologyStrategy` (summing per-DC factors), though the latter overstates the RF that
     * actually matters for a `LOCAL_*` consistency level, which is satisfied by *one* DC's replicas,
     * not the cluster-wide total. Good enough for catching the common single-DC/RF>3 case this issue
     * targets; a precise per-DC accounting is out of scope here.
     *
     * GH-109 item 5: this lookup is not cached — it re-reads and re-sums the keyspace's replication
     * map on every call while [ConsistencyConfig.strictMode] is on, i.e. on every read/write for as
     * long as Strict Mode stays enabled. No network I/O is involved (the driver keeps this metadata
     * in memory), so the per-call cost is likely small, but it's still on the hot path — worth
     * confirming under real load rather than assumed free, especially for teams planning to run
     * Strict Mode continuously rather than as a one-time diagnostic. Caching would need to invalidate
     * on a keyspace replication change (rare, but not impossible mid-process), so it's left uncached
     * here rather than adding that invalidation complexity speculatively.
     */
    private fun replicationFactorOrNull(): Int? {
        val ksId = session.keyspace?.orElse(null) ?: return null
        val ksMeta = session.metadata?.getKeyspace(ksId)?.orElse(null) ?: return null
        return ksMeta.replication.entries
            .filter { it.key != "class" }
            .sumOf { it.value.toIntOrNull() ?: 0 }
            .takeIf { it > 0 }
    }

    /** Effective replica count a given [KandraConsistency] level guarantees to have acknowledged, for RF [rf]. */
    private fun quorumWeight(level: KandraConsistency, rf: Int): Int = when (level) {
        KandraConsistency.ONE, KandraConsistency.LOCAL_ONE -> 1
        KandraConsistency.TWO -> 2
        KandraConsistency.THREE -> 3
        KandraConsistency.QUORUM, KandraConsistency.LOCAL_QUORUM,
        KandraConsistency.EACH_QUORUM, KandraConsistency.LOCAL_SERIAL, KandraConsistency.SERIAL -> (rf / 2) + 1
        KandraConsistency.ALL -> rf
    }

    private fun KandraConsistency.toDriverLevel() =
        DefaultConsistencyLevel.valueOf(this.name)

    /** Full primary key (partition + clustering columns, in that order) — every single-row WHERE clause needs all of it. */
    private fun TableSchema.primaryKeyColumns(): List<io.kandra.core.schema.ColumnSchema> = partitionKeys + clusteringKeys

    private fun TableSchema.primaryKeyWhereClause(): String =
        primaryKeyColumns().joinToString(" AND ") { "${it.cqlName} = ?" }

    /**
     * Guards against silently truncating key values to fewer than the full primary key — e.g. calling
     * `findById(userId)` on an entity with a clustering key must fail loudly, not silently scope to
     * the wrong row (or, for deletes, an entire partition) by dropping the missing clustering values.
     */
    private fun requireFullKey(schema: TableSchema, keyCols: List<io.kandra.core.schema.ColumnSchema>, providedCount: Int, op: String) {
        if (providedCount != keyCols.size) {
            throw KandraSchemaException(
                "$op on '${schema.tableName}' requires ${keyCols.size} key value(s) " +
                "(${keyCols.joinToString(", ") { it.cqlName }}) but $providedCount were provided."
            )
        }
    }

    fun insertPrimary(
        schema: TableSchema,
        entity: Any,
        ttlSeconds: Int? = null,
        ifNotExists: Boolean = false,
        timestampMicros: Long? = null,
        consistency: KandraConsistency? = null
    ): BoundStatement {
        // SchemaRegistry.buildSchema (GH-31) now throws at registration time for any two distinct
        // properties resolving to the same cqlName, so this distinctBy can no longer silently drop a
        // genuinely-different colliding column. It's still required, though: @LookupIndex columns
        // are deliberately listed twice here (once via `columns`, once via
        // `lookupTables.map { it.indexColumn }`), and this is what de-dupes that intentional overlap
        // rather than binding/inserting the same column value twice.
        val allCols = buildList {
            addAll(schema.partitionKeys)
            addAll(schema.clusteringKeys)
            addAll(schema.columns)
            addAll(schema.lookupTables.map { it.indexColumn })
        }.distinctBy { it.cqlName }

        val colNames = allCols.joinToString(", ") { it.cqlName }
        val placeholders = allCols.joinToString(", ") { "?" }

        val effectiveTtl = ttlSeconds ?: schema.defaultTtl
        val modifiers = buildList<String> {
            if (effectiveTtl != null) add("TTL $effectiveTtl")
            if (timestampMicros != null) add("TIMESTAMP $timestampMicros")
        }
        val usingClause = if (modifiers.isNotEmpty()) " USING ${modifiers.joinToString(" AND ")}" else ""
        val ifClause = if (ifNotExists) " IF NOT EXISTS" else ""

        val cql = "INSERT INTO ${schema.tableName} ($colNames) VALUES ($placeholders)$ifClause$usingClause"
        val prepared = prepare(cql)

        val props = schema.reflection.propertiesByName
        var stmt = prepared.bind()
        allCols.forEachIndexed { idx, col ->
            val prop = props[col.propertyName]
            val value = prop?.call(entity)
            val encoded = codec.encode(value, col.type)
            if (encoded === KandraUnset) {
                if (col.isPartitionKey || col.clusteringKey != null) {
                    throw KandraSchemaException(
                        "Primary/clustering key column '${col.propertyName}' cannot be UNSET (null). " +
                            "Partition keys must always have a value."
                    )
                }
                stmt = stmt.unset(idx)
            } else {
                stmt = stmt.setEncoded(idx, encoded!!)
            }
        }

        // Plain INSERT is NOT idempotent — retry = duplicate risk
        // LWT INSERT IF NOT EXISTS is idempotent — safe to retry
        stmt = stmt.setIdempotent(ifNotExists)
        stmt = stmt.setConsistencyLevel(resolveWriteConsistency(schema, consistency).toDriverLevel())
        return stmt
    }

    /** Suspend counterpart of [insertPrimary] (GH #27 / ISS-049) — see [prepareSuspend]. */
    suspend fun insertPrimarySuspend(
        schema: TableSchema,
        entity: Any,
        ttlSeconds: Int? = null,
        ifNotExists: Boolean = false,
        timestampMicros: Long? = null,
        consistency: KandraConsistency? = null
    ): BoundStatement {
        val allCols = buildList {
            addAll(schema.partitionKeys)
            addAll(schema.clusteringKeys)
            addAll(schema.columns)
            addAll(schema.lookupTables.map { it.indexColumn })
        }.distinctBy { it.cqlName }

        val colNames = allCols.joinToString(", ") { it.cqlName }
        val placeholders = allCols.joinToString(", ") { "?" }

        val effectiveTtl = ttlSeconds ?: schema.defaultTtl
        val modifiers = buildList<String> {
            if (effectiveTtl != null) add("TTL $effectiveTtl")
            if (timestampMicros != null) add("TIMESTAMP $timestampMicros")
        }
        val usingClause = if (modifiers.isNotEmpty()) " USING ${modifiers.joinToString(" AND ")}" else ""
        val ifClause = if (ifNotExists) " IF NOT EXISTS" else ""

        val cql = "INSERT INTO ${schema.tableName} ($colNames) VALUES ($placeholders)$ifClause$usingClause"
        val prepared = prepareSuspend(cql)

        val props = schema.reflection.propertiesByName
        var stmt = prepared.bind()
        allCols.forEachIndexed { idx, col ->
            val prop = props[col.propertyName]
            val value = prop?.call(entity)
            val encoded = codec.encode(value, col.type)
            if (encoded === KandraUnset) {
                if (col.isPartitionKey || col.clusteringKey != null) {
                    throw KandraSchemaException(
                        "Primary/clustering key column '${col.propertyName}' cannot be UNSET (null). " +
                            "Partition keys must always have a value."
                    )
                }
                stmt = stmt.unset(idx)
            } else {
                stmt = stmt.setEncoded(idx, encoded!!)
            }
        }

        stmt = stmt.setIdempotent(ifNotExists)
        stmt = stmt.setConsistencyLevel(resolveWriteConsistency(schema, consistency).toDriverLevel())
        return stmt
    }

    /**
     * Same as [insertPrimary] but binds null as actual null (creates tombstones).
     * Use when the caller explicitly wants to clear optional columns.
     */
    fun insertPrimaryWithNulls(
        schema: TableSchema,
        entity: Any,
        ttlSeconds: Int? = null,
        ifNotExists: Boolean = false,
        timestampMicros: Long? = null,
        consistency: KandraConsistency? = null
    ): BoundStatement {
        // SchemaRegistry.buildSchema (GH-31) now throws at registration time for any two distinct
        // properties resolving to the same cqlName, so this distinctBy can no longer silently drop a
        // genuinely-different colliding column. It's still required, though: @LookupIndex columns
        // are deliberately listed twice here (once via `columns`, once via
        // `lookupTables.map { it.indexColumn }`), and this is what de-dupes that intentional overlap
        // rather than binding/inserting the same column value twice.
        val allCols = buildList {
            addAll(schema.partitionKeys)
            addAll(schema.clusteringKeys)
            addAll(schema.columns)
            addAll(schema.lookupTables.map { it.indexColumn })
        }.distinctBy { it.cqlName }

        val colNames = allCols.joinToString(", ") { it.cqlName }
        val placeholders = allCols.joinToString(", ") { "?" }
        val effectiveTtl = ttlSeconds ?: schema.defaultTtl
        val modifiers = buildList<String> {
            if (effectiveTtl != null) add("TTL $effectiveTtl")
            if (timestampMicros != null) add("TIMESTAMP $timestampMicros")
        }
        val usingClause = if (modifiers.isNotEmpty()) " USING ${modifiers.joinToString(" AND ")}" else ""
        val ifClause = if (ifNotExists) " IF NOT EXISTS" else ""
        val cql = "INSERT INTO ${schema.tableName} ($colNames) VALUES ($placeholders)$ifClause$usingClause"
        val prepared = prepare(cql)

        val props = schema.reflection.propertiesByName
        var stmt = prepared.bind()
        allCols.forEachIndexed { idx, col ->
            val prop = props[col.propertyName]
            val value = prop?.call(entity)
            // Null is bound as null (tombstone) — intentional for saveWithNulls
            val encoded: Any? = if (value == null) null
            else codec.encode(value, col.type).let { if (it === KandraUnset) null else it }
            if (encoded == null) {
                stmt = stmt.setBytesUnsafe(idx, null)
            } else {
                stmt = stmt.setEncoded(idx, encoded)
            }
        }
        stmt = stmt.setIdempotent(ifNotExists)
        stmt = stmt.setConsistencyLevel(resolveWriteConsistency(schema, consistency).toDriverLevel())
        return stmt
    }

    /** Suspend counterpart of [insertPrimaryWithNulls] (GH #27 / ISS-049) — see [prepareSuspend]. */
    suspend fun insertPrimaryWithNullsSuspend(
        schema: TableSchema,
        entity: Any,
        ttlSeconds: Int? = null,
        ifNotExists: Boolean = false,
        timestampMicros: Long? = null,
        consistency: KandraConsistency? = null
    ): BoundStatement {
        val allCols = buildList {
            addAll(schema.partitionKeys)
            addAll(schema.clusteringKeys)
            addAll(schema.columns)
            addAll(schema.lookupTables.map { it.indexColumn })
        }.distinctBy { it.cqlName }

        val colNames = allCols.joinToString(", ") { it.cqlName }
        val placeholders = allCols.joinToString(", ") { "?" }
        val effectiveTtl = ttlSeconds ?: schema.defaultTtl
        val modifiers = buildList<String> {
            if (effectiveTtl != null) add("TTL $effectiveTtl")
            if (timestampMicros != null) add("TIMESTAMP $timestampMicros")
        }
        val usingClause = if (modifiers.isNotEmpty()) " USING ${modifiers.joinToString(" AND ")}" else ""
        val ifClause = if (ifNotExists) " IF NOT EXISTS" else ""
        val cql = "INSERT INTO ${schema.tableName} ($colNames) VALUES ($placeholders)$ifClause$usingClause"
        val prepared = prepareSuspend(cql)

        val props = schema.reflection.propertiesByName
        var stmt = prepared.bind()
        allCols.forEachIndexed { idx, col ->
            val prop = props[col.propertyName]
            val value = prop?.call(entity)
            val encoded: Any? = if (value == null) null
            else codec.encode(value, col.type).let { if (it === KandraUnset) null else it }
            if (encoded == null) {
                stmt = stmt.setBytesUnsafe(idx, null)
            } else {
                stmt = stmt.setEncoded(idx, encoded)
            }
        }
        stmt = stmt.setIdempotent(ifNotExists)
        stmt = stmt.setConsistencyLevel(resolveWriteConsistency(schema, consistency).toDriverLevel())
        return stmt
    }

    /**
     * [schema] is only needed to source the entity's cached [io.kandra.core.schema.EntityReflection]
     * property map (resolved once at [io.kandra.core.SchemaRegistry.register] time) — [entity] is
     * always an instance of [schema]'s `entityClass`.
     */
    fun insertLookup(schema: TableSchema, lookup: LookupTableSchema, entity: Any): BoundStatement {
        val cols = listOf(lookup.indexColumn) + lookup.partitionKeyColumns + lookup.clusteringKeyColumns
        val colNames = cols.joinToString(", ") { it.cqlName }
        val placeholders = cols.joinToString(", ") { "?" }
        val cql = "INSERT INTO ${lookup.tableName} ($colNames) VALUES ($placeholders)"
        val prepared = prepare(cql)

        val props = schema.reflection.propertiesByName
        var stmt = prepared.bind()
        cols.forEachIndexed { idx, col ->
            val prop = props[col.propertyName]
            val value = prop?.call(entity)
            val encoded = codec.encode(value, col.type)
            stmt = if (encoded === KandraUnset) stmt.unset(idx)
                   else stmt.setEncoded(idx, encoded!!)
        }
        return stmt.setIdempotent(false) // lookup inserts are not idempotent
    }

    /** Suspend counterpart of [insertLookup] (GH #27 / ISS-049) — see [prepareSuspend]. */
    suspend fun insertLookupSuspend(schema: TableSchema, lookup: LookupTableSchema, entity: Any): BoundStatement {
        val cols = listOf(lookup.indexColumn) + lookup.partitionKeyColumns + lookup.clusteringKeyColumns
        val colNames = cols.joinToString(", ") { it.cqlName }
        val placeholders = cols.joinToString(", ") { "?" }
        val cql = "INSERT INTO ${lookup.tableName} ($colNames) VALUES ($placeholders)"
        val prepared = prepareSuspend(cql)

        val props = schema.reflection.propertiesByName
        var stmt = prepared.bind()
        cols.forEachIndexed { idx, col ->
            val prop = props[col.propertyName]
            val value = prop?.call(entity)
            val encoded = codec.encode(value, col.type)
            stmt = if (encoded === KandraUnset) stmt.unset(idx)
                   else stmt.setEncoded(idx, encoded!!)
        }
        return stmt.setIdempotent(false) // lookup inserts are not idempotent
    }

    fun deleteLookup(lookup: LookupTableSchema, indexValue: Any): BoundStatement {
        val cql = "DELETE FROM ${lookup.tableName} WHERE ${lookup.indexColumn.cqlName} = ?"
        val prepared = prepare(cql)
        return prepared.bind(codec.encode(indexValue, lookup.indexColumn.type))
            .setIdempotent(true) // delete is idempotent
    }

    /** Suspend counterpart of [deleteLookup] (GH #27 / ISS-049) — see [prepareSuspend]. */
    suspend fun deleteLookupSuspend(lookup: LookupTableSchema, indexValue: Any): BoundStatement {
        val cql = "DELETE FROM ${lookup.tableName} WHERE ${lookup.indexColumn.cqlName} = ?"
        val prepared = prepareSuspend(cql)
        return prepared.bind(codec.encode(indexValue, lookup.indexColumn.type))
            .setIdempotent(true) // delete is idempotent
    }

    fun selectById(schema: TableSchema, vararg idValues: Any, consistency: KandraConsistency? = null): BoundStatement {
        val keyCols = schema.primaryKeyColumns()
        requireFullKey(schema, keyCols, idValues.size, "selectById")
        val cql = "SELECT * FROM ${schema.tableName} WHERE ${schema.primaryKeyWhereClause()}"
        val prepared = prepare(cql)
        val encodedValues = keyCols.zip(idValues.toList()).map { (col, v) ->
            codec.encode(v, col.type)
        }
        return prepared.bind(*encodedValues.toTypedArray())
            .setIdempotent(true)
            .setConsistencyLevel(resolveReadConsistency(schema, consistency).toDriverLevel())
    }

    /** Suspend counterpart of [selectById] (GH #27 / ISS-049) — see [prepareSuspend]. */
    suspend fun selectByIdSuspend(schema: TableSchema, vararg idValues: Any, consistency: KandraConsistency? = null): BoundStatement {
        val keyCols = schema.primaryKeyColumns()
        requireFullKey(schema, keyCols, idValues.size, "selectById")
        val cql = "SELECT * FROM ${schema.tableName} WHERE ${schema.primaryKeyWhereClause()}"
        val prepared = prepareSuspend(cql)
        val encodedValues = keyCols.zip(idValues.toList()).map { (col, v) ->
            codec.encode(v, col.type)
        }
        return prepared.bind(*encodedValues.toTypedArray())
            .setIdempotent(true)
            .setConsistencyLevel(resolveReadConsistency(schema, consistency).toDriverLevel())
    }

    fun selectByLookup(schema: TableSchema, lookup: LookupTableSchema, value: Any, consistency: KandraConsistency? = null): BoundStatement {
        // Select both partition AND clustering key columns of the primary table -- a lookup row must
        // be able to reconstruct the primary table's FULL key, not just its partition key, since
        // selectById requires the full key (see ISS-029).
        val keyCols = (lookup.partitionKeyColumns + lookup.clusteringKeyColumns).joinToString(", ") { it.cqlName }
        val cql = "SELECT $keyCols FROM ${lookup.tableName} WHERE ${lookup.indexColumn.cqlName} = ?"
        val prepared = prepare(cql)
        return prepared.bind(codec.encode(value, lookup.indexColumn.type))
            .setIdempotent(true)
            .setConsistencyLevel(resolveReadConsistency(schema, consistency).toDriverLevel())
    }

    /** Suspend counterpart of [selectByLookup] (GH #27 / ISS-049) — see [prepareSuspend]. */
    suspend fun selectByLookupSuspend(schema: TableSchema, lookup: LookupTableSchema, value: Any, consistency: KandraConsistency? = null): BoundStatement {
        val keyCols = (lookup.partitionKeyColumns + lookup.clusteringKeyColumns).joinToString(", ") { it.cqlName }
        val cql = "SELECT $keyCols FROM ${lookup.tableName} WHERE ${lookup.indexColumn.cqlName} = ?"
        val prepared = prepareSuspend(cql)
        return prepared.bind(codec.encode(value, lookup.indexColumn.type))
            .setIdempotent(true)
            .setConsistencyLevel(resolveReadConsistency(schema, consistency).toDriverLevel())
    }

    fun selectByPartitionKeyIn(schema: TableSchema, ids: List<Any>, consistency: KandraConsistency? = null): BoundStatement {
        if (schema.partitionKeys.size != 1) throw KandraSchemaException(
            "IN on partition key is only supported for single-column partition keys. " +
            "Table '${schema.tableName}' has a composite partition key."
        )
        val pkCol = schema.partitionKeys.first()
        val cql = "SELECT * FROM ${schema.tableName} WHERE ${pkCol.cqlName} IN ?"
        val prepared = prepare(cql)
        val encoded = ids.map { codec.encode(it, pkCol.type) }
        return prepared.bind(encoded)
            .setIdempotent(true)
            .setConsistencyLevel(resolveReadConsistency(schema, consistency).toDriverLevel())
    }

    /** Suspend counterpart of [selectByPartitionKeyIn] (GH #27 / ISS-049) — see [prepareSuspend]. */
    suspend fun selectByPartitionKeyInSuspend(schema: TableSchema, ids: List<Any>, consistency: KandraConsistency? = null): BoundStatement {
        if (schema.partitionKeys.size != 1) throw KandraSchemaException(
            "IN on partition key is only supported for single-column partition keys. " +
            "Table '${schema.tableName}' has a composite partition key."
        )
        val pkCol = schema.partitionKeys.first()
        val cql = "SELECT * FROM ${schema.tableName} WHERE ${pkCol.cqlName} IN ?"
        val prepared = prepareSuspend(cql)
        val encoded = ids.map { codec.encode(it, pkCol.type) }
        return prepared.bind(encoded)
            .setIdempotent(true)
            .setConsistencyLevel(resolveReadConsistency(schema, consistency).toDriverLevel())
    }

    fun deleteById(schema: TableSchema, vararg idValues: Any): BoundStatement {
        val keyCols = schema.primaryKeyColumns()
        requireFullKey(schema, keyCols, idValues.size, "deleteById")
        val cql = "DELETE FROM ${schema.tableName} WHERE ${schema.primaryKeyWhereClause()}"
        val prepared = prepare(cql)
        val encodedValues = keyCols.zip(idValues.toList()).map { (col, v) ->
            codec.encode(v, col.type)
        }
        return prepared.bind(*encodedValues.toTypedArray())
            .setIdempotent(true) // delete is idempotent
    }

    /** Suspend counterpart of [deleteById] (GH #27 / ISS-049) — see [prepareSuspend]. */
    suspend fun deleteByIdSuspend(schema: TableSchema, vararg idValues: Any): BoundStatement {
        val keyCols = schema.primaryKeyColumns()
        requireFullKey(schema, keyCols, idValues.size, "deleteById")
        val cql = "DELETE FROM ${schema.tableName} WHERE ${schema.primaryKeyWhereClause()}"
        val prepared = prepareSuspend(cql)
        val encodedValues = keyCols.zip(idValues.toList()).map { (col, v) ->
            codec.encode(v, col.type)
        }
        return prepared.bind(*encodedValues.toTypedArray())
            .setIdempotent(true) // delete is idempotent
    }

    fun deleteByPartitionKeys(schema: TableSchema, vararg keyValues: Any): BoundStatement =
        deleteById(schema, *keyValues)

    fun appendToCollection(
        schema: TableSchema,
        keyValues: List<Any>,
        columnName: String,
        values: Any,
        consistency: KandraConsistency? = null
    ): BoundStatement {
        val col = (schema.columns + schema.lookupTables.map { it.indexColumn })
            .find { it.cqlName == columnName || it.propertyName == columnName }
            ?: throw KandraSchemaException("Column '$columnName' not found in schema '${schema.tableName}'")
        val keyCols = schema.primaryKeyColumns()
        requireFullKey(schema, keyCols, keyValues.size, "append")
        val cql = "UPDATE ${schema.tableName} SET ${col.cqlName} = ${col.cqlName} + ? WHERE ${schema.primaryKeyWhereClause()}"
        val prepared = prepare(cql)
        val encodedKeys = keyCols.zip(keyValues).map { (keyCol, v) ->
            codec.encode(v, keyCol.type)
        }
        return prepared.bind(values, *encodedKeys.toTypedArray())
            .setIdempotent(false)
            .setConsistencyLevel(resolveWriteConsistency(schema, consistency).toDriverLevel())
    }

    /** Suspend counterpart of [appendToCollection] (GH #27 / ISS-049) — see [prepareSuspend]. */
    suspend fun appendToCollectionSuspend(
        schema: TableSchema,
        keyValues: List<Any>,
        columnName: String,
        values: Any,
        consistency: KandraConsistency? = null
    ): BoundStatement {
        val col = (schema.columns + schema.lookupTables.map { it.indexColumn })
            .find { it.cqlName == columnName || it.propertyName == columnName }
            ?: throw KandraSchemaException("Column '$columnName' not found in schema '${schema.tableName}'")
        val keyCols = schema.primaryKeyColumns()
        requireFullKey(schema, keyCols, keyValues.size, "append")
        val cql = "UPDATE ${schema.tableName} SET ${col.cqlName} = ${col.cqlName} + ? WHERE ${schema.primaryKeyWhereClause()}"
        val prepared = prepareSuspend(cql)
        val encodedKeys = keyCols.zip(keyValues).map { (keyCol, v) ->
            codec.encode(v, keyCol.type)
        }
        return prepared.bind(values, *encodedKeys.toTypedArray())
            .setIdempotent(false)
            .setConsistencyLevel(resolveWriteConsistency(schema, consistency).toDriverLevel())
    }

    fun removeFromCollection(
        schema: TableSchema,
        keyValues: List<Any>,
        columnName: String,
        values: Any,
        consistency: KandraConsistency? = null
    ): BoundStatement {
        val col = (schema.columns + schema.lookupTables.map { it.indexColumn })
            .find { it.cqlName == columnName || it.propertyName == columnName }
            ?: throw KandraSchemaException("Column '$columnName' not found in schema '${schema.tableName}'")
        val keyCols = schema.primaryKeyColumns()
        requireFullKey(schema, keyCols, keyValues.size, "remove")
        val cql = "UPDATE ${schema.tableName} SET ${col.cqlName} = ${col.cqlName} - ? WHERE ${schema.primaryKeyWhereClause()}"
        val prepared = prepare(cql)
        val encodedKeys = keyCols.zip(keyValues).map { (keyCol, v) ->
            codec.encode(v, keyCol.type)
        }
        return prepared.bind(values, *encodedKeys.toTypedArray())
            .setIdempotent(false)
            .setConsistencyLevel(resolveWriteConsistency(schema, consistency).toDriverLevel())
    }

    /** Suspend counterpart of [removeFromCollection] (GH #27 / ISS-049) — see [prepareSuspend]. */
    suspend fun removeFromCollectionSuspend(
        schema: TableSchema,
        keyValues: List<Any>,
        columnName: String,
        values: Any,
        consistency: KandraConsistency? = null
    ): BoundStatement {
        val col = (schema.columns + schema.lookupTables.map { it.indexColumn })
            .find { it.cqlName == columnName || it.propertyName == columnName }
            ?: throw KandraSchemaException("Column '$columnName' not found in schema '${schema.tableName}'")
        val keyCols = schema.primaryKeyColumns()
        requireFullKey(schema, keyCols, keyValues.size, "remove")
        val cql = "UPDATE ${schema.tableName} SET ${col.cqlName} = ${col.cqlName} - ? WHERE ${schema.primaryKeyWhereClause()}"
        val prepared = prepareSuspend(cql)
        val encodedKeys = keyCols.zip(keyValues).map { (keyCol, v) ->
            codec.encode(v, keyCol.type)
        }
        return prepared.bind(values, *encodedKeys.toTypedArray())
            .setIdempotent(false)
            .setConsistencyLevel(resolveWriteConsistency(schema, consistency).toDriverLevel())
    }

    /**
     * `Math.abs(Long.MIN_VALUE)` overflows back to `Long.MIN_VALUE` itself (two's-complement has no
     * positive representation for it), which would silently flip a `decrement(by = Long.MIN_VALUE)`
     * into binding a negative delta with a `+` operator baked into the CQL — i.e. it would actually
     * *decrement* the counter while claiming to increment it (or vice versa), with no error at all.
     * Guarded explicitly here (GH #36 item 3 / ISS-049) rather than left to `Math.abs`'s silent wrap.
     */
    private fun safeAbsoluteDelta(delta: Long, schema: TableSchema, columnName: String): Long {
        if (delta == Long.MIN_VALUE) {
            throw KandraSchemaException(
                "counterUpdate on '${schema.tableName}.$columnName' received delta = Long.MIN_VALUE " +
                "(${Long.MIN_VALUE}), which cannot be negated/absolute-valued without overflowing back " +
                "to itself (two's-complement has no positive counterpart for Long.MIN_VALUE). Use a " +
                "delta in [Long.MIN_VALUE + 1, Long.MAX_VALUE]."
            )
        }
        return Math.abs(delta)
    }

    fun counterUpdate(
        schema: TableSchema,
        columnName: String,
        partitionKeys: Map<String, Any>,
        delta: Long,
        consistency: KandraConsistency? = null
    ): BoundStatement {
        val col = schema.columns.find { it.propertyName == columnName || it.cqlName == columnName }
            ?: throw KandraSchemaException("Counter column '$columnName' not found in '${schema.tableName}'")
        val absDelta = safeAbsoluteDelta(delta, schema, columnName)
        val keyCols = schema.primaryKeyColumns()
        val op = if (delta >= 0) "+" else "-"
        val cql = "UPDATE ${schema.tableName} SET ${col.cqlName} = ${col.cqlName} $op ? WHERE ${schema.primaryKeyWhereClause()}"
        val prepared = prepare(cql)
        val keyValues = keyCols.map { key ->
            partitionKeys[key.propertyName] ?: partitionKeys[key.cqlName]
                ?: throw KandraSchemaException("Missing key value for '${key.cqlName}'")
        }
        return prepared.bind(absDelta, *keyValues.toTypedArray())
            .setIdempotent(false)
            .setConsistencyLevel(resolveWriteConsistency(schema, consistency).toDriverLevel())
    }

    /** Suspend counterpart of [counterUpdate] (GH #27 / ISS-049) — see [prepareSuspend]. */
    suspend fun counterUpdateSuspend(
        schema: TableSchema,
        columnName: String,
        partitionKeys: Map<String, Any>,
        delta: Long,
        consistency: KandraConsistency? = null
    ): BoundStatement {
        val col = schema.columns.find { it.propertyName == columnName || it.cqlName == columnName }
            ?: throw KandraSchemaException("Counter column '$columnName' not found in '${schema.tableName}'")
        val absDelta = safeAbsoluteDelta(delta, schema, columnName)
        val keyCols = schema.primaryKeyColumns()
        val op = if (delta >= 0) "+" else "-"
        val cql = "UPDATE ${schema.tableName} SET ${col.cqlName} = ${col.cqlName} $op ? WHERE ${schema.primaryKeyWhereClause()}"
        val prepared = prepareSuspend(cql)
        val keyValues = keyCols.map { key ->
            partitionKeys[key.propertyName] ?: partitionKeys[key.cqlName]
                ?: throw KandraSchemaException("Missing key value for '${key.cqlName}'")
        }
        return prepared.bind(absDelta, *keyValues.toTypedArray())
            .setIdempotent(false)
            .setConsistencyLevel(resolveWriteConsistency(schema, consistency).toDriverLevel())
    }

    /**
     * GH #107: [whereCql] is spliced directly into the generated CQL with no identifier/heuristic
     * validation of its own -- exactly the same shape of raw-CQL splicing
     * [QueryExecutor.checkRawInjectionRisk] guards for `raw()`/`rawQuery()` (GH #50 / ISS-050).
     * [StatementBuilder] is a public class with a public constructor, reachable cross-module (e.g.
     * from `kandra-kodein`), so `existsQuery` isn't purely internal plumbing even though nothing in
     * this codebase currently calls it -- guarded the same way every other raw-CQL entry point is,
     * rather than relying on it staying unreachable.
     */
    private fun checkRawInjectionRisk(cql: String, callerName: String) {
        if (!SUSPICIOUS_LITERAL_PATTERN.containsMatchIn(cql)) return
        val message = "$callerName() CQL appears to contain a string literal spliced directly into the " +
            "query (independent of any other bound parameters). If any of it came from user input this " +
            "is a CQL injection risk. Build the WHERE clause from parameterized predicates instead."
        if (debugConfig.rawQueryStrictMode) {
            throw KandraQueryException(message)
        } else {
            logger.warn { message }
        }
    }

    fun existsQuery(schema: TableSchema, whereCql: String, values: List<Any?>): BoundStatement {
        checkRawInjectionRisk(whereCql, "existsQuery")
        val pkCols = schema.partitionKeys.joinToString(", ") { it.cqlName }
        val cql = "SELECT $pkCols FROM ${schema.tableName} WHERE $whereCql LIMIT 1"
        val prepared = prepare(cql)
        return prepared.bind(*values.toTypedArray()).setIdempotent(true)
    }
}
