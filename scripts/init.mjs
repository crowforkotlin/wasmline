#!/usr/bin/env node
/**
 * Wasmtime C-API Init Script (Node.js / ESM)
 * ============================================
 * Equivalent to init.sh — downloads and deploys Wasmtime C-API platform assets.
 *
 * Usage:
 *   node scripts/init.mjs [proxy]
 *
 * Examples:
 *   node scripts/init.mjs
 *   node scripts/init.mjs 127.0.0.1:7890
 */

import { createWriteStream, cpSync, existsSync, mkdirSync, rmSync, statSync, readdirSync } from "node:fs";
import { basename, dirname, join, resolve } from "node:path";
import { createInterface } from "node:readline";
import { pipeline } from "node:stream/promises";
import { createGunzip } from "node:zlib";
import { fileURLToPath } from "node:url";
import { execFileSync, execSync } from "node:child_process";
import { randomBytes } from "node:crypto";

const __filename = fileURLToPath(import.meta.url);
const SCRIPT_DIR = dirname(__filename);
const PROJECT_ROOT = resolve(SCRIPT_DIR, "..");
const PLATFORMS_ROOT = join(PROJECT_ROOT, "build", "platforms");
const REPO = "crowforkotlin/wasmtime";

// ── Colors ──────────────────────────────────────────────────────────────────
const isTTY = process.stdout.isTTY && !process.env.NO_COLOR;
const c = (code, t) => (isTTY ? `\x1b[${code}m${t}\x1b[0m` : t);
const red = (t) => c("1;31", t);
const green = (t) => c("1;32", t);
const yellow = (t) => c("1;33", t);
const blue = (t) => c("1;34", t);
const magenta = (t) => c("1;35", t);
const cyan = (t) => c("1;36", t);
const white = (t) => c("1;37", t);
const gray = (t) => c("0;90", t);

const logInfo = (m) => console.log(`${magenta("[INFO]")} ${m}`);
const logOk = (m) => console.log(`${green("[OK]")}   ${m}`);
const logWarn = (m) => console.log(`${yellow("[WARN]")} ${m}`);
const logErr = (m) => console.log(`${red("[ERR]")}  ${m}`);
const logStep = (m) => console.log(`${blue("[STEP]")} ${m}`);
const logDetail = (m) => console.log(`       ${gray("└─")} ${m}`);
const logHeader = (m) => {
  const bar = cyan("=".repeat(49));
  console.log(`${bar}\n      ${m}\n${bar}`);
};

// ── Helpers ─────────────────────────────────────────────────────────────────

function formatSize(n) {
  if (n < 1024) return `${n}B`;
  if (n < 1048576) return `${Math.round((n + 512) / 1024)}KB`;
  const mb = Math.floor((n * 100) / 1048576);
  return `${Math.floor(mb / 100)}.${String(mb % 100).padStart(2, "0")}MB`;
}

function ask(prompt) {
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  return new Promise((res) => rl.question(prompt, (a) => { rl.close(); res(a.trim()); }));
}

function tmpDir() {
  const p = join(PLATFORMS_ROOT, `.tmp_${randomBytes(6).toString("hex")}`);
  mkdirSync(p, { recursive: true });
  return p;
}

// ── Targets ─────────────────────────────────────────────────────────────────

