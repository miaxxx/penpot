# Harness Workflow

## Small isolated task

1. Read the relevant module memory.
2. Run the matching environment profile.
3. Inspect, edit, and run the smallest relevant checks.
4. Review scope and residual risk.

No STATUS update is required unless the task changes active long-running work or
ends unfinished.

## Long, cross-module, or resumed task

1. Read `feature-list.json` and `STATUS.md`.
2. Confirm task scope and owner.
3. Run the matching environment profile.
4. Inspect and plan; optionally build/query Source Map if useful.
5. Edit, run registered checks, record evidence, and update STATUS.

Source Map is never a mandatory startup gate.
