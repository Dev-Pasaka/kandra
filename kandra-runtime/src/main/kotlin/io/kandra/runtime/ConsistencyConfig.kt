package io.kandra.runtime

import io.kandra.core.InternalKandraApi
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
 *
 * **Read-your-writes above RF 3** (see ISS-069 / GH #70 item 3): read-your-writes requires
 * `R + W >= RF`. The defaults below (`LOCAL_ONE` + `LOCAL_QUORUM`) only satisfy that for `RF <= 3`
 * (`1 + 2 = 3`) — a keyspace with `RF = 5` (plausible for a larger multi-DC deployment) using these
 * defaults silently stops guaranteeing read-your-writes, with no warning from Strict Mode (which only
 * fires on `LOCAL_ONE`/`ONE` usage in a multi-DC topology, not on an RF/consistency mismatch). This is
 * exactly the kind of thing that passes every test against an `RF=1` Testcontainers setup and surfaces
 * as a mystery stale-read bug only under a real `RF=5` production topology — raise [defaultRead] (e.g.
 * to `LOCAL_QUORUM`) if your keyspace's replication factor exceeds 3.
 */
class ConsistencyConfig {
    var defaultRead: KandraConsistency = KandraConsistency.LOCAL_ONE
    var defaultWrite: KandraConsistency = KandraConsistency.LOCAL_QUORUM
    var defaultSerialConsistency: KandraConsistency = KandraConsistency.LOCAL_SERIAL

    /**
     * Strict Mode (GH #5) — opt-in, default `false`, WARN-only, never throws.
     *
     * When `true` *and* [multiDcTopology] is also `true`, [StatementBuilder] logs a WARN every time a
     * query resolves (after per-call override / `@ReadConsistency`/`@WriteConsistency` / these defaults)
     * to `LOCAL_ONE` or `ONE`. In a multi-DC deployment those levels are satisfied by a single replica in
     * a single datacenter — usually not what's intended, since `LOCAL_QUORUM` is the normal default for
     * durability across datacenters. This never throws or blocks the query — it only warns, so turning it
     * on cannot break an existing deployment.
     *
     * Set via the `consistency { }` DSL block in `install(Kandra) { }`:
     * ```kotlin
     * install(Kandra) {
     *     consistency { strictMode = true }
     *     loadBalancing { allowedRemoteDcs = listOf("eu-west") } // multi-DC topology signal
     * }
     * ```
     */
    var strictMode: Boolean = false

    /**
     * Multi-DC topology signal — **not** user-set directly (hence [InternalKandraApi]). Automatically
     * derived and populated by the `Kandra` Ktor plugin's install path from
     * `KandraConfig.loadBalancing.allowedRemoteDcs.isNotEmpty()`. A user configuring multi-DC failover
     * already sets `allowedRemoteDcs`; combined with [strictMode], `StatementBuilder` can then warn on
     * `LOCAL_ONE`/`ONE` resolutions without requiring any additional user-facing config, and without
     * `kandra-runtime` depending on `kandra-multidc` (or vice versa).
     */
    @InternalKandraApi
    var multiDcTopology: Boolean = false
}
