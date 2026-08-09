# Harness Architecture

## Layering

```text
User goal
  -> AGENTS.md / CLAUDE.md router
  -> Serena memory graph for stable module knowledge
  -> feature-list + progress + handoff shared state
  -> source-map/search context selection
  -> scoped editor/tool use
  -> registered verification and evidence
  -> human review / CI / next session
```

The Harness is a control plane. Penpot source and native tests remain the data
plane and source of truth.

## Five systems

- **Instruction:** startup order, invariants and progressive disclosure.
- **Tool:** explicit capabilities, default deny, command registry.
- **Environment:** non-destructive health report and version checks.
- **State:** one active feature, dependencies, acceptance and restart path.
- **Feedback:** command exit codes, bounded evidence, CI and review.

## Loop to graph

A simple feature follows one loop. Work that needs independent specialists,
parallel checks or approval should be represented as a graph with explicit
nodes, edges and shared state. Do not simulate multi-agent coordination through
an unstructured chat transcript.
