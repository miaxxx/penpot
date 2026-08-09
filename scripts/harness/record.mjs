#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const usage = `Usage:
  node scripts/harness/record.mjs [options]

Required:
  --task <id>
  --status <planned|in-progress|blocked|completed>
  --summary <text>

Optional:
  --command <command>   Repeatable.
  --result <text>
  --next <text>
  --dry-run
  --help
`;

const args = process.argv.slice(2);
const values = {
  commands: [],
  result: "",
  next: "",
  dryRun: false,
};

for (let index = 0; index < args.length; index += 1) {
  const arg = args[index];
  if (arg === "--help" || arg === "-h") {
    console.log(usage);
    process.exit(0);
  }
  if (arg === "--dry-run") {
    values.dryRun = true;
    continue;
  }
  const value = args[index + 1];
  if (!value) {
    console.error(`ERROR: ${arg} requires a value.`);
    process.exit(2);
  }
  if (arg === "--task") values.task = value;
  else if (arg === "--status") values.status = value;
  else if (arg === "--summary") values.summary = value;
  else if (arg === "--command") values.commands.push(value);
  else if (arg === "--result") values.result = value;
  else if (arg === "--next") values.next = value;
  else {
    console.error(`ERROR: unknown option ${arg}`);
    console.error(usage);
    process.exit(2);
  }
  index += 1;
}

const statuses = new Set(["planned", "in-progress", "blocked", "completed"]);
if (!values.task || !values.status || !values.summary) {
  console.error("ERROR: --task, --status, and --summary are required.");
  console.error(usage);
  process.exit(2);
}
if (!statuses.has(values.status)) {
  console.error(`ERROR: invalid status ${values.status}`);
  process.exit(2);
}

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const taskMapPath = path.join(repoRoot, ".harness/task-map.json");
const taskMap = JSON.parse(fs.readFileSync(taskMapPath, "utf8"));
const timestamp = new Date().toISOString();

const event = {
  timestamp,
  task: values.task,
  status: values.status,
  summary: values.summary,
  commands: values.commands,
  result: values.result,
  next: values.next,
};

taskMap.updatedAt = timestamp;
taskMap.current = {
  ...(taskMap.current ?? {}),
  id: values.task,
  status: values.status,
  next: values.next,
};
taskMap.events = [...(taskMap.events ?? []), event].slice(-200);

const serialized = `${JSON.stringify(taskMap, null, 2)}\n`;
if (values.dryRun) {
  process.stdout.write(serialized);
} else {
  fs.writeFileSync(taskMapPath, serialized, "utf8");
  console.log(`Recorded ${values.task} as ${values.status} in .harness/task-map.json`);
}
