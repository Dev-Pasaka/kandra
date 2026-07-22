# 1. Prerequisites and Environment

Do not start writing application code until every item in this file is confirmed working. Most
"Kandra doesn't work" reports turn out to be an environment problem — chase those out first so the
rest of this plan measures the library, not your setup.

## 1.0 Get Kandra's source and Claude Code skills (reference only)

Before touching the sample app, clone Kandra's own source repository somewhere **outside** the
sample app's project directory — you'll use it only as reference material (its per-module Claude
Code skills, its `docs/`, and its historical build specs), never as a dependency:

```bash
git clone https://github.com/Dev-Pasaka/kandra.git ~/reference/kandra
```

Copy the skills into the sample app's own project so Claude Code loads them automatically while you
work (per Kandra's own `README.md`, "Using with Claude Code" section):

```bash
mkdir -p .claude/skills
cp -r ~/reference/kandra/.claude/skills/. .claude/skills/
```

This gives you, inside the sample app project itself:

- `kandra` — the project-wide overview skill (annotation reference, repository signatures, exceptions).
- One exhaustive per-module skill (`kandra-core`, `kandra-runtime`, `kandra-ktor`, `kandra-kodein`,
  `kandra-koin`, `kandra-codegen`, `kandra-test`, `kandra-multidc`, `kandra-migrate`) — each traced
  from the actual implementation, including footguns that don't show up as compile errors. These are
  the same source used to write [file 6](06-known-pitfalls-and-what-to-watch-for.md) of this plan —
  read the relevant one whenever you're about to use a module this plan doesn't cover in exhaustive
  detail, or whenever a result surprises you and you want to check whether it's a documented behavior
  or a genuine new finding.
- `~/reference/kandra/docs/` (not copied into the sample app, just kept alongside for lookup) —
  `docs/issues/` for the reasoning behind every fix mentioned in this plan, `docs/changelog/` for
  what shipped in each version, `docs/features/` for the narrative reference, and `docs/history/` for
  the four build specs that explain *why* each version's feature set was chosen (useful background
  for [file 7](07-realistic-workload-capstone.md)'s "does this actually match real day-to-day
  Cassandra usage" framing).

**This is read-only reference material.** Rule 1 in the root [README](README.md#non-negotiable-ground-rules-for-whoever-executes-this-plan)
still applies without exception: the sample app's `build.gradle.kts` depends on the *published*
`ke.co.coinx.kandra` artifacts from Maven Central, never on this cloned source tree.

## 1.1 Toolchain

| Tool | Required version | Why exactly this version |
|---|---|---|
| JDK | 21 (Temurin recommended) | Kandra targets JDK 21. A known footgun exists in *this repo's own* build (Gradle 8.11's embedded Kotlin compiler breaks parsing under JDK 25 — see `docs/issues/` history) — sidestep the whole class of problem by using JDK 21 for the sample app's Gradle daemon too, not just the app's runtime. Confirm with `java -version` before `./gradlew` anything. |
| Kotlin | 2.1.21 | Must match the version Kandra was compiled against (binary compatibility for inline/reified functions like `repository<T>()`, `KandraTestcontainers.freshKeyspace` callers, etc.). |
| KSP | `2.1.21-2.0.1` | Must match the Kotlin version exactly, or `kandra-codegen` will fail to load with an opaque KSP version-mismatch error, not a Kandra-specific message. |
| Gradle | 8.11 (via wrapper) | Use the wrapper (`./gradlew`), don't rely on a system-installed Gradle — version drift here is a common source of unrelated build failures. |
| Ktor | 2.3.13 | Kandra's `kandra-ktor` module is compiled against this Ktor version. |
| Docker + Docker Compose | Any recent version supporting Compose v2 (`docker compose`, not the deprecated `docker-compose` binary) | For the real ScyllaDB cluster (1.2) and for Testcontainers-based tests, if you choose to also write any (1.4). |

Confirm all of these resolve correctly with a trivial `./gradlew --version` and `docker compose version`
before proceeding — a wrong toolchain version produces confusing, Kandra-unrelated errors later that
are easy to misattribute to the library.

## 1.2 Real ScyllaDB cluster (Docker Compose)

A **single-node** cluster is enough to prove basic CRUD works, but several test cases in this plan
specifically require **multiple nodes** to mean anything (e.g. `LOCAL_QUORUM`/`QUORUM` consistency
only differs observably from `ONE` when there's more than one replica to disagree; `EACH_QUORUM`
needs more than one DC). Use this 3-node, single-DC compose file as the default cluster, and treat
the optional two-DC variant in 1.2.3 as a stretch goal for the multi-DC-specific edge cases in file 4.

### 1.2.1 Three-node single-DC cluster (primary — build everything against this first)

```yaml
# docker-compose.scylla.yml
services:
  scylla-node1:
    image: scylladb/scylla:5.4
    container_name: scylla-node1
    command: --seeds=scylla-node1 --smp 1 --memory 750M --overprovisioned 1 --api-address 0.0.0.0
    ports:
      - "9042:9042"
    healthcheck:
      test: ["CMD-SHELL", "cqlsh -e 'describe cluster' || exit 1"]
      interval: 15s
      timeout: 10s
      retries: 20

  scylla-node2:
    image: scylladb/scylla:5.4
    container_name: scylla-node2
    command: --seeds=scylla-node1 --smp 1 --memory 750M --overprovisioned 1 --api-address 0.0.0.0
    depends_on:
      - scylla-node1

  scylla-node3:
    image: scylladb/scylla:5.4
    container_name: scylla-node3
    command: --seeds=scylla-node1 --smp 1 --memory 750M --overprovisioned 1 --api-address 0.0.0.0
    depends_on:
      - scylla-node1
```

Bring it up and **wait for full cluster convergence before running anything** — ScyllaDB nodes take
noticeably longer than a single-node Cassandra to gossip and agree on ring state:

```bash
docker compose -f docker-compose.scylla.yml up -d
docker exec -it scylla-node1 nodetool status
# Wait until all three nodes show "UN" (Up/Normal), not "UJ" (Up/Joining). This can take 1-3 minutes.
```

Contact point for the sample app: `contactPoints = "localhost:9042"`, `localDatacenter = "datacenter1"`
(ScyllaDB's default DC name — confirm with `nodetool status` output, the DC column, rather than
assuming; a mismatched `localDatacenter` fails the driver connection with an opaque
"no host was tried" error that does not obviously point at this setting).

**Set the keyspace replication factor to 3** (matching node count) once you create it, or
`LOCAL_QUORUM`/`QUORUM` consistency tests are meaningless (RF=1 makes every consistency level behave
identically). If using `autoCreateKeyspace = true`, set `replicationStrategy =
ReplicationStrategy.SimpleStrategy(replicationFactor = 3)` explicitly — the Kandra default is
`replicationFactor = 1`, which would silently defeat the point of a 3-node cluster.

### 1.2.2 Authentication-enabled variant (for the `auth {}` / permission-validation edge cases)

The default image above uses `AllowAllAuthenticator` — none of Kandra's `auth {}` config, credential
rotation, or the plugin's install-time permission validation (`validatePermissions`) is meaningfully
exercised without real authentication. Build a **second**, separate compose file with
`PasswordAuthenticator` enabled (via `SCYLLA_ARGS` / a mounted `scylla.yaml` with
`authenticator: PasswordAuthenticator` and `authorizer: CassandraAuthorizer`), a single node is
sufficient for this variant since it exists purely to exercise auth, not consistency. Default
superuser is `cassandra`/`cassandra` — create a second, less-privileged role via `cqlsh` for the
permission-validation edge cases in file 4 (a role with `SELECT` but not `MODIFY`, a role with
`SELECT`+`MODIFY` but not `ALTER`, etc. — see the `kandra-ktor` skill's exact permission-check order).

### 1.2.3 Two-DC variant (optional — only if you attempt the multi-DC edge cases)

Two `scylladb/scylla` services with different `--seeds`/`--rack`/DC configuration
(`--endpoint-snitch GossipingPropertyFileSnitch` plus a mounted `cassandra-rackdc.properties` per
node setting `dc=dc1`/`dc=dc2`) so `NetworkTopologyStrategy` and `EACH_QUORUM`/cross-DC failover are
real rather than simulated. This is meaningfully more setup than 1.2.1 — treat the multi-DC section
of file 4 as best-effort if you skip this, and say so explicitly in the final report rather than
silently marking those rows as passed against a single-DC cluster (a single-DC cluster cannot
actually fail over across DCs, so any test claiming to verify that is not testing anything).

## 1.3 Verify the published artifact resolves — BEFORE writing application code

This is the step most likely to silently waste hours if skipped. Kandra's CI publishes to Maven
Central with `automaticRelease = false` (see `docs/issues/`), meaning an upload can sit in Central's
Portal in "pending validation" indefinitely without a human clicking "Release" — in that state, the
artifact is **not yet resolvable** from `mavenCentral()` in a normal build.

Create a throwaway scratch project with nothing but this and confirm it resolves before building
anything else:

```kotlin
// settings.gradle.kts
rootProject.name = "kandra-resolve-check"

// build.gradle.kts
plugins { kotlin("jvm") version "2.1.21" }
repositories { mavenCentral() }
dependencies {
    implementation(platform("ke.co.coinx.kandra:kandra-bom:0.4.2"))
    implementation("ke.co.coinx.kandra:kandra-ktor")
}
```

```bash
./gradlew dependencies --configuration compileClasspath
```

- **If this fails to resolve** (`Could not find ke.co.coinx.kandra:kandra-bom:0.4.2`), the release
  hasn't actually gone live on Central yet regardless of what any CI log says — stop here and report
  it as a **blocking** finding (see the scoring rubric, file 5) rather than working around it with a
  local/composite build, which would test the wrong artifact entirely.
- Maven Central's search index can lag a live artifact by up to ~30 minutes after release — if it was
  *just* released, retry a few times before concluding it's broken.
- If you must make progress while waiting, you may temporarily point at a locally-published
  (`./gradlew publishToMavenLocal` against the Kandra source repo) `mavenLocal()` artifact **but must
  clearly label every result gathered this way** as "verified against a locally-built artifact, not
  the published one" in the final report — this plan's entire premise is testing what a real consumer
  gets, and local-build results don't prove that.

## 1.4 Ktor server template

Scaffold a standard Ktor server project (not a "Fat JAR" native-image variant — keep the build simple
so failures are attributable to Kandra, not to build exotica):

```kotlin
// build.gradle.kts (sample app)
plugins {
    kotlin("jvm") version "2.1.21"
    id("com.google.devtools.ksp") version "2.1.21-2.0.1"
    id("io.ktor.plugin") version "2.3.13"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation(platform("ke.co.coinx.kandra:kandra-bom:0.4.2"))
    implementation("ke.co.coinx.kandra:kandra-ktor")
    implementation("ke.co.coinx.kandra:kandra-koin")
    implementation("ke.co.coinx.kandra:kandra-kodein")   // build both DI wirings, see file 2
    implementation("ke.co.coinx.kandra:kandra-migrate")
    implementation("ke.co.coinx.kandra:kandra-multidc")
    implementation("ke.co.coinx.kandra:kandra-jakarta")
    ksp("ke.co.coinx.kandra:kandra-codegen")

    // Jakarta validation needs a real provider — kandra-jakarta only depends on the API, compileOnly
    implementation("org.hibernate.validator:hibernate-validator:8.0.1.Final")
    implementation("org.glassfish:jakarta.el:4.0.2")

    // Optional-dependency features Kandra gracefully no-ops without — include them so @CacheResult
    // and Micrometer metrics are actually exercised, not silently skipped
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    implementation("io.micrometer:micrometer-core:1.12.5")

    implementation("io.ktor:ktor-server-netty:2.3.13")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.13")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.13")

    testImplementation("ke.co.coinx.kandra:kandra-test")
    testImplementation("org.testcontainers:cassandra:1.19.8")
    testImplementation("org.testcontainers:junit-jupiter:1.19.8")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.example.kandratest.ApplicationKt")
}
```

Application entry point should install the plugin and both DI frameworks against the **real cluster
from 1.2**, driven by environment variables so contact points/credentials aren't hardcoded (this also
gives you a natural place to exercise `KandraAuth.fromEnv()`):

```bash
export SCYLLA_CONTACT_POINTS="localhost:9042"
export SCYLLA_KEYSPACE="kandra_sample"
export SCYLLA_LOCAL_DC="datacenter1"
```

## 1.5 What "done" for this file looks like

- [ ] `java -version` reports 21.
- [ ] `docker compose -f docker-compose.scylla.yml up -d` brings up 3 nodes, all `UN` in `nodetool status`, within a few minutes.
- [ ] The scratch resolve-check in 1.3 succeeds against real Maven Central (not `mavenLocal()`), or a blocking finding has been filed if it doesn't.
- [ ] The sample Ktor project (1.4) builds (`./gradlew build`) with zero source files yet beyond a bare `Application.kt` — confirms the dependency graph and KSP wiring work before any entity/route code is written.

Proceed to [file 2](02-sample-application-build-plan.md) only once every box above is checked.
