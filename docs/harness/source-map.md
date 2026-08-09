# Repository Source Map

The source-map layer applies the useful principle behind source-map recovery:
preserve the relationship between generated/physical files and original logical
sources, sanitize paths, and reconstruct a navigable source tree.

For Penpot it builds a derived JSON graph containing:

- files and language;
- Clojure namespaces and definitions;
- JS/TS imports, exports, functions and classes;
- Rust items;
- dependency edges;
- metadata from embedded `.map` files, including whether source content exists.

## Commands

```bash
node scripts/harness/sourcemap.mjs build --profile ai
node scripts/harness/sourcemap.mjs build --profile all --output tmp/harness/all-map.json
node scripts/harness/sourcemap.mjs query preview
node scripts/harness/sourcemap.mjs query app.common.ai
```

## Safety and limits

- `..`, URL/query fragments, absolute prefixes and webpack prefixes are
  sanitized before logical source paths are recorded.
- Vendored, dependency, cache and build directories are excluded.
- Oversized files are skipped and reported.
- Parsing is dependency-free and heuristic. Confirm every navigation result
  against source and tests before editing.
- Generated maps are ignored by git and never become a second source of truth.
