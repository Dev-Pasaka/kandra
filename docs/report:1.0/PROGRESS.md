**SESSION CHECKPOINT (2026-07-22, ~14:10): See `report/SUMMARY.md` for the aggregate report-so-far (produced
per file 5's format, honestly marked PARTIAL/NOT COMPLETE). Cluster (3-node primary) and the sample app are
both left running and healthy. 5 Critical/High findings confirmed with live repros. Resume from "Next
immediate action" below, or from `report/SUMMARY.md`'s "What's left for a complete run" section.**

# Kandra 0.4.2 Test Plan Execution — Progress Tracker

Resume point for continuing this work across sessions. Update this file after every meaningful step.
Do not rely on conversation memory to resume — read this file first.

## Scope decisions (confirmed with user 2026-07-22)
- Execute all 7 files of test-plan/, in order, fully.
- Build all three cluster variants: primary 3-node, auth-enabled, two-DC.
- On finding real Kandra bugs: report only, do NOT patch the library (per plan's own rule 3 / file 5.5).

## Environment facts (confirmed)
- JDK 21 available at: `/Users/pasaka/Library/Java/JavaVirtualMachines/jbr-21.0.10/Contents/Home` (JBR, not Temurin, but real OpenJDK 21 — use via JAVA_HOME override, system default is JDK 25 which must NOT be used for Gradle).
- Docker Desktop: 7.75GiB RAM allocated, 10 CPUs. Enough for all 3 cluster variants but not necessarily simultaneously with heavy load — bring up only what's needed per phase, tear down after.
- Network access to Maven Central confirmed working.
- Kandra 0.4.2 CONFIRMED live on Maven Central as of 2026-07-22 (checked https://repo1.maven.org/maven2/ke/co/coinx/kandra/kandra-bom/ — 0.4.2 dir present, published 2026-07-22 11:47).
- Kandra source cloned read-only reference at `/Users/pasaka/reference/kandra` (for skills/docs, NOT a build dependency).
- Skills copied into sample app at `/Users/pasaka/Developer/KandraTestingPlayground/sample-app/.claude/skills/`.
- Toolchain versions confirmed matching plan: kotlin 2.1.21, ksp 2.1.21-2.0.1, gradle 8.11, ktor 2.3.13 (from reference repo's gradle.properties).

## Directory layout
- `/Users/pasaka/Developer/KandraTestingPlayground/test-plan/` — the 7 plan files (input, read-only).
- `/Users/pasaka/Developer/KandraTestingPlayground/sample-app/` — the Ktor app being built (file 2 + capstone file 7).
- `/Users/pasaka/Developer/KandraTestingPlayground/report/` — this progress file + final findings/scoring output.
- `/Users/pasaka/Developer/KandraTestingPlayground/docker-compose.scylla.yml` — primary 3-node cluster (file 1 §1.2.1). Written, not yet confirmed converged.
- Auth-enabled variant compose file: NOT YET WRITTEN.
- Two-DC variant compose file: NOT YET WRITTEN.

## Phase checklist

### Phase 1 — Prerequisites (test-plan file 1)
- [x] Clone Kandra reference repo + copy skills
- [x] Confirm JDK 21 available
- [x] Confirm published artifact 0.4.2 resolves on Maven Central — CONFIRMED TWICE, including with a
      completely clean `--gradle-user-home` and `--refresh-dependencies` (no stale cache). **File 1 §1.3 = PASS.**
- [x] Write primary 3-node docker-compose file
- [ ] Bring up primary cluster, confirm all 3 nodes UN in `nodetool status` — IN PROGRESS, hit two infra snags
      (both environment/Docker-Desktop issues, NOT Kandra findings):
      1. node2 crashed permanently on first boot: "Node 172.19.0.2 has gossip status=UNKNOWN. Try fixing it
         before adding new node to the cluster." — a bootstrap race because node2 and node3 have no
         `depends_on` relationship to each other (both only depend on node1) and tried to join simultaneously.
         Fixed by `docker restart scylla-node2` after node1+node3 settled.
      2. Second node2 restart hit a Docker-Desktop-VM-level limit: `Could not setup Async I/O ... nr_event (1)
         exceeds /proc/sys/fs/aio-max-nr (65536)`. Fixed by raising the VM's aio-max-nr via
         `docker run --rm --privileged alpine sh -c 'echo 1048576 > /proc/sys/fs/aio-max-nr'` (aio-nr is a
         VM-kernel-wide, not per-container, limit — this may need repeating if the Docker Desktop VM restarts).
      **CONFIRMED CONVERGED 2026-07-22 ~13:07** — all 3 nodes UN (172.19.0.2/.3/.4), datacenter1.
      DC name confirmed = `datacenter1` (matches plan's assumption, no need to override).
- [ ] Create keyspace with RF=3 (will let `autoCreateKeyspace=true` + `SimpleStrategy(replicationFactor=3)` in the app's install(Kandra) block do this, per file 2 step 3 — no need to pre-create manually)
- [x] Scratch resolve-check project at `/Users/pasaka/Developer/KandraTestingPlayground/scratch-resolve-check/` — done, gradlew wrapper copied from reference repo.
- [x] Write auth-enabled cluster compose variant at `docker-compose.scylla-auth.yml` (port 9043, PasswordAuthenticator+CassandraAuthorizer) — written, NOT yet brought up (bring up only when running file 3 P4/P5 and file 4 §4.12, to avoid resource contention with primary cluster).
- [x] Write two-DC cluster compose variant at `docker-compose.scylla-twodc.yml` (ports 9044/9045, GossipingPropertyFileSnitch, rackdc configs in `twodc-config/`) — written, NOT yet brought up (bring up only when running file 3 X4 / file 4 multi-DC rows).
- [ ] Scaffold sample-app build.gradle.kts per file 1 §1.4, confirm `./gradlew build` succeeds with bare Application.kt
- [ ] Create low-priv role on auth cluster (for P4/P5, file 4 §4.12) — not started

### NOTE — toolchain discrepancy found (record as a minor finding later, not blocking)
Plan's file 1 §1.1 says Gradle 8.11, and the reference repo's `gradle.properties` has a stale-looking
`gradleVersion=8.11` custom property — but the reference repo's ACTUAL `gradle/wrapper/gradle-wrapper.properties`
points at **Gradle 9.3.0**, and that's the wrapper actually copied and used successfully (JDK 21 + Gradle 9.3.0
built and resolved fine, no JDK-25-parsing-bug class of issue since we used JDK 21 throughout). Using the
wrapper as it actually exists in Kandra's own repo (9.3.0) rather than forcing 8.11, since "use the wrapper,
don't rely on a system-installed Gradle" is the actual rule — the wrapper IS 9.3.0. Note this as a documentation
staleness finding (Medium, Low severity) in the final report: `gradle.properties`'s `gradleVersion=8.11` custom
property does not match the actual wrapper version.

### Phase 2 — Sample app build (test-plan file 2) — NOT STARTED
- [ ] Step 2: entity definitions (User, Wallet, OtpCode, PageViewCounter, AuditLog, Invoice)
- [ ] Step 3: install(Kandra) with every config knob
- [ ] Step 4: routes per table in file 2
- [ ] Step 5: migrations (separate entry point)
- [ ] Step 6 done-checklist

### Phase 3 — Functional coverage matrix (test-plan file 3) — NOT STARTED
- 13 subsections, ~100 rows (C1-C31, S1-S7, R1-R8, U1-U5, D1-D6, F1-F15, K1-K7, P1-P22, I1-I5, M1-M6, T1-T3, J1-J4, X1-X4)
- Results to be recorded in `report/03-functional-coverage-results.md` (mirror the table, fill Result column). NOT YET CREATED.

### Phase 4 — Edge cases (test-plan file 4) — NOT STARTED
- 18 scenarios (4.1-4.18). Results to `report/04-edge-case-results.md`. NOT YET CREATED.

### Phase 5 — Known pitfalls (file 6) — reference only, no separate execution; fold into interpretation of phases 3/4.

### Phase 6 — Capstone (test-plan file 7) — NOT STARTED
- Post/PostLikeCounter/PostByTag entities, 5 flows, acceptance checklist.

### Phase 7 — Final report (test-plan file 5 format) — NOT STARTED
- `report/SUMMARY.md` — aggregate table, findings by severity, confirmed-fixed/regressed lists, top 5 findings.

## Next immediate action when resuming
Check background task `bltulcx2s` (docker compose up for primary cluster) — read its output file, then run
`docker exec -it scylla-node1 nodetool status` and wait for all 3 nodes UN before proceeding to keyspace creation.

## Findings log (running — move formal write-ups to report/ files, but jot anything surprising here immediately so it's not lost)

1. **[Doc gap, Low]** `kandra-core` skill's annotation table omits `@SoftDelete`'s `markerProperty: String = ""`
   parameter entirely (only shows `ttlSeconds`). Confirmed in actual source
   (`Annotations.kt:107`: `annotation class SoftDelete(val ttlSeconds: Int = 86400, val markerProperty: String = "")`).
   Not a library bug — a skill-doc completeness gap. `findActive()` (in `kandra-runtime`, `KandraRepository.kt:134`/
   `KandraSuspendRepository.kt:141`) is ALSO entirely undocumented in the `kandra-runtime` skill despite being a
   real, shipped 0.4.2 feature — confirmed via direct source read instead.

2. **[Finding, needs severity classification later — likely Medium]** `findById(vararg idValues)` /
   `deleteById(vararg keyValues)` both build their WHERE clause from `schema.partitionKeys.zip(idValues)` ONLY
   (`StatementBuilder.kt:206` `selectById`, `StatementBuilder.kt:240` `deleteById`) — clustering-key values are
   **never included** in the WHERE clause, even if you pass extra idValues expecting them to disambiguate a
   clustering row (the extra values are silently dropped by `.zip()`'s shorter-list truncation, no error).
   Consequence for an entity like `User` (partition key `userId`, clustering key `bucketedAt`):
   - `findById(userId)` returns **whatever single row** `rs.one()` happens to return for that whole partition
     (i.e. across all `bucketedAt` values under that `userId`) — not a specific clustered row. With clustering
     order DESC this is likely (not guaranteed by any documented contract) the most-recently-bucketed row.
   - `deleteById(userId)` issues `DELETE FROM users WHERE user_id = ?` with **no clustering-key restriction**,
     which deletes **the entire partition** (every `bucketedAt` row under that `userId`), not one specific row —
     a real surprise if a caller assumes `deleteById` is scoped to one logical entity on a clustering-keyed table.
   Needs to be tested empirically against the real cluster once it's up (create 2+ rows same userId different
   bucketedAt, confirm deleteById wipes both) and written up formally under file 3 read/delete-family rows
   (closest existing rows: F1/D-series) or as a new file-4-style edge case if no existing row ID fits — note
   in the final report either way, this is exactly the kind of thing the matrix's F/D rows don't explicitly ask
   about since they don't test entities with BOTH a partition key and an independent clustering key on the
   read/delete-by-id paths.
   Sample app routes were adjusted to just call `findById(id)`/`deleteById(id)` (single arg) accordingly — see
   `routes/UserRoutes.kt`.

3. **[Build/env finding, Medium-ish, not a Kandra runtime bug]** The sample app's `ksp("ke.co.coinx.kandra:kandra-codegen")`
   dependency (as written verbatim in test-plan file 1 §1.4) does NOT resolve without an explicit version — the
   `ksp` configuration is not covered by the `kandra-bom` platform constraint the way `implementation`/`testImplementation`
   are. Had to pin `ksp("ke.co.coinx.kandra:kandra-codegen:0.4.2")` explicitly. Worth a documentation fix suggestion.

4. **[Build/env finding, Low]** Kandra's own repo's `gradle.properties` has a stale `gradleVersion=8.11` custom
   property that does not match its actual `gradle/wrapper/gradle-wrapper.properties` (which is Gradle 9.3.0).
   For the sample app itself, Gradle 9.3.0 does NOT work with `io.ktor.plugin` 2.3.13 (its bundled legacy Shadow
   plugin calls `Project.convention`, removed in Gradle 9) — had to use Gradle 8.11 for the sample app specifically,
   which happens to match the plan's stated requirement (for a different stated reason: JDK25/Kotlin-compiler
   parsing, not Shadow/Gradle-9 incompatibility). Net effect: plan's "Gradle 8.11" requirement is correct advice,
   just for an additional reason beyond the one documented.

5. **[Infra-only, not a Kandra finding]** ScyllaDB docker-compose bootstrap race: `scylla-node2` and `scylla-node3`
   both only `depends_on: scylla-node1` (no dependency on each other), so they raced to join simultaneously and
   node2 hit `"gossip status=UNKNOWN"` and crash-looped to FATAL. Fixed with `docker restart scylla-node2` once
   node1+node3 had settled. Separately hit a Docker-Desktop-VM `aio-max-nr` (65536) exhaustion on that same
   restart — fixed by raising it via a privileged one-off container. Both are Docker/ScyllaDB-multi-node-bootstrap
   environment issues, not Kandra bugs — noting here so future-session-me doesn't re-diagnose from scratch if it
   recurs (e.g. after a Docker Desktop restart, the aio-max-nr fix will need repeating).

## Sample app build status
- Bare scaffold (file 1 §1.4 done-check): BUILDS SUCCESSFULLY with Gradle 8.11 + JDK 21.
- Entities (file 2 step 2): ALL SIX WRITTEN (User, Wallet, OtpCode, PageViewCounter, AuditLog, Invoice) at
  `sample-app/src/main/kotlin/com/example/kandratest/model/`. Not yet compiled/registered against a live cluster.
- Plugin install (file 2 step 3): WRITTEN at `sample-app/src/main/kotlin/com/example/kandratest/Application.kt`.
  Added `install(Koin) {}` before `configureKandra()` per the kandra-koin call-order requirement (file 2 didn't
  show this explicitly but the `kandra-koin` skill's call-order note, referenced in file 2's prose, requires it).
- Routes (file 2 step 4): UserRoutes.kt WRITTEN (all table rows from file 2's step-4 table for /users/*).
  Wallet routes and PageView routes NOT YET WRITTEN (next step). Response DTOs use String-encoded UUID/Instant/
  BigDecimal (not raw kotlinx.serialization of the entity — UUID/Instant/BigDecimal aren't natively
  @Serializable) — added `kotlin("plugin.serialization")` to build.gradle.kts for this.
- NOT YET COMPILED — next action is to write Wallet/PageView routes, then run `./gradlew build` and fix
  compile errors iteratively (per the plan's own step-by-step discipline).

## MILESTONE: sample app BUILDS SUCCESSFULLY (2026-07-22)
`./gradlew build` is green. All 6 entities + plugin config (every knob) + all UserRoutes/WalletRoutes/PageViewRoutes
compile. Cluster (3-node primary) is up and converged. NOT YET RUN against the cluster — that's the next step.

### Finding #6 — CRITICAL — kandra-codegen cannot handle Set/Map columns at all
`KandraProcessor.kt` (kandra-codegen module, line ~61): `val typeName = prop.type.resolve().declaration.qualifiedName?.asString()`
reads only the raw declaration's qualified name, discarding ALL generic type arguments. For any `Set<T>`/`Map<K,V>`
column, this generates `KandraColumnRef<kotlin.collections.Set>("tags")` / `KandraColumnRef<kotlin.collections.Map>("metadata")`
— **raw generic types, which are not valid Kotlin syntax** (unlike Java, Kotlin has no raw-type escape hatch) — so
the KSP-generated `*Table.kt` file itself is a compile error, which fails the ENTIRE module's compilation (not just
usages of that specific column ref). This affects any codegen-registered entity with a collection column — both
`User` (`tags: Set<String>`, `metadata: Map<String,String>`) and, per file 7, the capstone's `Post` (`tags: Set<String>`)
hit this immediately.
**Severity: Critical** (blocks compilation entirely, not a narrow edge case — every entity in this plan with a
collection column is affected, and Set/Map columns are extremely common in real Cassandra schemas).
**Repro**: register any `@ScyllaTable`-annotated class with a `Set<String>` or `Map<String,String>` property,
run KSP codegen, inspect the generated `<ClassName>Table.kt` — see raw `KandraColumnRef<kotlin.collections.Set>`/
`KandraColumnRef<kotlin.collections.Map>` with zero type arguments where 1 or 2 are required. `./gradlew build`
fails at `:compileKotlin` with "One type argument expected" / "2 type arguments expected".
**Decision (confirmed with user)**: work around it to keep testing the other ~150 rows/scenarios, rather than
halting the whole plan. Implemented as a `patchBrokenCodegenCollectionRefs` Gradle task in `sample-app/build.gradle.kts`
that runs after `kspKotlin` and before `compileKotlin`, textually rewriting the two known-broken generated lines
(`UserTable.kt`'s tags/metadata refs, `PostTable.kt`'s tags ref once the capstone entities are added) to valid
generic types. **This does NOT modify the published Kandra artifact/jar** — it only patches this project's own
generated-sources output, and is clearly documented as a workaround for a Critical bug, not a silent fix. Any
matrix/edge-case row that depends on the generated `*Table.kt` DSL for a collection column should note "tested
via the patched/workaround Table ref, underlying codegen bug is Critical/FAIL regardless of whether the
downstream behavior itself passes" — do not let a passing K1/K2/K3 (append/remove/put) row imply this bug doesn't
exist; it's recorded independently as its own Critical finding (goes in file 3 C29 and/or a new file-4-style
entry, and the capstone write-up, since it directly affects file 7's `Post.tags`).

### Other build-fix notes (own bugs, not Kandra findings, but noted for anyone resuming)
- `install(Koin) { }` needs explicit `io.insert-koin:koin-ktor:3.5.6` + `io.insert-koin:koin-core:3.5.6`
  dependencies in the sample app — `kandra-koin` declares Koin as `implementation` (not `api`), so it is NOT
  transitively exposed to consumers. Test-plan file 1 §1.4's dependency list doesn't mention this — worth a
  documentation-gap note (Low severity) alongside finding #3 (ksp version pinning) in the final report's "doc
  gaps found along the way" section.
- Route functions (`userRoutes`/`walletRoutes`/`pageViewRoutes`) had to be `Route` extensions (called *inside*
  the outer `routing { }` block), not `Application` extensions each opening their own nested `routing { }` — an
  implicit-receiver issue, purely a sample-app authoring mistake on my part, not a Kandra finding.
- `UserTable.status.eq(...)` needs a `UserStatus` enum value, not a raw `String` (the generated `KandraColumnRef`
  is correctly typed `<UserStatus>` for enum columns) — fixed call sites to `UserStatus.valueOf(status)`.
- `findById`/`deleteById` calls simplified to single-arg (partition key only) — see finding #2 above for why.

### File 4 §4.12 bullet 3 — CONFIRMED, PASS (first real test result recorded!)
Starting the app with `SCYLLA_USERNAME`/`SCYLLA_PASSWORD` genuinely unset (not just empty) against the primary
cluster threw exactly as file 4 §4.12 describes:
`KandraAuthException: Environment variable 'SCYLLA_USERNAME' is not set. Set it or configure a different KandraAuthProvider.`
Source-confirmed asymmetric wording (`KandraAuth.kt:39-47`): the username-missing message suggests an alternative
provider ("...or configure a different KandraAuthProvider"), the password-missing message does not (just
"Environment variable 'SCYLLA_PASSWORD' is not set."). **File 3/4 row: file 4 §4.12 bullet 3 = PASS.**
Practical consequence for running the rest of the plan: `KandraAuth.fromEnv()` (the library's own default
provider) throws unconditionally when the var is *unset*, it does NOT fall through to the "blank credentials ->
skip auth" path in that case (confirmed via `CqlSessionBuilder.kt:53-61`: `creds.username.isNotBlank()` gate) —
that path only helps if the provider returns a blank-but-non-null username, e.g. via `SCYLLA_USERNAME=""` (set,
empty) rather than truly unset. Restarted the app with `SCYLLA_USERNAME=""`/`SCYLLA_PASSWORD=""` (empty strings,
not unset) to get past this and let the rest of the plan proceed against the non-auth primary cluster.

### Finding #7 — CRITICAL — KandraCache reflection crashes on every real-Caffeine cache write
`KandraCache.resolveMethod()` (`kandra-runtime/.../cache/KandraCache.kt`) does
`target.javaClass.getMethod(name, *paramTypes)` where `target` is the actual object returned by
`Caffeine.newBuilder()...build()`. Caffeine's real cache implementations are package-private concrete classes
(only the `Cache`/`LocalManualCache` interfaces are public) — `Class.getMethod()` still finds the method (since
it's declared public on an interface), but invoking that `Method` object throws
`java.lang.IllegalAccessException: class io.kandra.runtime.cache.KandraCache cannot access a member of interface
com.github.benmanes.caffeine.cache.LocalManualCache with modifiers "public"` because the method's *resolved
declaring class* (the internal Caffeine impl) isn't itself public, and `resolveMethod` never calls
`method.setAccessible(true)`.
**Reproduced live**: first `POST /users` against the real cluster (User had `@CacheResult` per file 2's spec)
→ unhandled 500, full stack trace confirms `KandraSuspendRepository.save → KandraCache.invalidate → IllegalAccessException`.
**Severity: Critical** — this is not a narrow edge case, it's the *only* code path that ever exists once Caffeine
is actually on the classpath (which file 1 §1.4's own dependency list requires, specifically so `@CacheResult`
is "actually exercised, not silently skipped") — every write to every `@CacheResult` entity crashes,
unconditionally, with no graceful degradation. Strongly suggests this exact combination (real Caffeine + a real
write) was never exercised by Kandra's own test suite before — consistent with the plan's own framing.
**Cannot be worked around from outside the library** (needs `setAccessible(true)` inside Kandra's own reflective
call, which lives in the published jar, not generated/owned code — unlike the codegen bug, there's no textual
generated-source file to patch here).
**Decision (confirmed with user)**: removed `@CacheResult` from `User` (commented out, not deleted, in
`model/User.kt`) to unblock the rest of the plan. Direct consequence: **C25, C26(*), D5, and every row in file 4
§4.7 (cache invalidation gaps) are FAIL/BLOCKED by this bug**, not independently testable with a real cache —
(*) C26 specifically (`@CacheResult` with Caffeine *absent* from the classpath) does NOT hit this bug (no real
Caffeine object is ever constructed in that path) and can still be tested as a standalone side-experiment if
time permits — it exercises a different, unaffected code path (`ClassNotFoundException` branch in `buildCache`).

### Finding #8 — CRITICAL — non-nullable empty Set/Map columns become permanently unreadable
`KandraCodec.decode()` (`kandra-runtime/.../codec/KandraCodec.kt:79-85`) does a blanket
`if (row.isNull(name)) { if (nullable) return null else throw KandraQueryException(...) }` **before** dispatching
to any type-specific decode logic — with no special case for collection types. But CQL/Cassandra itself cannot
represent an empty (non-frozen) `Set`/`Map`/`List` column as anything other than NULL/absent at the storage layer
— inserting `emptySet()`/`emptyMap()` results in no value stored, full stop (this part is standard, well-known
Cassandra behavior, not a Kandra bug). The DataStax driver's own `Row.getSet`/`getMap`/`getList` accessors are
specifically designed around this — they return an **empty collection**, never `null`, when the column is NULL,
precisely so callers don't have to think about this distinction. Kandra's codec never reaches those safe
accessors, because its own `row.isNull(name)` check fires first and throws for any **non-nullable** Kotlin
property — which is exactly how you'd naturally declare a `Set<String> = emptySet()` default (looks 100% safe
from the Kotlin side).
**Reproduced live**: created a `User` via `POST /users` (default `tags`/`metadata`, never touched via
`append`/`put`) → confirmed via `cqlsh` the stored row has `tags = null, metadata = null` (expected/correct CQL
behavior) → `GET /users/{id}` (→ `findById`) → **500**, log shows
`KandraQueryException: Column 'tags' is NULL in Scylla but property 'tags' is non-nullable (kotlin.collections.Set<kotlin.String>). Mark the property nullable or ensure the column always has a value.`
Also reproduced via the direct-repository JUnit harness (`U2` test) hitting the identical exception on plain
`findById` of a freshly-saved, untouched entity.
**Severity: CRITICAL.** This isn't a boundary/edge case — it's the *default*, most natural way to declare an
optional collection property in Kotlin, and it's exactly the pattern the plan's own kitchen-sink `User` entity
uses (`tags: Set<String> = emptySet()`, `metadata: Map<String,String> = emptyMap()`). **Any freshly-created User
that hasn't had at least one `append()`/`put()` call is permanently unreadable via `findById`/`find`/`findAll`/
`findPage`** until/unless a collection element is added. This single bug quietly invalidates a large fraction of
straightforward "create then read" flows for any entity using idiomatic non-nullable collection defaults —
worth flagging as the single highest-impact finding of this run so far.
**Not worked around** — this is a read-path bug that fires the moment you touch a real row saved by ordinary
`save()`, and there's no way to avoid triggering it except making every collection property nullable (a real,
observable behavior change to note, not a workaround to silently apply) or never leaving a collection empty
(impractical for real usage). Recorded as FAIL for C29 (collection round-trip) with a dedicated new edge-case
entry since no existing single row ID in file 3/4 precisely names "empty collection round-trip via findById."

### Finding #9 — CRITICAL/HIGH — key-based operations omit clustering keys from WHERE clause (systemic, ~8 call sites)
Both `StatementBuilder.kt` and `BatchEngine.kt` build the WHERE clause for every "operate on one row by key"
statement from `schema.partitionKeys.joinToString(" AND ") { "${it.cqlName} = ?" }` **only** — `schema.clusteringKeys`
is never included, in any of: `selectById` (→ `findById`), `deleteById`, `appendToCollection` (→ `append`),
`removeFromCollection` (→ `remove`/`put`), `counterUpdate` (→ `increment`/`decrement`), the soft-delete rewrite
UPDATE and marker-column UPDATE (both in `BatchEngine`'s delete path), and both the `@Version`-LWT `update()` and
`updateForce()` UPDATE statements. This is one repeated code pattern, not 8 independent bugs.
**Consequences differ by operation, confirmed live against `User` (partition key `userId`, clustering key `bucketedAt`)**:
- `findById(userId)` — **silent wrong-row risk** (already Finding #2): returns whatever row `rs.one()` picks from
  the whole partition, not a specific clustering row. No exception; just possibly-wrong data.
- `deleteById(userId)` — **silent over-deletion / data loss** (already Finding #2): `DELETE ... WHERE user_id = ?`
  with no clustering restriction deletes **every** row in that partition, not the one row a caller thinks they're
  deleting.
- `append`/`remove`/`put` (via `appendToCollection`/`removeFromCollection`) — **hard crash**, confirmed live:
  `com.datastax.oss.driver.api.core.servererrors.InvalidQueryException: Missing mandatory PRIMARY KEY part bucketed_at`
  — Cassandra itself rejects an `UPDATE` statement missing a required key component (unlike `SELECT`, `UPDATE`
  requires the full primary key). This makes `append`/`remove`/`put` **completely non-functional** on any
  entity with a clustering key.
- `update(old, new)` with `@Version` present — **hard crash**, confirmed live via the direct-repository `U2`
  test's underlying CQL path (same `InvalidQueryException`, wrapped/observed as the concurrent-update test's
  actual first failure before ever reaching the LWT-contention question it was designed to test). `updateForce`
  shares the identical code pattern and would fail identically (not yet independently re-confirmed after fixing
  the test's unrelated collection-decode blocker, but the shared root cause makes this a near-certainty — treat
  as PLAUSIBLE-not-yet-independently-isolated until a clean repro without Finding #8 interference is captured).
- `increment`/`decrement` (`counterUpdate`) — same crash risk, **not yet triggered in this plan's own entities**
  since `PageViewCounter` has no clustering key — but this is a real risk for any counter table that does.
**Severity: Critical for append/remove/put/update/updateForce (hard functional break — these five ordinary,
commonly-used repository methods simply do not work at all on any clustering-keyed entity), High for
findById/deleteById (silent incorrect behavior/data loss, not a crash, so easier to miss in casual testing).**
Given `User`'s clustering key (`bucketedAt`) is literally the plan's own deliberately-chosen kitchen-sink design,
and clustering keys are one of the two or three most fundamental, load-bearing Cassandra modeling patterns
Kandra explicitly exists to support well (per the plan's own README background section), this is a very high-value
finding — it suggests the *entire* single-row key-based repository surface was only ever validated against
partition-key-only entities.
**Not worked around** — no way to avoid this from outside the library for `append`/`remove`/`put`/`update`/
`updateForce`; every route depending on these against `User` will fail. Marked K1/K2/K3/U1/U2/U3 as FAIL
pending final write-up; D4-adjacent `deleteById`-on-clustering-keyed-entity and F1-adjacent `findById`-on-
clustering-keyed-entity marked as a new FAIL/finding each (existing D4/F1 rows in file 3 didn't anticipate
testing against a clustering-keyed entity specifically — this needed the direct-repository harness to surface).

### Finding #10 — CRITICAL — `KandraBatchScope`'s documented `with(repo) { save(entity) }` pattern silently does NOT batch anything
File 7 §7.2's own worked example (copied verbatim into `PostRoutes.kt`'s first draft):
```kotlin
application.kandra.batchBlocking {
    with(postRepo) { save(post) }
    req.tags.forEach { tag -> with(postByTagRepo) { save(PostByTag(...)) } }
}
```
**With suspend repositories** (`KandraSuspendRepository`, matching how every other route in this app is written),
this **does not even compile**: `save` resolves to the repository's own real `suspend fun save(...)` member
(Kotlin's member-function-always-wins-over-extension-function rule — `KandraBatchScope.save()` is a *member
extension* declared inside `KandraBatchScope`, but a same-named, same-signature-compatible member function on
the extension receiver itself always takes priority, unconditionally, regardless of context) — producing
`Suspension functions can only be called within coroutine body` since `batchBlocking`'s lambda isn't a suspend
context. **Switched to blocking `KandraRepository` instances specifically for use inside `batchBlocking`** (a
plausible enough fix to at least compile) — but this is where it gets much worse: **it compiles and returns
200 OK with no error, but does not batch at all.** Confirmed via `debug.logBatches=true` log output for a real
`POST /posts` request with 2 tags:
```
Executing LOGGED BATCH with 2 statements for posts        <- Post's own INSERT + its own @LookupIndex insert
Executing LOGGED BATCH with 1 statements for posts_by_tag  <- tag 1, saved completely independently
Executing LOGGED BATCH with 1 statements for posts_by_tag  <- tag 2, saved completely independently
```
Three **entirely separate** `BatchEngine` executions, not one combined batch across `Post`+`PostByTag`s. This
means `with(postRepoBlocking) { save(post) }` inside `batchBlocking { }` **also** resolved to each repository's
own ordinary `save()` member (silently, no compiler warning — since a blocking repo's own `save()` is just as
valid a non-suspend call as the intended `KandraBatchScope` extension would have been), completely bypassing
`KandraBatchScope`'s intended "collect statements without executing, commit as one batch at scope exit"
mechanism (`BatchEngine.collectSave`, per the `kandra-runtime` skill).
**Severity: CRITICAL, in the "silent correctness/atomicity violation" category** — this is precisely the failure
mode `KandraBatchScope` exists to prevent (per the plan's own file 7 §7.2 framing: "a partial failure between
two unrelated `save()` calls would leave a post visible in the feed but invisible to tag search, or vice versa").
The API silently does not provide the atomicity guarantee its own name and documentation promise, with **no
error, no warning, nothing observable except reading debug logs line-by-line or doing exactly the kind of
node-kill-mid-batch differential test file 7 §7.2 itself suggests** — an easy trap for any real consumer copying
the documented usage pattern in good faith.
**Root cause, precisely**: Kotlin's overload resolution rule that a member function always wins over an
extension function with a matching signature, applied to `KandraBatchScope`'s design of declaring `save`/
`delete` as member-extensions with the *same names* as the real repository methods they're meant to intercept.
There is no normal Kotlin call syntax that reaches the intended extension once the ordinary member exists (which
it always does, since `save`/`delete` are core repository methods) — this isn't a usage mistake being made
here, it looks structurally unreachable via the documented calling convention as: `with(repo) { save(entity) }`,
for **any** repository (blocking or suspend).
**Not worked around** — no way to force the correct resolution from outside the library via normal syntax found
so far (a reflection-based explicit extension-function-value invocation might theoretically work but would be a
deeply unnatural way to use a public API, and wasn't pursued given the plan's "record, don't fix, don't
vendor a workaround that scores the workaround instead of Kandra" rule). Recorded as the capstone's
flow-1/multi-write-atomicity result: **FAIL**, with the honest note that the *individual* `save()` calls each
still work correctly and durably — only the cross-table atomicity guarantee is the part that's fictional here.

## Capstone (file 7) status — see `report/07-capstone-results.md` for full detail
Built and tested against the live cluster. Flow 2 (feed pagination), Flow 3 (tag search), Flow 4 (concurrent
likes), and node-kill/restart resilience all PASS cleanly. Flow 1 (multi-write atomicity) is a **FAIL** — this
is where Finding #10 (KandraBatchScope silently doesn't batch) was discovered. Flow 5 (delete a post) is a
**FAIL** — this is where Finding #9 (clustering-key WHERE-clause omission) concretely breaks a real capstone
flow (Post has both a clustering key and @SoftDelete). Node-kill test at 2-of-3-down also surfaced a
PARTIAL: the failure exception is a raw unwrapped `AllNodesFailedException`/`UnavailableException`, not the
`KandraQueryException` the plan expected, because `UnavailableException` isn't in `RetryConfig`'s default
`retryOn` set.

## Test harness status
Two testing approaches now in use:
1. **HTTP routes** (`sample-app`, running against `localhost:8080`) — per file 2's own intent, exercises the
   full plugin-wired stack (validators, event listeners, etc.).
2. **Direct-repository JUnit harness** (`src/test/kotlin/.../DirectRepositoryIntegrationTest.kt`) — connects
   directly to the same live docker-compose cluster (NOT Testcontainers/NOT a separate cluster) via a
   hand-built `CqlSession`+`StatementBuilder`+`BatchEngine`+`KandraSuspendRepository`, bypassing Ktor/HTTP
   entirely. Much faster to batch many assertions per run. **Known gap**: this harness does NOT have
   `validate<T>`/`validateJakarta<T>` wired (those are plugin/`install(Kandra)`-level hooks, not
   repository-level) — J1/J2 and P19/P20 need the HTTP route instead. Also add `-Test` methods here freely;
   each `@Test` gets its own fresh session (see `newSession()`), so tests are independent aside from shared
   process-global `SchemaRegistry` state (harmless — registrations are idempotent/additive).
Also added: `src/test/kotlin/.../BrokenEntityRegistrationTest.kt` — the file 4 §4.10 negative-registration
suite (all 13 broken entities), run via `./gradlew test --tests "*BrokenEntityRegistrationTest"`, results
extracted from `build/test-results/test/*.xml` `system-out` CDATA (println-based, not assertions — this
harness is for *observing and recording* outcomes, not pass/fail gating a build).

## Progress snapshot (2026-07-22, ~14:00)
Confirmed results so far (see `report/03-functional-coverage-results.md` for full detail): ~25 file-3 rows
PASS, 4 Critical/High findings (codegen Set/Map, cache reflection, empty-collection decode, clustering-key
WHERE-clause omission), file 4 §4.3 (soft-delete timing, full 6-step sequence) all PASS, file 4 §4.5/§4.6
(migration concurrency + checksum fixes) all PASS, file 4 §4.10 (13 broken-entity negative registration
tests) all PASS, file 4 §4.12 bullet 3 (auth env var asymmetric wording) PASS.

Remaining, NOT yet attempted: most of P-series (plugin config P1-P22), I-series (DI resolution I1-I5),
T-series (kandra-test module), J-series beyond the note already made, X2-X4 (consistency resolution/node-kill
differentials), S2-S7 (AUTO_MIGRATE variants/VALIDATE/NONE), C8/C9 precise timing (structure confirmed, exact
atomicity/eventual-delay timing not yet independently measured), C17/C18/C24/C26/C28/C31, most of file 4
(§4.1 suspend concurrency, §4.2 batch chunking, §4.4 no-@Version data-loss — PARTIALLY done via direct
harness already showing all 10 concurrent updates succeed on `AuditLog`, §4.8 codec boundaries beyond the
empty-collection finding, §4.9 DDL naming edge cases, §4.11 node-kill resilience, §4.13 codegen correctness,
§4.14 volume/scale, §4.15 consistency matrix, §4.16 idempotency/retry, §4.17 graceful shutdown, §4.18
validator conflict), the auth-enabled and two-DC cluster variants (written but never brought up), and the
ENTIRE capstone (file 7).

## Next immediate action when resuming
1. Start the app (`export` the SCYLLA_* env vars per file 1 §1.4, `./gradlew run` or run the built jar) against
   the live 3-node cluster (already up, converged) and confirm it starts cleanly — check `KandraMultiDc.describe()`
   log output (8 lines per docs — file 3 X1), confirm `GET /kandra/health` returns 200.
2. Exercise every route in file 2 step 4's table at least once (file 2 done-checklist), capturing CQL logs
   (`debug.logQueries=true` already on) for the functional coverage matrix (file 3) writeups.
3. Start filling in `report/03-functional-coverage-results.md` (create this file, mirror file 3's tables, one
   Result cell per row) as rows get exercised — don't wait until the very end to start recording.
4. Migrations (file 2 step 5) and the DI resolution checks (I1-I5) still need their own small test/entry-point
   code — not yet written.
