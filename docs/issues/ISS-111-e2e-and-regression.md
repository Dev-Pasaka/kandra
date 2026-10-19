# ISS-111: End-to-end multi-keyspace suite and full regression

**Status:** Open — part of ISS-102 (see [multi-keyspace-plan](../features/multi-keyspace-plan.md))

## Problem

Per-module work must be verified together.

## Scope

Full `./gradlew build` plus two-keyspace E2E on the cloud cluster; cleanup check for leftover kt_* keyspaces.

## Acceptance

Definition of done in the plan doc.
