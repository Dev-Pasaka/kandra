# ISS-104: Runtime emits keyspace-qualified CQL

**Status:** Open — part of ISS-102 (see [multi-keyspace-plan](../features/multi-keyspace-plan.md))

## Problem

`StatementBuilder` etc. use bare `schema.tableName`; strict-mode RF check reads `session.keyspace` (`StatementBuilder.kt:239`).

## Scope

Use `qualifiedTableName` in StatementBuilder, BatchEngine, QueryExecutor, query DSL, lookup tables; RF check resolves the entity's keyspace.

## Acceptance

Null-keyspace CQL byte-identical (existing tests unchanged); cloud tests across two keyspaces.
