# Harness Status

## Current task

`HARN-006` — deliver one bounded AI Design Agent capability using the simplified
Core Harness. Source Map is optional and should be used only when it improves
cross-module discovery.

## State

- The repository Harness has been reduced to a 12-file required core.
- Progress and session handoff are consolidated into this file.
- Environment initialization supports `core`, `frontend`, `backend`, and
  `devenv` profiles.
- Extended docs and Source Map remain available but are no longer structural
  prerequisites or mandatory startup steps.

## Evidence

- `harness-self-test`: run for the simplification change.
- `harness-validate`: run in strict mode for the simplification change.
- Module checks remain selected according to the application files changed.

## Risks

- The tool registry is a repository policy plus evidence-runner boundary; it
  does not replace host-level shell permissions.
- Source Map extraction is heuristic and must be confirmed against source.
- Full Penpot module tests require the corresponding local/devenv profile.

## Next step

Choose one concrete AI Design Agent capability, narrow `HARN-006` scope and
acceptance criteria, then run the matching backend/common/frontend checks.
