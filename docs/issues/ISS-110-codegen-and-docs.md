# ISS-110: Codegen and documentation for multi-keyspace

**Status:** Fixed — verified 2026-10-20 (Docker regression + live Scylla Cloud over TLS); see [multi-keyspace-plan](../features/multi-keyspace-plan.md)

**What was done:** the generated `*Table` carries only column refs (no table/keyspace names), so its output is unchanged for keyspaced entities (asserted byte-identical). The processor now validates `@ScyllaTable(keyspace)` at build time. `KandraProcessorKeyspaceTest` added. Docs written for the planned design: README, USER_GUIDE, features/{core-annotations,ktor-plugin,schema-modes,migrations,di-integrations,multidc}, kandra-codegen skill, proposed changelog 0.5.0.

## Problem

Generated `*Table` objects and docs know nothing about keyspaces.

## Scope

Codegen emits qualified names for keyspaced entities; USER_GUIDE, README, features docs, kandra-* skills notes.

## Acceptance

Codegen tests; docs describe config, annotation, precedence, non-goals.

See GH #170, [PR #172](https://github.com/Dev-Pasaka/kandra/pull/172).