// Ordered, grouped by platform family.
const TARGETS = [
  // Android
  { key: "1", name: "Android   arm64-v8a",          filter: "aarch64-android",                platform: "android/arm64-v8a" },
  { key: "2", name: "Android   armeabi-v7a  [pulley only]", filter: "armv7-android",           platform: "android/armeabi-v7a" },
  { key: "3", name: "Android   x86          [pulley only]", filter: "x86-android",             platform: "android/x86" },
  { key: "4", name: "Android   x86_64",             filter: "x86_64-android",                 platform: "android/x86_64" },
  // iOS
  { key: "5", name: "iOS       arm64 (Device)   [pulley only]", filter: "aarch64-ios-pulley-min-c-api",   platform: "ios/arm64" },
  { key: "6", name: "iOS       arm64 (Simulator)[pulley only]", filter: "aarch64-ios-sim-pulley-min-c-api", platform: "ios/simulator-arm64" },
  // Linux
  { key: "7", name: "Linux     aarch64",            filter: "aarch64-linux",                  platform: "linux/aarch64" },
  { key: "8", name: "Linux     x64",                filter: "x86_64-linux",                   platform: "linux/x64" },
  // macOS
  { key: "9", name: "macOS     aarch64",            filter: "aarch64-macos",                  platform: "mac/aarch64" },
  { key: "0", name: "macOS     x64",                filter: "x86_64-macos",                   platform: "mac/x64" },
  // Windows
  { key: "x", name: "Windows   x64",                filter: "x86_64-windows",                 platform: "windows/x64" },
  // Other
  { key: "a", name: "All Platforms (cranelift + pulley)", filter: "all",                      platform: null },
];

// Group definitions for menu rendering: [label, [indices into TARGETS]]
const TARGET_GROUPS = [
  ["Android", [0, 1, 2, 3]],
  ["iOS",     [4, 5]],
  ["Linux",   [6, 7]],
  ["macOS",   [8, 9]],
  ["Windows", [10]],
  ["Other",   [11]],
];

const TARGETS_BY_KEY = new Map(TARGETS.map((target) => [target.key, target]));

const PLATFORM_MAP = {
  // Short keys — variant-agnostic (work for both pulley and cranelift assets)
  "aarch64-android": "android/arm64-v8a",
  "aarch64-ios-sim": "ios/simulator-arm64",
  "aarch64-ios":     "ios/arm64",
  "aarch64-linux":   "linux/aarch64",
  "x86_64-linux":    "linux/x64",
  "aarch64-macos":   "mac/aarch64",
  "x86_64-macos":    "mac/x64",
  "x86_64-windows":  "windows/x64",
  "armv7-android":   "android/armeabi-v7a",
  "x86-android":     "android/x86",
  "x86_64-android":  "android/x86_64",
};

// Pulley-only platforms (iOS, armeabi-v7a, x86) — Cranelift not available.
const PULLEY_ONLY_FILTERS = new Set([
  "aarch64-ios-pulley-min-c-api",
  "aarch64-ios-sim-pulley-min-c-api",
  "armv7-android",
  "x86-android",
]);

async function selectTarget() {
  console.log();
  logHeader("Platform & Architecture Selection");
  console.log();

  const nameW = Math.max(...TARGETS.map((t) => t.name.length), 22);

  for (const [groupLabel, indices] of TARGET_GROUPS) {
    console.log(`  ${gray(`── ${groupLabel} ──`)}`);
    for (const i of indices) {
      const t = TARGETS[i];
      const padded = t.name.padEnd(nameW);
      const pathStr = t.platform ? `build/platforms/${t.platform}` : "—";
      console.log(`  ${white(t.key + ")")} ${padded}  ${gray(`→ ${pathStr}`)}`);
    }
    console.log();
  }

  while (true) {
    const choice = (await ask(`  ${cyan("Enter choice [1-9, 0, x, a]:")} `)).toLowerCase();
    const target = TARGETS_BY_KEY.get(choice);
    if (target) {
      console.log();
      logOk(`Target: ${white(target.name)}`);
      return target.filter;
    }
    console.log(`  ${red("Invalid input, please try again.")}`);
  }
}

async function selectVariant(userFilter) {
  console.log();
  logHeader("Runtime Variant Selection");

  let variant;
  if (userFilter === "all") {
    variant = "both";
    logInfo("All Platforms: downloading both Cranelift and Pulley assets.");
  } else if (PULLEY_ONLY_FILTERS.has(userFilter)) {
    variant = "pulley";
    logInfo("Platform requires Pulley runtime (no Cranelift support).");
  } else {
    console.log(`  ${white("1)")} Cranelift — .pwasm + .cwasm AOT  ${gray("(default, larger binary)")}`);
    console.log(`  ${white("2)")} Pulley    — .pwasm only            ${gray("(smaller binary)")}`);
    console.log();
    while (true) {
      const v = (await ask(`  ${cyan("Choice [1/2] (default: 1):")} `)).trim();
      if (v === "" || v === "1") { variant = "cranelift"; break; }
      if (v === "2") { variant = "pulley"; break; }
      console.log(`  ${red("Invalid input, please try again.")}`);
    }
  }

  console.log();
  logOk(`Variant: ${white(variant)}`);
  return variant;
}

