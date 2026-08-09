# Session handoff

## Resume point

- Repository: `miaxxx/penpot`
- Branch: `agent/penpot-ai-harness`
- Milestone: Repository Harness v1
- Durable machine state: `.harness/task-map.json`
- Human summary: `.harness/PROGRESS.md`

## First command

```sh
./scripts/harness/init.sh
```

## Then

```sh
HARNESS_BASE_REF=develop ./scripts/harness/check.sh changed
```

## Current boundaries

- Do not replace Serena memories; Harness execution rules complement them.
- Do not claim product behavior from `fast` checks alone.
- Preserve source-output-evidence traceability.
- Keep failures and skipped checks explicit.

## Handoff template

Before stopping unfinished work, record:

- objective;
- files changed;
- commands run and exit codes;
- blocker;
- exact next command;
- expected success condition.
