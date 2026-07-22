---
name: kandra-codegen
description: Exhaustive reference for kandra-codegen — the KSP processor that generates type-safe *Table objects from @ScyllaTable entities. Load when adding KSP to a build, debugging codegen output, or writing code that uses generated *Table objects (UserTable.email eq "..." style queries).
---

# kandra-codegen

`kandra-codegen` is a single KSP `SymbolProcessor` (`io.kandra.codegen.KandraProcessor`, in
`kandra-codegen/src/main/kotlin/io/kandra/codegen/KandraProcessor.kt`) that scans for classes annotated
`@ScyllaTable` and emits one `*Table` Kotlin object per entity, containing a `KandraColumnRef<T>` property
for every non-`@Transient` field. Nothing else in the module does anything — there's no config DSL, no
processor options, no multi-file output. This doc traces the actual generation logic line by line; nothing
here is guessed from annotation names.

## Trigger and registration

- Service registration: `kandra-codegen/src/main/resources/META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`
  contains exactly one line: `io.kandra.codegen.KandraProcessorProvider`. That's the whole KSP wiring — add
  `ksp("io.kandra:kandra-codegen:...")` to a module's build script and KSP auto-discovers the provider via
  this file.
- `KandraProcessorProvider.create(environment)` just does
  `KandraProcessor(environment.codeGenerator, environment.logger)` — it does **not** read
  `environment.options`, so there are currently no `ksp { arg(...) }` processor options to configure
  anything (no way to disable it for one class, change the naming convention, etc.).
- `process(resolver)` triggers on **exactly one** annotation FQN: `io.kandra.core.annotations.ScyllaTable`.
  Nothing else in the codebase (not `@PartitionKey`, not `@Column` standing alone, nothing) causes a file to
  be generated. A class with `@Column`/`@PartitionKey` fields but no class-level `@ScyllaTable` produces
  nothing.

## What `process()` actually does

```kotlin
override fun process(resolver: Resolver): List<KSAnnotated> {
    val symbols = resolver.getSymbolsWithAnnotation("io.kandra.core.annotations.ScyllaTable")
    val unprocessed = symbols.filter { !it.validate() }.toList()

    symbols
        .filter { it is KSClassDeclaration && it.validate() }
        .forEach { processClass(it as KSClassDeclaration) }

    return unprocessed
}
```

- `getSymbolsWithAnnotation` finds every declaration carrying `@ScyllaTable`. Since the annotation's
  `@Target` is `AnnotationTarget.CLASS`, in practice this is always a `KSClassDeclaration` — but note the
  `it is KSClassDeclaration` filter is the *only* shape check performed. **There is no check on `classKind`.**
  Putting `@ScyllaTable` on an `object`, an `interface`, an `enum class`, or an `abstract class` is not
  rejected — it will happily generate a `*Table` object referencing that type as the entity type parameter,
  which will very likely fail to compile or make no sense downstream (e.g. an object has no properties to
  key by). The processor trusts that `@ScyllaTable` is only ever applied to a normal concrete/data class.
