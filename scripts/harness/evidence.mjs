import fs from "node:fs";
import path from "node:path";
import { spawn } from "node:child_process";
import { REPO_ROOT, bounded, gitValue, isMain, parseArgs, readJson, writeJson } from "./lib.mjs";

const DENIED_RISKS = new Set(["destructive"]);
const DENIED_PATTERNS = [
  /\brm\s+-rf\b/i,
  /\bdrop-devenv\b/i,
  /docker\s+compose\s+down\b.*(?:-v|--volumes)/i,
  /docker\s+volume\s+rm\b/i,
  /\bgit\s+push\b.*--force/i,
];

export async function runRegisteredCheck(id, {
  root = REPO_ROOT,
  record = true,
} = {}) {
  const registry = readJson(path.join(root, ".harness/checks.json"));
  const check = (registry.checks || []).find((item) => item.id === id);
  if (!check) throw new Error(`Unknown check id: ${id}`);
  if (DENIED_RISKS.has(check.risk)) throw new Error(`Check ${id} has denied risk ${check.risk}`);
  if (DENIED_PATTERNS.some((pattern) => pattern.test(check.command))) {
    throw new Error(`Check ${id} contains a denied command pattern`);
  }

  const cwd = path.resolve(root, check.cwd);
  if (!cwd.startsWith(path.resolve(root))) throw new Error(`Check ${id} cwd escapes repository`);
  if (!fs.existsSync(cwd)) throw new Error(`Check ${id} cwd does not exist: ${check.cwd}`);

  const startedAt = new Date();
  let stdout = "";
  let stderr = "";
  let timedOut = false;

  const result = await new Promise((resolve) => {
    const child = spawn(check.command, {
      cwd,
      shell: true,
      env: process.env,
      stdio: ["ignore", "pipe", "pipe"],
    });
    child.stdout.on("data", (chunk) => {
      const text = chunk.toString();
      stdout += text;
      process.stdout.write(text);
    });
    child.stderr.on("data", (chunk) => {
      const text = chunk.toString();
      stderr += text;
      process.stderr.write(text);
    });
    child.on("error", (error) => resolve({ exitCode: null, error: error.message }));
    child.on("close", (code, signal) => resolve({ exitCode: code, signal, error: null }));
    const timer = setTimeout(() => {
      timedOut = true;
      child.kill("SIGTERM");
      setTimeout(() => child.kill("SIGKILL"), 3000).unref();
    }, check.timeout_ms);
    child.on("close", () => clearTimeout(timer));
  });

  const finishedAt = new Date();
  const evidence = {
    schema_version: 1,
    check_id: id,
    description: check.description,
    command: check.command,
    cwd: check.cwd,
    risk: check.risk,
    revision: gitValue(["rev-parse", "HEAD"], root),
    started_at: startedAt.toISOString(),
    finished_at: finishedAt.toISOString(),
    duration_ms: finishedAt - startedAt,
    exit_code: result.exitCode,
    signal: result.signal || null,
    timed_out: timedOut,
    result: !timedOut && result.exitCode === 0 ? "pass" : "fail",
    error: result.error || null,
    stdout: bounded(stdout),
    stderr: bounded(stderr),
  };

  if (record) {
    const stamp = startedAt.toISOString().replace(/[:.]/g, "-");
    const destination = path.join(root, ".harness/evidence", `${stamp}-${id}.json`);
    writeJson(destination, evidence);
    console.log(`Evidence: ${path.relative(root, destination)}`);
  }
  return evidence;
}

if (isMain(import.meta.url)) {
  const { positional, options } = parseArgs(process.argv.slice(2));
  const id = positional[0];
  if (!id) {
    console.error("Usage: node scripts/harness/evidence.mjs <check-id> [--no-record] [--root PATH]");
    process.exitCode = 2;
  } else {
    try {
      const evidence = await runRegisteredCheck(id, {
        root: path.resolve(options.root || REPO_ROOT),
        record: !options["no-record"],
      });
      process.exitCode = evidence.result === "pass" ? 0 : 1;
    } catch (error) {
      console.error(error.message);
      process.exitCode = 1;
    }
  }
}
