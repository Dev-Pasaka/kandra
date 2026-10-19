# ISS-102: Multiple keyspaces over a single connection (epic)

**Status:** Fixed — verified 2026-10-20 (Docker regression + live Scylla Cloud over TLS); see [multi-keyspace-plan](../features/multi-keyspace-plan.md)

## Problem

Kandra binds one `CqlSession` to one keyspace (`CqlSessionBuilder.kt` `withKeyspace`) and emits unqualified CQL, so a multi-module app cannot share one connection across keyspaces. Entities have no keyspace (`TableSchema`, `@ScyllaTable`).

## Scope

Tracks ISS-103..ISS-111. Design decisions are fixed in the plan doc.

## Acceptance

All plan-doc Definition-of-done items verified on the live Scylla Cloud cluster.