- **Symbol validation, not business validation**: `it.validate()` is KSP's own resolvability check (all
  referenced types resolve cleanly) — it exists to support incremental, multi-round annotation processing
  when generated code from one round is needed to resolve types in another. It is *not* a Kandra-specific
  correctness check. A class that references an unresolved type (e.g. because it depends on another KSP
  processor's not-yet-generated output) fails `validate()` and is deferred to a later round via the returned
  `unprocessed` list, standard KSP round-tripping. It has nothing to do with whether the entity's annotation
  usage is sane.
- **No check that the entity has a `@PartitionKey` at all.** A `@ScyllaTable` class with zero `@PartitionKey`
  properties generates a `*Table` object exactly like a valid one — codegen has no opinion on primary-key
  correctness. (Whatever validates that, if anything does, lives outside this module — presumably schema
  registration/DDL generation in `kandra-core`/`kandra-runtime` at application startup, not at compile time.)

## `processClass(classDecl)` — the actual generation

```kotlin
private fun processClass(classDecl: KSClassDeclaration) {
    val packageName = classDecl.packageName.asString()
    val className = classDecl.simpleName.asString()
    val objectName = "${className}Table"

    val properties = classDecl.getAllProperties().toList()

    val columnRefs = properties
        .filter { prop -> !prop.hasAnnotation("io.kandra.core.annotations.Transient") }
        .joinToString("\n    ") { prop ->
            val propName = prop.simpleName.asString()
            val cqlName = resolveCqlName(prop)
            val typeName = resolveTypeName(prop.type.resolve())
            val isLookup = prop.hasAnnotation("io.kandra.core.annotations.LookupIndex")
            if (isLookup) {
                "val $propName = io.kandra.runtime.dsl.KandraColumnRef<$typeName>(\"$cqlName\", isLookup = true)"
            } else {
                "val $propName = io.kandra.runtime.dsl.KandraColumnRef<$typeName>(\"$cqlName\")"
            }
        }
    // ... build file, write it, log it
}
```

### Naming

| Thing | Rule |
|---|---|
| Generated object name | `"${className}Table"` — exact simple class name, `Table` suffix appended. `User` → `UserTable`. No configurability. |
| Generated file name | Same as object name (`UserTable`); KSP appends `.kt`. Lands in the same package as the source entity, under the KSP-generated sources root (typically `build/generated/ksp/<sourceSet>/kotlin/...`). |
| Generated property name | **Identical to the Kotlin property name** — `propName = prop.simpleName.asString()`, no transformation. A property called `userId` produces `val userId = ...` on the table object, not `val user_id`. |
| CQL column name (`cqlName`, the string literal baked into `KandraColumnRef`) | See `resolveCqlName` below — this is the one name that *does* get transformed. |

So the two names you see side by side in generated code are: Kotlin-cased property name (left of `=`) and
CQL-cased column name (the string argument) — e.g. `val displayName = KandraColumnRef<...>("display_name")`.

### `resolveCqlName(prop)` — Kotlin property → CQL column name

```kotlin
private fun resolveCqlName(prop: KSPropertyDeclaration): String {
    val columnAnnotation = prop.annotations.find {
        it.annotationType.resolve().declaration.qualifiedName?.asString() == "io.kandra.core.annotations.Column"
    }
    if (columnAnnotation != null) {
        val nameArg = columnAnnotation.arguments.find { it.name?.asString() == "name" }
        val value = nameArg?.value as? String
        if (!value.isNullOrBlank()) return value
    }
    return camelToSnake(prop.simpleName.asString())
}
```

1. If the property carries `@Column(name = "...")` **and** that string is non-null and non-blank, use it
   verbatim as the CQL column name.
2. Otherwise (no `@Column`, or `@Column(name = "")`/whitespace-only) fall back to
   `camelToSnake(propertyName)`.
3. **Gotcha**: `@Column`'s `name` parameter has no default (`annotation class Column(val name: String)`), so
   you can't omit it — but you *can* pass `@Column(name = "")`. The processor silently treats that the same
   as "no `@Column` at all" and snake-cases the property name instead. This is not an error; it's a silent
   fallback. If you ever see a blank-`@Column` field land at the auto-derived name instead of erroring, this
   is why.

### `camelToSnake(name)` — the auto-derivation

```kotlin
private fun camelToSnake(name: String): String =
    name.replace(Regex("([A-Z])")) { "_${it.value.lowercase()}" }.trimStart('_')
```

Every individual uppercase letter is replaced with `_` + its lowercase form; a leading underscore (from a
property starting with a capital, unusual for `val`s) is trimmed.

- `userId` → `user_id`
- `email` → `email` (no caps, unchanged)
- `createdAt` → `created_at`
- **Gotcha — no acronym handling**: this is a per-character regex, not a word-boundary-aware converter.
  Consecutive capitals each get their own underscore. `userURL` → `user_u_r_l`, not `user_url`. `parseHTML`
  → `parse_h_t_m_l`. If an entity has a property with a run of capitals (acronyms, initialisms), either
  rename the Kotlin property to avoid the run (`userUrl` instead of `userURL`) or pin the CQL name explicitly
  with `@Column(name = "user_url")`.

### Type resolution

```kotlin
private fun resolveTypeName(type: KSType): String {
    val qualifiedName = type.declaration.qualifiedName?.asString() ?: "kotlin.Any"
    if (type.arguments.isEmpty()) return qualifiedName
    val argNames = type.arguments.joinToString(", ") { arg ->
        arg.type?.resolve()?.let { resolveTypeName(it) } ?: "*"
    }
    return "$qualifiedName<$argNames>"
}
```

`resolveTypeName` recurses into generic type arguments, not just the raw declaration — the property's
resolved type's **fully-qualified** name is spliced directly into the generated source as the
`KandraColumnRef<T>` type argument (e.g. `kotlin.String`, `java.util.UUID`, `java.time.Instant`,
`kotlin.Long`, or for a generic property, `kotlin.collections.Set<kotlin.String>`,
`kotlin.collections.Map<kotlin.String, kotlin.String>`). Recursion into `type.arguments` is required —
Kotlin has no raw-type escape hatch (unlike Java), so a generic column like `Set<String>` or
`Map<String, String>` **must** get its type arguments spliced in or the generated file (a raw
`KandraColumnRef<kotlin.collections.Set>` with zero type arguments) fails to compile with "One/Two type
argument(s) expected" — a hard compile error for the entire module, not a narrow edge case, since any
entity with a `List`/`Set`/`Map` column hits it. Two other consequences:

- Because the FQN is used, there's no import-management problem in the generated file — every type
  reference is fully qualified, so the generated object never needs an `import` statement, at the cost of
  verbose generated code.
- If a type declaration can't be resolved (`declaration.qualifiedName` is null — e.g. some synthetic or
  unresolvable type shapes), the processor falls back to the literal string `"kotlin.Any"` for that
  position — i.e. it emits `KandraColumnRef<kotlin.Any>(...)` (or `Set<kotlin.Any>` for an unresolvable
  element type), silently erasing the real type rather than failing the build. This is a silent
  degradation, not a compile error; if a generated column ref is unexpectedly typed `Any`, this is why —
  go check whether the source type resolves cleanly (it usually means a KSP/classpath issue, not something
  wrong with your annotation usage).

