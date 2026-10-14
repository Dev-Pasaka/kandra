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

enum class UuidStrategy { TIME_ORDERED, RANDOM }

/**
 * Auto-populates a `UUID` field on every INSERT via [io.kandra.core.KandraUuid], the same way
 * [CreatedAt] is auto-populated. Any caller-supplied value is overwritten. Never touched by
 * update()/updateForce() — primary key components are immutable in Cassandra. Must be on a `UUID` field.
 *
 * Use on a `@ClusteringKey`/`@PartitionKey` to avoid the same-millisecond collision risk of keys
 * derived from `Instant.now()`.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class GeneratedUuid(val strategy: UuidStrategy = UuidStrategy.TIME_ORDERED)

/**
 * When applied, delete() sets a TTL on the non-key, non-marker columns instead of issuing a
 * DELETE — deferring the tombstone rather than avoiding it, see below.
 *
 * Lifecycle: at [ttlSeconds], the entity's data columns expire. The row survives past that as a
 * key+marker **husk** — [markerProperty]'s column is deliberately given a *longer* TTL than the
 * data (see [markerTtlSeconds]) so `findActive()` can keep telling this row apart from a live one
 * for the whole window between the two expiries; without that window there'd be no way to
 * distinguish "deleted, data not yet expired" from "never deleted." The husk itself fully
 * disappears once the marker's own TTL elapses — this is deferred tombstone accumulation, not
 * avoided, and only for as long as [markerTtlSeconds] (GH #154 / ISS-097).
 *
 * Every persisted, non-key, non-marker property on a `@SoftDelete` entity must be nullable (or
 * have a constructor default) — `SchemaRegistry.register()` rejects the entity otherwise. During
 * the husk window its data columns genuinely read back as `NULL`; a non-nullable property with no
 * default has no legal value to decode into there, and would otherwise throw on any read that
 * reaches the row (GH #153 / ISS-098).
 *
 * @param markerProperty Opt-in name of a `Boolean` property on the entity that records deletion
 * state (`true` = deleted) for as long as [markerTtlSeconds]. Required to use `findActive()` —
 * without it there is no CQL predicate that can distinguish a soft-deleted row from a live one
 * before its TTL expires.
 * @param markerTtlSeconds How long the marker column (and so the whole husk row) survives past
 * the data columns' own [ttlSeconds]. Must be strictly greater than [ttlSeconds] — if left at the
 * default `null`, resolves to `ttlSeconds + gcGraceSeconds` of the entity's own `@ScyllaTable`
 * (Cassandra/Scylla's real default of 864000s/10 days when `gcGraceSeconds` is left at its own
 * sentinel default of `-1`).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class SoftDelete(
    val ttlSeconds: Int = 86400,
    val markerProperty: String = "",
    val markerTtlSeconds: Int = -1
)

/** Marks a field as sensitive — never logged. */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Sensitive

/** Enables Caffeine-backed findById caching. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class CacheResult(val ttlSeconds: Int = 60, val maxSize: Long = 1000)
