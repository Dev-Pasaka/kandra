package io.kandra.kodein.admin

import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import java.util.UUID

/**
 * GH-35 fixture: a `User` entity in the "admin" package, sharing its simple name with
 * [io.kandra.kodein.billing.User] but not its fully-qualified name — the exact `billing.User` /
 * `admin.User` collision shape from the issue.
 */
@ScyllaTable("kodein_admin_users")
data class User(@PartitionKey val id: UUID, val name: String)
