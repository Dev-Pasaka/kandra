# 6. Known Pitfalls and What to Watch For

A consolidated watch-list, pulled from source-level inspection (`.claude/skills/kandra-*/SKILL.md`)
and `docs/issues/`. Nothing here needs its own test run — most of it is either already a specific row
in file 3/4, or a thing to keep in mind while interpreting *other* results so you don't misattribute
a config no-op to "Kandra is broken" or, worse, misattribute a real bug to "that's just a known
limitation." Skim this before you start, then come back to it whenever a result looks surprising.

## 6.1 Config fields that are declared but currently do nothing

Setting these changes nothing in `CqlSessionBuilder`/`BatchEngine` today, per direct source
inspection — don't spend time debugging "why didn't this config take effect," and don't score a test
row as FAIL just because one of these had no observable impact (confirm the *absence* of effect
matches this list first):

- `PoolConfig.localRequestsPerConnection`
- `SslConfig.requireEncryption`, `SslConfig.minimumTlsVersion`, `SslConfig.cipherSuites`
- `LoadBalancingConfig.tokenAware`, `LoadBalancingConfig.maxRemoteNodesPerRemoteDc`
- `FailoverConfig.remoteRetryDelayMs`
- `ConsistencyConfig.defaultSerialConsistency` (declared, but `saveIfNotExists` takes
  `serialConsistency` as a direct method parameter instead of reading this config field)

If you're testing one of these specifically (file 3 rows P7/P9), the correct PASS outcome is
**confirming it has no effect** — a surprising observable effect from one of these would itself be a
finding (something changed since these skill docs were written, worth reporting either way).

## 6.2 Silent no-ops (log a WARN or nothing at all, never throw)

- `@CacheResult` without Caffeine on the classpath — cache silently disabled, one WARN at first use.
- `metrics.enabled = true` with no `recorder` set — WARN at install, metrics just aren't recorded.
- `@Column(name = "")` (blank string, not omitted) — silently falls back to auto-derived snake_case
  name, in both `SchemaRegistry` and `kandra-codegen` (confirm both layers actually agree — see file 4 §4.9).
- `validatePermissions` when `system.local.role` comes back null (common on ScyllaDB) — logs
  informational, returns early, **no error** even if permissions are actually wrong.
- `validatePermissions` missing `ALTER` specifically (as opposed to `SELECT`/`MODIFY`) — WARN only.
- Any exception during `validatePermissions`'s own query (e.g. `system_auth` inaccessible) — swallowed
  at DEBUG, startup proceeds as if nothing happened.

## 6.3 Things that bypass expected safety nets

These five repository methods **never go through `BatchEngine`** — no retry-on-transient-failure, no
shutdown rejection, no metrics recording, no cache invalidation, regardless of which repository
(blocking or suspend) you call them on:

- `append()`, `remove()`, `put()`, `increment()`, `decrement()`

Additionally, even though they're not gone through `BatchEngine`, in `KandraSuspendRepository` these
five are declared `suspend fun` and correctly call `session.executeSuspend(...)` as of 0.4.2 (verify
this is still true — it was fixed alongside the collection/counter consistency-parameter change; see
file 3 K7 for the retry-bypass consequence specifically, and confirm in file 3/table K-series that
the suspend path is genuinely non-blocking here too, not just that it happens to call the right method name).

Also bypassing cache invalidation specifically (even though these two *do* go through `BatchEngine`,
so retry/shutdown-rejection *do* apply to them):

- `deleteBy()` — routes through `BatchEngine` (soft-delete and retry work), but does not call
  `cache.invalidate()`.

`deleteById()` **used to** be in this bypass list too (no soft-delete respect on the blocking
repository, no cache invalidation on either repository) — that was fixed in 0.4.2
([ISS-015](../issues/ISS-015-delete-by-id-bypasses-soft-delete.md)). Confirm the fix actually holds
(file 3 D4/D5) rather than assuming it from the changelog alone.

## 6.4 `updateForce` and lookup-table staleness

`updateForce(entity)` diffs a pre-timestamp-injection copy of `entity` against the post-timestamp
copy to decide which lookup rows need updating — since both copies have the *same* lookup-indexed
field value (only `@CreatedAt`/`@UpdatedAt` differ between them), the "did the lookup column change"
check can never fire. If `updateForce` is used on an entity whose `@LookupIndex` field actually
changed since it was loaded, **the stale old lookup row is never cleaned up** — only two-argument
`update(old, new)` (which diffs against a caller-supplied, genuinely-different `old`) does this
correctly. This is a real, documented, unfixed gap — don't be surprised by it (file 3 U5), and don't
report it as a *new* finding unless it behaves differently from this description.

