# Definition of done

A Harness-managed task is done only when all applicable statements are true.

## Behavior

- The requested behavior is implemented.
- Scope and non-goals are explicit.
- No unrelated module was changed to mask a problem.

## Evidence

- The minimum check from `.harness/CHECKS.md` ran.
- The exit code and meaningful result are recorded.
- Failed or skipped checks are visible with reasons.
- Generated artifacts are traceable to their sources.
- Strict source-map checks pass when build artifacts include maps.

## Repository state

- Long-running state is in `.harness/task-map.json`.
- Human progress is current.
- The handoff contains an exact next command when work is unfinished.
- No secret or private absolute path was added.

## Claims

Do not use these as completion evidence:

- “It should work.”
- “The code looks correct.”
- “The agent is confident.”
- “A similar test passed before.”
- “The build was not run, but the change is small.”

Use command output, tests, builds, diffs, logs, screenshots, or another
reproducible artifact.
