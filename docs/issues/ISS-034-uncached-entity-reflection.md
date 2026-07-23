# ISS-034: Entity reflection re-resolved via uncached Kotlin reflection on every call

**Status:** Fixed

## Problem

Every entity read/write went through uncached Kotlin reflection, resolved fresh on every single
call rather than once per entity type:

- `BatchEngine.injectTimestamps()`/`injectVersion()` — `entity::class.memberFunctions.find { it.name
  == "copy" }`, a linear scan repeated on every `save()`/`update()`.
- `BatchEngine` — `entity::class.memberProperties.associateBy { it.name }` rebuilt on every
  `update()`, `delete()`, `deleteSuspend()`, `collectDelete()`, both versioned-update helpers, and
  `buildUpdateStatements()`.
- `StatementBuilder.insertPrimary()`/`insertPrimaryWithNulls()`/`insertLookup()` — the same
  `entity::class.memberProperties.associateBy { it.name }` pattern on every statement build.
- `KandraRepository`/`KandraSuspendRepository` — the same pattern for primary-key extraction.
- `QueryExecutor.decodeEntity()` — resolved `entityClass.primaryConstructor` and rebuilt the full
  column list on every row decoded, so `findAll()` returning N rows repeated constructor-parameter
  resolution N times.

`SchemaRegistry` already cached `TableSchema` per `KClass` in a `ConcurrentHashMap` at registration
time, but this pattern was never extended to the reflection objects actually used to read/write
entity fields at runtime — `kandra-codegen` (KSP) generates type-safe `*Table` query objects, not
entity read/write accessors, so this reflection overhead couldn't be avoided even with codegen
opted in.

## Fix

Added `EntityReflection` (`kandra-core`, `io.kandra.core.schema`) — a small immutable holder for the
reflection surface needed to read/write an entity at runtime:

- the `copy` `KFunction` and its `KParameter` list,
- a `Map<String, KProperty1<*, *>>` of every member property, keyed by property name,
- the `primaryConstructor` and its `KParameter` list.

`SchemaRegistry.register()` now resolves this exactly once per `KClass` (inside the same
`getOrPut`-backed `buildSchema` call that already builds `TableSchema`) and stores it on a new
`TableSchema.reflection` field, so it inherits `SchemaRegistry`'s existing thread-safety guarantee —
entities are registered once at startup and the cached reflection is read many times concurrently
after.

`BatchEngine`, `StatementBuilder`, `QueryExecutor`, `KandraRepository`, and
`KandraSuspendRepository` were updated to read `schema.reflection.*` instead of re-resolving via
`entity::class`/`entityClass` on every call. `StatementBuilder.insertLookup()` gained a leading
`schema: TableSchema` parameter (it previously had no way to reach the cached reflection, since
`LookupTableSchema` doesn't carry a reference to the parent entity's schema) — an internal-only
signature change, since `StatementBuilder` is gated behind `@InternalKandraApi`. This is a pure
performance change: no public repository API changed, and save/update/delete/decode semantics are
identical to the old uncached path.

**Files:** `kandra-core/.../schema/EntityReflection.kt`, `kandra-core/.../schema/SchemaModel.kt`,
`kandra-core/.../SchemaRegistry.kt`, `kandra-runtime/.../BatchEngine.kt`,
`kandra-runtime/.../StatementBuilder.kt`, `kandra-runtime/.../QueryExecutor.kt`,
`kandra-runtime/.../repository/KandraRepository.kt`,
`kandra-runtime/.../repository/KandraSuspendRepository.kt`.
