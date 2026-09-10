package io.kandra.core

/**
 * Consistency levels for ScyllaDB/Cassandra operations.
 *
 * ## Write consistency
 * Default: [LOCAL_QUORUM] — majority of replicas in the local DC must acknowledge.
 *
 * ## Read consistency
 * Default: [LOCAL_ONE] — single replica in the local DC, fastest possible read.
 *
 * ## LWT serial consistency (for `saveIfNotExists` and a `@Version`-locked `update`)
 * Use [LOCAL_SERIAL] (default) for Paxos within the local DC only.
 * Use [SERIAL] for globally unique constraints (e.g. a username unique across ALL DCs) or when an
 * optimistic-locked `update` must not let a concurrent write land undetected on a different DC
 * during a network partition (GH #134) — `LOCAL_SERIAL` only guarantees the check is linearizable
 * within the DC the write went through.
 *
 * ## Consistency resolution order (highest priority first)
 * 1. Per-operation parameter on the repository method
 * 2. `@ReadConsistency` / `@WriteConsistency` annotation on the entity class
 * 3. `KandraConfig.consistency.defaultRead` / `defaultWrite`
 */
enum class KandraConsistency {
    ONE, TWO, THREE,
    QUORUM,
    ALL,
    /** Single replica in the local DC — fastest read, weakest guarantee. */
    LOCAL_ONE,
    /** Majority of replicas in the local DC — default write. */
    LOCAL_QUORUM,
    /** Write to majority in EVERY DC — strongest multi-DC write guarantee. */
    EACH_QUORUM,
    /** Paxos serial consistency in local DC only — default for LWT. */
    LOCAL_SERIAL,
    /** Global Paxos across all DCs — required for globally unique constraints. */
    SERIAL;

    val isSerial: Boolean get() = this == LOCAL_SERIAL || this == SERIAL

    /**
     * Whether this level is valid as the *regular* (non-serial) consistency of a **read** operation.
     *
     * [EACH_QUORUM] is the only level here Cassandra/Scylla reject for reads — it's a write-only,
     * multi-DC level; the coordinator errors out server-side ("EACH_QUORUM ConsistencyLevel is only
     * supported for writes") rather than executing the read. Every other level, including
     * [SERIAL]/[LOCAL_SERIAL] (which perform a linearizable read), is valid here.
     *
     * See `resolveReadConsistency` in `kandra-runtime`'s `StatementBuilder` (validated at the point a
     * read consistency is resolved from a per-call override / `@ReadConsistency` / `defaultRead`) and
     * `kandra-ktor`'s `Kandra` plugin (validated eagerly for `consistency { defaultRead = ... }` at
     * install time, before any query runs).
     */
    val isValidForRead: Boolean get() = this != EACH_QUORUM

    /**
     * Whether this level is valid as the *regular* (non-serial) consistency of a **write** operation.
     *
     * [SERIAL]/[LOCAL_SERIAL] are the only levels here Cassandra/Scylla reject as a write's regular
     * consistency — the coordinator errors out server-side ("You must use conditional updates for
     * serializable writes"). Those two levels are only meaningful as the separate *serial*
     * consistency parameter of a conditional (`IF`) statement — see the `serialConsistency` parameter
     * on `saveIfNotExists()`/`update()` — not as a table's regular write consistency.
     *
     * See `resolveWriteConsistency` in `kandra-runtime`'s `StatementBuilder` and `kandra-ktor`'s
     * `Kandra` plugin, mirroring [isValidForRead]'s two enforcement points.
     */
    val isValidForWrite: Boolean get() = !isSerial
}
