# Optional Source Map

Source Map is an extended navigation aid for unfamiliar or cross-module work.
Use ordinary text, namespace, IDE, or symbol search first for routine tasks.

```bash
node scripts/harness/sourcemap.mjs build --profile ai
node scripts/harness/sourcemap.mjs query <term>
```

Its parser is heuristic. Confirm every result against source before editing.
Generated output is derived data, not project state or completion evidence.
The Core Harness validator and default CI do not require a map to exist or build.
