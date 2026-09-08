# ISS-051: `KandraColumnRef`'s public constructor accepted an unvalidated `cqlName`

**Status:** Fixed

## Problem

`KandraColumnRef` has a public constructor that accepted any string as the CQL column name, with
no validation whatsoever:

```kotlin
// kandra-runtime/src/main/kotlin/io/kandra/runtime/dsl/QueryDsl.kt:21 (before this fix)
class KandraColumnRef<T>(val cqlName: String, val isLookup: Boolean = false)
```

Every predicate built through `QueryContext` (`eq`, `gt`, `gte`, `lt`, `lte`, `isIn`) reads
`cqlName` straight off the receiver:

```kotlin
infix fun <T> KandraColumnRef<T>.eq(value: T) { predicates.add(KandraPredicate.Eq(cqlName, value)) }
```

and `QueryExecutor.buildWhere()` splices that column name directly into the generated `WHERE`
clause (`"${pred.column} = ?"` and friends) — only the *value* is parameterized, never the column
name.

Normally `KandraColumnRef` instances only come from `kandra-codegen`'s generated `*Table` objects,
where `cqlName` is derived from a compile-time-known property name via
`io.kandra.core.CqlNaming.resolveColumnName`. But the constructor itself was public, documented as
supporting manual construction ("Produced by `kandra-codegen` or constructed manually."), and
performed no validation at all — nothing stopped application code from building
`KandraColumnRef<String>(userInput)` directly.

## Impact

If any caller ever builds a `KandraColumnRef` from a dynamic/user-influenced value — plausible for
generic admin tooling, dynamic filtering UIs, or anything that maps a request parameter to a
column — the resulting predicate's column name is spliced unparameterized into the `WHERE` clause
with no validation. This is a second and independent CQL-injection surface from the one tracked for
`raw()`/`rawQuery()` under GH #32 / ISS-050: that surface is the raw CQL string passed to `raw()`;
this one is the column name inside a DSL-built predicate, which looks type-safe at the call site but
was not actually validated.

Example of the exposed shape (never intended, but compiled and ran without error before this fix):

```kotlin
val col = KandraColumnRef<String>(untrustedRequestParam)  // e.g. "x' OR '1'='1" — no error here
repository.findAll { col eq "value" }                     // splices untrustedRequestParam into WHERE
```

## Fix

Added an `init` block to `KandraColumnRef` that validates `cqlName` against
`io.kandra.core.CqlNaming.isValidIdentifier` — the exact same basic CQL-identifier-shape check
`SchemaRegistry.buildSchema` (kandra-core) already applies to every column name resolved from an
entity's properties (see GH-30 / the `CqlNaming` object doc comment). `kandra-codegen`'s
`KandraProcessor.resolveCqlName` delegates to the same `CqlNaming.resolveColumnName` helper that
`SchemaRegistry` uses, so every `cqlName` a generated `*Table` object can ever carry is guaranteed
to already satisfy this check — the fix cannot reject anything the legitimate codegen path produces.

```kotlin
class KandraColumnRef<T>(val cqlName: String, val isLookup: Boolean = false) {
    init {
        if (!CqlNaming.isValidIdentifier(cqlName)) {
            throw KandraSchemaException(
                "Invalid CQL column name '$cqlName' — column names must be non-blank, start with " +
                    "a letter or underscore, and contain only letters, digits, and underscores."
            )
        }
    }
}
```

`KandraSchemaException` was chosen for consistency with every other schema/naming validation
failure in the codebase (`SchemaRegistry`, `DdlGenerator`, `StatementBuilder` all throw
`KandraSchemaException` for malformed/missing schema elements) — this is a schema-shape problem
(an invalid column reference), not a query-execution problem (`KandraQueryException`).

This is a one-time, cheap regex check performed once per `KandraColumnRef` construction. It does
not affect the generated-code path (those names always pass), and it closes the constructor as an
injection surface for hand-built instances.

## Tests

`kandra-runtime/src/test/kotlin/io/kandra/runtime/dsl/KandraColumnRefValidationTest.kt`:

- Valid identifiers (lowercase, `snake_case`, leading underscore, embedded digits, with
  `isLookup = true`) construct without error.
- Invalid identifiers throw `KandraSchemaException`: blank, leading digit, embedded space, an
  embedded single quote (`x' OR '1'='1`), an embedded semicolon (`email; DROP TABLE users`), a
  `--` comment marker, and stray punctuation (`.`, `-`).
- A dedicated test registers a realistic entity (`CrWidget`) through `SchemaRegistry` — with a
  camelCase property (`displayName` → `display_name`), a `@Column(name = "custom_col")` override,
  and a `@LookupIndex` column (`emailAddress` → `email_address`) — and constructs a
  `KandraColumnRef` from every `cqlName` `SchemaRegistry` resolves for it, proving the exact same
  naming logic `kandra-codegen` relies on (`CqlNaming.resolveColumnName`/`isValidIdentifier`) always
  produces names that pass this new validation.
- Verified before/after: with the `init` block temporarily removed, the seven "invalid identifier"
  tests fail (`AssertionFailedError` — no exception thrown); with it restored, all 13 tests pass.

## Files

`kandra-runtime/src/main/kotlin/io/kandra/runtime/dsl/QueryDsl.kt`,
`kandra-runtime/src/test/kotlin/io/kandra/runtime/dsl/KandraColumnRefValidationTest.kt` (new).