## 6.5 DI lookup-convention traps

Different naming schemes between the two DI integrations — mixing them up is the single most common
transcription error when switching between the two:

| | Kodein | Koin |
|---|---|---|
| Blocking repo tag/qualifier | `"${EntityName}"` | `named("${EntityName}Repo")` |
| Suspend repo tag/qualifier | `"${EntityName}Suspend"` | `named("${EntityName}SuspendRepo")` |

`"UserSuspend"` (Kodein) vs. `"UserSuspendRepo"` (Koin) — not interchangeable, not
`"SuspendUserRepo"`, not `"UserRepository"`. If a DI resolution fails with "no binding found," check
this table before assuming Kandra's DI wiring is broken.

Both `kandraKodein()`/`kandraKoin()` bind against **star-projected** types
(`KandraRepository<*>`/`KandraSuspendRepository<*>` for Kodein, similarly type-erased for Koin) — an
unchecked cast to the concrete `KandraRepository<User>` is required after resolution either way. This
is expected, not a bug.

## 6.6 Exception types that might surprise you

- A stale/renamed enum value stored in a column raises `IllegalArgumentException` on decode — **not**
  a `KandraQueryException` or any other Kandra type. `catch (e: KandraException)` will **not** catch
  this.
- Composite-partition-key `IN` queries throw `KandraSchemaException` — not `KandraQueryException`,
  even though it surfaces from the same `findAll { }` call path that throws `KandraQueryException` for
  the "IN on non-indexed column" case. Catch the right type if you're branching on it.
- `KandraSchemaException` and `KandraOptimisticLockException` have **no `cause` parameter** — if
  either is constructed by wrapping some lower-level exception, that lower-level exception's stack
  trace is not chained; only its message (if the constructing code bothered to fold it in) survives.
- A failing migration `up()` throws whatever raw exception the CQL driver (or your own code) raised —
  it is **not** wrapped into `KandraMigrationException`. Only the checksum-mismatch branch of
  `KandraMigrationRunner.run()` throws that specific type.
- `raw()`'s injection-risk WARN is a narrow heuristic (checks for a literal `'` or the substring
  `"="` in the CQL string with no bind params) — its *absence* is not proof a query is safe from
  injection; don't treat "no warning" as a security guarantee.

## 6.7 Quick reference — what changed in 0.4.1/0.4.2 (don't confuse old bugs with current state)

If you find prior documentation, old blog posts, or cached LLM knowledge describing Kandra, cross-check
against this list before trusting it — several of these were true before 0.4.2 and are specifically
**not** true anymore (or are newly true, in the case of the last two rows):

| Area | Before 0.4.2 | As of 0.4.2 (verify, don't assume) |
|---|---|---|
| `findActive()` | Did not exist at all | Exists, requires `@SoftDelete(markerProperty = "...")` |
| Suspend reads (`findById`/`findAll`/`findPage`/`exists`/`raw`/`rawQuery`) | Blocking under the hood despite `suspend fun` | True async via `executeAsync().await()` |
| `KandraRepository.deleteById` (blocking) | Always hard-deleted, ignoring `@SoftDelete` | Respects `@SoftDelete` |
| `deleteById` (both repos) | Never invalidated cache | Invalidates cache |
| Migration checksum | Hashed only `version:name:qualifiedClassName` | Also hashes the migration class's compiled bytecode |
| Migration concurrency | No locking — two runners could double-apply | Claims each version via LWT before running it |
| `appendToCollection`/`removeFromCollection`/`counterUpdate` | No consistency-level override possible | Optional `consistency` parameter, resolved same as writes |
| `IN`-on-non-indexed-column error message | Referenced a nonexistent `allowFiltering()` method | Reworded to correctly say ALLOW FILTERING isn't supported |
| Jakarta Bean Validation | Not integrated at all | New `kandra-jakarta` module, `validateJakarta<T>()` |
| Real-cluster integration tests | None existed | `kandra-test`/`kandra-ktor` now have a Testcontainers-based suite (still narrow — this whole test plan exists to widen it) |

Everything in this table is a claim from a source diff, not yet independently verified against a
real cluster by anyone — that verification is precisely what files 3 and 4 of this plan are for.
