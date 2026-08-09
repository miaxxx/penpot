---
title: Unified AI design operation kernel
desc: Shared Proposal, Tool Registry, MCP and native transaction architecture.
---

# Unified AI design operation kernel

Penpot's internal AI sidebar, authenticated RPC clients, MCP clients and future
plugin adapters use one design-operation kernel. Entry points may differ in
transport and user experience, but they do not implement separate canvas write
paths.

```text
Internal AI Tab ─┐
RPC client ──────┼── Tool/Capability Registry
MCP transport ──┤            │
Plugin adapter ─┘            ▼
                       Proposal Service
                             │
              Schema / Scope / Revision / Permission
                             │
                     Local Canvas Compiler
                             │
                       Preview + Diff
                             │
                     Penpot UI confirmation
                             │
                 Native Change Transaction Gateway
                             │
                  Penpot Change[] + one Undo entry
```

## Invariants

1. Every design write starts as Document or Patch DSL.
2. Every write creates a persistent proposal ID before it can be previewed.
3. Actor identity is injected by the authenticated transport and is never read
   from tool arguments.
4. File permissions use Penpot's existing file read/edit permission functions.
5. Scope and base revision are revalidated at creation, preview and apply.
6. MCP can create, list, read, discard and request application of proposals,
   but cannot begin, complete or directly perform a native commit.
7. Native canvas mutation exists in one frontend gateway only and uses native
   Penpot Change/Undo infrastructure.
8. An AI proposal is never considered applied until the native transaction
   callback reports a transaction ID.

## Persistent proposal model

The `ai_design_proposal` table stores:

- authenticated owner profile;
- file and page identity;
- entry-point origin;
- design mode and DSL type;
- base file revision;
- declared Scope;
- user-visible plan;
- validated Document/Patch DSL;
- preview Diff summary;
- lifecycle state and errors;
- short-lived apply lock token;
- resulting native transaction ID;
- expiry and lifecycle timestamps.

It deliberately does not store the complete live Penpot object graph. Unsaved
workspace objects remain client-side. The current Penpot workspace recompiles a
proposal against its live objects, creates a preview, performs stale-object
checks and submits native Changes.

### State machine

```text
validated ── previewed ── applying ── applied
    │             │           │
    ├─ discarded  ├─ discarded├─ conflicted
    └─ expired    ├─ conflicted└─ expired
                  └─ expired
```

Invalid transitions are rejected under a database advisory transaction lock.
Active proposals expire after one hour by default.

## Tool and Capability Registry

`app.common.ai.tools` is the source of truth for tools exposed to each
transport. Every entry contains:

- stable tool ID and version;
- description;
- access class;
- capability;
- allowed transports;
- confirmation policy;
- result type;
- JSON input schema.

`native.commit` is the only tool with `canvas/commit` capability and is allowed
only for the internal transport.

MCP descriptors are generated from this registry, including MCP-compatible
`name`, `description`, `inputSchema` and annotations. Internal RPC and future
plugin adapters read the same definitions.

## Identity and permissions

The authenticated transport injects `profile-id`. Proposal and MCP arguments do
not accept an owner/profile field.

Read operations call Penpot file read-permission checks. Proposal creation,
preview, discard and apply lifecycle operations call file edition-permission
checks. A proposal belonging to another profile returns the same not-found
behavior as a missing object so its existence is not disclosed.

## Scope policy

Supported scopes are:

- `selection`;
- `page`;
- `component`.

Selection and component operations require a root or explicit selection IDs.
Patch DSL Scope must match the proposal Scope. Selection/component Patch roots
must match exactly. Operations outside the resolved Scope are rejected by the
canvas Patch compiler.

## Revision policy

Proposal `baseRevision` must equal the current Penpot file revision at proposal
creation, preview and begin-apply. The live workspace also compares affected
objects against the preview base objects immediately before native commit. This
provides both file-level and object-level conflict protection.

## MCP adapter

The transport-neutral MCP adapter supports:

- `initialize`;
- `ping`;
- `tools/list`;
- `tools/call`.

A concrete HTTP/SSE/stdio transport is responsible for authentication and must
pass a trusted actor map. For live canvas reads, it must also supply a bounded
workspace-context bridge produced by the Penpot client; arbitrary context sent
inside MCP arguments is not trusted.

MCP write tools return a persistent `proposalId` and confirmation metadata.
They never return direct canvas-write success.

Example:

```json
{
  "name": "proposal.create-patch",
  "arguments": {
    "fileId": "…",
    "pageId": "…",
    "baseRevision": 184,
    "mode": "modify",
    "scope": {
      "type": "selection",
      "rootId": "pain-points-section"
    },
    "dsl": {
      "dslVersion": "1.0",
      "baseRevision": 184,
      "scope": {
        "type": "selection",
        "rootId": "pain-points-section"
      },
      "operations": []
    }
  }
}
```

The result contains `proposalId`, `status`, `baseRevision`, Scope and the
validated DSL. The Penpot workspace lists active proposals, recompiles the
selected proposal against live objects and presents Apply/Discard controls.

## Preview and apply

The workspace follows this sequence:

1. receive or load a persistent proposal;
2. validate and normalize its DSL;
3. compile against a lossless live Canvas Snapshot;
4. run Penpot Shape compatibility repair;
5. build an in-memory object Diff;
6. save only the Diff summary to the Proposal Service;
7. show the preview to the user;
8. obtain a one-time apply token after revision/permission checks;
9. recheck affected live objects;
10. submit one native Change/Undo transaction;
11. report the transaction ID or conflict to the Proposal Service.

The apply token does not contain canvas data and cannot be obtained through MCP.

## Recovery

Active validated/previewed proposals can be listed by owner, file and optional
page. This enables the Penpot UI to recover proposals created by MCP or before a
browser refresh. The live workspace must always recompile recovered DSL rather
than trusting a stored visual preview.

## Remaining transport work

This module provides a complete protocol adapter but does not itself open an
HTTP/SSE/stdio server. A deployment transport must still provide:

- authenticated MCP session establishment;
- CSRF/origin controls appropriate to the selected transport;
- trusted workspace-context bridge routing;
- JSON-RPC exception mapping;
- connection and request rate limits;
- session revocation and observability without canvas/credential leakage.

These transport concerns must not introduce a second Proposal or canvas commit
implementation.
