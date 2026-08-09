import fs from "node:fs";
import path from "node:path";
import { REPO_ROOT, isMain, parseArgs, readJson, writeJson } from "./lib.mjs";

const LANGUAGE = new Map([
  [".clj", "clojure"], [".cljs", "clojurescript"], [".cljc", "clojure-common"],
  [".bb", "babashka"], [".js", "javascript"], [".mjs", "javascript"],
  [".cjs", "javascript"], [".ts", "typescript"], [".tsx", "typescript-react"],
  [".rs", "rust"], [".scss", "scss"], [".css", "css"], [".map", "source-map"],
]);

export function sanitizeSourcePath(sourcePath) {
  let value = String(sourcePath || "")
    .replace(/\\/g, "/")
    .replace(/^webpack:\/\/\/?/, "")
    .replace(/^file:\/\/\/?/, "")
    .replace(/^https?:\/\/[^/]+\//, "")
    .replace(/[?#].*$/, "")
    .replace(/^\/+/, "");
  const safe = [];
  for (const part of value.split("/")) {
    if (!part || part === ".") continue;
    if (part === "..") safe.push("_dotdot_");
    else safe.push(part.replace(/[\0-\x1f]/g, "_"));
  }
  return safe.join("/") || "_unknown_source_";
}

function shouldExclude(relative, excludes) {
  const normalized = relative.replace(/\\/g, "/");
  return excludes.some((entry) => {
    const clean = String(entry).replace(/^\.?\//, "").replace(/\/$/, "");
    return normalized === clean || normalized.startsWith(`${clean}/`) || normalized.includes(`/${clean}/`);
  });
}

function walkEntry(root, entry, config, found, skipped) {
  const absolute = path.resolve(root, entry);
  if (!absolute.startsWith(path.resolve(root))) {
    skipped.push({ path: entry, reason: "outside-root" });
    return;
  }
  if (!fs.existsSync(absolute)) {
    skipped.push({ path: entry, reason: "missing" });
    return;
  }
  const stat = fs.statSync(absolute);
  if (stat.isFile()) {
    found.add(absolute);
    return;
  }
  const stack = [absolute];
  while (stack.length) {
    const current = stack.pop();
    const relativeDir = path.relative(root, current);
    if (shouldExclude(relativeDir, config.exclude || [])) continue;
    for (const dirent of fs.readdirSync(current, { withFileTypes: true })) {
      const child = path.join(current, dirent.name);
      const relative = path.relative(root, child);
      if (shouldExclude(relative, config.exclude || [])) continue;
      if (dirent.isDirectory()) stack.push(child);
      else if (dirent.isFile() && (config.extensions || []).includes(path.extname(dirent.name))) found.add(child);
    }
  }
}

function unique(values) {
  return [...new Set(values.filter(Boolean))].sort();
}

export function parseClojure(content) {
  const namespace = content.match(/\(ns\s+([^\s()[\]{}]+)/)?.[1] || null;
  const symbols = [];
  const symbolPattern = /\((defn-?|defmacro|defprotocol|defrecord|deftype|defmulti|defmethod)\s+(?:\^[^\s]+\s+)?([^\s()[\]{}]+)/g;
  for (const match of content.matchAll(symbolPattern)) {
    symbols.push({ kind: match[1], name: match[2] });
  }
  const header = content.slice(0, 30000);
  const imports = [];
  for (const match of header.matchAll(/\[\s*([a-zA-Z0-9_.-]+)(?:\s|:|\])/g)) {
    if (match[1].includes(".")) imports.push(match[1]);
  }
  return { namespace, symbols, imports: unique(imports) };
}

export function parseJavaScript(content) {
  const imports = [];
  for (const match of content.matchAll(/(?:import|export)\s+(?:[\s\S]*?\s+from\s+)?["']([^"']+)["']/g)) imports.push(match[1]);
  for (const match of content.matchAll(/require\(\s*["']([^"']+)["']\s*\)/g)) imports.push(match[1]);
  const symbols = [];
  for (const match of content.matchAll(/\b(?:export\s+)?(?:async\s+)?(function|class)\s+([A-Za-z_$][\w$]*)/g)) {
    symbols.push({ kind: match[1], name: match[2] });
  }
  for (const match of content.matchAll(/\bexport\s+(?:const|let|var)\s+([A-Za-z_$][\w$]*)/g)) {
    symbols.push({ kind: "export", name: match[1] });
  }
  return { namespace: null, symbols, imports: unique(imports) };
}

export function parseRust(content) {
  const imports = unique([...content.matchAll(/\buse\s+([^;]+);/g)].map((match) => match[1].trim()));
  const symbols = [...content.matchAll(/\b(?:pub\s+)?(fn|struct|enum|trait|mod)\s+([A-Za-z_][\w]*)/g)]
    .map((match) => ({ kind: match[1], name: match[2] }));
  return { namespace: null, symbols, imports };
}

function parseSourceMap(content, relativePath) {
  const value = JSON.parse(content);
  const sources = Array.isArray(value.sources) ? value.sources : [];
  const contents = Array.isArray(value.sourcesContent) ? value.sourcesContent : [];
  return {
    path: relativePath,
    source_root: value.sourceRoot || null,
    generated_file: value.file || relativePath.replace(/\.map$/, ""),
    sources: sources.map((source, index) => ({
      raw: source,
      safe: sanitizeSourcePath(source),
      embedded: typeof contents[index] === "string",
      bytes: typeof contents[index] === "string" ? Buffer.byteLength(contents[index]) : 0,
    })),
  };
}

function resolveLocalImport(fromPath, specifier, fileIndex) {
  if (!specifier.startsWith(".")) return null;
  const base = path.posix.normalize(path.posix.join(path.posix.dirname(fromPath), specifier));
  const candidates = [base, ...[".ts", ".tsx", ".js", ".mjs", ".cjs", ".cljs", ".cljc", ".clj"].map((ext) => `${base}${ext}`),
    ...["index.ts", "index.tsx", "index.js"].map((name) => `${base}/${name}`)];
  return candidates.find((candidate) => fileIndex.has(candidate)) || null;
}

export function buildSourceMap({
  root = REPO_ROOT,
  profile = "ai",
  output = null,
  configPath = ".harness/sourcemap.config.json",
} = {}) {
  const config = readJson(path.join(root, configPath));
  const entries = config.profiles?.[profile];
  if (!entries) throw new Error(`Unknown source-map profile: ${profile}`);
  const found = new Set();
  const skipped = [];
  for (const entry of entries) walkEntry(root, entry, config, found, skipped);

  const files = [];
  const sourceMaps = [];
  const maxFile = Number(config.max_file_bytes || 1048576);
  const maxMap = Number(config.max_source_map_bytes || 5242880);

  for (const absolute of [...found].sort()) {
    const relative = path.relative(root, absolute).replace(/\\/g, "/");
    const ext = path.extname(relative);
    const stat = fs.statSync(absolute);
    const limit = ext === ".map" ? maxMap : maxFile;
    if (stat.size > limit) {
      skipped.push({ path: relative, reason: "oversize", bytes: stat.size });
      continue;
    }
    const content = fs.readFileSync(absolute, "utf8");
    if (ext === ".map") {
      try {
        sourceMaps.push(parseSourceMap(content, relative));
      } catch (error) {
        skipped.push({ path: relative, reason: `invalid-source-map: ${error.message}` });
      }
      continue;
    }

    let parsed = { namespace: null, symbols: [], imports: [] };
    if ([".clj", ".cljs", ".cljc", ".bb"].includes(ext)) parsed = parseClojure(content);
    else if ([".js", ".mjs", ".cjs", ".ts", ".tsx"].includes(ext)) parsed = parseJavaScript(content);
    else if (ext === ".rs") parsed = parseRust(content);

    files.push({
      id: relative,
      path: relative,
      language: LANGUAGE.get(ext) || ext.slice(1),
      bytes: stat.size,
      namespace: parsed.namespace,
      symbols: parsed.symbols,
      imports: parsed.imports,
    });
  }

  const fileIndex = new Set(files.map((item) => item.path));
  const namespaceIndex = new Map(files.filter((item) => item.namespace).map((item) => [item.namespace, item.path]));
  const edges = [];
  for (const file of files) {
    for (const dependency of file.imports) {
      const target = namespaceIndex.get(dependency) || resolveLocalImport(file.path, dependency, fileIndex);
      edges.push({
        from: file.path,
        to: target || dependency,
        kind: target ? "internal-dependency" : "external-or-unresolved",
      });
    }
  }
  edges.sort((a, b) => `${a.from}\0${a.to}`.localeCompare(`${b.from}\0${b.to}`));

  const result = {
    schema_version: 1,
    profile,
    generated_at: new Date().toISOString(),
    root: ".",
    summary: {
      files: files.length,
      symbols: files.reduce((sum, file) => sum + file.symbols.length, 0),
      edges: edges.length,
      source_maps: sourceMaps.length,
      skipped: skipped.length,
    },
    files,
    edges,
    source_maps: sourceMaps.sort((a, b) => a.path.localeCompare(b.path)),
    skipped: skipped.sort((a, b) => a.path.localeCompare(b.path)),
  };
  const destination = path.resolve(root, output || config.output);
  if (!destination.startsWith(path.resolve(root)) && !output) throw new Error("Configured output escapes repository");
  writeJson(destination, result);
  return { result, destination };
}

export function querySourceMap(term, { root = REPO_ROOT, input = ".harness/generated/code-map.json", limit = 25 } = {}) {
  const map = readJson(path.resolve(root, input));
  const needle = String(term || "").toLowerCase();
  const matches = [];
  for (const file of map.files || []) {
    const haystack = [
      file.path,
      file.namespace,
      ...(file.symbols || []).flatMap((item) => [item.name, item.kind]),
      ...(file.imports || []),
    ].filter(Boolean).join(" ").toLowerCase();
    if (haystack.includes(needle)) matches.push(file);
  }
  return matches.slice(0, limit);
}

function printMatches(matches) {
  if (!matches.length) {
    console.log("No matches.");
    return;
  }
  for (const file of matches) {
    const symbols = (file.symbols || []).slice(0, 8).map((item) => `${item.kind}:${item.name}`).join(", ");
    console.log(`${file.path}${file.namespace ? ` [${file.namespace}]` : ""}`);
    if (symbols) console.log(`  ${symbols}`);
  }
}

if (isMain(import.meta.url)) {
  const { positional, options } = parseArgs(process.argv.slice(2));
  const command = positional[0] || "build";
  const root = path.resolve(options.root || REPO_ROOT);
  try {
    if (command === "build") {
      const { result, destination } = buildSourceMap({
        root,
        profile: options.profile || "ai",
        output: options.output || null,
      });
      console.log(`Source map: ${path.relative(root, destination)}`);
      console.log(JSON.stringify(result.summary, null, 2));
    } else if (command === "query") {
      const term = positional[1] || options.term;
      if (!term) throw new Error("query requires a term");
      printMatches(querySourceMap(term, {
        root,
        input: options.input || ".harness/generated/code-map.json",
        limit: Number(options.limit || 25),
      }));
    } else {
      throw new Error(`Unknown command: ${command}`);
    }
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
