package io.kandra.koin.ksa

import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import java.util.UUID

/** ISS-107 fixture: same simple name `Invoice` as the sibling package, but a different keyspace. */
@ScyllaTable("koin_ksa_invoices", keyspace = "billing_koin")
data class Invoice(@PartitionKey val id: UUID, val total: Long)
