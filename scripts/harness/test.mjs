import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { REPO_ROOT, isMain } from "./lib.mjs";
import { validateHarness } from "./check.mjs";
import { buildSourceMap, parseClojure, parseJavaScript, querySourceMap, sanitizeSourcePath } from "./sourcemap.mjs";

export function runTests(root = REPO_ROOT) {
  assert.equal(sanitizeSourcePath("../../secret.ts?raw"), "_dotdot_/_dotdot_/secret.ts");
  assert.equal(sanitizeSourcePath("webpack:///src/main.ts"), "src/main.ts");
  assert.equal(sanitizeSourcePath("https://example.com/src/a.ts#x"), "src/a.ts");

  const cljs = parseClojure(`(ns app.demo (:require [app.helper :as h]))\n(defn run [] (h/go))`);
  assert.equal(cljs.namespace, "app.demo");
  assert(cljs.imports.includes("app.helper"));
  assert(cljs.symbols.some((item) => item.name === "run"));

  const ts = parseJavaScript(`import { x } from "./helper"; export function run() { return x; }`);
  assert(ts.imports.includes("./helper"));
  assert(ts.symbols.some((item) => item.name === "run"));

  const report = validateHarness(root, { strict: true });
  assert.equal(report.ok, true, JSON.stringify(report.issues, null, 2));

  const fixture = fs.mkdtempSync(path.join(os.tmpdir(), "penpot-harness-map-"));
  fs.mkdirSync(path.join(fixture, ".harness"), { recursive: true });
  fs.mkdirSync(path.join(fixture, "src"), { recursive: true });
  fs.writeFileSync(path.join(fixture, "src", "helper.cljs"), `(ns app.helper)\n(defn go [] true)\n`);
  fs.writeFileSync(path.join(fixture, "src", "main.cljs"), `(ns app.main (:require [app.helper :as h]))\n(defn run [] (h/go))\n`);
  fs.writeFileSync(path.join(fixture, "src", "helper.ts"), `export const value = 1;\n`);
  fs.writeFileSync(path.join(fixture, "src", "main.ts"), `import { value } from "./helper"; export function read() { return value; }\n`);
  fs.writeFileSync(path.join(fixture, "src", "bundle.js.map"), JSON.stringify({
    version: 3,
    file: "bundle.js",
    sources: ["webpack:///../src/original.ts"],
    sourcesContent: ["export const original = true;"],
    names: [],
    mappings: "",
  }));
  fs.writeFileSync(path.join(fixture, ".harness", "sourcemap.config.json"), JSON.stringify({
    version: 1,
    output: ".harness/generated/code-map.json",
    max_file_bytes: 100000,
    max_source_map_bytes: 100000,
    extensions: [".cljs", ".ts", ".map"],
    exclude: [".harness/generated"],
    profiles: { test: ["src"] },
  }));

  const { result } = buildSourceMap({ root: fixture, profile: "test" });
  assert.equal(result.summary.files, 4);
  assert(result.edges.some((edge) => edge.from === "src/main.cljs" && edge.to === "src/helper.cljs"));
  assert(result.edges.some((edge) => edge.from === "src/main.ts" && edge.to === "src/helper.ts"));
  assert.equal(result.source_maps[0].sources[0].safe, "_dotdot_/src/original.ts");
  assert.equal(querySourceMap("app.main", { root: fixture }).length, 1);
  fs.rmSync(fixture, { recursive: true, force: true });

  return { passed: 12, harness_score: report.score };
}

if (isMain(import.meta.url)) {
  try {
    const result = runTests();
    console.log(`HARNESS TEST PASS (${result.passed} assertions, score ${result.harness_score}/100)`);
  } catch (error) {
    console.error(error.stack || error.message);
    process.exitCode = 1;
  }
}
