# Penpot Harness

The Harness is a lightweight repository control layer for reliable agent work.
It does not replace Penpot architecture, Serena memories, tests, or review.

## Core Harness

The validator requires 12 files:

1. `AGENTS.md`
2. `init.sh`
3. `.harness/README.md`
4. `.harness/RULES.md`
5. `.harness/STATUS.md`
6. `.harness/feature-list.json`
7. `.harness/checks.json`
8. `.harness/tool-registry.json`
9. `scripts/harness/init.mjs`
10. `scripts/harness/check.mjs`
11. `scripts/harness/evidence.mjs`
12. `scripts/harness/test.mjs`

Core rules are scope control, explicit verification, persistent state for long
work, secret protection, and human approval for destructive operations.

## Extended Harness

`CLAUDE.md`, `docs/harness/`, `.harness/CHECKS.md`, `.harness/TOOLS.md`, Source
Map configuration/scripts, generated maps, and committed evidence are optional.
Use them when the task benefits from deeper explanation, cross-module discovery,
or durable audit evidence.

## Task sizing

- **Small task:** initialize the relevant profile, edit, verify, review. No
  mandatory STATUS update.
- **Long or resumed task:** read/update `feature-list.json` and `STATUS.md`, then
  leave a concrete next step.

`./init.sh` is non-destructive. It checks the selected profile and never
installs dependencies, starts services, or deletes data.
