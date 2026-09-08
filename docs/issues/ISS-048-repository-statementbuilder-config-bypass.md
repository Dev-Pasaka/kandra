# ISS-048: Repositories silently discarded the plugin's configured `StatementBuilder` and `codec`

**Status:** Fixed

## Problem

`kandra-ktor`'s plugin install builds exactly one correctly-configured `StatementBuilder` — wired up
with the installer's `codec`, `debug`, `consistency`, and `preparedStatementCacheSize` — and hands it
to `BatchEngine`:

```kotlin
// Kandra.kt:189-203
val statementBuilder = StatementBuilder(
    session = session,
    codec = config.codec,
    debugConfig = config.debug,
    consistencyConfig = config.consistency,
    cacheSize = config.preparedStatementCacheSize
)
val batchEngine = BatchEngine(
    session = session,
    statementBuilder = statementBuilder,
    ...
)
```

But `KandraRepository` and `KandraSuspendRepository` never received it. Both built their own,
all-defaults `StatementBuilder` in a field initializer instead:

```kotlin
// KandraRepository.kt:30 (and KandraSuspendRepository.kt:34, identically)
private val statementBuilder = StatementBuilder(session)
private val executor = QueryExecutor(session, schema, statementBuilder)
```

`QueryExecutor`'s own constructor independently defaults two more fields the same way —
`codec: KandraCodec = KandraCodec.default` and `debugConfig: DebugConfig = DebugConfig()` — and the
3-arg call above doesn't supply either, so this wasn't only a `StatementBuilder` problem.

Every repository-construction path hit this, since none of them ever passed a `StatementBuilder`
through — `KandraRuntime.repository()`/`.suspendRepository()`, `kandra-koin`'s `kandraKoin()`,
`kandra-kodein`'s `kandraKodein()` and `bindKandraRepository`, and `kandra-test`'s `KandraRuntime`
all construct `KandraRepository`/`KandraSuspendRepository` with only `(session, schema, entityClass,
batchEngine)` — the configured builder living on `batchEngine` was reachable in every one of these
call sites, just never read.

## Impact

Every one of these was silently wrong on the **read** path and on the repository-level
counter/collection helpers (`append`/`remove`/`put`/`increment`/`decrement`, and `deleteById`'s
"not found" branch) — all of which call `statementBuilder.*` directly from the repository classes,
not through `BatchEngine`:

- **`consistency { defaultRead = ... }` was never applied to reads.** Every `findById`/`find`/
  `findAll`/`findPage`/`findActive` call resolved consistency from a *second*, unconfigured
  `ConsistencyConfig()` (default `LOCAL_ONE`) instead of the plugin's configured one. Writes were
  unaffected — they go through `BatchEngine`'s own correctly-configured builder.
- **Consistency Strict Mode (`ISS-037`/GH #5) never fired for reads.** `strictMode` and
  `multiDcTopology` live on the discarded `ConsistencyConfig`, so the WARN this feature exists to
  produce could never trigger on the read path it's meant to also cover.
- **A custom `codec` was ignored for decoding every row a repository read back** —
  `QueryExecutor.decodeEntity()` used `KandraCodec.default`, not `config.codec`, regardless of what
  was installed.
- **`debug.logQueries` never logged repository reads or the repository-level counter/collection
  writes** — `StatementBuilder.prepare()` checks `debugConfig.logQueries` before every log line, and
  the repository's copy was always a fresh, non-debug `DebugConfig()`.
- **`preparedStatementCacheSize` was ignored, and every repository instance got its own separate
  1000-entry LRU cache** instead of sharing the one `BatchEngine` already uses — splitting what
  should be one cache per CQL string into N+1 caches (one per repository, plus `BatchEngine`'s),
  each independently warning on eviction at the hardcoded default size.

## Fix

`BatchEngine` already held the correctly-configured `StatementBuilder`, `codec`, and `debugConfig` as
constructor properties — they were just `private`. Widened all three to `internal`, and had
`KandraRepository`/`KandraSuspendRepository` read them off the `batchEngine` they already receive,
instead of constructing their own:

```kotlin
private val statementBuilder = batchEngine.statementBuilder
private val executor = QueryExecutor(session, schema, statementBuilder, batchEngine.codec, batchEngine.debugConfig)
```

No constructor signature changed on `KandraRepository`, `KandraSuspendRepository`, or any DI-binding
function — every call site already passed `batchEngine`, so this is a pure internal fix. `internal`
(not a public getter) keeps `StatementBuilder`/`KandraCodec`/`DebugConfig` out of the public API
surface, consistent with `StatementBuilder` remaining `@InternalKandraApi`; `kandra-koin`,
`kandra-kodein`, and `kandra-ktor` compile unchanged since none of them construct `StatementBuilder`
themselves for the repository path.

`QueryExecutor`'s own `debugConfig` parameter remains unused within the class after this fix (it was
already dead before this change — `QueryExecutor.kt` never reads it) — passing the real one through
anyway costs nothing and avoids leaving a second stale default in place for whenever that parameter
does get used.

**Files:** `kandra-runtime/.../BatchEngine.kt`, `kandra-runtime/.../repository/KandraRepository.kt`,
`kandra-runtime/.../repository/KandraSuspendRepository.kt`.
