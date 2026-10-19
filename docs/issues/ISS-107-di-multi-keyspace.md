# ISS-107: Koin/Kodein bindings with multiple keyspaces

**Status:** Open — part of ISS-102 (see [multi-keyspace-plan](../features/multi-keyspace-plan.md))

## Problem

DI modules bind every registry entity on the one session; verify and fix behaviour with entities from several keyspaces.

## Scope

Koin + Kodein: correct bindings, duplicate-name guard retained.

## Acceptance

Tests resolving repositories for entities in two keyspaces.
