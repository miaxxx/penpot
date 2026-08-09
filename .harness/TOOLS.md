# Harness tool map

## Repository inspection

- `git status --short --branch`
- `git diff --stat`
- `git diff --name-only <base>...HEAD`
- Serena memory tools, or direct reads under `.serena/memories/`

## Environment and startup

- `./scripts/harness/init.sh` — inspect the local environment and run Harness
  self-checks.
- `./manage.sh run-devenv` — Penpot's containerized development environment.
- `./manage.sh attach-devenv` — attach to a running development environment.
- `./manage.sh start-coding-agent` — start a configured coding agent in the
  development environment.

## Validation

- `./scripts/harness/check.sh fast` — repository Harness integrity and
  source-map checker self-tests.
- `./scripts/harness/check.sh changed` — fast checks plus checks selected from
  changed frontend file types.
- `./scripts/harness/check.sh full` — frontend format, lint, build, and tests.
- `node scripts/harness/sourcemap-check.mjs ...` — fail-closed source-map
  integrity validation.

## State

- `node scripts/harness/record.mjs ...` — append a structured event to
  `.harness/task-map.json`.
- `.harness/PROGRESS.md` — concise human status and evidence.
- `.harness/session-handoff.md` — next-session entry point.

## Frontend commands

Run from `frontend/`:

- `pnpm run check-fmt:clj`
- `pnpm run lint:clj`
- `pnpm run check-fmt:js`
- `pnpm run check-fmt:scss`
- `pnpm run lint:scss`
- `pnpm run build:app`
- `pnpm run test`

Use `corepack pnpm` only when the local environment intentionally manages pnpm
through Corepack. The repository declares pnpm 11.7.0.
