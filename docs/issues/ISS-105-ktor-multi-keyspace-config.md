# ISS-105: Plugin config and bootstrap for several keyspaces

**Status:** Fixed — verified 2026-10-20 (Docker regression + live Scylla Cloud over TLS); see [multi-keyspace-plan](../features/multi-keyspace-plan.md)

**What was done:** `keyspace(name) { autoCreate; replicationStrategy }` DSL (`KeyspaceConfig`); up-front validation (identifiers, duplicates, clash with default, NTS DC names); entities registered before connecting and checked against default+declared keyspaces (`KandraSchemaException` listing declared keyspaces); one `CqlSession`; per-keyspace CREATE KEYSPACE, permission validation, AUTO_CREATE/AUTO_MIGRATE/VALIDATE restricted to that keyspace's entities with correct `system_schema` keyspace_name; `kandra_ddl_locks` lives in each non-default keyspace (qualified) with the keyspace in the lock name; the default keyspace keeps the unqualified table and unchanged lock names. A blank default keyspace is allowed only if extra keyspaces are declared and every entity names a keyspace. Tests: `MultiKeyspaceConfigTest`, `MultiKeyspaceIntegrationTest` (Docker + cloud; cloud skipped when env unset).


## Problem

`KandraConfig.keyspace` is a single String; create/permissions/DDL/system_schema lookups/ddl locks all assume one keyspace (`Kandra.kt`).

## Scope

`keyspace(name){autoCreate; replicationStrategy}` DSL; per-keyspace bootstrap; undeclared keyspace => KandraSchemaException; lock names include keyspace; session still built once.

## Acceptance

Existing config unchanged; cloud tests for AUTO_CREATE/AUTO_MIGRATE/VALIDATE across keyspaces; one `CqlSession` only.

See GH #166, [PR #172](https://github.com/Dev-Pasaka/kandra/pull/172).
