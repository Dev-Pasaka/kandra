# ISS-037: Consistency Strict Mode — warn on `LOCAL_ONE`/`ONE` in multi-DC deployments

**Status:** Fixed

## Problem

GitHub #5 asked for an optional "Strict Mode" that warns when a query resolves to `LOCAL_ONE`
consistency in a multi-DC deployment, where `LOCAL_QUORUM` is usually the intended default. The issue
flagged this as needing a design decision because `kandra-runtime`'s consistency resolution
(`StatementBuilder.resolveWriteConsistency`/`resolveReadConsistency`) has no awareness of DC topology —
`kandra-runtime` and `kandra-multidc` are separate modules with no dependency between them, and
`KandraMultiDc.describe()` is purely a startup-logging string builder, not in the runtime read/write
path.

## Fix

No new cross-module dependency was needed. The multi-DC topology signal already exists in core config:
`KandraConfig.loadBalancing.allowedRemoteDcs` (a non-empty list implies a multi-DC deployment — it's
already what a multi-DC deployment sets for failover).

1. Added `var strictMode: Boolean = false` to `ConsistencyConfig` — user-facing, opt-in, set via the
   existing `consistency { }` DSL block in `install(Kandra) { }`.
2. Added `@InternalKandraApi var multiDcTopology: Boolean = false` to `ConsistencyConfig` — **not**
   user-set. The `Kandra` Ktor plugin's install path populates it automatically:
   `config.consistency.multiDcTopology = config.loadBalancing.allowedRemoteDcs.isNotEmpty()`, before the
   `StatementBuilder` is constructed.
3. `StatementBuilder.resolveWriteConsistency`/`resolveReadConsistency` now share a
   `warnIfStrictModeViolation` check, run after resolving the final consistency level (per-call override
   → `@ReadConsistency`/`@WriteConsistency` → `ConsistencyConfig` default): if `strictMode &&
   multiDcTopology` and the resolved level is `LOCAL_ONE` or `ONE`, logs a WARN naming the table and the
   resolved level. Logged unconditionally on every matching call — no "warn once" tracking, matching the
   existing `QueryExecutor.activeMarkerWarning()`/`findActive()` precedent. Never throws — a user turning
   this on cannot break an existing deployment.
4. Documented in `docs/USER_GUIDE.md` (Consistency Levels section) and `docs/features/multidc.md`, plus
   the `kandra-runtime`/`kandra-multidc` skill references.

```kotlin
install(Kandra) {
    consistency { strictMode = true }
    loadBalancing { allowedRemoteDcs = listOf("eu-west") } // already set for multi-DC failover
}
```

## Verification

- Unit tests in `kandra-runtime/src/test/kotlin/io/kandra/runtime/ConsistencyStrictModeTest.kt` (new
  source set) cover: no warning with `strictMode=false` regardless of topology; no warning with
  `strictMode=true` but single-DC; WARN logged for `strictMode=true` + multi-DC + `LOCAL_ONE`/`ONE`; no
  warning for `LOCAL_QUORUM`/`QUORUM`/`EACH_QUORUM`/`ALL`/`LOCAL_SERIAL`/`SERIAL`/`TWO`/`THREE`; and that
  resolution never throws under any strictMode/topology/level combination.
- A real-cluster integration test was added to `kandra-ktor`'s `KandraPluginTest.kt` (Testcontainers, no
  fakes) proving `install(Kandra) { consistency { strictMode = true }; loadBalancing { allowedRemoteDcs =
  listOf("fake-remote-dc") } }` wires correctly and a `save`/`findById` round trip still executes
  normally — the point being the wiring doesn't break real query execution, not that a real multi-DC
  cluster is required (a single-node cluster with a fake remote DC name is enough to exercise the
  config-only topology signal).
- `./gradlew :kandra-runtime:test` — green (8/8 new tests pass, full suite green).
- `./gradlew test` (full suite, real Testcontainers) — green except a pre-existing, unrelated
  `kandra-ktor:KandraPluginTest` failure (`KandraAuthException: Environment variable 'SCYLLA_USERNAME' is
  not set`, and separately a keyspace-name-length `InvalidQueryException` once that env var is worked
  around) that affects all three tests in that class identically, including the two pre-existing tests
  untouched by this change — confirmed by temporarily shortening the test keyspace name, which made all
  four tests in the class (including the new one) pass. This is already fixed separately in an unmerged
  sibling PR (#18, issue #16); this worktree branched before that landed.

**Files:** `kandra-runtime/.../ConsistencyConfig.kt`, `kandra-runtime/.../StatementBuilder.kt`,
`kandra-ktor/.../Kandra.kt`, `kandra-runtime/src/test/kotlin/io/kandra/runtime/ConsistencyStrictModeTest.kt`,
`kandra-ktor/src/test/kotlin/io/kandra/ktor/KandraPluginTest.kt`, `docs/USER_GUIDE.md`,
`docs/features/multidc.md`.
