# ISS-099: `kandra-codegen` emits an invalid empty package line for entities in the default package

**Status:** Fixed

## Problem

Filed as GH #156, found while verifying the kandraorm.com quickstart against the published 0.4.7
artifacts.

If an entity is declared in the default package (no `package` line — common in quick experiments
and copy-pasted snippets), `KandraProcessor` generated a file that started with an empty `package`
declaration, and compilation failed:

```
e: UserTable.kt:2:8 Syntax error: Package name must be a '.'-separated identifier list placed on a single line.
```

## Fix

Added a `packageLine(packageName: String)` helper (`KandraProcessor.kt`) returning `"package
$packageName\n"` when non-empty, or `""` when empty, used at all 3 sites that splice a `package`
line into a generated file template (the `*Table` object, and the Koin/Kodein accessor files)
instead of interpolating `package $packageName` unconditionally.

**Files:** `kandra-codegen/.../KandraProcessor.kt`.

**Tests:** `KandraProcessorDefaultPackageTest` — compiles a real entity with no package line through
the actual KSP processor (same real-compilation harness as `KandraProcessorTableContentTest`, no
mocking of KSP internals) and asserts the compilation succeeds, the generated file has no bare
`package` line, and the expected `KandraColumnRef` content is still present.

See GH #156, [PR #159](https://github.com/Dev-Pasaka/kandra/pull/159).
