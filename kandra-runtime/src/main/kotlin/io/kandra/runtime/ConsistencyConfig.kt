package io.kandra.runtime

import io.kandra.core.KandraConsistency

/**
 * Default consistency levels applied to all operations.
 *
 * Override per-operation or per-table with `@ReadConsistency`/`@WriteConsistency` annotations,
 * or pass a `consistency` parameter directly to repository methods.
 *
 * Resolution order (highest priority first):
 * 1. Per-operation parameter
 * 2. `@ReadConsistency` / `@WriteConsistency` on the entity class
 * 3. These defaults
 */
class ConsistencyConfig {
    var defaultRead: KandraConsistency = KandraConsistency.LOCAL_ONE
    var defaultWrite: KandraConsistency = KandraConsistency.LOCAL_QUORUM
    var defaultSerialConsistency: KandraConsistency = KandraConsistency.LOCAL_SERIAL
}
