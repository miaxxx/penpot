# Source-map and agent traceability

The Harness applies one provenance rule to both generated code and agent work:

> An output is trustworthy only when it can be traced back to its source and
> verified by a reproducible check.

## JavaScript source maps

`scripts/harness/sourcemap-check.mjs` validates every discovered `.map` file.

It requires:

- source-map version 3;
- parseable JSON;
- non-empty `sources` and `mappings`;
- safe relative paths;
- no `file://`, network URL, absolute, drive-letter, UNC, home, or escaping
  source path;
- each source backed by `sourcesContent` or an existing file under the allowed
  root;
- an optional generated-file `sourceMappingURL` reference in strict artifact
  mode.

The checker fails closed. `--allow-empty` changes only the “no maps found”
condition; it does not weaken validation for discovered maps.

## Agent source maps

`.harness/task-map.json` is the agent-level equivalent of a source map:

| Source-map concept | Agent Harness equivalent |
| --- | --- |
| generated file | implementation output |
| original source | request, rule, design decision, affected code |
| mappings | task-map outputs and evidence |
| source content | repository files and durable task state |
| map consumer | next agent, reviewer, CI, or human operator |

A task event records:

- task ID and status;
- source/rationale;
- outputs;
- commands;
- results;
- next executable action.

## Claude-style provenance

When an AI system generates or transforms code, retain a chain such as:

`instruction -> selected source span -> generated diff -> validation command -> result`

Do not expose private chain-of-thought. Store concise engineering provenance:
inputs used, files affected, checks executed, observable results, and next
action.

## Release policy

A build that publishes source maps must run strict artifact validation. A broken
map is not a warning-only condition because it damages debugging, error
attribution, and generated-code provenance.
