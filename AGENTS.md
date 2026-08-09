# Penpot Agent Entry

This file is a router, not the project manual. Load only the guidance needed
for the current task.

## Start

1. Read `.serena/memories/critical-info.md`.
2. Read the `<module>/core` memories for modules you will change.
3. Run `./init.sh --check --profile <core|frontend|backend|devenv>`.
4. For multi-step, cross-module, or resumed work, read
   `.harness/feature-list.json` and `.harness/STATUS.md`.
5. Use Source Map only when dependency discovery is difficult or the task spans
   unfamiliar modules. Normal source search remains the default.

Small isolated fixes do not need state-file updates unless they affect the
active feature or leave unfinished work.

## Work loop

`scope -> inspect -> plan -> edit -> run relevant registered checks -> review`

For long-running work, append: `record evidence -> update STATUS -> hand off`.

- Stay inside the requested scope. Record follow-up work instead of silently
  expanding the task.
- Run reproducible checks by ID with
  `node scripts/harness/evidence.mjs <check-id>`.
- Never claim completion from confidence alone.

## Hard invariants

- Never run destructive operations without explicit human approval.
- Never persist raw API keys, authorization headers, access tokens, or secrets.
- Do not touch unrelated modules or create unrelated formatting diffs.
- Preserve Penpot native change/undo transaction boundaries.
- AI Design Agent changes remain feature-flagged, validate Document/Patch DSL
  and Canonical Design IR before apply, and keep preview separate from apply.
- Source Map is optional derived navigation data, never a source of truth.

## Routing

| Need | Read |
|---|---|
| Core Harness model | `.harness/README.md` |
| Operational and safety rules | `.harness/RULES.md` |
| Current long-task state | `.harness/feature-list.json`, `.harness/STATUS.md` |
| Registered checks | `.harness/checks.json` |
| Tool policy | `.harness/tool-registry.json` |
| Extended Source Map guidance | `docs/harness/source-map.md` |
| AI Design Agent boundaries | `docs/harness/ai-design-agent.md` |
| Module engineering | `.serena/memories/<module>/core.md` |

## Common commands

```bash
./init.sh --check --profile core
./init.sh --check --profile frontend
./init.sh --check --profile backend
./init.sh --check --profile devenv
node scripts/harness/check.mjs --strict
node scripts/harness/test.mjs
node scripts/harness/evidence.mjs <check-id>
# Optional for cross-module discovery:
node scripts/harness/sourcemap.mjs build --profile ai
node scripts/harness/sourcemap.mjs query <term>
```
