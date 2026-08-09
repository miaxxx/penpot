# Harness commands

## Initialize

```sh
./scripts/harness/init.sh
```

Reports the repository, branch, tool versions, expected versions, and Harness
integrity. It never installs dependencies.

## Validate Harness integrity

```sh
./scripts/harness/check.sh fast
```

## Validate changed frontend files

```sh
HARNESS_BASE_REF=develop ./scripts/harness/check.sh changed
```

The command fails rather than pretending success when a required changed-file
check cannot run.

## Full frontend validation

```sh
./scripts/harness/check.sh full
```

Run in the Penpot development environment. This invokes commands declared in
`frontend/package.json`.

## Record durable task state

```sh
node scripts/harness/record.mjs \
  --task canvas-selection \
  --status in-progress \
  --summary "Added selection state transition" \
  --command "cd frontend && pnpm run lint:clj" \
  --result "exit 0" \
  --next "Run focused frontend tests"
```

Use `--dry-run` to preview without modifying the file.

## Validate source maps

```sh
node scripts/harness/sourcemap-check.mjs --allow-empty frontend/target
```

For a release artifact:

```sh
node scripts/harness/sourcemap-check.mjs \
  --require-reference \
  --root frontend/target \
  frontend/target
```
