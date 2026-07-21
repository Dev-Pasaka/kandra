package io.kandra.core.annotations

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ScyllaTable(
    val name: String,
    val gcGraceSeconds: Int = -1  // -1 means use ScyllaDB default
)

/** Marks a partition key field. Use [index] for composite partition keys. */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class PartitionKey(val index: Int = 0)

enum class ClusteringOrder { ASC, DESC }

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class ClusteringKey(
    val order: ClusteringOrder = ClusteringOrder.ASC,
    val index: Int = 0
)

enum class LookupConsistency { BATCH, EVENTUAL }

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class LookupIndex(
    val tableSuffix: String,
    val consistency: LookupConsistency = LookupConsistency.BATCH
)

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Column(val name: String)

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Transient

/** Sets a table-level default TTL in seconds. Lookup tables never inherit this TTL. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Ttl(val seconds: Int)

/** Marks a ScyllaDB COUNTER column. All non-key columns in a counter table must use this. */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Counter

/** Auto-set to [java.time.Instant.now] on INSERT. Never updated by Kandra. */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class CreatedAt

/** Auto-set to [java.time.Instant.now] on every INSERT and UPDATE. */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class UpdatedAt

/**
 * Creates a native ScyllaDB secondary index on this column.
 *
 * **WARNING:** Secondary indexes use scatter-gather across ALL nodes in the cluster.
 * Only use for low-cardinality fields (e.g. `accountStatus`, `isVerified`) and
 * non-hot-path queries. Never use on high-cardinality columns like `email` or `userId` —
 * use `@LookupIndex` instead for those.
 *
 * Kandra logs a WARN every time a secondary index query executes in production to remind
 * developers of the cost.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class SecondaryIndex

/**
 * Overrides the default read consistency level for this entity's table.
 * Per-operation consistency overrides this annotation.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ReadConsistency(val level: io.kandra.core.KandraConsistency)

/**
 * Overrides the default write consistency level for this entity's table.
 * Per-operation consistency overrides this annotation.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class WriteConsistency(val level: io.kandra.core.KandraConsistency)

/** Marks the optimistic lock version column. Must be Long or Instant. Updates use LWT IF version = ?. */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Version

/** When applied, delete() sets a TTL on the row instead of issuing a DELETE. Avoids tombstone accumulation. */
/**
 * @param markerProperty Opt-in name of a `Boolean` property on the entity that permanently
 * records deletion state (`true` = deleted). Required to use `findActive()` — without it there
 * is no CQL predicate that can distinguish a soft-deleted row from a live one before its TTL
 * expires. The marker column itself is written without a TTL, so it survives after the other
 * columns' values are gone.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class SoftDelete(val ttlSeconds: Int = 86400, val markerProperty: String = "")

/** Marks a field as sensitive — never logged. */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Sensitive

/** Enables Caffeine-backed findById caching. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class CacheResult(val ttlSeconds: Int = 60, val maxSize: Long = 1000)
