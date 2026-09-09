# ISS-094: Defense-in-depth gaps (silent skip-auth on blank credentials, unguarded existsQuery raw CQL, unvalidated KandraPredicate column)

**Status:** Open

## Problem

Filed as GH #107.

Three defense-in-depth security gaps found during a cross-cutting audit:

**1. Silent skip-auth on blank credentials, no log signal.** In `CqlSessionBuilder.kt`, when `config.auth.provider.getCredentials()` returns a blank username, `builder.withAuthProvider(...)` is simply never called — and nothing logs that the session is being opened without authentication at connect time. The only place this condition is ever surfaced is the credential-rotation warning branch (`Kandra.kt`), which only exists when `auth.refreshIntervalSeconds` is configured (not the default). Against a cluster with `AllowAllAuthenticator`, a misconfigured custom `KandraAuthProvider` (or an env var set to `""` instead of left unset — `KandraAuth.fromEnv()` only throws on `null`, not blank) puts the deployment into permanent no-auth mode with zero startup signal.

**2. `StatementBuilder.existsQuery()` splices raw CQL with no injection guard.** (`kandra-runtime/src/main/kotlin/io/kandra/runtime/StatementBuilder.kt`) `whereCql` is spliced directly into `"SELECT $pkCols FROM ${schema.tableName} WHERE $whereCql LIMIT 1"` with zero identifier/heuristic validation — none of the `checkRawInjectionRisk` machinery #50/ISS-050 added to `raw()`/`rawQuery()` applies here. `StatementBuilder` is a public class with a public constructor (reachable cross-module, e.g. from `kandra-kodein`), so it's not purely internal plumbing. It currently has zero call sites anywhere in the codebase (dead code) — exactly the shape of forgotten-escape-hatch #50 was filed to close everywhere else.

**3. `KandraPredicate.Eq/Gt/Gte/Lt/Lte/In` accept an unvalidated `column: String`, protected only by an `internal` visibility modifier.** (`kandra-runtime/src/main/kotlin/io/kandra/runtime/dsl/QueryDsl.kt`) Unlike `KandraColumnRef` (hardened by #51/ISS-051 with an `init { CqlNaming.isValidIdentifier(...) }` check), `KandraPredicate`'s public constructors do zero validation. The only thing preventing an injected column string from reaching `QueryExecutor.buildWhere()` (which splices `pred.column` unparameterized) is that `QueryContext.predicates` is `internal` to `kandra-runtime` — an access-control accident, not an invariant of the predicate type. Any future public API surfacing `List<KandraPredicate>` silently reopens the hole #51 closed for `KandraColumnRef`.

## Impact

Medium — none is exploitable today given current call-site visibility/reachability, but all three are latent hazards that a future, innocuous-looking API addition (a "raw predicate" escape hatch, exposing `existsQuery` for a legitimate use case) could silently activate.

## Suggested fix

- #1: log a WARN/INFO at connection time whenever `creds.username.isBlank()`, independent of whether rotation is configured.
- #2: delete `existsQuery` (dead code) or route it through `checkRawInjectionRisk`/identifier validation like every other raw-CQL surface.
- #3: add the same `CqlNaming.isValidIdentifier` check to `KandraPredicate`'s `column` at construction, so the invariant doesn't depend on visibility modifiers holding forever.

## Files

`kandra-ktor/src/main/kotlin/io/kandra/ktor/CqlSessionBuilder.kt`, `kandra-runtime/src/main/kotlin/io/kandra/runtime/StatementBuilder.kt`, `kandra-runtime/src/main/kotlin/io/kandra/runtime/dsl/QueryDsl.kt`

Filed from a critical post-fix audit (2026-09-09) ahead of experimental multi-DC cluster testing. Related: #50 (ISS-050), #51 (ISS-051) — same defense-in-depth class.
