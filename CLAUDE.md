# Claude Code entry point

This file is intentionally a router, not a second rule book.

## Start here

1. Read `AGENTS.md`.
2. Run `./scripts/harness/init.sh`.
3. Read `.harness/GUIDE.md`, `.harness/RULES.md`, and `.harness/PROGRESS.md`.
4. Read `.serena/memories/critical-info.md`.
5. Read the core memory for every affected module and follow relevant `mem:`
   references.
6. Select and run the checks in `.harness/CHECKS.md`.
7. Persist cross-session state in `.harness/task-map.json` and
   `.harness/session-handoff.md`.

## Completion contract

A task is not complete until:

- the requested behavior exists;
- the appropriate checks have run;
- command output or another reproducible artifact is recorded;
- failures and skipped checks are explicit;
- the next agent can resume from repository state without relying on chat.

## Common commands

```sh
./scripts/harness/init.sh
./scripts/harness/check.sh fast
./scripts/harness/check.sh changed
./scripts/harness/check.sh full
node scripts/harness/record.mjs --help
node scripts/harness/sourcemap-check.mjs --help
```

The detailed model is documented in `docs/harness/`.
