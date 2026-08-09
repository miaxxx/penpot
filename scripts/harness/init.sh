#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

say() {
  printf '[harness:init] %s\n' "$*"
}

version_or_missing() {
  local command_name="$1"
  shift
  if command -v "$command_name" >/dev/null 2>&1; then
    printf '  %-12s %s\n' "$command_name" "$("$@" 2>&1 | head -n 1)"
  else
    printf '  %-12s %s\n' "$command_name" "MISSING"
  fi
}

say "repository: $(git remote get-url origin 2>/dev/null || printf 'unknown')"
say "branch: $(git branch --show-current 2>/dev/null || printf 'unknown')"
say "worktree:"
git status --short --branch || true

say "tool versions:"
version_or_missing git git --version
version_or_missing node node --version
version_or_missing pnpm pnpm --version
version_or_missing clojure clojure --version
version_or_missing cljfmt cljfmt version
version_or_missing clj-kondo clj-kondo --version
version_or_missing docker docker --version

expected_node="$(tr -d '[:space:]' < .nvmrc)"
actual_node="$(node --version 2>/dev/null || true)"
if [[ -z "$actual_node" ]]; then
  say "ERROR: node is required for Harness checks (expected $expected_node)."
  exit 1
fi
if [[ "$actual_node" != "$expected_node" ]]; then
  say "WARNING: node mismatch; expected $expected_node, found $actual_node."
fi

say "running fast integrity checks"
"$SCRIPT_DIR/check.sh" fast

say "ready"
say "next: read AGENTS.md, .harness/GUIDE.md, .harness/RULES.md, and affected Serena memories"
