# ISS-108: Migrations tracked per keyspace

**Status:** Open — part of ISS-102 (see [multi-keyspace-plan](../features/multi-keyspace-plan.md))

## Problem

`KandraMigrationRunner(session)` creates `kandra_migrations`/`kandra_ddl_locks` unqualified, i.e. in the session keyspace only.

## Scope

Runner takes a target keyspace (default = session keyspace); bookkeeping tables and lock names per keyspace; checksums unaffected.

## Acceptance

Cloud test: independent migration histories in two keyspaces.