### `getAllProperties()` — what's included, and the inheritance gotcha

`properties = classDecl.getAllProperties().toList()` uses KSP's `getAllProperties()`, which returns **every**
member property visible on the class, including those inherited from superclasses/interfaces — not just
properties declared directly in the entity's own primary constructor/body.

- **Gotcha**: any computed/derived property (a `val` with a custom getter, no backing CQL column) that isn't
  explicitly marked `@Transient` still gets a `KandraColumnRef` generated for it, pointing at a CQL column
  that doesn't exist in the table's DDL. There is no automatic detection of "this property has no backing
  field" — the processor doesn't inspect whether a property is computed vs. stored. Always mark computed
  properties `@Transient`.
- **Gotcha**: if an entity extends a base class or implements an interface with properties, those inherited
  properties are pulled into the generated `*Table` object too (unless `@Transient`), and the relative
  ordering of inherited vs. own-declared properties in the emitted file is whatever `getAllProperties()`
  yields — not guaranteed to be "own properties first." If you rely on the generated object's property order
  for something (you generally shouldn't — access is by name), don't.

### What gets *no* special treatment at all

The task of tracing `KandraProcessor.kt` line by line yields an important negative result: **only three
property-level annotations and one class-level annotation are ever read by this processor**:

| Annotation | Read by codegen? | Effect on generated code |
|---|---|---|
| `@ScyllaTable` (class) | Yes | Triggers processing at all; `name`/`gcGraceSeconds` arguments are **not read** — codegen doesn't even look at them. |
| `@Transient` (property) | Yes | Property excluded entirely — no `KandraColumnRef` emitted for it. |
| `@Column` (property) | Yes | Supplies the CQL column-name string (see `resolveCqlName` above). |
| `@LookupIndex` (property) | Yes | Sets `isLookup = true` on the generated `KandraColumnRef` (see below). Its `tableSuffix`/`consistency` arguments are **not read** — codegen doesn't emit anything about the denormalized lookup table itself, only flips the boolean. |
| `@PartitionKey` | **No** | Zero effect on generated code. A partition-key column looks exactly like any other column in the `*Table` object — a plain `KandraColumnRef<T>("cql_name")`. No index metadata, no "this is composite key index N" annotation carried through. |
| `@ClusteringKey` | **No** | Same — no special generated shape, no order/index info carried into the `*Table` object. |
| `@Counter` | **No** | Same — a counter column generates an ordinary `KandraColumnRef`; nothing marks it as a counter. |
| `@CreatedAt` / `@UpdatedAt` | **No** | Same. |
| `@Version` | **No** | Same. |
| `@Sensitive` | **No** | Same — masking is a logging-layer concern elsewhere, invisible to codegen. |
| `@SecondaryIndex` | **No** | Same. |
| `@Ttl`, `@SoftDelete`, `@CacheResult`, `@ReadConsistency`, `@WriteConsistency` (class-level) | **No** | None of these class annotations are referenced anywhere in `KandraProcessor.kt`. They don't affect the generated object's shape or contents at all. |

In other words: **composite partition keys, clustering key order/index, and counter columns get *zero*
codegen-level special casing.** Whatever enforces "every non-key column in a counter table must be
`@Counter`" or assembles composite-partition-key CQL, or reads `@ClusteringKey(order=...)` to build
`ORDER BY` clauses, does so by reflecting on the annotations directly at runtime (in `kandra-core`/
`kandra-runtime`, e.g. schema registration or repository query building) — **not** by consuming anything the
`*Table` object carries, because the `*Table` object carries no such metadata. Every `KandraColumnRef` on a
generated table object is structurally identical (`cqlName: String`, `isLookup: Boolean`) regardless of
whether the source property was a partition key, clustering key, counter, or plain column. The *only* boolean
that varies is `isLookup`, and it varies *only* based on `@LookupIndex` presence.

### `isLookup` — the one piece of real metadata that flows through

`isLookup = prop.hasAnnotation("io.kandra.core.annotations.LookupIndex")` — a property with `@LookupIndex`
(any suffix, any consistency level — both arguments ignored by codegen) gets
`KandraColumnRef<T>("cql_name", isLookup = true)`; everything else defaults `isLookup` to `false` via
`KandraColumnRef`'s constructor default (see `kandra-runtime/src/main/kotlin/io/kandra/runtime/dsl/QueryDsl.kt`:
`class KandraColumnRef<T>(val cqlName: String, val isLookup: Boolean = false)`). This flag is
presumably what lets `kandra-runtime`'s query-building code (`QueryContext`, repository `findAll { }`, etc.)
recognize at runtime that `UserTable.email eq "..."` should route through the denormalized lookup table rather
than requiring `email` to be a partition/clustering key — but that routing logic lives in `kandra-runtime`,
outside this file; codegen's only job is to stamp the boolean.

