# Harness Rules

## Scope

- Keep at most one `in_progress` feature unless `feature-list.json` contains
  explicit, non-overlapping ownership.
- Do not rewrite acceptance criteria to make unfinished work appear complete.
- Record discoveries as planned work or blockers before expanding scope.

## State

- `feature-list.json` is the machine-readable plan.
- `PROGRESS.md` is the current human-readable execution record.
- `session-handoff.md` is the restart path for the next session.
- Update state after meaningful milestones and before ending a long session.
- Chat summaries may supplement these files but never replace them.

## Verification

- Use check IDs from `.harness/checks.json`.
- Run checks with `node scripts/harness/evidence.mjs <check-id>`.
- `done` requires passing evidence for all acceptance criteria.
- A skipped, unavailable or timed-out check is not a pass.
- Record the exact command, working directory, result and relevant failure.

## Safety

- Commands marked `destructive` are never executed by the Harness.
- Commands outside the registry require explicit human approval.
- Do not persist secrets in state, evidence, source maps, logs or diffs.
- Generated source maps must sanitize traversal paths and ignore vendored/build
  directories.
- Preview and apply are separate operations for AI-generated Penpot changes.

## Completion

A feature is complete only when:

1. Acceptance criteria are satisfied.
2. Required checks pass.
3. Diff and scope are reviewed.
4. State and handoff files are current.
5. Remaining risks are explicit.
