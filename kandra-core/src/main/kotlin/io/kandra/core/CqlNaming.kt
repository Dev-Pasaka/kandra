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
     * grammar validator (no reserved-word check, no quoted-identifier support) — it exists only to
     * catch obvious mistakes (blank names, names starting with a digit, names containing spaces or
     * punctuation) before they get spliced into DDL/DML strings.
     */
    fun isValidIdentifier(name: String): Boolean =
        name.isNotBlank() && IDENTIFIER_REGEX.matches(name)
}
