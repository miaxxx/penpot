#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const usage = `Usage:
  node scripts/harness/sourcemap-check.mjs [options] <file-or-directory>...

Options:
  --allow-empty         Exit 0 when no .map files are found.
  --require-reference   Require the generated file to reference its map.
  --root <directory>    Allowed source root (default: current directory).
  --help                Show this help.
`;

const args = process.argv.slice(2);
let allowEmpty = false;
let requireReference = false;
let root = process.cwd();
const targets = [];

for (let index = 0; index < args.length; index += 1) {
  const arg = args[index];
  if (arg === "--allow-empty") {
    allowEmpty = true;
  } else if (arg === "--require-reference") {
    requireReference = true;
  } else if (arg === "--root") {
    const value = args[index + 1];
    if (!value) {
      console.error("ERROR: --root requires a directory.");
      process.exit(2);
    }
    root = path.resolve(value);
    index += 1;
  } else if (arg === "--help" || arg === "-h") {
    console.log(usage);
    process.exit(0);
  } else if (arg.startsWith("-")) {
    console.error(`ERROR: unknown option ${arg}`);
    console.error(usage);
    process.exit(2);
  } else {
    targets.push(path.resolve(arg));
  }
}

if (targets.length === 0) {
  console.error("ERROR: provide at least one file or directory.");
  console.error(usage);
  process.exit(2);
}

if (!fs.existsSync(root) || !fs.statSync(root).isDirectory()) {
  console.error(`ERROR: root is not a directory: ${root}`);
  process.exit(2);
}

const ignoredDirectories = new Set([
  ".git",
  ".shadow-cljs",
  "node_modules",
  ".yarn",
]);

function collectMaps(target, output) {
  if (!fs.existsSync(target)) {
    return;
  }
  const stat = fs.lstatSync(target);
  if (stat.isSymbolicLink()) {
    return;
  }
  if (stat.isFile()) {
    if (target.endsWith(".map")) {
      output.push(target);
    }
    return;
  }
  if (!stat.isDirectory() || ignoredDirectories.has(path.basename(target))) {
    return;
  }
  for (const entry of fs.readdirSync(target, { withFileTypes: true })) {
    collectMaps(path.join(target, entry.name), output);
  }
}

function isWithin(candidate, parent) {
  const relative = path.relative(parent, candidate);
  return relative === "" || (!relative.startsWith(`..${path.sep}`) && relative !== ".." && !path.isAbsolute(relative));
}

function unsafeSourceReason(source) {
  if (typeof source !== "string" || source.length === 0) {
    return "source entry must be a non-empty string";
  }
  if (/^(?:https?:|file:)/i.test(source)) {
    return "network and file URLs are forbidden";
  }
  if (/^[A-Za-z]:[\\/]/.test(source)) {
    return "drive-letter absolute paths are forbidden";
  }
  if (/^(?:\\\\|\/\/)/.test(source)) {
    return "UNC paths are forbidden";
  }
  if (path.posix.isAbsolute(source) || path.win32.isAbsolute(source)) {
    return "absolute paths are forbidden";
  }
  if (source === "~" || source.startsWith("~/") || source.startsWith("~\\")) {
    return "home-directory paths are forbidden";
  }
  return null;
}

function generatedFileFor(mapPath, payload) {
  if (typeof payload.file === "string" && payload.file.length > 0) {
    return path.resolve(path.dirname(mapPath), payload.file);
  }
  return mapPath.slice(0, -4);
}

function validateMap(mapPath) {
  const errors = [];
  let payload;

  try {
    payload = JSON.parse(fs.readFileSync(mapPath, "utf8"));
  } catch (error) {
    return [`invalid JSON: ${error.message}`];
  }

  if (payload.version !== 3) {
    errors.push("version must equal 3");
  }
  if (!Array.isArray(payload.sources) || payload.sources.length === 0) {
    errors.push("sources must be a non-empty array");
  }
  if (typeof payload.mappings !== "string" || payload.mappings.length === 0) {
    errors.push("mappings must be a non-empty string");
  }
  if (payload.names !== undefined && !Array.isArray(payload.names)) {
    errors.push("names must be an array when present");
  }
  if (payload.sourceRoot !== undefined && typeof payload.sourceRoot !== "string") {
    errors.push("sourceRoot must be a string when present");
  }
  if (payload.sourcesContent !== undefined && !Array.isArray(payload.sourcesContent)) {
    errors.push("sourcesContent must be an array when present");
  }
  if (payload.file !== undefined) {
    const fileReason = unsafeSourceReason(payload.file);
    if (fileReason) {
      errors.push(`unsafe file field "${payload.file}": ${fileReason}`);
    }
  }

  if (Array.isArray(payload.sources)) {
    const sourceRoot = payload.sourceRoot ?? "";
    const rootReason = sourceRoot ? unsafeSourceReason(sourceRoot) : null;
    if (rootReason) {
      errors.push(`unsafe sourceRoot "${sourceRoot}": ${rootReason}`);
    }

    payload.sources.forEach((source, index) => {
      const reason = unsafeSourceReason(source);
      if (reason) {
        errors.push(`unsafe source[${index}] "${String(source)}": ${reason}`);
        return;
      }

      const embedded = Array.isArray(payload.sourcesContent)
        && typeof payload.sourcesContent[index] === "string";
      const resolved = path.resolve(path.dirname(mapPath), sourceRoot, source);

      if (!isWithin(resolved, root)) {
        errors.push(`source[${index}] escapes allowed root: ${source}`);
        return;
      }
      if (!embedded && !fs.existsSync(resolved)) {
        errors.push(`source[${index}] is not embedded and does not exist: ${source}`);
      }
    });
  }

  if (requireReference) {
    const generated = generatedFileFor(mapPath, payload);
    if (!isWithin(generated, root)) {
      errors.push(`generated file escapes allowed root: ${generated}`);
    } else if (!fs.existsSync(generated)) {
      errors.push(`generated file does not exist: ${generated}`);
    } else {
      const content = fs.readFileSync(generated, "utf8");
      const expected = path.basename(mapPath).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
      const reference = new RegExp(`sourceMappingURL\\s*=\\s*(?:[^\\r\\n]*\\/)?${expected}(?:\\s|$)`);
      if (!reference.test(content)) {
        errors.push(`generated file does not reference ${path.basename(mapPath)}`);
      }
    }
  }

  return errors;
}

const maps = [];
for (const target of targets) {
  collectMaps(target, maps);
}
maps.sort();

if (maps.length === 0) {
  const message = "No source maps found.";
  if (allowEmpty) {
    console.log(`PASS: ${message}`);
    process.exit(0);
  }
  console.error(`FAIL: ${message}`);
  process.exit(1);
}

let failureCount = 0;
for (const mapPath of maps) {
  const errors = validateMap(mapPath);
  const shown = path.relative(process.cwd(), mapPath) || mapPath;
  if (errors.length === 0) {
    console.log(`PASS: ${shown}`);
  } else {
    failureCount += 1;
    console.error(`FAIL: ${shown}`);
    for (const error of errors) {
      console.error(`  - ${error}`);
    }
  }
}

if (failureCount > 0) {
  console.error(`Source-map validation failed: ${failureCount}/${maps.length} map(s).`);
  process.exit(1);
}

console.log(`Source-map validation passed: ${maps.length} map(s).`);
