# Verification Model

Verification is part of the implementation loop, not a final decoration.

## Gates

1. Harness structure and schemas.
2. Focused tests for changed namespaces.
3. Module lint and formatting.
4. Cross-module regression checks when shared semantics change.
5. Runtime/manual checks for behavior not covered by automation.
6. CI and human review.

A feature cannot become `done` while any required automated gate is failed,
unavailable, timed out or unrecorded.

## Evidence quality

Good evidence names the registered check, exact revision, working directory,
timestamps and exit code. Output is bounded to keep repository state useful.
Secrets and complete environment dumps are forbidden.

The structural Harness benchmark only proves that the control plane is wired
correctly. It does not prove that the AI Design Agent works end-to-end.
