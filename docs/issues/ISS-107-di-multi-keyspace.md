# ISS-107: Koin/Kodein bindings with multiple keyspaces

**Status:** Implemented — pending integration (Phase 3). Part of ISS-102 (see [multi-keyspace-plan](../features/multi-keyspace-plan.md))

**What was done:** Bindings were already keyspace-agnostic (each repo gets its entity's schema, so the runtime qualifies CQL).
The simple-name guard in `kandraKoin()` / `kandraKodein()` is kept; its message now lists each colliding class with its
keyspace and, when the keyspaces differ, says the keyspace does not disambiguate DI names. No new qualifier scheme.
Added `Kandra{Koin,Kodein}MultiKeyspaceTest` (guard test + Docker round-trip of entities in two keyspaces; the round-trip
needs the ISS-104 runtime qualification to pass).

## Problem

DI modules bind every registry entity on the one session; verify and fix behaviour with entities from several keyspaces.

## Scope

Koin + Kodein: correct bindings, duplicate-name guard retained.

## Acceptance

Tests resolving repositories for entities in two keyspaces.
