# DI Integrations

- **`kandra-koin`** — `Application.kandraKoin()` binds one `KandraRepository`/`KandraSuspendRepository`
  pair per entity in `SchemaRegistry`, under named qualifiers `named("${Entity}Repo")` /
  `named("${Entity}SuspendRepo")`. Must run after both `install(Koin)` and `install(Kandra)`.
- **`kandra-kodein`** — `Application.kandraKodein()` does the same for Kodein, tagged `"${Entity}"` /
  `"${Entity}Suspend"`. Also ships `DI.MainBuilder.bindKandraRepository<T>(...)`, a reified,
  untagged single-entity binder for use outside the Ktor plugin entirely.
- Both frameworks bind by **star-projected type** (`KandraRepository<*>`, `KandraSuspendRepository<*>`) —
  neither Koin nor Kodein can recover the entity type parameter at runtime (JVM type erasure), so every
  hand-written lookup needs an unchecked cast to the concrete `KandraRepository<Foo>`.

## Typed accessors (since 0.4.7)

`kandra-codegen`'s KSP processor conditionally generates typed accessor functions per `@ScyllaTable` entity
— `FooKoinDi.kt` if `koin-core` resolves on the compiling module's classpath, `FooKodeinDi.kt` if `kodein-di`
does (neither is a compile dependency of `kandra-codegen` itself; presence is probed via
`Resolver.getClassDeclarationByName`, and absence is silent — not an error). See the `kandra-codegen` skill's
"Typed Koin/Kodein DI accessors" section for the exact detection/generation mechanics.

```kotlin
// generated, wraps the exact named()/tag lookup kandraKoin()/kandraKodein() bind under
fun KoinComponent.fooRepo(): KandraRepository<Foo>
fun KoinComponent.fooSuspendRepo(): KandraSuspendRepository<Foo>

fun DIAware.fooRepo(): KandraRepository<Foo>
fun DIAware.fooSuspendRepo(): KandraSuspendRepository<Foo>
```

Prefer these over hand-typed `named("FooRepo")` / `tag = "Foo"` lookups wherever `kandra-codegen` is on the
build for that module — a typo or an entity rename now fails to *compile* instead of throwing
`NoDefinitionFoundException`/`DI.NotFoundException` at first resolution, and no unchecked cast is needed at
the call site (the generated function does it once, internally). See
[ISS-038](../issues/ISS-038-typed-di-codegen-accessors.md).
