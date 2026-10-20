# ISS-111: End-to-end multi-keyspace suite and full regression

**Status:** Fixed — verified 2026-10-20 (Docker regression + live Scylla Cloud over TLS); see [multi-keyspace-plan](../features/multi-keyspace-plan.md)

## Problem

Per-module work must be verified together.

## Scope

Full `./gradlew build` plus two-keyspace E2E on the cloud cluster; cleanup check for leftover kt_* keyspaces.

## Acceptance

Definition of done in the plan doc.

See GH #171, [PR #172](https://github.com/Dev-Pasaka/kandra/pull/172).
