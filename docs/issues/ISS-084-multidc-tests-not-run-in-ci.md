# ISS-084: kandra-multidc's entire test suite is tagged 'manual' with zero CI/scheduled execution

**Status:** Open

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
