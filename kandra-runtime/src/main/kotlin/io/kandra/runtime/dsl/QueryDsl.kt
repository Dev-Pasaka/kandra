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
 *
 * Comparison operators (`eq`/`gt`/`gte`/`lt`/`lte`/`isIn`) are declared as [QueryContext] member
 * extensions, not here — see [QueryContext] for why.
 */
class KandraColumnRef<T>(val cqlName: String, val isLookup: Boolean = false)

/**
 * DSL receiver for building query predicates and limit.
 *
 * Every predicate must resolve to a partition key, clustering key, or lookup-table
 * column. Kandra does not support `ALLOW FILTERING` — queries that cannot be served
 * from a partition key will throw at query time.
 *
 * ```kotlin
 * repository.findAll {
 *     UserTable.email eq "alice@example.com"   // via @LookupIndex
 *     limit(10)
 * }
 * ```
 *
 * **Why there's no `+`:** earlier versions required `+UserTable.email.eq(...)`, with `eq()`
 * returning a plain [KandraPredicate] that `unaryPlus()` then registered. Forgetting the `+` on
 * one predicate out of several compiled cleanly (Kotlin only warns on an unused expression) and
 * silently dropped that predicate from the query — a correctness footgun with no way to detect it
 * from inside `QueryContext`, since the discarded object never reached it. Declaring `eq`/`gt`/etc.
 * as member extensions here instead means the comparison itself *is* the registration — there is no
 * intermediate value that can be built and then forgotten.
 */
class QueryContext {
    internal val predicates = mutableListOf<KandraPredicate>()
    internal var limit: Int? = null

    infix fun <T> KandraColumnRef<T>.eq(value: T) { predicates.add(KandraPredicate.Eq(cqlName, value)) }
    infix fun <T> KandraColumnRef<T>.gt(value: T) { predicates.add(KandraPredicate.Gt(cqlName, value)) }
    infix fun <T> KandraColumnRef<T>.gte(value: T) { predicates.add(KandraPredicate.Gte(cqlName, value)) }
    infix fun <T> KandraColumnRef<T>.lt(value: T) { predicates.add(KandraPredicate.Lt(cqlName, value)) }
    infix fun <T> KandraColumnRef<T>.lte(value: T) { predicates.add(KandraPredicate.Lte(cqlName, value)) }
    infix fun <T> KandraColumnRef<T>.isIn(values: List<T>) { predicates.add(KandraPredicate.In(cqlName, values)) }

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
