package io.kandra.runtime.dsl

/** Safe parameterised CQL query builder — use instead of string interpolation in [raw]. */
class KandraRawQuery internal constructor(
    val cql: String,
    val params: List<Any?>
) {
    companion object {
        fun cql(template: String): KandraRawQueryBuilder = KandraRawQueryBuilder(template)
    }
}

class KandraRawQueryBuilder(private val template: String) {
    private val params = mutableListOf<Any?>()

    fun bind(vararg values: Any?): KandraRawQueryBuilder {
        params.addAll(values)
        return this
    }

    fun build(): KandraRawQuery = KandraRawQuery(template, params.toList())
}
