---
title: Repository-driven AI Harness 2.0
desc: Instructions, tools, environment, state and evidence-based feedback for the Penpot AI agent.
---

# Repository-driven AI Harness 2.0

Penpot's embedded AI assistant is not a free-form chat wrapper. It runs inside a
persistent engineering harness that makes its rules, tools, environment, state
and completion evidence explicit.

This implementation combines two sources of architectural inspiration:

- the general harness boundaries visible in modern coding agents, implemented
  independently as a clean-room Penpot design;
- the MIT-licensed templates and educational model from
  `walkinglabs/learn-harness-engineering`.

No restored or source-map-derived Anthropic implementation code is copied into
Penpot. Uploaded Skills, Plugins and Harness packages are declarative data and
are never executed as JavaScript, Clojure, shell or arbitrary plugin source.

## Five systems

### 1. Instruction system

Each Harness workspace contains repository-style artifacts:

```text
AGENTS.md
.harness/GUIDE.md
.harness/RULES.md
.harness/TOOLS.md
.harness/CHECKS.md
.harness/ENVIRONMENT.md
PROGRESS.md
session-handoff.md
feature_list.json
```

`AGENTS.md` is deliberately short. It is a router to detailed documents, not a
large prompt that attempts to contain every rule.

The server applies progressive disclosure. A basic generation task loads the
entry, guide, rules and tools. Modify/refactor tasks also load progress and
feature state. Continue/resume tasks load the handoff. Verification tasks load
checks. Remote/MCP/plugin tasks load environment rules.

### 2. Tool system

The shared Tool/Capability Registry remains the source of truth. Internal AI,
RPC, MCP and plugins do not receive separate implementations.

- canvas reads require an authenticated live workspace bridge;
- writes produce Document or Patch DSL and a persistent Proposal;
- external MCP/plugin callers receive a `proposalId`;
- only the internal `native.commit` capability can create Penpot Change data;
- uploaded packages cannot add arbitrary executable tools.

### 3. Environment system

Before a model turn, Penpot stores an environment snapshot and checks:

- AI feature flag;
- current file Revision;
- trusted workspace context;
- provider configuration;
- MCP availability;
- exactly one internal native-commit capability;
- bounded context budget.

Blocking failures stop execution before a provider request. Warning failures
mark the environment degraded and remain visible in the run state.

### 4. State system

Chat history is not treated as durable project state. A Harness workspace and
run persist:

- one scoped goal;
- completed work;
- remaining work;
- blockers;
- current status;
- next action;
- Proposal identity;
- verification results;
- completion data;
- session handoff.

`PROGRESS.md` and `session-handoff.md` are generated from this canonical state.
They can be exported in a Harness package, but the database remains the source
of truth inside Penpot.

### 5. Feedback system

A model cannot declare success based on confidence. Completion is controlled by
an executable evidence gate.

Required checks currently include:

```text
environment.feature-flag
environment.file-revision
environment.workspace-bridge
context.within-budget
proposal.dsl-valid
proposal.scope-valid
proposal.preview-compiled
proposal.native-shapes-valid
proposal.undo-ready
registry.references-resolved
mcp.policy-isolated
proposal.transaction-applied
```

Preview compilation automatically records the compiler/shape/undo evidence.
The final transaction check runs only after the user confirms Apply and Penpot
records a native transaction ID. Missing, failed, running or skipped required
checks all block completion.

## Runtime lifecycle

```text
Read routed Harness artifacts
        ↓
Inspect environment
        ↓
Create persistent Harness Run
        ↓
Planner / Designer / Verifier orchestration
        ↓
Document or Patch DSL
        ↓
Persistent Proposal
        ↓
Native preview compilation
        ↓
Pre-apply checks + Diff evidence
        ↓
Penpot UI confirmation
        ↓
Single native Change/Undo transaction
        ↓
Transaction evidence check
        ↓
Completion Gate
        ↓
PROGRESS + session handoff
```

A canvas conflict moves the run to `blocked`, records the blocker and writes a
handoff instructing the next agent session to refresh context and rebuild the
Proposal from the current Revision.

## Workspace package

The export format is data-only:

```json
{
  "format": "penpot-ai-harness",
  "version": "2.0",
  "workspace": {
    "name": "Penpot AI Harness",
    "version": "2.0",
    "settings": {}
  },
  "artifacts": [
    {
      "path": "AGENTS.md",
      "kind": "entry",
      "contentType": "text/markdown",
      "content": "...",
      "required": true,
      "readOrder": 10
    }
  ]
}
```

Imports enforce bounded file count, path validation, per-artifact size and total
package size. Paths cannot be absolute, contain traversal segments or contain
arbitrary characters. Imported content never obtains code execution authority.

## Data model

Migration `0154-add-ai-harness-workspaces.sql` adds:

- `ai_harness_workspace`;
- `ai_harness_artifact`;
- `ai_harness_environment_snapshot`;
- `ai_harness_check_result`;
- long-running state columns on `ai_harness_run`.

Workspace/file access reuses Penpot's existing read and edition permission
checks. Actor identity comes from the authenticated transport and is never
accepted from model output or tool arguments.

## RPC boundary

`invoke-ai-harness-repository` exposes one authenticated action gateway. Its
action allowlist covers workspace, artifact, package, environment, checks and
run lifecycle operations.

The legacy `run-ai-harness-turn` command is retained for UI compatibility, but
its implementation now enters `turn.run` and therefore follows the Repository
Harness lifecycle.

Generic progress updates cannot set `completed`. Only `run.complete` can do so,
and that operation invokes the evidence gate.

## Attribution

The concepts and template structure adapted from
`walkinglabs/learn-harness-engineering` are used under the MIT License. Exported
packages include an attribution notice.

The earlier `ChinaSiro/claude-code-sourcemap` repository is an unofficial
research reconstruction without a reusable open-source license. It was used
only to understand broad capability boundaries. Penpot's implementation is
independently written and does not reproduce those restored source files.

## Validation before merge

The branch includes pure contract tests for routing, safe paths, singular native
commit authority and completion gating. The database migration and the full
backend/frontend integration must still be executed in a Penpot development
environment or CI before the draft PR can be merged.