### Generated file structure

```kotlin
val fileContent = """
// GENERATED BY KANDRA — DO NOT EDIT
package $packageName

object $objectName : io.kandra.runtime.dsl.KandraTable<$className> {
    $columnRefs
}
""".trimIndent()

val file = codeGenerator.createNewFile(
    dependencies = Dependencies(aggregating = false, classDecl.containingFile!!),
    packageName = packageName,
    fileName = objectName
)
file.write(fileContent.toByteArray())
file.close()

logger.info("Kandra codegen: generated $objectName for $className")
```

- The generated object implements the marker interface `io.kandra.runtime.dsl.KandraTable<T>` (defined in
  `kandra-runtime`, `interface KandraTable<T>` — no members, pure type marker), parameterized by the entity
  class itself.
- `Dependencies(aggregating = false, classDecl.containingFile!!)` — **non-aggregating** incremental
  dependency: this generated file is only reprocessed/invalidated when the *specific* source file containing
  the entity changes, not on any change anywhere in the compilation. Standard, correct choice for a
  1-input-file → 1-output-file generator like this.
- `classDecl.containingFile!!` — a non-null assertion. `containingFile` is null for declarations that don't
  come from a source file being compiled in this round (e.g., a class supplied only as a compiled binary
  dependency with no attached source). Since the processor only reaches `processClass` for symbols that
  already passed `it.validate()` from real source, this is unlikely to fire in normal use, but it is a real
  crash path (`NullPointerException`, not a clean KSP error) if `@ScyllaTable` is ever encountered on a
  binary-only declaration. Not a designed error path — this would look like a raw KSP/Gradle build crash, not
  a "Kandra says X is wrong" message.
- One `logger.info(...)` call per generated table, no other logging.

## Every declaration in `KandraProcessor.kt`

| Declaration | Kind | Signature / role |
|---|---|---|
| `KandraProcessor` | `class ... : SymbolProcessor` | Constructor `(codeGenerator: CodeGenerator, logger: KSPLogger)`. Both params `private`. |
| `KandraProcessor.process(resolver: Resolver): List<KSAnnotated>` | `override fun` | Entry point per KSP round; finds `@ScyllaTable` symbols, dispatches valid ones to `processClass`, returns invalid ones for deferral. |
| `KandraProcessor.processClass(classDecl: KSClassDeclaration): Unit` | `private fun` | Builds and writes one `*Table.kt` file for one entity, as traced above. |
| `KandraProcessor.resolveCqlName(prop: KSPropertyDeclaration): String` | `private fun` | `@Column` name override, else `camelToSnake` of the property name. |
| `KandraProcessor.camelToSnake(name: String): String` | `private fun` | Per-character capital-letter → `_lowercase` regex substitution; see acronym gotcha above. |
| `KandraProcessor.hasAnnotation(fqn: String): Boolean` | `private fun`, extension on `KSPropertyDeclaration` | Exact-match check: does any annotation on this property resolve to the given fully-qualified annotation class name. |
| `KandraProcessorProvider` | `class ... : SymbolProcessorProvider` | `create(environment: SymbolProcessorEnvironment): SymbolProcessor` → `KandraProcessor(environment.codeGenerator, environment.logger)`. Ignores `environment.options`. |

