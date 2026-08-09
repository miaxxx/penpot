# Harness Engineering

- Root router: `AGENTS.md`; detailed project knowledge remains in module
  memories and `docs/harness/`.
- Session start: read active feature/progress/handoff; run `./init.sh --check`.
- One active feature by default. Scope, dependencies, acceptance, owner and
  required checks are explicit in `.harness/feature-list.json`.
- Navigation: build/query the focused repository source map, then verify against
  source; map output is derived and ignored by git.
- Command policy: default deny. Execute checks by ID from
  `.harness/checks.json`; never pass arbitrary shell through the evidence tool.
- Feedback loop: edit -> registered check -> evidence -> state update. Failed or
  unavailable checks are not completion evidence.
- Long task state lives in `.harness/PROGRESS.md` and
  `.harness/session-handoff.md`, not chat.
- `done` requires acceptance criteria, passing evidence, scope review, current
  handoff and explicit residual risks.
- Secrets never enter Harness state/evidence/maps.
- Destructive operations remain human-gated.
- AI Design Agent: preserve feature flag, SSRF-safe provider access,
  request-local credentials, DSL/IR validation, and separate reversible preview
  from native apply/undo transactions.
