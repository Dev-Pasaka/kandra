# ISS-104: Runtime emits keyspace-qualified CQL

**Status:** Fixed — verified 2026-10-20 (Docker regression + live Scylla Cloud over TLS); see [multi-keyspace-plan](../features/multi-keyspace-plan.md)

Implemented: all `schema.tableName`/`lookup.tableName` uses in StatementBuilder, BatchEngine, QueryExecutor and the repositories use `qualifiedTableName` (CQL, prepared-statement cache keys, metrics and log labels). Strict-mode RF check resolves `schema.keyspace ?: session.keyspace`. Tests: `StatementBuilderQualifiedCqlTest` (kandra-runtime) and `MultiKeyspaceRuntimeIntegrationTest` (kandra-test; Docker, or `KANDRA_TEST_BACKEND=cloud`).

Part of ISS-102 (see [multi-keyspace-plan](../features/multi-keyspace-plan.md))

## Problem

`StatementBuilder` etc. use bare `schema.tableName`; strict-mode RF check reads `session.keyspace` (`StatementBuilder.kt:239`).

## Scope

Use `qualifiedTableName` in StatementBuilder, BatchEngine, QueryExecutor, query DSL, lookup tables; RF check resolves the entity's keyspace.

## Acceptance

Null-keyspace CQL byte-identical (existing tests unchanged); cloud tests across two keyspaces.

See GH #165, [PR #172](https://github.com/Dev-Pasaka/kandra/pull/172).
