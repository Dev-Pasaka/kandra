# Documentation review — 2026-10-20 (multi-keyspace branch)

**Scope:** `README.md`, `docs/**` (User Guide, features, changelog, issues, test-plan, reviews, site), KDoc touched by the
multi-keyspace work, and `.claude/skills/*/SKILL.md`, checked against the code on `feature/multi-keyspace`.
**Rule:** the code is the source of truth; docs, markdown and KDoc only were changed. No version was bumped.

Categories: **I** inconsistent (new doc vs code or other doc), **O** outdated, **W** wrong, **M** missing.

## Security scan

No password, credential, token, CA material or private cluster hostname exists in tracked files or in the history of
added files (`.env`, `.pem`, `.jks`, `.crt` never added; `.gitignore` covers `.kandra-cloud*.env` / `.pem`).
One **private local path** was found and removed: `docs/features/multi-keyspace-plan.md` and
`docs/test-plan/multi-keyspace-baseline.md` pointed to `/Users/pasaka/Developer/Kandra/.kandra-cloud.env`. The only
cloud facts that remain are the DC name `AWS_US_EAST_1` (also the code default) and ports 9042/9142.
Local developer paths remain in historical `docs/report:1.0/PROGRESS.md` and `docs/test-plan:1.1/README.md`
(not secrets; see decisions).

## Findings

| # | Cat | File | Location | What was wrong | Fix |
|---|---|---|---|---|---|
| 1 | W | `docs/features/multi-keyspace-plan.md` | Live-cluster testing | "No Docker in this environment"; absolute private path to an env file; AZ/rack details of the shared cluster | Rewritten generically: Docker is the default backend, cloud via `KANDRA_CLOUD_*` env, nothing private |
| 2 | O | same | Status line, phase table | Status "Planned"; ISS-110 row said generated `*Table` objects emit qualified names (codegen only validates the identifier) | Status set to implemented/unreleased; row corrected |
| 3 | O | same | Open follow-ups | n/a | Left; GH #160 reference is correct |
| 4 | W | `docs/test-plan/multi-keyspace-baseline.md` | Header, multidc row, tables, running | Private env path; "kandra-multidc has no tests" (a `manual`-tagged suite exists, `multiDcTest`); "needs a shared connection provider" (since built: `KandraTestBackend`); rack names | Corrected; counts labelled as a point-in-time snapshot |
| 5 | M | `docs/features/testing.md`, `README.md`, `.claude/skills/kandra-test/SKILL.md` | Test sections | `KandraTestBackend`, `KandraCloud`, `KANDRA_TEST_BACKEND`, `KANDRA_CLOUD_*` (incl. TLS 9142, CA_PEM, TRUSTSTORE), skip-when-unconfigured, readiness wait, 30 s timeouts undocumented; skill showed `freshKeyspace(vararg classes)` only and the old keyspace-creation steps | Added sections/tables in all three; skill signature and call sequence updated |
| 6 | W | `docs/changelog/0.5.0.md` | Whole file | Missed: runtime qualified CQL and labels, DI guard, `KandraMigration.up(session, keyspace)`, lock naming, blank-default rule, test backend; said SimpleStrategy flag works on Scylla Cloud (rejected on `tablets_mode_for_new_keyspaces: enforced`); listed "logged batches spanning keyspaces" as non-goal although `MultiKeyspaceRuntimeIntegrationTest` shows it works | Rewritten; tests listed by real class names; status note that build files still say 0.4.8 |
| 7 | W | `README.md`, `docs/USER_GUIDE.md`, `docs/features/ktor-plugin.md` | Multiple keyspaces | Same cross-keyspace-batch non-goal claim | Reworded: mixed `batch { }` works, usual cost |
| 8 | M | README, User Guide, `ktor-plugin.md`, kandra-ktor skill | Multiple keyspaces | Missing: blank-default-keyspace rule, declaration validation rules, lock naming (default keeps old name, others `ks.kandra_ddl_locks`), qualified labels, inherited strategy, error texts | Added to all four |
| 9 | W | kandra-ktor skill | Install sequence, notes, gotchas, `ReplicationStrategy` | "Blank keyspace always throws"; `SimpleStrategy` shown without `disableTablets` | Corrected incl. exception for declared extra keyspaces |
| 10 | M | README, User Guide, `ktor-plugin.md`, kandra-ktor skill, `KandraConfig.kt` KDoc | `disableTablets` | No mention that `tablets_mode_for_new_keyspaces: enforced` rejects SimpleStrategy even with the flag (KDoc said the flag is "required" on Scylla Cloud). Evidence: `SimpleStrategyMultiKeyspaceCloudTest` skips on that error | Added caveat everywhere; KDoc fixed |
| 11 | M | `docs/features/di-integrations.md`, User Guide | Multiple keyspaces | Same-simple-name collision across keyspaces and the `[keyspace: X]` message not explained | Added |
| 12 | M | User Guide, `migrations.md` | Per-keyspace migrations | Constructor signature, `up(session, keyspace)`, keyspace must exist, `IllegalArgumentException`, migration CQL must qualify table names | Added |
| 13 | M | User Guide | Codegen paragraph | Did not say codegen only validates the identifier | Added |
| 14 | O | kandra, kandra-core skills | `@ScyllaTable` row, `TableSchema`/`LookupTableSchema` listing | `keyspace` param missing; TableSchema listing missing `keyspace`, `qualifiedTableName`, `reflection`, soft-delete marker fields, `generatedUuidColumns`, lookup `clusteringKeyColumns` | Updated |
| 15 | W | User Guide, kandra-ktor/-multidc/-runtime skills | Read-your-writes / Strict Mode | Said `R + W >= RF` holds for RF <= 3 and that Strict Mode does not check RF. Code requires strict `R + W > RF` (defaults OK only for RF <= 2) and `strictMode` does check RF (ISS-075), per entity keyspace | Corrected in all; Strict Mode RF check documented in User Guide, `multidc.md`, runtime skill |
| 16 | W | `StatementBuilder.kt` KDoc | RF check | Said "session's current keyspace" and "warns when `R + W < RF`" | Fixed (entity keyspace; does-not-exceed) |
| 17 | W | `KandraMultiDc.kt` KDoc | Class and `describe` | Claimed the module provides the multi-DC features and "validates" config (it only formats a string) | Reworded |
| 18 | O | `docs/features/multidc.md` | Intro | Did not say the module is thin or that its test suite is excluded from default `test` | Added |
| 19 | O | `docs/issues/README.md` | "Pending live-cluster verification" | Claimed no Docker; ISS-007/013/017/018 are verified by live tests that pass on Docker (ISS-013 file already said Fixed/confirmed); "nothing open" date stale and ignored GH #160 | Moved ISS-007/013/017/018 to Fixed; ISS-014/015/019 relabelled "no dedicated live test" (no test pins them, so not moved); date and GH #160 note updated; ISS-106 gap noted |
| 20 | O | ISS-007/014/015/017/018/019, ISS-109 | Status lines / Problem | "pending live-cluster verification"; ISS-109 "no Docker available" | Updated |
| 21 | O | `docs/features/README.md` | Title, index | "0.4.8" title; `multi-keyspace-plan.md` not linked | Retitled with release note; linked |
| 22 | O | `docs/changelog/README.md` | 0.5.0 row | Highlights incomplete, no unreleased marker | Updated |
| 23 | O | `README.md`, User Guide | Version/status | Multi-keyspace described without noting it is unreleased (build files say 0.4.8) | Notes added; User Guide index row added |
| 24 | W | `docs/test-plan/README.md` | Intro, background | "Nothing here has been executed", "used in production by CoinX" (not in production use), "43 unit tests", "never run against a live cluster" | Corrected |
| 25 | W | USER_GUIDE, README | Metrics | `operation` lists wrong/inconsistent; table label now `keyspace.table` | Lists from `BatchEngine` calls; label noted; kandra-core skill too |
| 26 | W | User Guide, `core-annotations.md`, README, `test-plan:1.1/00-fix-verification.md` | Links | 6 broken anchors (`#counter-operations-1`, `#savingwith-explicit-nulls`, `#findactive-soft-delete-...`, `...-entity-iss-029`) | Fixed; all links in tracked `.md` now resolve |
| 27 | O | `docs/site/README.md` | 0.4.7 row | "not yet a tagged release" | Updated |
| 28 | O | `README.md`, `Kandra.kt` KDoc, ISS-101 | Ktor caveat | Pointed only to GH #155; ktorVersion location wrong in ISS-101 | GH #160 added; location fixed |
| 29 | O | `docs/reviews/README.md` | Index | Missing three reviews | Added |

