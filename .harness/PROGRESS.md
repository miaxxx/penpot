# Harness progress

Last updated: 2026-08-09  
Branch: `agent/penpot-ai-harness`

## Current milestone

Repository Harness v1: make agent work reproducible through instructions,
environment checks, durable state, executable feedback, and source-map
traceability.

## Completed in this milestone

- Kept Serena module memories as the architecture source of truth.
- Added concise agent entry points and progressive routing.
- Added five-system Harness documentation.
- Added non-destructive environment initialization.
- Added `fast`, `changed`, and `full` validation modes.
- Added fail-closed JavaScript source-map integrity validation.
- Added structured cross-session state recording.
- Added a read-only CI self-check workflow.

## Verification evidence

Run after checkout:

```sh
./scripts/harness/check.sh fast
```

The fast check includes:
- required-file checks;
- JSON parsing and task-map invariants;
- shell syntax checks;
- a valid source-map fixture that must pass;
- malformed and untraceable fixtures that must fail.

## Not claimed by this milestone

- A complete Penpot frontend production build has not been proven merely by
  the Harness self-check.
- Browser behavior and backend integration still require task-specific tests.
- Existing application-level AI Harness behavior must continue to be validated
  by its relevant frontend tests.

## Next actions

1. Run `changed` inside the Penpot development environment.
2. Attach application-level activity timeline exports to task-map evidence.
3. Add focused tests for any new UI that reads Harness activity/state.
4. Run `full` before release or merge.
