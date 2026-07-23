# Documentation Website — Build Prompts

The Kandra documentation website is a **separate project** (its own TypeScript/React codebase, deployed
independently) — this folder does not contain the site itself. It holds the self-contained prompt(s)
used to hand a fresh Claude Code session (or any engineer) everything needed to build or update that
site end to end, without any prior context from this repo's conversation history.

One file per Kandra version the prompt was authored against — newest first — the same convention as
[`docs/changelog/`](../changelog/README.md) and [`docs/history/`](../history/). The site's source
material (module list, annotation set, DSL syntax, skill docs) changes release to release, so a prompt
written against an older version can go stale in ways that are easy to miss if it's edited in place.
Keeping one file per version makes that drift visible instead of silent.

| Version | File |
|---|---|
| [0.4.5](build-prompt-0.4.5.md) | Initial build prompt — full site scope, information architecture, and content requirements |

## Updating this for a new Kandra release

Don't edit an existing `build-prompt-X.Y.Z.md` in place once it's been used to build or update the
site. Instead:

1. Copy the latest file to `build-prompt-<new-version>.md`.
2. Re-verify every claim in it against current source — annotation set, method signatures, DSL syntax,
   module list (`settings.gradle.kts`), and anything under `docs/issues/` added since the last prompt.
   Treat the previous prompt as a draft to correct, not a source of truth (see its own §4/§9 for why).
   Note in the new file's header what changed since the previous version, if anything did.
3. Add a row to the table above.

The prompt itself is the authority on how to hand it off and what "done" looks like — see its §11
("Definition of done") rather than duplicating that here.
