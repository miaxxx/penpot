# Penpot AI Harness guide

The Harness turns an agent session into a reproducible engineering process.
It is repository-driven rather than chat-driven.

## Five systems

| System | Repository implementation | Purpose |
| --- | --- | --- |
| Instructions | `AGENTS.md`, `CLAUDE.md`, `.serena/memories/`, `.harness/RULES.md` | Define boundaries, read order, and invariants. |
| Tools | `.harness/TOOLS.md`, `scripts/harness/` | Make inspection, validation, state recording, and trace checks executable. |
| Environment | `.nvmrc`, `frontend/package.json`, `manage.sh`, `.harness/environment.json` | State versions, package managers, services, and startup routes. |
| State | `.harness/PROGRESS.md`, `.harness/task-map.json`, `.harness/session-handoff.md` | Preserve goals, progress, blockers, evidence, and next actions across sessions. |
| Feedback | `.harness/CHECKS.md`, CI, test/build/lint output, source-map checks | Replace subjective confidence with executable evidence. |

## Required work loop

1. **Read rules** — enter through `AGENTS.md`, then read the affected Serena
   memories and focused Harness documentation.
2. **Initialize** — run `scripts/harness/init.sh`; do not install or mutate the
   machine implicitly.
3. **Execute** — keep the patch scoped and observable.
4. **Verify** — run the smallest sufficient check first, then broader checks
   when risk requires them.
5. **Record** — update `task-map.json`, `PROGRESS.md`, or both with commands and
   results.
6. **Hand off** — leave a concrete next command in `session-handoff.md`.

## Source-map principle

Every important output must retain a path back to its source:

`request/rule -> files changed -> generated artifact -> verification -> status`

This applies to JavaScript source maps and to agent work. See
`docs/harness/source-map-traceability.md`.

## Definition of done

Use `docs/harness/definition-of-done.md`. “Looks correct” and “the agent said it
finished” are not evidence.