That's the entire module: one processor class with four private helpers plus the two entry functions, and
one trivial provider class. No other public API surface in `kandra-codegen`.

## Error conditions — what the processor actually raises

This is the most important negative finding to be explicit about, since it's easy to assume a codegen module
validates its inputs: **`KandraProcessor` never calls `logger.error(...)` or `logger.warn(...)` anywhere.**
The only `KSPLogger` call in the whole file is the single `logger.info(...)` on successful generation. There
is no Kandra-specific compile-time diagnostic for:

- a `@ScyllaTable` class with no `@PartitionKey` at all
- multiple `@PartitionKey(index = N)` properties with colliding or gapped indices
- `@ClusteringKey` present without any `@PartitionKey`
- `@Counter` mixed with non-`@Counter`, non-key columns on the same entity (the `kandra` skill documents this
  as a DDL-time failure — that enforcement, if it exists, is not here)
- `@Column(name = "")` (silently falls back to auto-derived name, as documented above — not an error)
- `@ScyllaTable` applied to a non-class-shaped declaration (object/interface/enum) — silently processed as if
  it were a normal class
- duplicate CQL column names produced by two different Kotlin properties (e.g. one explicit
  `@Column(name = "user_id")` colliding with another property's auto-derived `user_id`) — nothing checks for
  collisions; the generated object would just have two `val`s with different Kotlin names but the same CQL
  string, silently

So in practice there are exactly two failure modes for this module, neither of which is a designed
"Kandra says X" diagnostic:

1. **Silent fallback** — blank `@Column` name, or an unresolvable type (falls back to `Any`). No log, no
   error, no warning; you only notice from the generated output looking wrong.
2. **Deferral, not failure** — a symbol that fails `it.validate()` (KSP-level unresolved reference, typically
   transient during multi-round/multi-processor builds) is simply held back and retried on a later round via
   the `List<KSAnnotated>` return value. If it never resolves, KSP itself (not this processor) will eventually
   report the underlying unresolved-symbol error — this processor contributes no additional diagnostic to
   that failure.
3. **Uncaught exception** — `classDecl.containingFile!!` can NPE for a `@ScyllaTable` class with no attached
   source file, which surfaces as a raw KSP task crash, not a clean message.

If you're debugging "why doesn't my entity's `*Table` look right," the processor will not tell you — you have
to eyeball the generated file (see path below) and cross-check it against the annotations you actually wrote.

Any invariant-checking beyond this (schema/DDL sanity such as "counter tables need `@Counter` on every
non-key column," which the `kandra` skill documents) is out of scope for `kandra-codegen` — it is enforced
elsewhere in `kandra-core`/`kandra-runtime` at schema-registration or DDL-generation time, not at
annotation-processing time. This doc does not claim to trace that logic since it's outside
`KandraProcessor.kt`.

## Full worked example — before and after

Input entity (`com/example/app/User.kt`):

```kotlin
package com.example.app

import io.kandra.core.annotations.*
import java.time.Instant
import java.util.UUID

@ScyllaTable("users", gcGraceSeconds = 864000)
@SoftDelete(ttlSeconds = 2_592_000)
data class User(
    @PartitionKey val userId: UUID,
    @ClusteringKey(order = ClusteringOrder.DESC) val createdAt: Instant,
    @LookupIndex(tableSuffix = "by_email", consistency = LookupConsistency.BATCH)
    val email: String,
    @Column(name = "display_name") val displayName: String,
    @Sensitive val passwordHash: String,
    @Version val version: Long,
    @Transient val sessionToken: String? = null
)
```

Tracing `processClass`:

| Property | `@Transient`? | `isLookup`? | `cqlName` (via `resolveCqlName`) | `typeName` |
|---|---|---|---|---|
| `userId` | no | false | no `@Column` → `camelToSnake("userId")` = `user_id` | `java.util.UUID` |
| `createdAt` | no | false | `camelToSnake("createdAt")` = `created_at` | `java.time.Instant` |
| `email` | no | **true** (`@LookupIndex`) | `camelToSnake("email")` = `email` | `kotlin.String` |
| `displayName` | no | false | explicit `@Column(name = "display_name")` = `display_name` | `kotlin.String` |
| `passwordHash` | no | false (`@Sensitive` not read by codegen) | `camelToSnake("passwordHash")` = `password_hash` | `kotlin.String` |
| `version` | no | false (`@Version` not read by codegen) | `camelToSnake("version")` = `version` | `kotlin.Long` |
| `sessionToken` | **yes** | — excluded entirely — | — | — |

Note also: `@ScyllaTable("users", gcGraceSeconds = 864000)`'s two arguments and the class-level
`@SoftDelete(ttlSeconds = 2_592_000)` are never read by codegen — neither shows up anywhere in the generated
file below.

Generated output, `com/example/app/UserTable.kt` (under the KSP-generated sources root, same package as the
source entity):

```kotlin
// GENERATED BY KANDRA — DO NOT EDIT
package com.example.app

object UserTable : io.kandra.runtime.dsl.KandraTable<User> {
    val userId = io.kandra.runtime.dsl.KandraColumnRef<java.util.UUID>("user_id")
    val createdAt = io.kandra.runtime.dsl.KandraColumnRef<java.time.Instant>("created_at")
    val email = io.kandra.runtime.dsl.KandraColumnRef<kotlin.String>("email", isLookup = true)
    val displayName = io.kandra.runtime.dsl.KandraColumnRef<kotlin.String>("display_name")
    val passwordHash = io.kandra.runtime.dsl.KandraColumnRef<kotlin.String>("password_hash")
    val version = io.kandra.runtime.dsl.KandraColumnRef<kotlin.Long>("version")
}
```

Downstream usage (from `KandraColumnRef`'s actual API in `kandra-runtime`'s `QueryDsl.kt` — `eq`, `gt`, `gte`,
`lt`, `lte`, `isIn`, all `infix`, plus the `unaryPlus()`/`QueryContext` DSL):

```kotlin
repository.findAll {
    UserTable.email eq "alice@example.com"   // routed via the by_email lookup table (isLookup = true)
    limit(10)
}

repository.findAll {
    UserTable.userId eq someUuid              // ordinary partition-key predicate — isLookup = false,
    limit(1)                                     // codegen doesn't know or care it's a partition key
}
```

Both `UserTable.email` and `UserTable.userId` are, at the type level, plain `KandraColumnRef<T>` instances —
the *only* structural difference the generated code encodes between "this backs the primary lookup path via
a denormalized table" and "this is the partition key" is the `isLookup` boolean on `email`. Everything else
about how a predicate against `userId` vs. `email` actually gets executed is runtime logic in
`kandra-runtime`, not something visible in `UserTable.kt` itself.

## Gotchas checklist

- Property names on the generated object match the Kotlin source exactly; only the CQL string argument is
  snake-cased (or taken from `@Column`).
- `camelToSnake` has no acronym awareness — `userURL` → `user_u_r_l`. Rename the property or pin
  `@Column(name = ...)` explicitly.
- `@Column(name = "")` is silently ignored (falls back to auto-derivation) — not an error.
- Computed/derived properties must be marked `@Transient` explicitly; `getAllProperties()` has no way to know
  a `val` lacks a backing CQL column.
- `@PartitionKey`, `@ClusteringKey`, `@Counter`, `@CreatedAt`, `@UpdatedAt`, `@Version`, `@Sensitive`,
  `@SecondaryIndex`, and all class-level annotations except `@ScyllaTable`'s mere presence, are **completely
  invisible to codegen** — they influence runtime behavior elsewhere, not the shape of the generated `*Table`
  object. Don't expect `UserTable.userId` to look any different from `UserTable.displayName`.
- `isLookup` is the one bit of real metadata that flows from annotation to generated code, and it comes
  exclusively from `@LookupIndex`'s presence (its arguments are ignored).
- The processor emits zero compile-time diagnostics of its own for malformed annotation usage — a "wrong"
  entity still compiles to a "wrong-looking but valid" `*Table` object. Cross-check the generated file by
  hand (or add your own KSP-round build failure via other means) if something looks off; this processor won't
  tell you.
- Unresolvable property types silently degrade to `KandraColumnRef<Any>(...)` rather than failing the build.
