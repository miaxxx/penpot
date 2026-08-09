# Verification matrix

## Modes

### Fast

```sh
./scripts/harness/check.sh fast
```

Checks required Harness files, JSON validity, shell syntax, the source-map
validator, and positive/negative source-map fixtures. This is safe to run
without installing Penpot dependencies.

### Changed

```sh
HARNESS_BASE_REF=develop ./scripts/harness/check.sh changed
```

Runs `fast`, inspects the Git diff, and selects frontend format/lint checks for
the changed file types. It fails when a required tool or dependency is absent;
it does not silently downgrade.

### Full

```sh
./scripts/harness/check.sh full
```

Runs Harness integrity plus the repository's frontend format, lint, release
build, and test commands. Use the Penpot development environment for
reproducibility.

## Evidence format

Record:

- command;
- exit status;
- meaningful output or artifact path;
- date/time;
- skipped checks and reason;
- next executable command when blocked.

## Risk routing

| Change type | Minimum check |
| --- | --- |
| Harness docs or JSON only | `fast` |
| Harness shell/Node script | `fast` including self-test |
| Frontend ClojureScript | `changed` plus relevant tests |
| Build pipeline or source maps | `changed` and strict source-map scan |
| Release candidate | `full` and CI status |

## Source-map scan

Development scan that allows no map files:

```sh
node scripts/harness/sourcemap-check.mjs --allow-empty frontend/target
```

Strict artifact scan:

```sh
node scripts/harness/sourcemap-check.mjs \
  --require-reference \
  --root frontend/target \
  frontend/target
```
