import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { REPO_ROOT, isMain } from "./lib.mjs";
import { CORE_REQUIRED, validateHarness } from "./check.mjs";
import { ENVIRONMENT_PROFILES, normalizeProfile } from "./init.mjs";

function copyCoreFixture(sourceRoot) {
  const target = fs.mkdtempSync(path.join(os.tmpdir(), "penpot-harness-core-"));
  for (const relative of CORE_REQUIRED) {
    const source = path.join(sourceRoot, relative);
    const destination = path.join(target, relative);
    fs.mkdirSync(path.dirname(destination), { recursive: true });
    fs.copyFileSync(source, destination);
  }
  for (const relative of ["package.json", ".nvmrc", "scripts/harness/lib.mjs"]) {
    const source = path.join(sourceRoot, relative);
    if (!fs.existsSync(source)) continue;
    const destination = path.join(target, relative);
    fs.mkdirSync(path.dirname(destination), { recursive: true });
    fs.copyFileSync(source, destination);
  }
  return target;
}

export function runTests(root = REPO_ROOT) {
  const report = validateHarness(root, { strict: true });
  assert.equal(report.ok, true, JSON.stringify(report.issues, null, 2));
  assert.equal(report.required_files, 12);
  assert.equal(CORE_REQUIRED.includes(".harness/PROGRESS.md"), false);
  assert.equal(CORE_REQUIRED.includes(".harness/session-handoff.md"), false);
  assert.equal(CORE_REQUIRED.includes("scripts/harness/sourcemap.mjs"), false);
  assert.deepEqual(ENVIRONMENT_PROFILES.core, ["node", "git"]);
  assert(ENVIRONMENT_PROFILES.frontend.includes("pnpm"));
  assert(ENVIRONMENT_PROFILES.backend.includes("clojure"));
  assert(ENVIRONMENT_PROFILES.devenv.includes("docker"));
  assert.equal(normalizeProfile("FRONTEND"), "frontend");
  assert.throws(() => normalizeProfile("unknown"), /Unknown environment profile/);

  const fixture = copyCoreFixture(root);
  const optionalMap = path.join(fixture, ".harness/sourcemap.config.json");
  if (fs.existsSync(optionalMap)) fs.rmSync(optionalMap);
  const fixtureReport = validateHarness(fixture, { strict: true });
  assert.equal(fixtureReport.ok, true, JSON.stringify(fixtureReport.issues, null, 2));

  fs.rmSync(path.join(fixture, ".harness/STATUS.md"));
  const missingStatus = validateHarness(fixture, { strict: true });
  assert.equal(missingStatus.ok, false);
  assert(missingStatus.issues.some((item) => item.code === "missing-core-file"));
  fs.rmSync(fixture, { recursive: true, force: true });

  return { passed: 14, harness_score: report.score, required_files: report.required_files };
}

if (isMain(import.meta.url)) {
  try {
    const result = runTests();
    console.log(`HARNESS TEST PASS (${result.passed} assertions, ${result.required_files} required files, score ${result.harness_score}/100)`);
  } catch (error) {
    console.error(error.stack || error.message);
    process.exitCode = 1;
  }
}
