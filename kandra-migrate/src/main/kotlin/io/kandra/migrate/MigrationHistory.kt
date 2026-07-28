package io.kandra.migrate

import java.time.Instant

/**
 * One row of the `kandra_migrations` bookkeeping table.
 *
 * @property status [MigrationRowStatus.CLAIMED] means the migration's `up()` has not been
 *   confirmed complete — it may be running on another instance right now, or may be the debris
 *   of a crashed process. Only [MigrationRowStatus.APPLIED] rows represent a confirmed-complete
 *   migration. Defaults to `APPLIED` for legacy rows written before this column existed.
 * @property claimedAt when a runner reserved this version, prior to running `up()`. Null for
 *   legacy rows written before this column existed.
 * @property appliedAt when `up()` was confirmed to have completed. For a row still [CLAIMED][MigrationRowStatus.CLAIMED],
 *   this has not been set yet and reads as [Instant.EPOCH].
 */
data class MigrationHistory(
    val version: Int,
    val name: String,
    val appliedAt: Instant,
    val checksum: String,
    val status: MigrationRowStatus = MigrationRowStatus.APPLIED,
    val claimedAt: Instant? = null
)
