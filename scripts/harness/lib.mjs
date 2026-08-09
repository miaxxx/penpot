import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

export const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
export const REPO_ROOT = path.resolve(SCRIPT_DIR, "../..");

export function readJson(filePath) {
  try {
    return JSON.parse(fs.readFileSync(filePath, "utf8"));
  } catch (error) {
    throw new Error(`Cannot read JSON ${filePath}: ${error.message}`);
  }
}

export function writeJson(filePath, value) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, "utf8");
}

export function exists(root, relativePath) {
  return fs.existsSync(path.join(root, relativePath));
}

export function readText(root, relativePath) {
  return fs.readFileSync(path.join(root, relativePath), "utf8");
}

export function commandVersion(command, args = ["--version"], cwd = REPO_ROOT) {
  const result = spawnSync(command, args, {
    cwd,
    encoding: "utf8",
    timeout: 10000,
    shell: process.platform === "win32",
  });
  return {
    available: result.status === 0,
    version: String(result.stdout || result.stderr || "").trim().split(/\r?\n/)[0] || null,
    error: result.error?.message || null,
  };
}

export function gitValue(args, cwd = REPO_ROOT) {
  const result = spawnSync("git", args, { cwd, encoding: "utf8", timeout: 10000 });
  return result.status === 0 ? String(result.stdout).trim() : null;
}

export function parseArgs(argv) {
  const positional = [];
  const options = {};
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    if (!token.startsWith("--")) {
      positional.push(token);
      continue;
    }
    const key = token.slice(2);
    const next = argv[i + 1];
    if (next && !next.startsWith("--")) {
      options[key] = next;
      i += 1;
    } else {
      options[key] = true;
    }
  }
  return { positional, options };
}

export function isMain(metaUrl) {
  return process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(metaUrl);
}

export function bounded(value, max = 200000) {
  const text = String(value || "");
  return text.length <= max ? text : `${text.slice(0, max)}\n...[truncated ${text.length - max} chars]`;
}
