import fs from "node:fs";
import path from "node:path";
import { REPO_ROOT, commandVersion, gitValue, isMain, parseArgs, readJson } from "./lib.mjs";
import { validateHarness } from "./check.mjs";

export const ENVIRONMENT_PROFILES = Object.freeze({
  core: ["node", "git"],
  frontend: ["node", "git", "pnpm", "clj-kondo"],
  backend: ["node", "git", "pnpm", "clojure", "clj-kondo"],
  devenv: ["node", "git", "pnpm", "clojure", "clj-kondo", "docker"],
});

function parseNodeMajor(value) {
  const match = String(value || "").match(/v?(\d+)/);
  return match ? Number(match[1]) : null;
}

export function normalizeProfile(value = "core") {
  const profile = String(value || "core").toLowerCase();
  if (!ENVIRONMENT_PROFILES[profile]) {
    throw new Error(`Unknown environment profile: ${value}. Use core, frontend, backend, or devenv.`);
  }
  return profile;
}

export function inspectEnvironment(root = REPO_ROOT, { profile = "core" } = {}) {
  const selectedProfile = normalizeProfile(profile);
  const requiredTools = ENVIRONMENT_PROFILES[selectedProfile];
  const expectedNode = fs.existsSync(path.join(root, ".nvmrc"))
    ? fs.readFileSync(path.join(root, ".nvmrc"), "utf8").trim()
    : null;
  const pkg = readJson(path.join(root, "package.json"));
  const featureList = readJson(path.join(root, ".harness/feature-list.json"));
  const tools = {
    node: commandVersion("node", ["--version"], root),
    git: commandVersion("git", ["--version"], root),
    pnpm: commandVersion("pnpm", ["--version"], root),
    clojure: commandVersion("clojure", ["--version"], root),
    "clj-kondo": commandVersion("clj-kondo", ["--version"], root),
    docker: commandVersion("docker", ["--version"], root),
  };
  const active = (featureList.features || []).find((item) => item.id === featureList.active_feature_id) || null;
  const expectedMajor = parseNodeMajor(expectedNode);
  const actualMajor = parseNodeMajor(tools.node.version);
  const nodeMatches = expectedMajor === null || actualMajor === expectedMajor;
  const harness = validateHarness(root, { strict: true });
  const missingRequired = requiredTools.filter((name) => !tools[name]?.available);

  return {
    profile: selectedProfile,
    required_tools: requiredTools,
    missing_required: missingRequired,
    repository: {
      root,
      branch: gitValue(["branch", "--show-current"], root),
      revision: gitValue(["rev-parse", "HEAD"], root),
      dirty: Boolean(gitValue(["status", "--porcelain"], root)),
    },
    runtime: {
      expected_node: expectedNode,
      package_manager: pkg.packageManager || null,
      node_matches: nodeMatches,
      tools,
    },
    active_feature: active,
    optional_source_map: fs.existsSync(path.join(root, ".harness/generated/code-map.json")),
    harness,
    healthy: missingRequired.length === 0 && nodeMatches && harness.ok,
  };
}

function printReport(report) {
  console.log("Penpot Harness initialization");
  console.log(`profile: ${report.profile}`);
  console.log(`required tools: ${report.required_tools.join(", ")}`);
  console.log(`root: ${report.repository.root}`);
  console.log(`branch: ${report.repository.branch || "(not a git checkout)"}`);
  console.log(`revision: ${report.repository.revision || "(unknown)"}`);
  console.log(`working tree: ${report.repository.dirty ? "dirty" : "clean/unknown"}`);
  console.log(`node: ${report.runtime.tools.node.version || "missing"}; expected ${report.runtime.expected_node || "unspecified"}`);
  console.log(`active feature: ${report.active_feature?.id || "none"} ${report.active_feature?.title || ""}`.trim());
  console.log(`optional source map: ${report.optional_source_map ? "available" : "not built"}`);
  console.log(`harness: ${report.harness.ok ? "PASS" : "FAIL"} (${report.harness.score}/100)`);
  console.log("tools:");
  for (const [name, state] of Object.entries(report.runtime.tools)) {
    const required = report.required_tools.includes(name) ? "required" : "optional";
    console.log(`  ${name}: ${state.available ? state.version || "available" : "missing"} (${required})`);
  }
  if (report.missing_required.length) {
    console.log(`missing required: ${report.missing_required.join(", ")}`);
  }
  console.log(report.healthy ? "ENVIRONMENT HEALTHY" : "ENVIRONMENT NEEDS ATTENTION");
}

if (isMain(import.meta.url)) {
  const { options } = parseArgs(process.argv.slice(2));
  try {
    const root = path.resolve(options.root || REPO_ROOT);
    const report = inspectEnvironment(root, { profile: options.profile || "core" });
    if (options.json) console.log(JSON.stringify(report, null, 2));
    else printReport(report);
    if (options.check) process.exitCode = report.healthy ? 0 : 1;
  } catch (error) {
    console.error(error.message);
    process.exitCode = 2;
  }
}
