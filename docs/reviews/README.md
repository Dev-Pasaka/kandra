# Kandra — Reviews

Critical, source-verified passes over the library and its documentation — distinct from
[`docs/issues/`](../issues/README.md) (which tracks individual filed findings) in that each file here is
the narrative write-up a review batch produced, with its findings then filed as `ISS-NNN` entries.

| Date | Review | What it covers |
|---|---|---|
| 2026-09-08 | [Pre-multi-DC-cluster review](2026-09-08-pre-multidc-cluster-review.md) | Security, performance, consistency, scalability, and developer-experience audit of the library itself, done ahead of experimental testing against a real multi-cluster, multi-DC topology. Filed `ISS-070`–`ISS-076`. |
| 2026-09-09 | [Documentation reality check](2026-09-09-docs-reality-check.md) | Line-by-line audit of every user-facing doc (`README.md`, `docs/USER_GUIDE.md`, `docs/production-example.md`, `docs/features/*`, and every `.claude/skills/*/SKILL.md`) against current source — not a findings-filing pass, since every discrepancy found was fixed directly in the same change rather than filed as a separate issue. |
| 2026-09-09 | [Post-fix critical audit](2026-09-09-post-fix-critical-audit.md) | Seven independent read-only reviews re-checking the ISS-070–ISS-076 fixes and the rest of the library ahead of multi-DC testing. Filed `ISS-080`–`ISS-096`. |
| 2026-10-11 | [Documentation reality check — follow-up](2026-10-11-docs-reality-check-followup.md) | Re-check of `README.md`, the User Guide, `docs/production-example.md` and `docs/features/*` for drift since the 2026-09-09 pass. |
| 2026-10-20 | [Multi-keyspace documentation review](2026-10-20-docs-review.md) | Source-verified review of README, User Guide, features, issues, test plan, changelog and the in-repo skills against the `feature/multi-keyspace` branch; each finding was fixed in the same change or recorded as needing a decision. |
