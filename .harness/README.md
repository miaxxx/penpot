# Penpot Harness

The Harness is the repository control plane around coding agents. It does not
replace Penpot architecture, Serena memories, tests, or human review. It makes
their use explicit and repeatable.

## Five systems

1. **Instructions** — `AGENTS.md`, `CLAUDE.md`, `.serena/memories/**`,
   `.harness/RULES.md`.
2. **Tools** — `.harness/tool-registry.json` and registered command execution.
3. **Environment** — `init.sh` and `scripts/harness/init.mjs`.
4. **State** — `feature-list.json`, `PROGRESS.md`, `session-handoff.md`.
5. **Feedback** — `checks.json`, evidence records, CI, tests, lint, format and
   source-map validation.

## Canonical locations

- `AGENTS.md`: short startup router and invariants.
- `.serena/memories/`: durable Penpot module knowledge.
- `.harness/`: active operational state and machine-readable policy.
- `docs/harness/`: explanations and maintenance guidance.
- `scripts/harness/`: dependency-free executable control plane.
- `.harness/generated/`: derived source-map artifacts; never source of truth.
- `.harness/evidence/`: optional committed evidence for important milestones.

## Session protocol

```text
read -> initialize -> choose one feature -> inspect/map -> plan -> edit
     -> verify by check ID -> record result -> update state -> handoff
```

Start with `./init.sh --check`. The command is deliberately non-destructive: it
inspects versions, repository state, active work, registered tools and Harness
integrity. It never installs dependencies, starts services, or deletes data.
