package io.kandra.kodein.ksb

import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import java.util.UUID

/** ISS-107 fixture: same simple name `Invoice` as the sibling package, but a different keyspace. */
@ScyllaTable("kodein_ksb_invoices", keyspace = "archive_kodein")
data class Invoice(@PartitionKey val id: UUID, val total: Long)
