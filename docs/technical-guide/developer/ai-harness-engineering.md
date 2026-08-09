# Penpot AI Harness Engineering

## Purpose

This subsystem is a clean-room implementation of a rigorous agent harness for
Penpot. It reproduces the useful capability boundaries of modern coding-agent
harnesses without copying reconstructed or proprietary third-party source code.

The Harness does not replace Penpot's unified AI operation kernel. It sits above
it:

```text
Assistant / Buddy / Voice / Vim / Remote / MCP / Plugin
                         |
                         v
              Harness session and trace
                         |
       Skills + Commands + Context + Coordinator
                         |
                         v
              Document/Patch DSL Proposal
                         |
                         v
       Preview -> Penpot confirmation -> native Change/Undo
```

Every canvas write still ends at the single native transaction gateway:

```text
app.main.data.workspace.ai.execution/apply-proposal
```

## Capability modules

| Module | Penpot implementation |
| --- | --- |
| Tools | Existing transport-neutral Tool/Capability Registry |
| Commands | Slash commands and bounded Vim-style commands |
| Services | Provider, Proposal, Harness session and orchestration services |
| Utils | Package limits, safe ZIP paths, SHA-256 and context bounding |
| Context | Priority compartments, history compaction and budget report |
| Coordinator | Planner, designer/editor, accessibility reviewer, verifier, executor |
| Assistant | Default precise embedded assistant persona |
| Buddy | Friendly persona with identical permissions and confirmation |
| Remote | Authenticated session adapter; no public listener in this patch |
| Plugins | Declarative skills, commands, hooks, personas and MCP references |
| Skills | Built-ins plus uploaded SKILL.md/JSON/ZIP packages |
| Voice | Transcript input mode; STT transport remains external |
| Vim | Safe `:w`, `:q`, `:skills`, `:plugins`, `:context`, `:plan` controls |

## Skill packages

Supported upload forms:

1. A Markdown file containing instructions.
2. A JSON manifest.
3. A bounded ZIP containing `SKILL.md` and an optional `manifest.json` or
   `skill.json`.

Example:

```markdown
---
name: Product Card Audit
version: 1.0
description: Audit product cards and produce a minimal Patch DSL.
tools: ["canvas.read", "proposal.create-patch"]
capabilities: ["canvas/read", "proposal/create"]
keywords: ["card", "audit", "spacing"]
autoActivate: true
---

Inspect the selected product-card component. Preserve its identity and variants.
Fix hierarchy, spacing, alignment and readable type with a minimal Patch DSL.
```

The ZIP parser enforces:

- maximum 2 MB package size;
- maximum 64 entries;
- maximum 256 KB per file;
- no absolute paths or `..` traversal;
- text-only extraction into the database;
- no script execution.

A skill may request only registered tools. Unknown tool IDs reject installation.
Skills add instructions and capability requirements to the model context; they do
not receive direct JVM, JavaScript, shell, filesystem or network execution.

## Declarative plugins

Plugins use JSON manifests and may contribute:

- skill descriptors;
- command descriptors;
- before-turn prompt hooks;
- personas;
- references to already registered MCP tools.

Plugins cannot load executable code. Any future executable plugin runtime must
be separately sandboxed and must still call the same Proposal and native
transaction services.

## Sessions and traces

`ai_harness_session` records:

- authenticated owner;
- file/page;
- base revision;
- Scope;
- mode;
- transport;
- input mode;
- persona;
- coordinator and context settings;
- compacted summary;
- lifecycle and expiry.

`ai_harness_run` records:

- input text and mode;
- selected skills;
- command;
- coordinator plan;
- context report;
- structured trace;
- Proposal ID;
- control/result data;
- completion status.

API keys are never stored in these tables.

## Context engineering

Context is assembled into ordered compartments:

1. live bounded canvas context;
2. session identity, Scope and revision;
3. selected skills;
4. declarative plugin hooks;
5. coordinator plan;
6. command state;
7. compact recent history.

The default budget is 24,000 estimated tokens. The report exposes character
usage and which compartments were truncated or omitted.

## Coordinator

Coordinator mode is an optional sequential specialist workflow:

1. planner;
2. visual designer or structure editor;
3. optional accessibility reviewer;
4. verifier;
5. executor.

Only the executor may produce final DSL. Even the executor has no native commit
tool. All roles inherit the same file identity, Scope, base revision and
Capability Registry. The verifier can reject invented IDs/assets/tokens, Scope
escalation, revision drift or unnecessary whole-page rewrites.

## Commands

Natural-language turns may be mixed with:

```text
/help
/skills
/plugins
/context
/compact
/plan
/coordinator on
/coordinator off
/apply
/discard
/voice <transcript>
/remote
/vim :w
```

Safe Vim commands:

```text
:w          request UI application of the latest Proposal
:q          close the Harness session
:wq         request application and close the session
:skills     list skills
:plugins    list plugins
:context    show context report
:compact    compact old traces
:plan       enable coordinator
:noplan     disable coordinator
```

These commands never bypass the Proposal lifecycle.

## Remote and voice boundaries

The code includes input/session adapters, not a public remote listener or speech
recognition engine.

A production remote transport must add:

- authenticated session establishment;
- origin and CSRF checks;
- rate limits;
- revocation;
- trusted workspace bridge routing;
- JSON-RPC or WebSocket error mapping.

Voice mode accepts a trusted transcript. Microphone capture and STT may be added
in the client, but the resulting text must enter the same Harness turn endpoint.

## Security invariants

- Identity comes from authenticated RPC/MCP/remote transport state.
- File permissions use Penpot's native permission checks.
- Scope and base revision are immutable session boundaries.
- Uploaded packages are data only.
- Skills and plugins cannot register `native.commit`.
- MCP and remote adapters return Proposal IDs, never canvas-write success.
- Apply requires a one-time token and live object revalidation.
- One Proposal maps to one native Penpot Undo transaction.
- Provider credentials remain request-scoped and are not persisted in Harness
  sessions or traces.

## Database migration

Apply:

```text
backend/src/app/migrations/sql/0153-add-ai-harness-tables.sql
```

The migration creates:

- `ai_harness_skill`
- `ai_harness_plugin`
- `ai_harness_session`
- `ai_harness_run`

## Validation checklist

Before merging:

1. Run backend migration tests.
2. Run `common-tests.ai-harness-test`.
3. Run `backend-tests.ai-harness-archive-test`.
4. Run `backend-tests.ai-harness-contract-test`.
5. Compile the CLJS AI sidebar and upload:
   - one Markdown skill;
   - one JSON skill;
   - one ZIP skill;
   - one declarative plugin.
6. Confirm a selected skill appears in the Harness context report.
7. Confirm coordinator mode performs planner and verifier calls.
8. Confirm `/apply` only records UI confirmation request.
9. Confirm Apply creates exactly one Undo transaction.
10. Confirm MCP cannot list or invoke `native.commit`.

## Source and licensing note

The requested reference repository describes itself as an unofficial,
source-map-reconstructed copy of Anthropic software, for research only, and does
not publish an open-source license. No source file from that repository is
copied into this implementation. The module names and capability categories are
used only as high-level functional requirements; all Penpot code is independently
implemented under this repository's MPL-2.0 licensing.
