# ISS-041: `kandra-koin`/`kandra-kodein` qualifiers collided for same-named entities in different packages

**Status:** Fixed

## Category

Enhancement (DI safety).

## Problem

Filed as GH #35. Both `kandra-koin` and `kandra-kodein` bound repositories under a qualifier/tag
derived purely from `entityClass.simpleName`. Two `@ScyllaTable` entities with the same simple class
name in different packages (e.g. `billing.User` and `admin.User`) produced identical qualifiers/tags
for both, causing `DefinitionOverrideException` (Koin) or a Kodein binding-override error at
DI-module-build time — a confusing generic message that didn't point at the real cause (no compiler
or `SchemaRegistry` check caught duplicate simple names across packages).

## Fix

`Application.kandraKoin()` / `Application.kandraKodein()` now run a collision check as the very
first statement, before touching any plugin attributes or building bindings:
`registry.all().groupBy { it.entityClass.simpleName }.filterValues { it.size > 1 }`. If any group has
more than one entry, throws `KandraSchemaException` naming the colliding simple name and every
colliding class's fully-qualified name, plus which qualifier/tag pattern would have collided. This is
additive — no change to the qualifier/tag scheme or binding behavior for non-colliding entities (a
fully-qualified-name qualifier scheme was considered but rejected as a breaking change per the
issue's own analysis).

Added `KandraKoinQualifierCollisionTest` / `KandraKodeinQualifierCollisionTest`, each covering (1) two
same-simple-name entities in different packages triggering `KandraSchemaException` with both
fully-qualified names, and (2) a single non-colliding entity still binding normally.

## Files

- `kandra-koin/src/main/kotlin/io/kandra/koin/KandraKoin.kt`
- `kandra-kodein/src/main/kotlin/io/kandra/kodein/KandraKodein.kt`
- `kandra-koin/src/test/kotlin/io/kandra/koin/KandraKoinQualifierCollisionTest.kt`
- `kandra-kodein/src/test/kotlin/io/kandra/kodein/KandraKodeinQualifierCollisionTest.kt`
