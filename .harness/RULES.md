# Harness Rules

## Scope

- Prefer one active long-running feature per worktree or agent session.
- Do not rewrite acceptance criteria to hide unfinished work.
- Record discovered follow-up work before expanding scope.
- Small isolated tasks may bypass feature-state updates when they do not affect
  active long-running work.

## State

- `feature-list.json` is the machine-readable long-task plan.
- `STATUS.md` combines progress, handoff, risks, evidence, and next action.
- Update STATUS after meaningful long-task milestones and before pausing
  unfinished work. Do not maintain duplicate progress/handoff files.

## Verification

- Prefer check IDs from `.harness/checks.json` for reproducible evidence.
- A skipped, unavailable, or timed-out check is not a pass.
- Use the smallest relevant check set; repository-wide checks are not mandatory
  for every isolated change.

## Safety

- Destructive commands remain human-gated.
- The evidence runner rejects destructive risk and denied command patterns.
- Do not persist secrets in state, evidence, maps, logs, or diffs.
- Commands outside the evidence registry may still be used for normal
  development, but they do not count as recorded completion evidence.
- Preview and apply remain separate for AI-generated Penpot changes.

## Completion

A task is complete when target behavior is implemented, relevant checks pass,
the diff stays in scope, and remaining risks are explicit. Long-running tasks
also require current STATUS and feature state.