async function selectVersion(releases) {
  console.log();
  logHeader("Version Selection");

  const tags = releases.map((r) => r.tag_name).filter(Boolean);
  if (tags.length === 0) { logErr("No versions found."); process.exit(1); }
  if (tags.length === 1) {
    logInfo(`Only one version available: ${green(tags[0])}`);
    return tags[0];
  }

  console.log("  Available versions:");
  for (let i = 0; i < tags.length; i++) {
    const marker = i === 0 ? `${green("►")} ` : "  ";
    console.log(`  ${white(String(i + 1) + ")")} ${marker}${tags[i]}`);
  }
  console.log();

  while (true) {
    const raw = (await ask(`  ${cyan(`Choice [1-${tags.length}] (default: 1 = latest):`)} `)).trim();
    if (raw === "") return tags[0];
    const n = parseInt(raw, 10);
    if (Number.isInteger(n) && n >= 1 && n <= tags.length) return tags[n - 1];
    console.log(`  ${red("Invalid input, please try again.")}`);
  }
}

async function configureConcurrency() {
  console.log();
  logHeader("Download Settings");
  const raw = await ask(`Set max concurrent downloads (Default: ${white("3")}):\n${cyan("Count > ")}`);
  let n = parseInt(raw, 10);
  if (!Number.isFinite(n) || n < 1) n = 3;
  logOk(`Concurrency set to: ${white(String(n))}`);
  return n;
}

// ── Network ─────────────────────────────────────────────────────────────────

function setupProxy(proxy) {
  if (proxy) {
    process.env.http_proxy = `http://${proxy}`;
    process.env.https_proxy = `http://${proxy}`;
    logOk(`Proxy: ${proxy}`);
  } else {
    logInfo("Direct connection.");
    console.log(`${yellow("[TIP]")} If slow, use: node ${process.argv[1]} 127.0.0.1:7890`);
  }
}

async function fetchJSON(url) {
  const resp = await fetch(url, {
    headers: { Accept: "application/vnd.github+json" },
  });
  if (!resp.ok) throw new Error(`HTTP ${resp.status} for ${url}`);
  return resp.json();
}

async function downloadFile(url, dest) {
  const resp = await fetch(url, { redirect: "follow" });
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  const ws = createWriteStream(dest);
  // @ts-ignore – Node 18+ body is a ReadableStream / web stream
  await pipeline(resp.body, ws);
}

// ── Extract ─────────────────────────────────────────────────────────────────

function hasBin(bin) {
  try {
    execSync(process.platform === "win32" ? `where ${bin}` : `which ${bin}`, { stdio: "ignore" });
    return true;
  } catch { return false; }
}

function toMsysPath(p) {
  const normalized = resolve(p).replace(/\\/g, "/");
  const match = normalized.match(/^([A-Za-z]):\/(.*)$/);
  if (!match) return normalized;
  return `/${match[1].toLowerCase()}/${match[2]}`;
}

function findPython() {
  const candidates = process.platform === "win32" ? ["python", "py"] : ["python3", "python"];
  for (const candidate of candidates) {
    if (hasBin(candidate)) return candidate;
  }
  return null;
}

function extractArchiveWithPython(archive, destDir) {
  const python = findPython();
  if (!python) return false;

  const script = [
    "import pathlib, sys, tarfile, zipfile",
    "archive = pathlib.Path(sys.argv[1])",
    "dest = pathlib.Path(sys.argv[2])",
    "dest.mkdir(parents=True, exist_ok=True)",
    "if archive.suffix == '.zip':",
    "    with zipfile.ZipFile(archive) as zf:",
    "        zf.extractall(dest)",
    "else:",
    "    with tarfile.open(archive) as tf:",
    "        tf.extractall(dest)",
  ].join("\n");

  const args = python === "py"
    ? ["-3", "-c", script, archive, destDir]
    : ["-c", script, archive, destDir];

  execFileSync(python, args, { stdio: "ignore" });
  return true;
}

