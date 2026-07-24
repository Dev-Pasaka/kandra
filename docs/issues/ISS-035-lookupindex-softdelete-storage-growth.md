# ISS-035: `@LookupIndex` + `@SoftDelete` storage-growth implication was undocumented

**Status:** Fixed

## Problem

Since `docs/issues/ISS-030-soft-delete-removes-lookup-rows.md` (fixed in 0.4.4), soft-deleting an
entity deliberately leaves its `@LookupIndex` row(s) alone until the entity's own soft-delete TTL
expires — correct behavior, since a soft-deleted row still "exists" (queryable, non-key columns not
yet TTL'd) and must remain resolvable via its `@LookupIndex`, for the same reason `findById` still
finds it.

The undocumented consequence: for high-churn datasets combining `@LookupIndex` and `@SoftDelete` on
the same entity, the lookup table ends up holding significantly more live rows than the primary table
at any given time. The primary table's non-key columns get TTL'd/tombstoned relatively quickly on
soft-delete, but the corresponding lookup row survives until the *entity's* full soft-delete TTL
expires. This is a real, non-obvious storage-cost implication that was not documented anywhere —
`docs/features/core-annotations.md` didn't mention it, and worse, its `@SoftDelete` section still
said "Lookup rows are hard-deleted," directly contradicting the ISS-030 fix.

## Fix

Documented the interaction (not a behavior change):

- `docs/features/core-annotations.md` — corrected the stale `@SoftDelete` line and added a
  "Storage cost with `@LookupIndex`" note there, plus a matching note in the `@LookupTable` section,
  cross-linked to each other and to ISS-030.
- `.claude/skills/kandra-core/SKILL.md` — added a Gotchas entry describing the same effect for anyone
  writing `@ScyllaTable` entities that combine both annotations.

**Files:** `docs/features/core-annotations.md`, `.claude/skills/kandra-core/SKILL.md`.
