package io.kandra.kodein.ksa

import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import java.util.UUID

/** ISS-107 fixture: same simple name `Invoice` as the sibling package, but a different keyspace. */
@ScyllaTable("kodein_ksa_invoices", keyspace = "billing_kodein")
data class Invoice(@PartitionKey val id: UUID, val total: Long)
