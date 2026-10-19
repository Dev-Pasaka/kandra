# ISS-109: Live Scylla Cloud test harness

**Status:** Fixed — verified 2026-10-20 (Docker regression + live Scylla Cloud over TLS); see [multi-keyspace-plan](../features/multi-keyspace-plan.md)

## Problem

Integration tests rely on Testcontainers; no Docker available, so nothing exercises a real cluster here.

## Scope

`KandraCloud` in kandra-test: env-driven config (KANDRA_CLOUD_*), verify real local DC name, `freshKeyspace()` (kt_ prefix) with guaranteed drop, skip when unset. Baseline: record which existing tests pass against the cloud cluster.

## Acceptance

Harness usable by all agents; no credentials in repo; baseline report committed under docs/.
