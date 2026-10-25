package io.kandra.core

/**
 * Shared CQL identifier naming/validation logic.
 *
 * This is consumed by two independent call sites that must never drift apart:
 *  - `SchemaRegistry.buildSchema` (this module, runtime schema validation — see GH-30)
 *  - `kandra-codegen`'s `KandraProcessor.resolveCqlName` (compile-time `*Table` generation)
 *
 * `kandra-codegen` already has an `implementation(project(":kandra-core"))` compile dependency
 * (needed to reference the annotation classes), so it calls these functions directly rather than
 * re-implementing them — see `KandraProcessor.kt`, which delegates here instead of keeping its own
 * copy. If you ever change the blank-name fallback or the identifier-shape rule, this is the only
 * place to change it; both `SchemaRegistry` and `KandraProcessor` pick it up automatically.
 */
object CqlNaming {

    private val IDENTIFIER_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    /**
     * Naive camelCase -> snake_case conversion: every individual uppercase letter is replaced with
     * `_` + its lowercase form, then a leading underscore (from a name starting with a capital) is
     * trimmed. No special-casing for acronyms or existing underscores/digits — `"userId"` ->
     * `"user_id"`, but `"userURL"` -> `"user_u_r_l"`.
     */
    fun camelToSnake(name: String): String =
        name.replace(Regex("([A-Z])")) { "_${it.value.lowercase()}" }.trimStart('_')

    /**
     * Resolves the CQL column name for a property given the raw `@Column.name` argument value, or
     * `null` if the property carries no `@Column` annotation at all.
     *
     * A blank/whitespace-only `@Column(name = "")` is treated identically to "no `@Column`" and
     * falls back to `camelToSnake(propertyName)` — this fallback must stay in sync between
     * `SchemaRegistry` (runtime) and `KandraProcessor` (codegen), otherwise the same entity can
     * resolve to two different column names depending on which one looks at it (GH-30).
     */
    fun resolveColumnName(columnAnnotationName: String?, propertyName: String): String =
        columnAnnotationName?.takeIf { it.isNotBlank() } ?: camelToSnake(propertyName)

    /**
     * Basic CQL identifier shape check: non-blank, first character is a letter or underscore,
     * remaining characters are letters/digits/underscores. This is intentionally not a full CQL
     * grammar validator (reserved words are handled by [quoteIfNeeded]) — it exists only to
     * catch obvious mistakes (blank names, names starting with a digit, names containing spaces or
     * punctuation) before they get spliced into DDL/DML strings.
     */
    fun isValidIdentifier(name: String): Boolean =
        name.isNotBlank() && IDENTIFIER_REGEX.matches(name)

    /**
     * Words that Cassandra 4.1 or ScyllaDB 2026.1 reject as a bare identifier. Every word in the
     * keyword appendix of either grammar was tried as a bare column name in `CREATE TABLE` against
     * both databases (GH #188); this is the union of the ones that failed (e.g. `token`, `order`,
     * `default`), plus the documented reserved keywords that happened to parse in those versions
     * (`mbean`, `mbeans`). Unreserved keywords such as `timestamp`, `type`, `key`, `ttl`, `date` or
     * `count` are deliberately absent: they work bare, so their DDL stays byte-for-byte unchanged.
     */
    val RESERVED_WORDS: Set<String> = setOf(
        "add", "allow", "alter", "and", "apply", "asc", "authorize", "batch", "begin", "by", "cast",
        "columnfamily", "create", "default", "delete", "desc", "describe", "drop", "entries", "execute",
        "from", "full", "grant", "if", "in", "index", "infinity", "insert", "into", "is", "keyspace",
        "limit", "materialized", "mbean", "mbeans", "modify", "nan", "norecursive", "not", "null", "of",
        "on", "or", "order", "primary", "rename", "replace", "revoke", "schema", "select", "set", "table",
        "to", "token", "truncate", "unlogged", "unset", "update", "use", "using", "view", "where", "with",
    )

    /** `true` when [name] (case-insensitively) is in [RESERVED_WORDS]. */
    fun isReserved(name: String): Boolean = name.lowercase() in RESERVED_WORDS

    /**
     * Renders a column [name] for splicing into generated CQL. A reserved word is double-quoted in
     * its lowercase form (`token` -> `"token"`), which names exactly the column an unquoted
     * `token` would if the grammar allowed it, so existing tables, `system_schema` lookups and
     * `Row.get("token")` reads keep working. Every other name is returned unchanged. GH #188.
     */
    fun quoteIfNeeded(name: String): String =
        if (isReserved(name)) "\"${name.lowercase()}\"" else name
}
