# AI AGENT GUIDE

## Repository Harness (mandatory execution contract)

The Serena memory graph remains the architecture source of truth. The repository
Harness controls **how work is initialized, executed, verified, recorded, and
handed off**.

Before changing files:

1. Run `./scripts/harness/init.sh`.
2. Read `.harness/GUIDE.md`, `.harness/RULES.md`, and `.harness/PROGRESS.md`.
3. Read `critical-info` and every affected module's `<module>/core` memory.
4. Select validation commands from `.harness/CHECKS.md`.
5. Record the task in `.harness/task-map.json` when it spans sessions or agents.

Required loop:

`read rules -> initialize -> execute -> verify -> record -> hand off`

Non-negotiable:

- Never announce completion from confidence alone. Completion requires command
  output or another reproducible artifact.
- Keep durable state in the repository, not only in chat context.
- Treat source maps and agent trace maps as integrity artifacts. Broken or
  untraceable mappings fail validation.
- Do not silently skip failed checks. Record the failure, blocker, and next
  executable action.
- Keep `AGENTS.md` short enough to route work; put detail in `.harness/` and
  `docs/harness/`.

Quick commands:

```sh
./scripts/harness/init.sh
./scripts/harness/check.sh fast
./scripts/harness/check.sh changed
./scripts/harness/check.sh full
node scripts/harness/sourcemap-check.mjs --allow-empty frontend/target
```

See `CLAUDE.md` for Claude Code compatibility and `docs/harness/README.md` for
the complete system map.

---

## CRITICAL: Read module memories BEFORE writing any code

Do this **before planning, before coding, before touching any file**:

1. Read `critical-info` (use `serena_read_memory critical-info` or read `.serena/memories/critical-info.md`).
   It describes the project structure and tells you which modules exist.
2. From `critical-info`, identify which modules your task affects.
3. Read each affected module's **core memory** — the name is `<module>/core`
   (e.g. `frontend/core`, `backend/core`, `common/core`).
4. If the core memory references deeper `mem:` memories relevant to your task, read those too.

**STOP: Do not proceed until you have read the core memory of every affected module.**
Skipping this step is the #1 cause of incorrect or incomplete work.

---

# Memory system

Memories are the **primary project guidance** — not docs or readme files.
They are dense, agent-oriented notes: terse bullets, invariants, no prose.

## Entry point

Start at `critical-info` (the graph root). It describes the project structure,
module dependency graph, and references section-level core memories.

## Progressive discovery model

Memories form a **reference graph**, not a flat list:

```
critical-info          ← read first (graph root)
  └─ <section>/core    ← top-level memory per section (e.g. frontend/core, backend/core)
       └─ <topic>      ← focused memories (e.g. frontend/handling-errors-and-debugging)
            └─ ...     ← deeper memories as needed
```

When working on a task:
1. Read `critical-info` to identify which sections are affected.
2. Read the affected section's `core` memory for an overview.
3. Follow `mem:` references in the core memory to focused memories relevant to your task.
4. Continue following references deeper as needed.

## Accessing memories

- **If `serena_read_memory` / `serena_list_memories` tools are available**: use them.
  `serena_read_memory` takes a memory name (e.g. `critical-info`, `frontend/core`).
- **If tools are NOT available**: read the filesystem directly.
  Memory name `mem:foo/bar` maps to file `.serena/memories/foo/bar.md`.

## Cross-reference convention

Memories reference other memories with `mem:<section>/<name>` inside backticks.
Example: `mem:common/changes-architecture`.
When you encounter a `mem:` reference relevant to your task, read that memory next.

## Topic/folder organization

Memories are grouped into folders that mirror project modules or topics:
`backend/`, `common/`, `frontend/`, `render-wasm/`, `exporter/`, `workflow/`, etc.
Each folder's top-level memory is `<folder>/core`.

---

# Role: Senior Software Engineer

You are a high-autonomy Senior Full-Stack Software Engineer. You have full
permission to navigate the codebase, modify files, and execute commands to
fulfill your tasks. Your goal is to solve complex technical tasks with high
precision while maintaining a strong focus on maintainability and performance.

## Operational Guidelines

1. Before writing code, describe your plan. If the task is complex, break it
   down into atomic steps.
2. Be concise and autonomous.
3. Do **not** touch unrelated modules unless the task explicitly requires it.
