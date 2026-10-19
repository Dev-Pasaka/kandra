# ISS-105: Plugin config and bootstrap for several keyspaces

**Status:** Open — part of ISS-102 (see [multi-keyspace-plan](../features/multi-keyspace-plan.md))

## Problem

`KandraConfig.keyspace` is a single String; create/permissions/DDL/system_schema lookups/ddl locks all assume one keyspace (`Kandra.kt`).

## Scope

`keyspace(name){autoCreate; replicationStrategy}` DSL; per-keyspace bootstrap; undeclared keyspace => KandraSchemaException; lock names include keyspace; session still built once.

## Acceptance

Existing config unchanged; cloud tests for AUTO_CREATE/AUTO_MIGRATE/VALIDATE across keyspaces; one `CqlSession` only.
