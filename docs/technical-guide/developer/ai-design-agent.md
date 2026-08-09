---
title: AI Design Agent architecture
desc: Transactional, schema-first AI design generation and editing in Penpot.
---

# AI Design Agent architecture

The AI Design Agent is a fourth workspace sidebar mode alongside Design,
Prototype and Inspect. It keeps the canvas editable. AI output never writes to
the file directly: every operation must pass through a preview, explicit user
confirmation and one native Penpot change transaction.

The feature is disabled by default. Enable it with the standard Penpot flag
`enable-ai-design-agent`. Both the sidebar entry and backend provider endpoint
check the parsed `:ai-design-agent` capability.

## Product invariant

The core invariant is:

```text
User request
  -> scoped design context
  -> design plan
  -> Document or Patch DSL
  -> schema and capability validation
  -> canonical Design IR
  -> temporary Penpot Change[]
  -> preview and diff
  -> explicit confirmation
  -> atomic commit
  -> one-step Undo
```

No model or provider receives a direct commit capability. The `Apply design`
action remains disabled until the native Change compiler and preview overlay are
connected.

## First release boundary

The first release targets one reliable workflow: select a frame, describe a
structured UI generation or modification, inspect its diff, then apply or
discard it.

It deliberately excludes whole-project refactoring, arbitrary web import,
bidirectional React repository synchronization, unconfirmed changes, complete
special-effect compilation and multi-model agents.

## Protocol layers

The system uses three layers:

1. **Authoring DSL**: concise model-facing Document and Patch protocols.
2. **Canonical Design IR**: deterministic semantic exchange format.
3. **Penpot Change compiler**: the only layer allowed to produce native canvas
   mutations.

The DSL contains data only. It cannot contain JavaScript, Clojure, expressions,
callbacks or any other executable source.

### Document DSL

Document DSL creates a new semantic tree. Every node has a stable semantic ID,
a supported kind and optional layout, style, token and component bindings.
Components must reference the Component Registry rather than repeat their
visual implementation.

### Patch DSL

Patch DSL changes existing semantic nodes. Every patch carries a base revision,
a scope and a list of minimal operations. Selection-scoped patches require a
root ID. Operations outside the declared scope must be rejected by the future
node resolver/compiler.

## Canonical IR ownership

IR is a compiler and interchange format, not a second editable database. The
compiled result is stored as normal Penpot shapes. Semantic identity, registry
references, DSL version and code mapping belong in shape plugin data. The file
stores registry/version metadata. IR can later be reconstructed from shapes and
semantic metadata after manual edits.

## Context boundary

The client never sends the full file by default.

Selection context contains selected nodes, a bounded child tree, at most two
parent levels, layout constraints, component references and token bindings.
Page context starts as a summary and can be expanded only through explicit tool
requests. Component context is based on the main component, variants, slots and
instance override summaries.

The current foundation limits selection descendants to four levels and parents
to two levels.

## Component Registry

The Component Registry maps one semantic component identity to both a Penpot
component and a code component. It owns variant schemas, props, slots, defaults,
imports and exported symbols. An unknown component or variant is a validation
error; it may not silently degrade to an untyped frame.

## Credential and provider boundary

The first provider implementation uses the OpenAI-compatible protocol.

- The API key is held in component memory for the current panel session.
- It is sent only to an authenticated Penpot backend RPC.
- The credential-bearing RPC is explicitly excluded from generic RPC audit
  logging because audit properties normally derive from decoded request params.
- It is never returned in full, written to a file, persisted to browser storage
  or included in normal error data.
- Provider calls are made by the backend HTTP client with SSRF validation on the
  initial URI and every redirect.
- Logs and responses must use redaction or last-four display only.
- The frontend and backend are both closed unless `enable-ai-design-agent` is
  present in Penpot flags.

Encrypted account-level credentials, key rotation and a server-side credential
ID are deferred to the production-hardening phase.

## Transaction and preview rules

Preview state must remain local and ephemeral. It must not enter collaboration
history, persistence queues or other users' workspaces. A proposal records the
base file revision, page, selection IDs and relevant node revisions.

The foundation includes a native change transaction adapter. It can build a
local temporary object snapshot from parent-first Penpot shapes and can submit
the same redo/undo pair through a single workspace Undo transaction. It is not
yet exposed by the UI: revision checks, scope enforcement and the complete
IR-to-shape compiler must be connected first.

On apply, the compiler must generate both redo and undo changes and submit them
through the existing Penpot change/undo machinery as one origin-tagged
transaction. Partial commits are forbidden. Failed compilation or submission
must leave no shapes behind.

## Conflict policy

Before apply, compare Base, AI Proposed and Current Canvas:

- merge properties changed on only one side;
- surface same-property conflicts;
- reject operations targeting deleted nodes;
- attempt semantic relocation when a parent moved;
- reject any operation outside scope.

No conflict may be silently overwritten.

## Compile and free-canvas modes

Compile Mode requires semantic layout, registered components, design tokens and
supported responsive rules. Free Canvas Mode remains unrestricted but produces
a compatibility report and loss report. Code generation must be deterministic
and compiler-driven; the model does not concatenate source files directly.

## Foundation modules

```text
common/src/app/common/ai/
  schema.cljc       structural Document/Patch schemas
  ir.cljc           canonical IR utilities
  normalize.cljc    deterministic normalization
  validation.cljc   semantic and capability validation
  capability.cljc   Compile Mode readiness report
  registry.cljc     component registry primitives

frontend/src/app/main/data/workspace/ai/
  context.cljs      bounded selection/page context
  execution.cljs    native preview/apply transaction boundary

frontend/src/app/main/ui/workspace/sidebar/ai/
  panel.cljs        transaction-oriented sidebar shell
  panel.scss        panel styles

backend/src/app/ai/
  secrets.clj
  providers/protocol.clj
  providers/openai_compatible.clj

backend/src/app/rpc/commands/ai.clj
  provider connection implementation

backend/src/app/rpc/commands/feedback.clj
  temporary scanned-namespace registration shim for test-ai-provider
```

## Required follow-up PR sequence

1. AI sidebar shell and provider test.
2. DSL/IR validation and golden fixtures.
3. structured provider response and repair loop.
4. IR-to-native-Penpot preview compiler.
5. atomic apply plus one-step Undo.
6. Patch DSL node resolver, minimal diff and conflict checks.
7. file-level component/token registry.
8. responsive rules and compile-readiness report.
9. React/TypeScript AST adapter.
10. encrypted credentials, rate limits, audit and evaluation suite.

Each step must remain independently testable and reversible. Upstream Penpot UI,
canvas operations, shortcuts and native Change/Undo mechanisms remain the
source of truth; the AI feature should not reimplement them.
