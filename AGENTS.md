# Penpot Agent Entry

This file is a router, not the project manual. Read only the guidance required
for the active task, then work through the repository Harness.

## Start every session

1. Read `.serena/memories/critical-info.md`.
2. Identify every affected module and read each `<module>/core` memory.
3. Follow relevant `mem:` links before editing.
4. Read `.harness/feature-list.json`, `.harness/PROGRESS.md`, and
   `.harness/session-handoff.md`.
5. Run `./init.sh --check` and resolve or record environment failures.
6. Use `node scripts/harness/sourcemap.mjs query <term>` to locate symbols and
   dependencies before broad code search when the map exists.

## Work loop

`read rules -> initialize -> select one feature -> inspect -> plan -> edit ->
run registered checks -> record evidence -> update state -> hand off`

- Work on one active feature unless ownership is explicitly split.
- Keep changes inside the feature scope. Record newly discovered work instead
  of silently expanding the task.
- Run checks by ID through `node scripts/harness/evidence.mjs <check-id>`.
- A feature may be `done` only when every acceptance criterion has passing,
  reproducible evidence.
- Update `.harness/PROGRESS.md` and `.harness/session-handoff.md` before ending
  a long-running session.

## Hard invariants

- Chat history is not project state; repository files are.
- Confidence is not evidence. Never claim completion without command results.
- Never run destructive commands or bypass the tool registry without explicit
  user approval.
- Never persist raw API keys, authorization headers, access tokens, prompts
  containing secrets, or provider responses containing credentials.
- The source map is a derived navigation index, not a second source of truth.
- Preserve Penpot's native change/undo transaction boundaries.
- AI Design Agent changes remain feature-flagged, validate Document/Patch DSL
  and Canonical Design IR before apply, and keep preview separate from apply.
- Do not touch unrelated modules. Avoid unrelated formatting diffs.

## Routing

| Need | Read |
|---|---|
| Harness model and file map | `.harness/README.md` |
| Operational rules | `.harness/RULES.md` |
| Allowed tools and commands | `.harness/TOOLS.md`, `.harness/tool-registry.json` |
| Verification and evidence | `.harness/CHECKS.md`, `.harness/checks.json` |
| Detailed workflow | `docs/harness/workflow.md` |
| Source-map index | `docs/harness/source-map.md` |
| AI Design Agent boundaries | `docs/harness/ai-design-agent.md` |
| Security and permissions | `docs/harness/security.md` |
| Module-specific engineering | `.serena/memories/<module>/core.md` |

## Common commands

```bash
./init.sh --check
node scripts/harness/check.mjs --strict
node scripts/harness/test.mjs
node scripts/harness/sourcemap.mjs build --profile ai
node scripts/harness/sourcemap.mjs query <symbol-or-path>
node scripts/harness/evidence.mjs <check-id>
```
