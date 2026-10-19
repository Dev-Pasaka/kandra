# ISS-110: Codegen and documentation for multi-keyspace

**Status:** Open — part of ISS-102 (see [multi-keyspace-plan](../features/multi-keyspace-plan.md))

## Problem

Generated `*Table` objects and docs know nothing about keyspaces.

## Scope

Codegen emits qualified names for keyspaced entities; USER_GUIDE, README, features docs, kandra-* skills notes.

## Acceptance

Codegen tests; docs describe config, annotation, precedence, non-goals.
