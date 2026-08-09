# Harness Progress

## Current goal

Use the repository Harness to implement the next bounded AI Design Agent
capability without relying on chat context or unregistered verification.

## Completed

- Installed concise AGENTS/CLAUDE entry routing while preserving Serena memory
  discovery.
- Added machine-readable feature state, progress and session handoff.
- Added a permission-aware tool/check registry and evidence recorder.
- Added a dependency-free repository source-map builder and query CLI.
- Added structural self-tests and a focused GitHub Actions Harness gate.
- Documented AI Design Agent transaction, credential and validation invariants.

## In progress

`HARN-006` — select and deliver one bounded AI Design Agent slice using the
Harness from initialization through evidence and handoff.

## Pending

- Build the focused AI source map in a full local checkout.
- Choose the next capability from the current AI Design Agent backlog.
- Run the module-specific Clojure/ClojureScript checks for that capability.
- Attach CI results to the feature evidence after the pull request runs.

## Blockers

- This Harness change was assembled through the GitHub connector rather than a
  full Penpot development environment, so heavyweight Penpot builds and module
  test suites have not been executed in this session.

## Verification evidence

- `harness-self-test`: PASS in an isolated generated workspace.
- `harness-validate`: PASS in strict mode in the same workspace.
- Full Penpot lint, format, Clojure tests and browser checks: NOT RUN for this
  Harness-only change; CI and a configured Penpot devenv remain authoritative.

## Next action

In a full checkout, run:

```bash
./init.sh --check
node scripts/harness/sourcemap.mjs build --profile ai
node scripts/harness/sourcemap.mjs query app.common.ai
node scripts/harness/evidence.mjs harness-self-test
node scripts/harness/evidence.mjs harness-validate
```

Then update `HARN-006` with the selected capability, exact scope and required
focused checks before editing application code.
