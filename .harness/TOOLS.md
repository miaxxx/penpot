# Tool System

The tool registry separates capability from permission. Agents should prefer
the narrowest tool that can answer the question.

## Classes

- `read`: inspect files, git metadata, generated maps and logs.
- `search`: query text or the repository source map.
- `write`: edit files inside the active feature scope.
- `verify`: run a registered, reproducible check.
- `generated-write`: write only under `.harness/generated/` or `tmp/harness/`.
- `destructive`: denied unless a human explicitly performs or authorizes it.

`node scripts/harness/evidence.mjs <check-id>` executes only commands registered
in `.harness/checks.json`. The script does not accept arbitrary shell text.

## Source-map-first navigation

Use the generated code map to narrow the search:

```bash
node scripts/harness/sourcemap.mjs build --profile ai
node scripts/harness/sourcemap.mjs query app.common.ai
node scripts/harness/sourcemap.mjs query preview
```

The map extracts Clojure namespaces/defs, JS/TS imports/exports, Rust items and
embedded source-map metadata. Parsing is intentionally heuristic and must be
confirmed against source before edits.