function extractArchive(archive, destDir) {
  const name = basename(archive);
  mkdirSync(destDir, { recursive: true });

  if (name.endsWith(".zip")) {
    if (process.platform === "win32") {
      execSync(`tar -xf "${archive}" -C "${destDir}"`, { stdio: "ignore" });
    } else {
      execSync(`unzip -q -o "${archive}" -d "${destDir}"`, { stdio: "ignore" });
    }
    return;
  }

  // .tar.xz / .tar.gz — on Windows the built-in tar.exe cannot handle .xz
  if (process.platform === "win32" && name.endsWith(".tar.xz")) {
    // Prefer MSYS2 / Git-Bash tar.exe directly to avoid nested shell quoting issues.
    const msysTar = findMsysTar();
    if (msysTar) {
      execFileSync(msysTar, ["-xf", toMsysPath(archive), "-C", toMsysPath(destDir)], { stdio: "ignore" });
      return;
    }

    // Python's stdlib tarfile handles .tar.xz reliably when Python is available.
    if (extractArchiveWithPython(archive, destDir)) {
      return;
    }

    // Fallback: two-step  xz → tar  (requires xz on PATH)
    if (hasBin("xz")) {
      const tarFile = archive.replace(/\.xz$/, "");
      execSync(`xz -dk "${archive}"`, { stdio: "ignore" });
      execSync(`tar -xf "${tarFile}" -C "${destDir}"`, { stdio: "ignore" });
      rmSync(tarFile, { force: true });
      return;
    }
    // Last resort: 7z
    if (hasBin("7z")) {
      execSync(`7z x "${archive}" -so | 7z x -si -ttar -o"${destDir}"`, { stdio: "ignore", shell: true });
      return;
    }
    throw new Error(
      `Cannot extract .tar.xz on Windows — install Git-Bash / MSYS2, xz-utils, or 7-Zip and make sure they are on PATH.`
    );
  }

  // Unix or .tar.gz on any platform
  execSync(`tar -xf "${archive}" -C "${destDir}"`, { stdio: "ignore" });
}

/** Try to locate a MSYS2 / Git-Bash bash.exe that bundles GNU tar with xz support. */
function findMsysBash() {
  const candidates = [
    process.env.MSYS2_BASH,
    // Git for Windows
    "C:\\Program Files\\Git\\usr\\bin\\bash.exe",
    "C:\\Program Files (x86)\\Git\\usr\\bin\\bash.exe",
    // MSYS2 default
    "C:\\msys64\\usr\\bin\\bash.exe",
  ];
  for (const p of candidates) {
    if (p && existsSync(p)) return p;
  }
  // Check if current shell IS already MSYS/Git-Bash (MSYSTEM env var set)
  if (process.env.MSYSTEM && hasBin("bash")) return "bash";
  return null;
}

function findMsysTar() {
  const msysBash = findMsysBash();
  if (msysBash && msysBash !== "bash") {
    const candidate = join(dirname(msysBash), "tar.exe");
    if (existsSync(candidate)) return candidate;
  }

  const candidates = [
    "C:\\Program Files\\Git\\usr\\bin\\tar.exe",
    "C:\\Program Files (x86)\\Git\\usr\\bin\\tar.exe",
    "C:\\msys64\\usr\\bin\\tar.exe",
  ];
  for (const candidate of candidates) {
    if (existsSync(candidate)) return candidate;
  }

  return null;
}

