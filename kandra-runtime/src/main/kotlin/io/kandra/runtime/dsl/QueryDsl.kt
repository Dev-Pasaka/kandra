package io.kandra.runtime.dsl

/**
 * Represents a single WHERE clause predicate in a Kandra query.
 */
sealed class KandraPredicate {
    data class Eq(val column: String, val value: Any?) : KandraPredicate()
    data class Gt(val column: String, val value: Any?) : KandraPredicate()
    data class Gte(val column: String, val value: Any?) : KandraPredicate()
    data class Lt(val column: String, val value: Any?) : KandraPredicate()
    data class Lte(val column: String, val value: Any?) : KandraPredicate()
    data class In(val column: String, val values: List<Any?>) : KandraPredicate()
}

/**
 * Type-safe reference to a CQL column. Produced by `kandra-codegen` or constructed manually.
 */
class KandraColumnRef<T>(val cqlName: String, val isLookup: Boolean = false) {
    infix fun eq(value: T): KandraPredicate = KandraPredicate.Eq(cqlName, value)
    infix fun gt(value: T): KandraPredicate = KandraPredicate.Gt(cqlName, value)
    infix fun gte(value: T): KandraPredicate = KandraPredicate.Gte(cqlName, value)
    infix fun lt(value: T): KandraPredicate = KandraPredicate.Lt(cqlName, value)
    infix fun lte(value: T): KandraPredicate = KandraPredicate.Lte(cqlName, value)
    infix fun isIn(values: List<T>): KandraPredicate = KandraPredicate.In(cqlName, values)
}

/**
 * DSL receiver for building query predicates and limit.
 *
 * Every predicate must resolve to a partition key, clustering key, or lookup-table
 * column. Kandra does not support `ALLOW FILTERING` — queries that cannot be served
 * from a partition key will throw at query time.
 *
 * ```kotlin
 * repository.findAll {
 *     +UserTable.email.eq("alice@example.com")   // via @LookupIndex
 *     limit(10)
 * }
 * ```
 */
class QueryContext {
    internal val predicates = mutableListOf<KandraPredicate>()
    internal var limit: Int? = null

    operator fun KandraPredicate.unaryPlus() { predicates.add(this) }

    /** Appends `LIMIT n` to the generated CQL. */
    fun limit(n: Int) { limit = n }
}

/**
 * A single page of query results with a token for fetching the next page.
 *
 * @param nextPageToken base64-encoded paging state; null if this is the last page
 * @param hasMore true when more results exist beyond this page
 */
data class KandraPage<T>(
    val items: List<T>,
    val nextPageToken: String?,
    val hasMore: Boolean
)

/** Marker interface implemented by generated table objects (e.g. `UserTable`). */
interface KandraTable<T>
