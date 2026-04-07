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
const PLATFORMS_ROOT = join(PROJECT_ROOT, "platforms");
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
const gray = (t) => c("1;30", t);

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

const TARGETS = [
  ["1", "Android (aarch64)", "aarch64-android"],
  ["2", "iOS Device (aarch64)", "aarch64-ios-c-api"],
  ["3", "iOS Simulator (aarch64)", "aarch64-ios-sim"],
  ["4", "Linux (aarch64)", "aarch64-linux"],
  ["5", "Linux (x86_64)", "x86_64-linux"],
  ["6", "macOS (aarch64)", "aarch64-macos"],
  ["7", "macOS (x86_64)", "x86_64-macos"],
  ["8", "Windows (x86_64)", "x86_64-windows"],
  ["a", "All Platforms", "all"],
];

const PLATFORM_MAP = {
  "aarch64-android": "android/arm64-v8a",
  "aarch64-ios-sim": "ios/simulator-arm64",
  "aarch64-ios-c-api": "ios/arm64",
  "aarch64-linux": "linux/aarch64",
  "x86_64-linux": "linux/x64",
  "aarch64-macos": "mac/aarch64",
  "x86_64-macos": "mac/x64",
  "x86_64-windows": "windows/x64",
};

async function selectTarget() {
  console.log();
  logHeader("Platform & Architecture Selection");
  console.log("Select specific target:");
  for (const [key, label] of TARGETS) {
    console.log(`  ${white(key + ")")} ${label}`);
  }
  console.log();
  while (true) {
    const choice = (await ask(`${cyan("Choice [1-8, a]: ")}`)).toLowerCase();
    const match = TARGETS.find(([k]) => k === choice);
    if (match) {
      const display = match[2] === "all" ? "All Platforms" : match[2];
      logOk(`Target Filter: ${white(display)}`);
      return match[2];
    }
    console.log(red("Invalid input."));
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
  for (const e of entries) {
    if (e.isDirectory() && e.name === "include") return root;
    if (e.isDirectory()) {
      const found = findIncludeDir(join(root, e.name));
      if (found) return found;
    }
  }
  return null;
}

// ── Deploy ──────────────────────────────────────────────────────────────────

function deployPlatform(archive, plat) {
  const target = join(PLATFORMS_ROOT, plat);
  logStep(`Deploying: ${white(plat)}`);
  logDetail(`Archive: ${cyan(basename(archive))}`);

  if (existsSync(target)) rmSync(target, { recursive: true, force: true });
  mkdirSync(target, { recursive: true });

  const tmp = tmpDir();
  try {
    extractArchive(archive, tmp);
    const cRoot = findIncludeDir(tmp);
    if (!cRoot) throw new Error(`Invalid structure: ${basename(archive)}`);

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
  if (filter === "aarch64-ios-c-api") {
    return fname.includes(filter) && !fname.includes("sim");
  }
  return fname.includes(filter);
}

function fnameToPlatform(fname) {
  // Order matters: sim before generic ios
  const keys = [
    "aarch64-ios-sim",
    "aarch64-ios-c-api",
    "aarch64-android",
    "aarch64-linux",
    "x86_64-linux",
    "aarch64-macos",
    "x86_64-macos",
    "x86_64-windows",
  ];
  for (const k of keys) {
    if (fname.includes(k)) return PLATFORM_MAP[k];
  }
  return null;
}

// ── Main ────────────────────────────────────────────────────────────────────

async function main() {
  process.chdir(PROJECT_ROOT);
  mkdirSync(PLATFORMS_ROOT, { recursive: true });

  logHeader("Wasmtime SDK Init");
  setupProxy(process.argv[2] || null);

  // 1. Fetch release info
  logInfo("Fetching releases...");
  let data;
  try {
    data = await fetchJSON(`https://api.github.com/repos/${REPO}/releases/latest`);
  } catch (e) {
    logErr(`Fetch failed: ${e.message}`);
    process.exit(1);
  }

  const tag = data.tag_name || "";
  if (!tag) { logErr("Fetch failed: no tag_name."); process.exit(1); }
  logInfo(`Version: ${green(tag)}`);

  // 2. Interactive selections
  const userFilter = await selectTarget();
  const maxConcurrent = await configureConcurrency();

  // 3. Collect download URLs
  logInfo("Analyzing targets...");
  /** @type {{url:string, fname:string, plat:string}[]} */
  const jobs = [];
  for (const asset of data.assets || []) {
    const url = asset.browser_download_url || "";
    const fname = basename(url);
    if (!fname.includes("c-api")) continue;
    if (!matchesFilter(fname, userFilter)) continue;
    const plat = fnameToPlatform(fname);
    if (plat) jobs.push({ url, fname, plat });
  }

  if (jobs.length === 0) { logWarn("No assets found."); process.exit(0); }
  logInfo(`Ready. Queue: ${jobs.length} files (Max concurrent: ${maxConcurrent})`);
  console.log("-".repeat(80));

  // 4. Download with concurrency control
  const errors = [];
  /** @type {{plat:string, archive:string}[]} */
  const downloaded = [];

  async function doDownload({ url, fname, plat }) {
    const tmp = tmpDir();
    const dest = join(tmp, fname);
    const t0 = performance.now();
    try {
      await downloadFile(url, dest);
      const elapsed = ((performance.now() - t0) / 1000).toFixed(1);
      const size = formatSize(statSync(dest).size);
      logOk(`${plat.padEnd(18)}  ${size.padStart(10)}  ${String(elapsed).padStart(5)}s  ${cyan(fname)}`);
      downloaded.push({ plat, archive: dest });
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
  for (const { plat, archive } of downloaded) {
    try {
      deployPlatform(archive, plat);
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
  console.log(`Location: ${PLATFORMS_ROOT}/`);
}

main().catch((e) => { logErr(e.message); process.exit(1); });

