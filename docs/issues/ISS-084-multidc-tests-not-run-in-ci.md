# ISS-084: kandra-multidc's entire test suite is tagged 'manual' with zero CI/scheduled execution

**Status:** Fixed, verified via a real CI run

## Resolution

Fixed via GH #97's PR. Added `.github/workflows/multidc.yml`, a separate workflow from `ci.yml`'s fast
`test` job (these tests take several minutes against real Docker topologies, versus `ci.yml`'s normal
sub-minute run), that runs `./gradlew :kandra-multidc:multiDcTest :kandra-ktor:sslIntegrationTest` on:

- `schedule`: nightly at 03:00 UTC, so DataStax driver / `cassandra:4.1` image / failover-config drift
  gets caught even with no code changes.
- `workflow_dispatch`: on demand.
- `push`/`pull_request` against `main`, path-filtered to `kandra-multidc/**`, `kandra-ktor/**`,
  `kandra-test/**`, and the workflow file itself — so a PR touching this surface gets real-cluster
  coverage before merge, not just at the next nightly run (per this issue's own suggested fix, running
  on every push/PR regardless of path wasn't worth the multi-minute cost).

`ubuntu-latest` runners ship Docker (and the `docker compose` v2 plugin) preinstalled, so no extra
runner setup step was needed. Since this is fully automated (nightly + path-triggered + on-demand), no
manual pre-release step needed documenting in README/CONTRIBUTING as a residual gap — the change is
instead documented in [`docs/features/multidc.md`](../features/multidc.md#ci-coverage-gh-97--iss-084)
and referenced from `README.md`.

Verified by watching a real PR's CI run (`gh pr checks --watch`) with this workflow wired in, plus
running `./gradlew :kandra-multidc:multiDcTest :kandra-ktor:sslIntegrationTest` locally against real
Docker.

## Problem

Filed as GH #97.

The #84/ISS-076 fix added real 2-DC failover test coverage in `MultiDcFailoverTest.kt` — but it's tagged `@Tag("manual")` and `kandra-multidc/build.gradle.kts` excludes that tag from the default `test` task. It's the only test file anywhere in `kandra-multidc/src/test` (the module's `src/main` is an 88-line KDoc wrapper; the real DC-failover/load-balancing logic lives in `kandra-ktor`). `.github/workflows/ci.yml`'s only test step is `./gradlew test --no-daemon` — no `multiDcTest`/`sslIntegrationTest` task appears anywhere in an actual CI run for this PR, and there is no second workflow, `schedule:` trigger, or `workflow_dispatch` gate anywhere in the repo. README/CONTRIBUTING/docs/features/multidc.md have zero mentions of a manual pre-release step to run these suites.

Net effect: `:kandra-multidc:test` currently runs zero tests by default. A green CI checkmark for this module means "it compiled," not "DC failover works." This recreates, one layer down, exactly the failure mode #84/ISS-076 was filed to prevent (thin coverage on cluster-topology-sensitive code) — the fix closed the coverage gap on paper, but the new coverage is structurally unreachable from CI, and nothing will notice when the DataStax driver, the `cassandra:4.1` image, or the failover config surface drifts and silently breaks these tests.

The equivalent `kandra-ktor` SSL round-trip test (`sslIntegrationTest`) has the identical problem.

## Impact

Critical from a process standpoint: the module whose entire purpose is de-risking multi-DC failover ahead of real cluster testing currently has no tests running anywhere in CI, and nothing catches further regression before real cluster testing begins.

## Suggested fix

At minimum, add a scheduled GitHub Actions workflow (nightly/weekly `schedule` + `workflow_dispatch`) that runs `./gradlew :kandra-multidc:multiDcTest :kandra-ktor:sslIntegrationTest`, and document it as a required pre-release gate in README/CONTRIBUTING until then.

## Files

`.github/workflows/ci.yml`, `kandra-multidc/build.gradle.kts`, `kandra-ktor/build.gradle.kts`

Filed from a critical post-fix audit (2026-09-09) ahead of experimental multi-DC cluster testing, re-reviewing the brand-new #84 fix.