function findIncludeDir(root) {
  const entries = readdirSync(root, { withFileTypes: true });
  // Check for min/ subdirectory first (min variant structure)
  if (
    entries.some((e) => e.isDirectory() && e.name === "min") &&
    existsSync(join(root, "min", "include")) &&
    existsSync(join(root, "min", "lib"))
  ) {
    return join(root, "min");
  }
  for (const e of entries) {
    if (e.isDirectory() && e.name !== "min") {
      const found = findIncludeDir(join(root, e.name));
      if (found) return found;
    }
  }
  return null;
}

/** Find include/lib at any level (non-min variant fallback). */
function findIncludeDirFallback(root) {
  const entries = readdirSync(root, { withFileTypes: true });
  if (
    entries.some((e) => e.isDirectory() && e.name === "include") &&
    entries.some((e) => e.isDirectory() && e.name === "lib")
  ) {
    return root;
  }
  for (const e of entries) {
    if (e.isDirectory()) {
      const found = findIncludeDirFallback(join(root, e.name));
      if (found) return found;
    }
  }
  return null;
}

// ── Deploy ──────────────────────────────────────────────────────────────────

function deployPlatform(archive, plat, version, variant) {
  const target = join(PLATFORMS_ROOT, version, variant, plat);
  logStep(`Deploying: ${white(`${variant}/${plat}`)}`);
  logDetail(`Archive: ${cyan(basename(archive))}`);

  if (existsSync(target)) rmSync(target, { recursive: true, force: true });
  mkdirSync(target, { recursive: true });

  const tmp = tmpDir();
  try {
    extractArchive(archive, tmp);
    let cRoot = findIncludeDir(tmp);
    if (!cRoot) cRoot = findIncludeDirFallback(tmp);
    if (!cRoot) throw new Error(`Invalid artifact structure: ${basename(archive)}`);

    const srcInclude = join(cRoot, "include");
    const srcLib = join(cRoot, "lib");
    cpSync(srcInclude, join(target, "include"), { recursive: true });
    cpSync(srcLib, join(target, "lib"), { recursive: true });
    logOk(`Installed: ${plat}`);
  } finally {
    rmSync(tmp, { recursive: true, force: true });
  }
}


// ── Filter ──────────────────────────────────────────────────────────────────

function matchesFilter(fname, filter) {
  if (filter === "all") return true;
  // Map long filter IDs to short substrings for variant-agnostic matching
  const shortMap = {
    "aarch64-ios-pulley-min-c-api": "aarch64-ios",
    "aarch64-ios-sim-pulley-min-c-api": "aarch64-ios",
  };
  const short = shortMap[filter] || filter;
  if (!fname.includes(short)) return false;
  // iOS: exclude simulator when selecting device, and vice versa
  if (filter === "aarch64-ios-pulley-min-c-api" && fname.includes("sim")) return false;
  if (filter === "aarch64-ios-sim-pulley-min-c-api" && !fname.includes("sim")) return false;
  return true;
}

function fnameToPlatform(fname) {
  // Strip variant suffix to get a variant-agnostic core name
  const core = fname.replace(/(-pulley)?-min-c-api.*/, "");
  // Order matters: more specific patterns first
  const keys = [
    "aarch64-ios-sim",
    "aarch64-ios",
    "armv7-android",
    "x86_64-android",
    "x86-android",
    "aarch64-android",
    "aarch64-linux",
    "x86_64-linux",
    "aarch64-macos",
    "x86_64-macos",
    "x86_64-windows",
  ];
  for (const k of keys) {
    if (core.includes(k)) return PLATFORM_MAP[k];
  }
  return null;
}

// ── Main ────────────────────────────────────────────────────────────────────

