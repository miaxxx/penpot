# Session Handoff

## Current feature

`HARN-006` — Drive the next AI Design Agent slice through the Harness.

## Last known state

The Harness control plane is implemented. Structural scripts pass in the
isolated validation workspace. No application source files were changed by the
Harness upgrade.

## Resume steps

1. Read `AGENTS.md` and `.serena/memories/critical-info.md`.
2. Read the backend/common/frontend core memories for the selected slice.
3. Run `./init.sh --check`.
4. Build the AI profile source map.
5. Update `HARN-006` scope and acceptance criteria before application edits.
6. Execute checks through the evidence runner and commit evidence/state updates.

## Relevant files

- `.harness/feature-list.json`
- `.harness/PROGRESS.md`
- `.harness/checks.json`
- `.harness/tool-registry.json`
- `.harness/sourcemap.config.json`
- `docs/harness/ai-design-agent.md`

## Known risks

- Regex-based source extraction is a navigation aid, not an AST-level proof.
- Full Penpot module tests require the configured development environment.
- CI evidence must replace the provisional local structural evidence if results
  differ.

## Next command

```bash
./init.sh --check
```
