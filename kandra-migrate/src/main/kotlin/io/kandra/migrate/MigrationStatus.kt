package io.kandra.migrate

import java.time.Instant

data class MigrationHistory(
    val version: Int,
    val name: String,
    val appliedAt: Instant,
    val checksum: String
)

enum class MigrationStatus { PENDING, APPLIED, CHECKSUM_MISMATCH }
