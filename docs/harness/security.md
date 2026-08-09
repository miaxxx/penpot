# Harness Security

## Default deny

The registry allows reading, scoped editing, generated map writes and
pre-registered checks. Destructive operations are denied.

## Secrets

Never write raw credentials, authorization headers, cookies, environment dumps
or provider payloads into progress, handoff, evidence, source maps, logs or
commits. Evidence execution inherits the current process environment but does
not serialize it.

## Command execution

`evidence.mjs` accepts a check ID, not arbitrary shell text. The registry itself
is validated against destructive patterns. A registry change is a security-
sensitive code change and requires review.

## Source map extraction

Embedded source maps can contain hostile paths. The map builder normalizes
webpack/URL prefixes, removes query fragments, strips roots and replaces parent
traversal before recording paths. It never writes recovered source content into
the source tree.
