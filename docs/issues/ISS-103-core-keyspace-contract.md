# ISS-103: Entity-level keyspace in kandra-core

**Status:** Fixed — verified 2026-10-20 (Docker regression + live Scylla Cloud over TLS); see [multi-keyspace-plan](../features/multi-keyspace-plan.md)

## Problem

No way for an entity to declare its keyspace.

## Scope

`@ScyllaTable(keyspace)`, `TableSchema.keyspace: String?`, lookup-table inheritance, `qualifiedTableName`, identifier validation, DDL generation emitting qualified names when keyspace != null.

## Acceptance

Null keyspace => DDL identical to today. Unit tests for annotation parsing, validation errors and DDL.
