# Kandra — Reviews

Critical, source-verified passes over the library and its documentation — distinct from
[`docs/issues/`](../issues/README.md) (which tracks individual filed findings) in that each file here is
the narrative write-up a review batch produced, with its findings then filed as `ISS-NNN` entries.

| Date | Review | What it covers |
|---|---|---|
| 2026-09-08 | [Pre-multi-DC-cluster review](2026-09-08-pre-multidc-cluster-review.md) | Security, performance, consistency, scalability, and developer-experience audit of the library itself, done ahead of experimental testing against a real multi-cluster, multi-DC topology. Filed `ISS-070`–`ISS-076`. |
| 2026-09-09 | [Documentation reality check](2026-09-09-docs-reality-check.md) | Line-by-line audit of every user-facing doc (`README.md`, `docs/USER_GUIDE.md`, `docs/production-example.md`, `docs/features/*`, and every `.claude/skills/*/SKILL.md`) against current source — not a findings-filing pass, since every discrepancy found was fixed directly in the same change rather than filed as a separate issue. |
