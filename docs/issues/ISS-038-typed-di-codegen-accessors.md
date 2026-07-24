# ISS-038: `kandra-koin`/`kandra-kodein` qualifiers were hand-typed strings with no compile-time safety

**Status:** Fixed

## Problem

Filed as [GH-17](https://github.com/Dev-Pasaka/kandra/issues/17) after reviewing `kandra-koin`'s
`Application.kandraKoin()` and `kandra-kodein`'s `Application.kandraKodein()`. Both auto-bind a
`KandraRepository`/`KandraSuspendRepository` pair per entity registered in `SchemaRegistry`, disambiguated by
a hand-typed string qualifier/tag built from `entityClass.simpleName` — Koin/Kodein can't distinguish
`KandraRepository<User>` from `KandraRepository<Wallet>` at runtime due to JVM type erasure, so a qualifier is
unavoidable. Callers had to hand-type the matching string at every injection site and then apply an unchecked
cast:

```kotlin
val userRepo: KandraSuspendRepository<*> by inject(named("UserSuspendRepo"))
@Suppress("UNCHECKED_CAST")
val typed = userRepo as KandraSuspendRepository<User>
```

This had zero compile-time safety: a typo (`"UserSupendRepo"`) compiled fine and only threw
`NoDefinitionFoundException`/`DI.NotFoundException` at first injection; renaming an entity class didn't
propagate to the hand-typed qualifier string anywhere, with nothing to flag the mismatch; and there was no
IDE autocomplete for what qualifiers existed short of reading `KandraKoin.kt`/`KandraKodein.kt` directly.

## Fix

Extended `kandra-codegen`'s existing KSP processor (`KandraProcessor`) to conditionally emit typed DI
accessor functions per `@ScyllaTable` entity, alongside the `*Table` object it already generates:

- **Detection, not a dependency.** `kandra-codegen` still takes no compile dependency on `koin-core` or
  `kodein-di`. Presence is probed once per KSP round via
  `resolver.getClassDeclarationByName("org.koin.core.component.KoinComponent")` /
  `"org.kodein.di.DIAware"` — a `null` result (the common case: most consumers, including this repo's own
  module graph, have neither on the classpath) silently skips generation for that framework. No error, no
  warning.
- **Generated shape**, for an entity `Foo`: `FooKoinDi.kt` (if Koin detected) with
  `fun KoinComponent.fooRepo(): KandraRepository<Foo>` / `fun KoinComponent.fooSuspendRepo(): KandraSuspendRepository<Foo>`,
  wrapping `get<KandraRepository<*>>(named("FooRepo")) as KandraRepository<Foo>`; and `FooKodeinDi.kt` (if
  Kodein detected) with `fun DIAware.fooRepo(): KandraRepository<Foo>` /
  `fun DIAware.fooSuspendRepo(): KandraSuspendRepository<Foo>`, wrapping
  `direct.instance<KandraRepository<*>>(tag = "Foo") as KandraRepository<Foo>`. Every referenced Kandra type
  is spliced in as a raw fully-qualified string, exactly like the existing `*Table` generation — only the
  *consuming* module needs `koin-core`/`kodein-di` as a real dependency.
- **Corrected two API details the originating issue's pseudocode got wrong**: Koin's `KoinComponent.get<T>()`
  is `inline fun <reified T>`, and an `as` cast doesn't propagate an expected type backward into the call it's
  cast from — so the generated code supplies the reified type argument explicitly rather than relying on
  inference. Kodein's `DIAware.instance<T>(tag)` returns a `LazyDelegate<T>` for `by` property delegation, not
  a directly usable `T` — the generated code goes through `DIAware.direct.instance<T>(tag)` (a `DirectDI`
  read) instead, which returns the value immediately.
- The generated qualifier/tag strings match `KandraKoin.kt`/`KandraKodein.kt`'s existing convention exactly
  (`"${Entity}Repo"`/`"${Entity}SuspendRepo"` for Koin, `"${Entity}"`/`"${Entity}Suspend"` for Kodein), since
  both sides are now derived from the same entity name independently rather than one being hand-typed.

Verified with a KSP compile-testing suite (`kotlin-compile-testing`'s KSP2 mode) covering all four classpath
combinations — Koin only, Kodein only, neither, both — asserting both correct generated content and that the
generated code actually compiles against the real Koin/Kodein artifacts. Additionally verified end-to-end
against a real ScyllaDB/Cassandra Testcontainers cluster: `kandra-koin` and `kandra-kodein` each now run
`kandra-codegen` over their own test sources (since both already depend on `koin-core`/`kodein-di` for
production code, the accessors are generated automatically), and a real Ktor application wires up
`install(Kandra)` + `kandraKoin()`/`kandraKodein()`, then calls the *generated* `fooSuspendRepo()` accessor to
`save()`/`findById()` a real row.

**Files:** `kandra-codegen/src/main/kotlin/io/kandra/codegen/KandraProcessor.kt`,
`kandra-codegen/src/test/kotlin/io/kandra/codegen/KandraProcessorDiAccessorsTest.kt`,
`kandra-koin/src/test/kotlin/io/kandra/koin/KandraKoinDiAccessorsTest.kt`,
`kandra-kodein/src/test/kotlin/io/kandra/kodein/KandraKodeinDiAccessorsTest.kt`.
