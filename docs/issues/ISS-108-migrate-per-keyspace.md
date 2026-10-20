# ISS-108: Migrations tracked per keyspace

**Status:** Fixed — verified 2026-10-20 (Docker regression + live Scylla Cloud over TLS); see [multi-keyspace-plan](../features/multi-keyspace-plan.md)

Done: `KandraMigrationRunner(session, staleClaimThreshold, keyspace: String? = null)`; `kandra_migrations` and `kandra_ddl_locks` are created/read/written qualified in the target keyspace (null = session keyspace, unchanged). Keyspace validated with `CqlNaming.isValidIdentifier` (IllegalArgumentException). `KandraMigration` gained `open fun up(session, keyspace)` (defaults to `up(session)`); checksums unchanged. Tests: `KandraMigrationMultiKeyspaceTest` (Docker + cloud).

## Problem

`KandraMigrationRunner(session)` creates `kandra_migrations`/`kandra_ddl_locks` unqualified, i.e. in the session keyspace only.

## Scope

Runner takes a target keyspace (default = session keyspace); bookkeeping tables and lock names per keyspace; checksums unaffected.

## Acceptance

Cloud test: independent migration histories in two keyspaces.

See GH #168, [PR #172](https://github.com/Dev-Pasaka/kandra/pull/172).
