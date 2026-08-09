# Checks and Evidence

Run recorded checks by ID:

```bash
node scripts/harness/evidence.mjs <check-id>
```

Use the smallest relevant check set. Harness self-tests validate the control
files; they do not prove application behavior. Application completion requires
module-specific lint, format, tests, or runtime checks as appropriate.

Environment readiness is profile-based:

```bash
./init.sh --check --profile core
./init.sh --check --profile frontend
./init.sh --check --profile backend
./init.sh --check --profile devenv
```
