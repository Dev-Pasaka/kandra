# ISS-039: Migration runner requires LWT support as an explicit prerequisite

**Status:** Fixed

## Category

Documentation.

## Problem

Filed as GH #7. `docs/features/migrations.md` already documented *that* each migration is claimed
via an LWT (`INSERT ... IF NOT EXISTS`) for concurrency-safe locking, but didn't call out the
consequence as an explicit prerequisite: if a cluster has LWT disabled or restricted (some
operators do this for performance reasons, since LWT has real latency/throughput cost),
`KandraMigrationRunner` fails outright. This surfaced as a runtime surprise instead of something
caught during planning/review.

## Fix

Added a short "Prerequisites" section to the top of `docs/features/migrations.md` stating plainly
that LWT support is required for the migration runner to function, and why (the `claim()` step's
`IF NOT EXISTS` insert).

## Files

- `docs/features/migrations.md`
