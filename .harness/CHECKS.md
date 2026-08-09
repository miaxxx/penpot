# Verification and Evidence

`.harness/checks.json` is the command registry. Each check defines an ID,
working directory, command, risk class, expected use and timeout.

Run a check:

```bash
node scripts/harness/evidence.mjs harness-self-test
node scripts/harness/evidence.mjs harness-validate
node scripts/harness/evidence.mjs backend-ai-tests
```

Evidence is written under `.harness/evidence/` by default. It contains the
command ID, exact registered command, timestamps, exit code, git revision and
bounded output. It never captures environment variables.

## Result rules

- Exit code `0` is a pass.
- A command that could not start is a failure.
- Timeout is a failure.
- Manual observation must be labeled `manual`; it cannot replace automated
  evidence when an automated check exists.
- Do not mark a feature `done` with empty or stale evidence.

Full monorepo lint/format checks are expensive. Run focused module checks while
iterating, then the required cross-module checks before completion.
