# Harness Engineering

- Root router: `AGENTS.md`; project knowledge remains in module memories.
- Start with the environment profile matching the task: `core`, `frontend`,
  `backend`, or `devenv`.
- Small isolated tasks: scope -> edit -> relevant checks -> review. State updates
  are optional unless active long-running work changes or remains unfinished.
- Long/resumed tasks: read `.harness/feature-list.json` and `.harness/STATUS.md`.
- One active feature per shared state file; separate worktrees may keep separate
  task state.
- Command policy: default deny for recorded verification. Checks run by ID from
  `.harness/checks.json`; destructive operations remain human-gated.
- Source Map is optional extended navigation for unfamiliar/cross-module work,
  not a startup requirement or source of truth.
- `done` requires relevant passing evidence and explicit residual risks.
- Secrets never enter Harness state, evidence, maps, logs, or diffs.
- AI Design Agent: preserve feature flag, SSRF-safe provider access,
  request-local credentials, DSL/IR validation, and separate reversible preview
  from native apply/undo transactions.