async function main() {
  process.chdir(PROJECT_ROOT);
  mkdirSync(PLATFORMS_ROOT, { recursive: true });

  logHeader("Wasmtime SDK Init");
  setupProxy(process.argv[2] || null);

  // 1. Fetch releases
  logInfo("Fetching releases...");
  let allReleases;
  try {
    allReleases = await fetchJSON(`https://api.github.com/repos/${REPO}/releases?per_page=10`);
  } catch (e) {
    logErr(`Fetch failed: ${e.message}`);
    process.exit(1);
  }

  // 2. Version selection
  const selectedVersion = await selectVersion(allReleases);

  // Fetch the specific version's release details for asset URLs
  let data;
  try {
    data = await fetchJSON(`https://api.github.com/repos/${REPO}/releases/tags/${selectedVersion}`);
  } catch (e) {
    logErr(`Failed to fetch release: ${e.message}`);
    process.exit(1);
  }
  const tag = data.tag_name || "";
  if (!tag) { logErr("Fetch failed: no tag_name."); process.exit(1); }
  logInfo(`Version: ${green(tag)}`);

  // 3. Interactive selections
  const userFilter = await selectTarget();
  const variant = await selectVariant(userFilter);
  const maxConcurrent = await configureConcurrency();

  // 3. Collect download URLs
  logInfo("Analyzing targets...");
  /** @type {{url:string, fname:string, plat:string, variant:string}[]} */
  const jobs = [];
  for (const asset of data.assets || []) {
    const url = asset.browser_download_url || "";
    const fname = basename(url);
    // Must be a min-c-api asset
    if (!fname.includes("-min-c-api")) continue;
    // Determine per-asset variant from filename
    const assetVariant = fname.includes("-pulley-min-c-api") ? "pulley" : "cranelift";
    // Filter by variant (skip when "both")
    if (variant !== "both") {
      if (variant === "pulley" && assetVariant !== "pulley") continue;
      if (variant === "cranelift" && assetVariant !== "cranelift") continue;
    }
    if (!matchesFilter(fname, userFilter)) continue;
    const plat = fnameToPlatform(fname);
    if (plat) jobs.push({ url, fname, plat, variant: assetVariant });
  }

  if (jobs.length === 0) { logWarn("No assets found."); process.exit(0); }
  logInfo(`Ready. Queue: ${jobs.length} files (Max concurrent: ${maxConcurrent})`);
  console.log("-".repeat(80));

  // 4. Download with concurrency control
  const errors = [];
  /** @type {{plat:string, archive:string, variant:string}[]} */
  const downloaded = [];

  async function doDownload({ url, fname, plat, variant: assetVariant }) {
    const tmp = tmpDir();
    const dest = join(tmp, fname);
    const t0 = performance.now();
    try {
      await downloadFile(url, dest);
      const elapsed = ((performance.now() - t0) / 1000).toFixed(1);
      const size = formatSize(statSync(dest).size);
      const label = `${assetVariant}/${plat}`;
      logOk(`${label.padEnd(28)}  ${size.padStart(10)}  ${String(elapsed).padStart(5)}s  ${cyan(fname)}`);
      downloaded.push({ plat, archive: dest, variant: assetVariant });
    } catch (e) {
      errors.push(`${fname}: ${e.message}`);
      logErr(`${plat.padEnd(18)}  FAILED  ${fname}`);
      rmSync(tmp, { recursive: true, force: true });
    }
  }

  // Simple concurrency pool
  let running = 0;
  let idx = 0;
  await new Promise((done) => {
    function next() {
      while (running < maxConcurrent && idx < jobs.length) {
        running++;
        const job = jobs[idx++];
        doDownload(job).finally(() => {
          running--;
          if (idx >= jobs.length && running === 0) done();
          else next();
        });
      }
      if (jobs.length === 0) done();
    }
    next();
  });

  console.log("-".repeat(80));
  logOk("All Downloads Finished.");
  console.log();

  // 5. Deploy
  logInfo("Deploying...");
  for (const { plat, archive, variant: assetVariant } of downloaded) {
    try {
      deployPlatform(archive, plat, selectedVersion, assetVariant);
    } catch (e) {
      errors.push(e.message);
    }
    // Clean temp dir containing the archive
    rmSync(dirname(archive), { recursive: true, force: true });
    console.log();
  }

  if (errors.length > 0) {
    logErr("Completed with errors:");
    for (const e of errors) console.log(`  - ${e}`);
    process.exit(1);
  }

  logHeader("Success");
  console.log(`Location: ${PLATFORMS_ROOT}/${selectedVersion}/`);
}

main().catch((e) => { logErr(e.message); process.exit(1); });
