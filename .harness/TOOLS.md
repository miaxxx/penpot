# Tool System

The registry describes repository policy; the evidence runner enforces the
registered verification boundary. It does not attempt to replace the host
agent runtime's shell permission system.

Capability classes remain: `read`, `search`, `write`, `verify`,
`generated-write`, and `destructive`. Destructive operations stay denied by
default.

Source Map is an optional extended tool for unfamiliar or cross-module work:

```bash
node scripts/harness/sourcemap.mjs build --profile ai
node scripts/harness/sourcemap.mjs query <term>
```

Use ordinary source/symbol search first for routine changes.
