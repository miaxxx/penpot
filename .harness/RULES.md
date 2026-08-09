# Harness rules

## Read and scope

- Read `AGENTS.md` and affected Serena memories before editing.
- State the target repository, branch, and file scope before a write.
- Do not modify unrelated modules to make a check pass.
- Prefer focused documents and commands over one giant prompt or rule file.

## Execution

- Make the smallest coherent change that satisfies the task.
- Use existing project tools and conventions before introducing new
  dependencies.
- Initialization scripts inspect and report; they do not silently install,
  delete, reset, or migrate.
- Destructive commands require explicit human approval.

## Verification

- A completion claim requires reproducible evidence.
- A failed command is evidence and must be recorded, not hidden.
- Skipped checks must include the reason and the exact command to run later.
- `fast` is a Harness integrity check, not proof that all Penpot behavior works.
- `full` is required for release-grade completion unless a documented
  environment limitation blocks it.
- Source maps must be version 3, parseable, non-leaking, and traceable to
  embedded source content or an existing source file.

## State and handoff

- Persist long-running task status in `.harness/task-map.json`.
- Keep `.harness/PROGRESS.md` human-readable and evidence-oriented.
- Update `.harness/session-handoff.md` before stopping with unfinished work.
- Do not rely on chat history as the only record of current state.

## Security and privacy

- Never commit secrets, tokens, private absolute paths, or home-directory paths.
- Do not permit `file://`, HTTP(S), absolute, UNC, drive-letter, or escaping
  `../` source entries in production source maps.
- Do not execute generated code solely to inspect it.
- Keep CI permissions read-only unless a workflow explicitly needs more.

## Licensing

The Harness structure is adapted from general Harness Engineering practices and
the MIT-licensed `walkinglabs/learn-harness-engineering` reference repository.
This implementation and its wording are specific to Penpot.
