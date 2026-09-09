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
 * **Read-your-writes above RF 2** (see ISS-069 / GH #70 item 3, corrected by ISS-075 / GH #83):
 * read-your-writes requires the *strict* inequality `R + W > RF` — with `R + W == RF`, an unlucky
 * replica placement can still make the read set and write set fully disjoint (e.g. `RF=3`, a write
 * quorum of 2 lands on replicas `{A, B}`, and a single-replica read happens to land on `{C}` — no
 * overlap). Only `R + W > RF` guarantees overlap, by pigeonhole. The defaults below (`LOCAL_ONE` +
 * `LOCAL_QUORUM`) sum to a fixed weight of `1 + quorum(RF)`, which only exceeds `RF` for `RF <= 2`
 * (at `RF = 3`, `1 + 2 = 3` does **not** exceed `3`) — a keyspace with `RF = 3` or higher using these
 * defaults silently stops guaranteeing read-your-writes. This is exactly the kind of thing that passes
 * every test against an `RF=1` Testcontainers setup and surfaces as a mystery stale-read bug only under
 * a real multi-replica production topology — raise [defaultRead] (e.g. to `LOCAL_QUORUM`) once your
 * keyspace's replication factor exceeds 2. [strictMode] (below) checks this directly against the
 * resolved table's *actual* replication factor (read live from driver metadata) instead of relying on
 * this comment alone — see [StatementBuilder]'s `warnIfRfConsistencyMismatch`.
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
     *
     * **Also enables the RF-vs-(R+W) check (ISS-075 / GH #83)**: independently of [multiDcTopology],
     * when `strictMode` is `true`, [StatementBuilder] reads the resolved table's actual replication
     * factor from live driver metadata and warns whenever the resolved read/write consistency pair
     * doesn't satisfy `R + W > RF` (strictly) — the read-your-writes guarantee described above. Unlike
     * the `LOCAL_ONE`/`ONE` check, this one applies on a single-DC cluster too (an RF > 2 keyspace is
     * not exclusive to multi-DC deployments) — e.g. it fires for the defaults above once `RF >= 3`.
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
