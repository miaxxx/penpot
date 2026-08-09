#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
MODE="${1:-fast}"
cd "$REPO_ROOT"

log() {
  printf '[harness:check] %s\n' "$*"
}

fail() {
  printf '[harness:check] ERROR: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command is missing: $1"
}

require_file() {
  [[ -f "$1" ]] || fail "required file is missing: $1"
}

run_fast() {
  require_command bash
  require_command node

  local required_files=(
    "AGENTS.md"
    "CLAUDE.md"
    ".harness/GUIDE.md"
    ".harness/RULES.md"
    ".harness/TOOLS.md"
    ".harness/CHECKS.md"
    ".harness/PROGRESS.md"
    ".harness/session-handoff.md"
    ".harness/environment.json"
    ".harness/feature_list.json"
    ".harness/task-map.json"
    ".harness/task-map.schema.json"
    "docs/harness/README.md"
    "docs/harness/source-map-traceability.md"
    "scripts/harness/init.sh"
    "scripts/harness/check.sh"
    "scripts/harness/record.mjs"
    "scripts/harness/sourcemap-check.mjs"
  )
  for file in "${required_files[@]}"; do
    require_file "$file"
  done

  log "validate JSON and task-map invariants"
  node --input-type=module <<'NODE'
import fs from "node:fs";

const paths = [
  ".harness/environment.json",
  ".harness/feature_list.json",
  ".harness/task-map.json",
  ".harness/task-map.schema.json",
];
for (const file of paths) {
  JSON.parse(fs.readFileSync(file, "utf8"));
}

const map = JSON.parse(fs.readFileSync(".harness/task-map.json", "utf8"));
const statuses = new Set(["planned", "in-progress", "blocked", "completed"]);
if (map.schemaVersion !== 1) throw new Error("task-map schemaVersion must equal 1");
if (!statuses.has(map.current?.status)) throw new Error("task-map current.status is invalid");
if (!Array.isArray(map.events)) throw new Error("task-map events must be an array");
for (const event of map.events) {
  if (!statuses.has(event.status)) throw new Error(`invalid event status: ${event.status}`);
  if (!Array.isArray(event.commands)) throw new Error("event.commands must be an array");
}
console.log("PASS: JSON and task-map invariants");
NODE

  log "validate shell syntax"
  bash -n scripts/harness/init.sh scripts/harness/check.sh

  log "validate Node entrypoints"
  node scripts/harness/record.mjs --help >/dev/null
  node scripts/harness/sourcemap-check.mjs --help >/dev/null

  log "run source-map positive and negative fixtures"
  local fixture
  fixture="$(mktemp -d)"
  trap 'rm -rf "$fixture"' RETURN

  mkdir -p "$fixture/good/src" "$fixture/bad-json" "$fixture/bad-source"
  printf 'export const answer = 42;\n' > "$fixture/good/src/input.js"
  printf '(()=>{})();\n//# sourceMappingURL=output.js.map\n' > "$fixture/good/output.js"
  cat > "$fixture/good/output.js.map" <<'JSON'
{"version":3,"file":"output.js","sources":["src/input.js"],"sourcesContent":["export const answer = 42;\n"],"names":[],"mappings":"AAAA"}
JSON
  node scripts/harness/sourcemap-check.mjs \
    --require-reference \
    --root "$fixture/good" \
    "$fixture/good" >/dev/null

  printf '{invalid\n' > "$fixture/bad-json/output.js.map"
  if node scripts/harness/sourcemap-check.mjs --root "$fixture/bad-json" "$fixture/bad-json" >/dev/null 2>&1; then
    fail "malformed source map unexpectedly passed"
  fi

  cat > "$fixture/bad-source/output.js.map" <<'JSON'
{"version":3,"sources":["../../private/source.js"],"names":[],"mappings":"AAAA"}
JSON
  if node scripts/harness/sourcemap-check.mjs --root "$fixture/bad-source" "$fixture/bad-source" >/dev/null 2>&1; then
    fail "escaping source map unexpectedly passed"
  fi

  log "scan known build output locations"
  local existing_targets=()
  local candidate
  for candidate in frontend/target frontend/dist bundles; do
    [[ -e "$candidate" ]] && existing_targets+=("$candidate")
  done
  if (( ${#existing_targets[@]} > 0 )); then
    node scripts/harness/sourcemap-check.mjs --allow-empty "${existing_targets[@]}"
  else
    log "no build output directories exist; fixture validation is the current evidence"
  fi

  log "fast checks passed"
}

changed_files() {
  local base="${HARNESS_BASE_REF:-develop}"
  if git rev-parse --verify "$base" >/dev/null 2>&1; then
    git diff --name-only "$base"...HEAD
  elif git rev-parse --verify "origin/$base" >/dev/null 2>&1; then
    git diff --name-only "origin/$base"...HEAD
  else
    fail "cannot resolve HARNESS_BASE_REF=$base; fetch it or set HARNESS_BASE_REF"
  fi
}

run_changed() {
  run_fast
  require_command git
  require_command pnpm

  local files
  files="$(changed_files)"
  if [[ -z "$files" ]]; then
    log "no changed files relative to ${HARNESS_BASE_REF:-develop}"
    return
  fi

  printf '%s\n' "$files" | grep -qE '^frontend/.*\.(clj|cljs|cljc)$' && {
    log "run Clojure/ClojureScript format and lint checks"
    (cd frontend && pnpm run check-fmt:clj && pnpm run lint:clj)
  } || true

  printf '%s\n' "$files" | grep -qE '^frontend/(scripts|playwright|src)/.*\.(js|jsx|mjs|cjs)$' && {
    log "run JavaScript format checks"
    (cd frontend && pnpm run check-fmt:js)
  } || true

  printf '%s\n' "$files" | grep -qE '^frontend/.*\.scss$' && {
    log "run SCSS format and lint checks"
    (cd frontend && pnpm run check-fmt:scss && pnpm run lint:scss)
  } || true

  log "changed checks passed"
}

run_full() {
  run_fast
  require_command pnpm
  log "run full frontend checks"
  (
    cd frontend
    pnpm run check-fmt:clj
    pnpm run lint:clj
    pnpm run check-fmt:js
    pnpm run check-fmt:scss
    pnpm run lint:scss
    pnpm run build:app
    pnpm run test
  )
  log "full checks passed"
}

case "$MODE" in
  fast) run_fast ;;
  changed) run_changed ;;
  full) run_full ;;
  *) fail "unknown mode '$MODE'; expected fast, changed, or full" ;;
esac
