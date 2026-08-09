import path from "node:path";
import { REPO_ROOT, exists, isMain, parseArgs, readJson, readText } from "./lib.mjs";

export const CORE_REQUIRED = Object.freeze([
  "AGENTS.md",
  "init.sh",
  ".harness/README.md",
  ".harness/RULES.md",
  ".harness/STATUS.md",
  ".harness/feature-list.json",
  ".harness/checks.json",
  ".harness/tool-registry.json",
  "scripts/harness/init.mjs",
  "scripts/harness/check.mjs",
  "scripts/harness/evidence.mjs",
  "scripts/harness/test.mjs",
]);

const DANGEROUS = [
  /\brm\s+-rf\b/i,
  /\bdrop-devenv\b/i,
  /docker\s+compose\s+down\b.*(?:-v|--volumes)/i,
  /docker\s+volume\s+rm\b/i,
  /\bgit\s+push\b.*--force/i,
  /\bDROP\s+(?:DATABASE|TABLE)\b/i,
];

function issue(list, severity, code, message) {
  list.push({ severity, code, message });
}

export function validateHarness(root = REPO_ROOT, { strict = false } = {}) {
  const issues = [];

  for (const relative of CORE_REQUIRED) {
    if (!exists(root, relative)) issue(issues, "error", "missing-core-file", relative);
  }
  if (issues.some((item) => item.code === "missing-core-file")) {
    return { ok: false, score: 0, required_files: CORE_REQUIRED.length, issues };
  }

  const agents = readText(root, "AGENTS.md");
  if (agents.length > 6000) issue(issues, "warning", "agents-large", "AGENTS.md should remain a concise router under 6000 characters.");
  for (const marker of [
    ".serena/memories/critical-info.md",
    ".harness/feature-list.json",
    ".harness/STATUS.md",
    "scripts/harness/evidence.mjs",
  ]) {
    if (!agents.includes(marker)) issue(issues, "error", "agents-routing", `AGENTS.md does not route to ${marker}.`);
  }
  if (/Source Map.*(?:must|required|before broad)/i.test(agents)) {
    issue(issues, "warning", "source-map-mandatory", "Source Map should remain optional guidance.");
  }

  const featureList = readJson(path.join(root, ".harness/feature-list.json"));
  const features = Array.isArray(featureList.features) ? featureList.features : [];
  if (featureList.version !== 1) issue(issues, "error", "feature-version", "feature-list version must be 1.");
  if (features.length === 0) issue(issues, "error", "feature-empty", "feature-list must contain features.");

  const ids = new Set();
  const statuses = new Set(["planned", "in_progress", "blocked", "done"]);
  let inProgress = 0;
  for (const feature of features) {
    if (!feature.id || ids.has(feature.id)) issue(issues, "error", "feature-id", `Invalid or duplicate feature id: ${feature.id}`);
    ids.add(feature.id);
    if (!statuses.has(feature.status)) issue(issues, "error", "feature-status", `${feature.id} has invalid status ${feature.status}.`);
    if (feature.status === "in_progress") inProgress += 1;
    for (const field of ["scope", "acceptance", "depends_on", "evidence"]) {
      if (!Array.isArray(feature[field])) issue(issues, "error", "feature-shape", `${feature.id}.${field} must be an array.`);
    }
    if (!feature.owner) issue(issues, "error", "feature-owner", `${feature.id} needs an owner.`);
    if (feature.status === "done") {
      if (!feature.evidence?.length) issue(issues, "error", "done-without-evidence", `${feature.id} is done without evidence.`);
      if (feature.evidence?.some((entry) => entry.result !== "pass")) {
        issue(issues, "error", "done-with-nonpass", `${feature.id} has non-passing completion evidence.`);
      }
    }
  }
  if (inProgress > 1) issue(issues, "error", "multiple-active", "Only one feature may be in_progress in this state file.");
  if (featureList.active_feature_id !== null) {
    const active = features.find((item) => item.id === featureList.active_feature_id);
    if (!active) issue(issues, "error", "active-missing", "active_feature_id does not exist.");
    else if (!["in_progress", "blocked"].includes(active.status)) {
      issue(issues, "error", "active-status", "active feature must be in_progress or blocked.");
    }
  }
  if (strict && featureList.active_feature_id !== null && inProgress === 0) {
    issue(issues, "error", "strict-active", "Strict mode requires the active feature to be in_progress.");
  }

  const checks = readJson(path.join(root, ".harness/checks.json"));
  const checkIds = new Set();
  for (const check of checks.checks || []) {
    if (!check.id || checkIds.has(check.id)) issue(issues, "error", "check-id", `Invalid or duplicate check id: ${check.id}`);
    checkIds.add(check.id);
    if (!check.command || !check.cwd || !check.risk || !Number.isFinite(check.timeout_ms)) {
      issue(issues, "error", "check-shape", `${check.id} is missing required fields.`);
    }
    if (DANGEROUS.some((pattern) => pattern.test(check.command || ""))) {
      issue(issues, "error", "dangerous-command", `${check.id} contains a denied command pattern.`);
    }
    const resolvedCwd = path.resolve(root, check.cwd || ".");
    if (!resolvedCwd.startsWith(path.resolve(root))) {
      issue(issues, "error", "check-cwd", `${check.id} escapes the repository.`);
    }
  }
  for (const feature of features) {
    for (const evidence of feature.evidence || []) {
      if (!checkIds.has(evidence.check_id)) {
        issue(issues, "error", "unknown-evidence-check", `${feature.id} references unknown check ${evidence.check_id}.`);
      }
    }
  }

  const registry = readJson(path.join(root, ".harness/tool-registry.json"));
  if (registry.default_policy !== "deny") issue(issues, "error", "tool-policy", "Tool registry must default deny.");
  if (!(registry.tools || []).some((tool) => tool.capability === "destructive" && tool.allowed?.length === 0)) {
    issue(issues, "error", "destructive-policy", "Destructive capability must have no default allow-list.");
  }

  const status = readText(root, ".harness/STATUS.md");
  for (const heading of ["## Current task", "## State", "## Evidence", "## Risks", "## Next step"]) {
    if (!status.includes(heading)) issue(issues, "error", "status-section", `STATUS.md is missing ${heading}.`);
  }

  const sourceMapConfig = exists(root, ".harness/sourcemap.config.json");
  const sourceMapScript = exists(root, "scripts/harness/sourcemap.mjs");
  if (sourceMapConfig !== sourceMapScript) {
    issue(issues, "warning", "source-map-pair", "Optional Source Map config and script should be kept together.");
  }
  if (sourceMapConfig) {
    const mapConfig = readJson(path.join(root, ".harness/sourcemap.config.json"));
    if ((mapConfig.exclude || []).some((item) => item.includes(".."))) {
      issue(issues, "error", "map-exclude", "Source Map excludes must not traverse parents.");
    }
  }

  const pkg = readJson(path.join(root, "package.json"));
  for (const script of ["harness:init", "harness:check", "harness:test", "harness:evidence"]) {
    if (!pkg.scripts?.[script]) issue(issues, "error", "package-script", `package.json is missing ${script}.`);
  }

  const errors = issues.filter((item) => item.severity === "error").length;
  const warnings = issues.filter((item) => item.severity === "warning").length;
  const score = Math.max(0, 100 - errors * 15 - warnings * 2);
  return { ok: errors === 0, score, errors, warnings, required_files: CORE_REQUIRED.length, issues };
}

function printReport(report) {
  console.log(`Harness score: ${report.score}/100`);
  console.log(`Required core files: ${report.required_files}`);
  console.log(`Errors: ${report.errors || 0}; warnings: ${report.warnings || 0}`);
  for (const item of report.issues) console.log(`${item.severity.toUpperCase()} [${item.code}] ${item.message}`);
  console.log(report.ok ? "HARNESS PASS" : "HARNESS FAIL");
}

if (isMain(import.meta.url)) {
  const { options } = parseArgs(process.argv.slice(2));
  const root = path.resolve(options.root || REPO_ROOT);
  const report = validateHarness(root, { strict: Boolean(options.strict) });
  if (options.json) console.log(JSON.stringify(report, null, 2));
  else printReport(report);
  process.exitCode = report.ok ? 0 : 1;
}
