package io.kandra.koin.billing

import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import java.util.UUID

/**
 * GH-35 fixture: a `User` entity in the "billing" package, sharing its simple name with
 * [io.kandra.koin.admin.User] but not its fully-qualified name — the exact `billing.User` /
 * `admin.User` collision shape from the issue.
 */
@ScyllaTable("koin_billing_users")
data class User(@PartitionKey val id: UUID, val name: String)
