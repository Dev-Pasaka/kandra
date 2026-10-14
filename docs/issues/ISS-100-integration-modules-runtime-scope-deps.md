# ISS-100: Integration modules publish `kandra-core`/`kandra-runtime` at runtime scope, so consumers can't compile

**Status:** Fixed

## Problem

Filed as GH #152, found while verifying the kandraorm.com quickstart against the published 0.4.7
artifacts.

Every integration module declared its Kandra dependencies with `implementation(project(...))`, so
the published POMs listed them at `<scope>runtime</scope>`. A consumer depending on, say,
`kandra-ktor` alone couldn't compile against `io.kandra.core.annotations.*`, `KandraAuth`, or
`io.kandra.runtime.repository.*` — exactly the types every Kandra app uses — without adding
`kandra-core`/`kandra-runtime` explicitly. The kandraorm.com quickstart had to work around this by
listing both dependencies directly, which the docs shouldn't need to do.

## Fix

Changed the affected modules' Gradle dependencies from `implementation` to `api` wherever that
module's own public API exposes another module's types. `kandra-multidc` already had this right —
used as the template:

- **kandra-runtime**: `kandra-core` → `api` (`TableSchema`, repository constructor params, etc.)
- **kandra-ktor**: `kandra-core`, `kandra-runtime`, `ktor-server-core` → `api`
  (`install(Kandra)`/`Application.kandra` are Ktor types)
- **kandra-koin**, **kandra-kodein**: `kandra-core`, `kandra-runtime`, `kandra-ktor` → `api`
  (bound repositories and `kandraKoin()`/`kandraKodein()` expose all three)
- **kandra-test**: `kandra-core`, `kandra-runtime` → `api`
  (`KandraRuntimeHandle`/`KandraTestUtils`/`FakeKandraSession` hand back both directly)
- **kandra-migrate**: `kandra-core` → `api` (`KandraMigrationException`/`KandraSchemaException`);
  `datastax-driver` → `api` too (`KandraMigrationRunner`'s constructor takes `CqlSession` directly)
- **kandra-jakarta**: `kandra-core`, `kandra-ktor` → `api` (`JakartaKandraValidator` implements
  `KandraValidator`; `validateJakarta()` extends `KandraConfig`)

**Files:** `build.gradle.kts` in each of the modules listed above.

**Verification:** `./gradlew :kandra-ktor:generatePomFileForMavenPublication` — the generated POM
now lists `kandra-core`/`kandra-runtime` at `<scope>compile</scope>`, not `runtime`.

See GH #152, [PR #157](https://github.com/Dev-Pasaka/kandra/pull/157).
