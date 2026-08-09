# Penpot repository Harness

This directory explains the repository-level working system for coding agents.
It complements, rather than replaces, Penpot's Serena memory graph.

## Architecture

The Harness combines five systems:

1. instructions;
2. tools;
3. environment;
4. durable state;
5. executable feedback.

The root entry files remain concise. Detailed rules live in `.harness/` and
`docs/harness/`, and machine actions live in `scripts/harness/`.

## Why this exists

A prompt can describe a task, but it does not establish:

- where work occurs;
- which project rules apply;
- which tools and services are available;
- what state survived the previous session;
- how completion will be proven.

The Harness supplies those missing engineering constraints.

## Documents

- `directory-guide.md` — what belongs where.
- `commands.md` — initialization and check commands.
- `definition-of-done.md` — evidence required before completion.
- `source-map-traceability.md` — build and agent provenance model.

## Reference

The structure is conceptually informed by the MIT-licensed
`walkinglabs/learn-harness-engineering` project and adapted to Penpot's actual
ClojureScript, pnpm, Docker, and Serena environment.
