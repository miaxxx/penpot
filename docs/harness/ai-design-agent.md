# AI Design Agent Harness Profile

The AI Design Agent spans backend provider access, shared Design/Patch DSL and
IR, and frontend workspace transactions. Harness work touching it must preserve
these boundaries.

## Mandatory invariants

- Feature flag: no public behavior when `:ai-design-agent` is disabled.
- Credentials: request-local only; redact logs, exceptions, telemetry, evidence
  and persisted Penpot files.
- Network safety: user-provided provider URLs use the existing SSRF and redirect
  validation path.
- Input: validate bounded context and Document/Patch DSL before execution.
- IR: Canonical Design IR is an exchange/compile form, not a second mutable
  source of truth.
- Transaction: preview is reversible and separate from apply; apply uses native
  Penpot change/undo boundaries.
- Verification: backend secrets/provider tests, common DSL tests and frontend
  lint/format/runtime checks are registered separately.
- Completion: no UI or agent message may claim success before validation and
  apply evidence exists.

## Suggested graph

```text
goal -> context builder -> model/provider -> DSL validation -> IR analysis
     -> preview transaction -> checker -> human/apply gate -> native apply
     -> verification -> state/evidence
```

Failures route back to the narrowest responsible node. Provider, parser,
preview and apply failures must not collapse into one generic retry loop.