Counts: inconsistent/wrong 15 (rows 1, 4, 6, 7, 9, 10, 15, 16, 17, 24, 25, 26 and parts of 5, 14), outdated 11, missing 6 rows
(5, 8, 10, 11, 12, 13) — about 29 rows, 70+ individual edits.

## Checked and found consistent

`@ScyllaTable` signature, `TableSchema.keyspace`/`qualifiedTableName`, `keyspace(name){autoCreate; replicationStrategy}`,
undeclared-keyspace and duplicate/invalid-name errors, `KandraMigrationRunner` signature, `SimpleStrategy(rf, disableTablets=false)`
DDL, `KandraCloud` variables and defaults, `settings.gradle.kts` module list vs README table, bom/jakarta/multidc present.

## Code issues (not fixed, per rules)

1. `gradle.properties` carries `gradleVersion=8.11` and `kandraVersion` that nothing reads; the wrapper is 9.3.0. Misleading.
2. `ktorVersion` exists in both `gradle.properties` and `gradle/libs.versions.toml` (`ktor`): two sources of truth.
3. `README` / metrics docs say the recorder fires "after every query"; only `BatchEngine` calls the recorder, so reads emit
   nothing (consistent with ISS-074 scope but surprising).
4. `KandraCloud.freshKeyspace` does not bump RF=1 to 3 whereas `KandraTestBackend.freshKeyspace` does; RF=1 can hit `Unavailable` on the shared cluster.
5. `KandraCloud` KDoc points readers to `multi-keyspace-plan.md` for the DC value; fine, but the DC is also hard-coded as a default.

## Needs a user decision

1. `docs/report:1.0/` and `docs/test-plan:1.1/` contain a colon in the directory name (breaks checkout on Windows, ambiguous in
   markdown links) and local `/Users/pasaka/...` paths. Rename and scrub?
2. The v1.0 test plan files (`docs/test-plan/01..07`) are deliberately pinned to 0.4.2 / Gradle 8.11; left as historical. Refresh or archive?
3. `docs/site/` has no build prompt for 0.4.8 or 0.5.0; writing one is new content.
4. ISS-014/015/019 stay in "no dedicated live test" until targeted tests exist; add tests, or accept as verified?
5. The `tablets_mode_for_new_keyspaces: enforced` statement rests on the cloud test's error-message handling and the lead's ground
   truth; confirm the wording is acceptable before release.
6. Version labels: docs say "0.4.8 released, 0.5.0 proposed"; bump only when you release.
