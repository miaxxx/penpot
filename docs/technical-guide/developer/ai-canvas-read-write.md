---
title: AI canvas read/write compiler
desc: Lossless Penpot canvas understanding, scoped Patch DSL and native Change compilation.
---

# AI canvas read/write compiler

This document describes the second implementation layer of Penpot's AI Design
Agent. It extends the sidebar/DSL foundation with native canvas reading,
structured provider generation, local proposal compilation and atomic Penpot
transactions.

The implementation is intentionally split into four trust boundaries:

```text
Native Penpot Shape graph
  -> lossless local Canvas Snapshot
  -> bounded provider context
  -> validated Document/Patch DSL
  -> local native Shape graph proposal
  -> native redo/undo Change transaction
```

The model never receives a native Change commit function. It proposes data-only
DSL. The browser validates and compiles that proposal, displays its diff, checks
that the canvas has not changed, and only then allows an explicit apply action.

## Lossless local snapshot

`app.common.ai.canvas/build-snapshot` stores the original Penpot object map under
`:penpot`. This is the source of truth for compilation and conflict checks. No
second editable design database is introduced.

Each semantic node also exposes:

- stable AI semantic ID and Penpot UUID;
- native shape type and inferred semantic kind;
- parent, frame and ordered child references;
- x/y/width/height/rotation and selection geometry;
- Flex/Grid layout, sizing, padding, gaps and alignment;
- fills, strokes, opacity, radius, shadow, blur and blend mode;
- rich text content summary;
- component identity, variant properties, shape references and touched state;
- constraints, interactions, exports, grids, locking and visibility;
- applied design tokens and plugin data;
- the complete original Shape map for local-only operations.

The snapshot also builds a semantic-ID index, detects duplicate semantic IDs,
missing parents/children and parent cycles, and computes the exact UUID set
allowed by the active scope.

## Provider context

The model receives `compact-context`, not the lossless snapshot. Context is
bounded to 500 scoped nodes by default and includes at most two parent levels.
The payload reports truncation and graph-integrity errors.

Selection and component scopes include the selected root and descendants. Page
scope includes the page object map. Parent context may be read to understand
constraints but does not expand write permission.

The backend additionally limits serialized context to 300,000 characters and
user prompts to 12,000 characters.

## Structured provider protocol

The OpenAI-compatible provider calls `/chat/completions` through Penpot's SSRF
protected HTTP client. Initial and redirected URLs are validated. Responses are
limited to 2 MiB and must contain one JSON object:

```json
{
  "plan": {
    "title": "Update the selected cards",
    "steps": ["Change the copy", "Bind the brand token"]
  },
  "dslType": "patch",
  "dsl": {
    "dslVersion": "1.0",
    "baseRevision": 42,
    "scope": {
      "type": "selection",
      "rootId": "feature-grid"
    },
    "operations": []
  }
}
```

The service validates the plan and DSL and permits at most one constrained
repair request. Modify, refactor and adapt modes must return Patch DSL. Patch
scope, scope root and base revision must match the context supplied by Penpot.
A model cannot promote a Selection request to Page scope.

Credential-bearing provider RPCs and canvas context are excluded from generic
RPC audit logging.

## Read compatibility

| Penpot capability | Current read support |
| --- | --- |
| Shape tree and ordering | Full native object graph locally; bounded semantic graph for provider |
| Geometry and transforms | Core geometry, rotation, flips, points and selection rectangle |
| Flex and Grid | Layout type, direction, gaps, padding, alignment, sizing and grid definitions |
| Visual styles | Fills, strokes, opacity, radius, shadows, blur and blend mode |
| Rich text | Plain-text understanding plus lossless native rich-text tree locally |
| Components | Component IDs, files, main/instance state, variants, references and touched state |
| Design tokens | Native `applied-tokens` bindings and currently resolved visual values |
| Prototype behavior | Interaction arrays, fixed-scroll and viewer visibility are readable |
| Plugin metadata | Full local plugin data; AI semantic identity is included in model context |
| Constraints and export | Constraints, exports, grids, locking, collapse and hidden state |

## Write compatibility

### Implemented native operations

- `set` and `unset` for supported semantic paths;
- advanced `penpot.<attribute>` changes except protected identity/tree fields;
- rich-text copy replacement while preserving existing run styling;
- `bindToken` into native Penpot `applied-tokens` attributes;
- `setVariant` for existing instance variant metadata;
- `move` with ordered insertion and cycle prevention;
- subtree `remove`;
- `batch` operations;
- semantic node `create` and `insert`;
- subtree `replace`;
- subtree `duplicate` with new UUIDs and semantic IDs;
- Document DSL generation below an allowed container;
- native diff generation for created, modified, moved and removed nodes;
- atomic apply through Penpot redo/undo Changes;
- one-step Undo for the complete AI operation;
- stale-proposal rejection when affected objects changed after preview.

### Native shape compilation

The compiler currently creates common UI primitives as native Penpot shapes:

- section, frame, stack, grid, card, button, badge, input, textarea, select,
  tabs, navigation, modal, list, table, form, slot and variant as frames;
- text, heading and paragraph as native text shapes;
- shape, divider and icon as native rectangle primitives;
- image as an explicit placeholder that reports a compatibility warning;
- Flex/Grid layout, fill/hug/fixed sizing, gaps, padding, alignment, fills,
  strokes, radius and opacity;
- AI semantic identity in Shape plugin data.

### Explicit compatibility boundaries

The following operations are rejected or reported as lossy until their native
Penpot adapters are available:

- creating or replacing a real component instance without a Component Registry
  `shape-factory`;
- uploading image/media assets or resolving a provider image into Penpot media;
- synthesizing arbitrary SVG/path geometry and boolean path results;
- modifying path control points through a high-level semantic path;
- resolving prototype destination IDs that are not present in the supplied
  context;
- applying design tokens that do not exist in the file token library;
- full three-way property merge when collaborators edit the same attribute;
- streaming/cancel transport for long generation requests;
- visual overlay rendering separate from the diff card.

These boundaries must remain explicit. Unsupported component, media or vector
operations must never silently degrade to visually similar but semantically
incorrect frames.

## Scope enforcement

Every read/write proposal carries a scope. Existing-node targets and structural
parents are resolved from stable semantic IDs or Penpot UUIDs. The compiler
rejects:

- targets outside the scope UUID set;
- parent changes that escape scope;
- removing the active scope root;
- moving a node below its own descendant;
- direct writes to `id`, `type`, `parent-id`, `frame-id`, `shapes`, transform or
  selection geometry through generic property paths;
- unsupported token targets and invalid token names;
- text changes on non-text shapes.

## Preview and apply

A proposal is compiled into an in-memory target object graph. The diff adapter
then creates native redo and undo changes using Penpot's existing builders for
add, remove, parent change and shape update.

Preview does not commit those changes. Apply compares the current affected
objects with the proposal's base snapshot. A mismatch emits an AI conflict event
and performs no mutation. A valid proposal is committed as one Undo transaction,
followed by native layout recalculation for affected parents.

## Test coverage

The added golden/unit fixtures cover:

- lossless and compact canvas snapshots;
- selection scope isolation;
- property and parent changes;
- out-of-scope rejection;
- rich-text style preservation;
- native design-token binding;
- invalid token targets;
- Document IR to valid Penpot shapes;
- structural Patch insertion;
- structured provider output validation;
- one constrained repair attempt.

The current connector environment does not provide a local Clojure/ClojureScript
toolchain or a network-capable repository checkout. These tests must run in the
project development environment and CI before merge.
