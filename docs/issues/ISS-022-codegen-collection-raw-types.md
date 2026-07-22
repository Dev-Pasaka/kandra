# ISS-022: `kandra-codegen` generated invalid Kotlin for any `Set`/`Map` column

**Status:** Fixed, verified live

## Problem

Found during real-cluster test plan execution (`docs/report:1.0/`, Finding #6). `KandraProcessor`'s
type-name resolution (`prop.type.resolve().declaration.qualifiedName?.asString()`) read only the raw
declaration's qualified name, discarding all generic type arguments. For any `Set<T>`/`Map<K,V>`
column this generated `KandraColumnRef<kotlin.collections.Set>`/`KandraColumnRef<kotlin.collections.Map>`
— raw generic types, which don't exist in Kotlin (unlike Java, there's no raw-type escape hatch) — so
the KSP-generated `*Table.kt` file itself failed to compile, taking down the entire consuming module's
build. Affected any `@ScyllaTable` entity with a collection column, not a narrow edge case.

## Fix

`resolveTypeName` now recurses into `KSType.arguments`, rendering nested generics correctly (e.g.
`kotlin.collections.Map<kotlin.String, kotlin.String>`). Falls back to `kotlin.Any` per unresolvable
position instead of failing the build.

**File:** `kandra-codegen/.../KandraProcessor.kt`.

**Verification:** new `IntegrationEvent`/`IntegrationCollections` test entities in `kandra-test` (with
`Set<String>`/`Map<String,String>` columns) compile via real KSP codegen (`kspTest(project(":kandra-codegen"))`
wired into `kandra-test/build.gradle.kts`); generated output inspected directly and confirmed to carry
full type arguments.
