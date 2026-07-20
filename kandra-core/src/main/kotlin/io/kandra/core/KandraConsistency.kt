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
 * ## LWT serial consistency (for [saveIfNotExists])
 * Use [LOCAL_SERIAL] (default) for Paxos within the local DC only.
 * Use [SERIAL] for globally unique constraints (e.g. a username unique across ALL DCs).
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
}
