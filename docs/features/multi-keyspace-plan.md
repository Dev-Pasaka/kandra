# Multi-keyspace support — implementation plan

**Status:** Planned (branch `feature/multi-keyspace`) · **Epic:** ISS-102

## Goal

One `install(Kandra)`, one `CqlSession` (one connection pool to the cluster), **many keyspaces**.
Feature modules of a multi-module app each own entities in their own keyspace and share the one
connection.

## Decisions (fixed — agents must not re-litigate)

1. **The keyspace is a property of the entity, set at compile time:**
   `@ScyllaTable(name = "invoices", keyspace = "billing")`. Default `""` = "the plugin's default
   keyspace". Per-call / per-tenant runtime keyspace selection is a **non-goal** (different design).
2. **`TableSchema.keyspace: String?`** — `null` means "default keyspace". Lookup tables inherit the
   parent entity's keyspace.
3. **Zero-regression rule:** when `TableSchema.keyspace == null`, every generated CQL string must be
   **byte-for-byte what it is today** (unqualified). Only entities with an explicit keyspace emit
   `keyspace.table`. Existing single-keyspace users see no change.
4. **Qualification helper lives in kandra-core:** `TableSchema.qualifiedTableName` (and the same for
   `LookupTableSchema`) returns `tableName` if keyspace is null, else `"$keyspace.$tableName"`.
   All keyspace names are validated with `CqlNaming.isValidIdentifier` (GH #65 precedent).
5. **`SchemaRegistry` stays a global, KClass-keyed object.** Entities carry their keyspace, so the
   registry no longer blocks multi-keyspace. (Scoping it per runtime is out of scope.)
6. **Config stays backward compatible** (no major bump). `keyspace = "x"` remains the default
   keyspace. Additional keyspaces are *declared* (not populated) in the DSL:
   ```kotlin
   install(Kandra) {
       keyspace = "core"                       // default keyspace
       keyspace("billing") {                   // extra keyspace: settings only
           autoCreate = true
           replicationStrategy = ReplicationStrategy.SimpleStrategy(3)
       }
       register(User::class, Invoice::class)   // Invoice is @ScyllaTable(keyspace = "billing")
   }
   ```
   An entity naming a keyspace that is neither the default nor declared → `KandraSchemaException`
   with an actionable message. Entity annotation is the single source of truth for membership.
7. **Per-keyspace bootstrap:** keyspace creation, permission validation, DDL (`AUTO_CREATE` /
   `AUTO_MIGRATE` / `VALIDATE`), `system_schema` lookups and the `kandra_ddl_locks` table are all
   handled per keyspace. Lock names must include the keyspace.
8. **Strict-mode RF check** resolves the replication of the *entity's* keyspace, not
   `session.keyspace`.

## Live-cluster testing (Scylla Cloud)

No Docker in this environment, so integration tests run against the shared Scylla Cloud cluster.
Credentials live in the gitignored `/Users/pasaka/Developer/Kandra/.kandra-cloud.env`
(`source` it; never copy values into code, docs, commits or logs).

Rules for every agent, because the cluster is **shared by parallel agents**:

- Create keyspaces named `kt_<task>_<random8>` (lowercase), **always drop them in `finally`/teardown**.
  Never touch any keyspace that doesn't start with `kt_`. Never touch `system*`.
- Use `SimpleStrategy` RF=1 or 3 only (the cluster is multi-AZ in one DC, not multi-DC).
  The cluster enables **tablets by default**, and `SimpleStrategy` rejects tablets, so
  `CREATE KEYSPACE ... SimpleStrategy` must end with `AND tablets = {'enabled': false}`
  (`KandraCloud.freshKeyspace` does this for you).
- Verified (ISS-109): a single datacenter `AWS_US_EAST_1` (so `KANDRA_CLOUD_LOCAL_DC=AWS_US_EAST_1`
  is correct), 3 nodes, racks `use1-az4`/`use1-az4`/`use1-az2` (2 distinct AZs), `system.local.release_version`
  reports 3.0.8 (Scylla's Cassandra-compat version string). Plain username/password on port 9042 works (TLS not required); TLS is also available on 9142
  (`KANDRA_CLOUD_TLS=true` + `KANDRA_CLOUD_CA_PEM`, harness builds the trust store from the PEM at runtime).
  RF=1 keyspaces can hit `Unavailable` on this cluster; prefer RF=3.
- Use the `KandraCloud` harness (kandra-test): `KandraCloud.assumeConfigured()` in `@BeforeAll`
  (skips when unset), `KandraCloud.freshKeyspace("prefix", rf).use { ks -> ks.session ... }`
  (creates `kt_<prefix>_<random8>`, always drops it), `KandraCloud.newSession(keyspace?)`.
  The default driver timeout (2s) is too short for DDL over the WAN; the harness sets 30s.
  Run: `source /Users/pasaka/Developer/Kandra/.kandra-cloud.env && ./gradlew :<module>:test --tests '<Class>'`.
- Don't run the whole suite concurrently with others; run only your module's tests plus the
  cloud tests you add. The full regression run is done once, at integration time (Phase 3).
- Cloud tests must be skipped (not failed) when `KANDRA_CLOUD_CONTACT_POINTS` is unset, so CI and
  contributors without access stay green.

## Phases and parallelism

```
Phase 0 (parallel)      A: core contract (ISS-103)     B: cloud test harness (ISS-109)
                                   \                      /
                          merge into feature/multi-keyspace
                                         |
Phase 1 (parallel, 5 agents, disjoint modules)
   C: runtime CQL qualification   (ISS-104)  kandra-runtime
   D: ktor config + bootstrap     (ISS-105)  kandra-ktor
   E: DI modules                  (ISS-107)  kandra-koin, kandra-kodein
   F: migrations                  (ISS-108)  kandra-migrate
   G: codegen + docs              (ISS-110)  kandra-codegen, docs/
                                         |
Phase 2: merge Phase 1 branches, resolve seams
Phase 3: full regression + end-to-end multi-keyspace tests on the cloud cluster (ISS-111)
```

D depends on C's API only through core (`qualifiedTableName`), so C and D can run in parallel
once Phase 0 is merged; they meet at integration (Phase 3).

| Phase | Issue | Module(s) | Deliverable |
|---|---|---|---|
| 0 | ISS-103 | kandra-core | `keyspace` on `@ScyllaTable`/`TableSchema`/lookup schemas, `qualifiedTableName`, identifier validation, DDL generation emits qualified names; unit tests |
| 0 | ISS-109 | kandra-test | `KandraCloud` helper: env-driven session, `freshKeyspace()` + auto-drop, skip when unconfigured; baseline run of existing cloud-capable tests |
| 1 | ISS-104 | kandra-runtime | `StatementBuilder`/`BatchEngine`/`QueryExecutor`/query DSL use qualified names; RF check per entity keyspace; unit + cloud tests |
| 1 | ISS-105 | kandra-ktor | `keyspace(name){}` DSL, per-keyspace create/permissions/DDL/locks/system_schema lookups, validation errors; cloud tests |
| 1 | ISS-107 | kandra-koin, kandra-kodein | Bindings correct for entities in several keyspaces; tests |
| 1 | ISS-108 | kandra-migrate | Migrations/`kandra_migrations`/locks per keyspace; tests |
| 1 | ISS-110 | kandra-codegen, docs | Generated `*Table` objects emit qualified names; USER_GUIDE/README/features docs |
| 3 | ISS-111 | all | Full regression + two-keyspace end-to-end suite |

## Definition of done

- All existing tests pass unchanged (single-keyspace behaviour is byte-identical).
- New cloud E2E: one install, ≥2 keyspaces, CRUD + lookup tables + batch + soft-delete + counter
  across keyspaces; `AUTO_CREATE`, `AUTO_MIGRATE` and `VALIDATE` per keyspace; migrations in two
  keyspaces; DI resolves repositories for both.
- `./gradlew build` green; no leftover `kt_*` keyspaces on the cluster.
- Docs updated; ISS entries moved to Fixed with verification notes (per `docs/issues/README.md`).

## Open follow-ups (not in scope)

- GitHub issues for ISS-102…111 (not created automatically — outward-facing).
- Runtime/per-tenant keyspace selection (repository factory taking a keyspace).
- Ktor 3.x (GH #160) is unrelated.
