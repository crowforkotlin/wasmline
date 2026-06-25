#!/usr/bin/env python3
"""
Wasmtime C-API Init Script (Python)
====================================
Equivalent to init.sh — downloads and deploys Wasmtime C-API platform assets.

Usage:
    python3 scripts/init.py [proxy]

Examples:
    python3 scripts/init.py
    python3 scripts/init.py 127.0.0.1:7890
"""

from __future__ import annotations

import json
import os
import shutil
import sys
import tarfile
import tempfile
import time
import zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import URLError

# ── Paths ────────────────────────────────────────────────────────────────────
SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parent
PLATFORMS_ROOT = PROJECT_ROOT / "build" / "platforms"

REPO = "crowforkotlin/wasmtime"

# ── Colors ───────────────────────────────────────────────────────────────────
_NO_COLOR = os.environ.get("NO_COLOR")

def _c(code: str, text: str) -> str:
    if _NO_COLOR or not sys.stdout.isatty():
        return text
    return f"\033[{code}m{text}\033[0m"

def red(t: str) -> str: return _c("1;31", t)
def green(t: str) -> str: return _c("1;32", t)
def yellow(t: str) -> str: return _c("1;33", t)
def blue(t: str) -> str: return _c("1;34", t)
def magenta(t: str) -> str: return _c("1;35", t)
def cyan(t: str) -> str: return _c("1;36", t)
def white(t: str) -> str: return _c("1;37", t)
def gray(t: str) -> str: return _c("0;90", t)

def log_info(msg: str) -> None: print(f"{magenta('[INFO]')} {msg}")
def log_ok(msg: str) -> None: print(f"{green('[OK]')}   {msg}")
def log_warn(msg: str) -> None: print(f"{yellow('[WARN]')} {msg}")
def log_err(msg: str) -> None: print(f"{red('[ERR]')}  {msg}")
def log_step(msg: str) -> None: print(f"{blue('[STEP]')} {msg}")
def log_detail(msg: str) -> None: print(f"       {gray('└─')} {msg}")

def log_header(msg: str) -> None:
    bar = cyan("=" * 49)
    print(f"{bar}\n      {msg}\n{bar}")

# ── Helpers ──────────────────────────────────────────────────────────────────

def format_size(n: int) -> str:
    if n < 1024:
        return f"{n}B"
    if n < 1048576:
        return f"{(n + 512) // 1024}KB"
    mb = n * 100 // 1048576
    return f"{mb // 100}.{mb % 100:02d}MB"

# ── Target menu ──────────────────────────────────────────────────────────────

TARGETS = [
    {"key": "1", "name": "Android / arm64-v8a", "filter": "aarch64-android", "platform": "android/arm64-v8a", "asset": "aarch64-android-pulley-min-c-api"},
    {"key": "2", "name": "iOS Device / arm64", "filter": "aarch64-ios-pulley-min-c-api", "platform": "ios/arm64", "asset": "aarch64-ios-pulley-min-c-api"},
    {"key": "3", "name": "iOS Simulator / simulator-arm64", "filter": "aarch64-ios-sim-pulley-min-c-api", "platform": "ios/simulator-arm64", "asset": "aarch64-ios-sim-pulley-min-c-api"},
    {"key": "4", "name": "Linux / aarch64", "filter": "aarch64-linux", "platform": "linux/aarch64", "asset": "aarch64-linux-pulley-min-c-api"},
    {"key": "5", "name": "Linux / x64", "filter": "x86_64-linux", "platform": "linux/x64", "asset": "x86_64-linux-pulley-min-c-api"},
    {"key": "6", "name": "macOS / aarch64", "filter": "aarch64-macos", "platform": "mac/aarch64", "asset": "aarch64-macos-pulley-min-c-api"},
    {"key": "7", "name": "macOS / x64", "filter": "x86_64-macos", "platform": "mac/x64", "asset": "x86_64-macos-pulley-min-c-api"},
    {"key": "8", "name": "Windows / x64", "filter": "x86_64-windows", "platform": "windows/x64", "asset": "x86_64-windows-pulley-min-c-api"},
    {"key": "9", "name": "Android / armeabi-v7a", "filter": "armv7-android", "platform": "android/armeabi-v7a", "asset": "armv7-android-pulley-min-c-api"},
    {"key": "x", "name": "Android / x86 (32-bit)", "filter": "x86-android", "platform": "android/x86", "asset": "x86-android-pulley-min-c-api"},
    {"key": "0", "name": "Android / x86_64", "filter": "x86_64-android", "platform": "android/x86_64", "asset": "x86_64-android-pulley-min-c-api"},
    {"key": "a", "name": "All Platforms", "filter": "all", "platform": None, "asset": None},
]

TARGETS_BY_KEY = {target["key"]: target for target in TARGETS}

PLATFORM_MAP: dict[str, str] = {
    "aarch64-android":    "android/arm64-v8a",
    "aarch64-ios-sim-pulley-min-c-api": "ios/simulator-arm64",
    "aarch64-ios-pulley-min-c-api": "ios/arm64",
    "aarch64-linux":      "linux/aarch64",
    "x86_64-linux":       "linux/x64",
    "aarch64-macos":      "mac/aarch64",
    "x86_64-macos":       "mac/x64",
    "x86_64-windows":     "windows/x64",
    "armv7-android":      "android/armeabi-v7a",
    "x86_64-android":     "android/x86_64",
    "x86-android":        "android/x86",
    # Pulley-min aliases (same platform paths)
    "aarch64-android-pulley-min-c-api":  "android/arm64-v8a",
    "x86_64-android-pulley-min-c-api":   "android/x86_64",
    "x86-android-pulley-min-c-api":      "android/x86",
    "armv7-android-pulley-min-c-api":    "android/armeabi-v7a",
    "aarch64-linux-pulley-min-c-api":    "linux/aarch64",
    "x86_64-linux-pulley-min-c-api":     "linux/x64",
    "aarch64-macos-pulley-min-c-api":    "mac/aarch64",
    "x86_64-macos-pulley-min-c-api":     "mac/x64",
    "x86_64-windows-pulley-min-c-api":   "windows/x64",
}


def format_target_summary(target: dict[str, str | None]) -> str:
    if target["filter"] == "all":
        return str(target["name"])
    asset = target.get("asset", target["filter"])
    return f"{target['name']} -> build/platforms/{target['platform']} [asset: {asset}]"


def select_target() -> str:
    print()
    log_header("Platform & Architecture Selection")
    print("Select specific target:")
    for target in TARGETS:
        print(f"  {white(str(target['key']) + ')')} {format_target_summary(target)}")
    print()
    while True:
        choice = input(f"{cyan('Choice [1-9, 0, x, a]: ')}").strip().lower()
        target = TARGETS_BY_KEY.get(choice)
        if target is not None:
            log_ok(f"Target: {white(format_target_summary(target))}")
            return str(target["filter"])
        print(f"{red('Invalid input.')}")


def configure_concurrency() -> int:
    print()
    log_header("Download Settings")
    raw = input(f"Set max concurrent downloads (Default: {white('3')}):\n{cyan('Count > ')}").strip()
    try:
        n = int(raw)
        if n < 1:
            raise ValueError
    except ValueError:
        n = 3
    log_ok(f"Concurrency set to: {white(str(n))}")
    return n


# ── Network ──────────────────────────────────────────────────────────────────

def setup_proxy(proxy: str | None) -> None:
    if proxy:
        os.environ["http_proxy"] = f"http://{proxy}"
        os.environ["https_proxy"] = f"http://{proxy}"
        log_ok(f"Proxy: {proxy}")
    else:
        log_info("Direct connection.")
        print(f"{yellow('[TIP]')} If slow, use: python3 {sys.argv[0]} 127.0.0.1:7890")


def api_get_json(url: str) -> dict:
    req = Request(url, headers={"Accept": "application/vnd.github+json"})
    with urlopen(req, timeout=30) as resp:
        return json.loads(resp.read())


def download_file(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    req = Request(url)
    with urlopen(req, timeout=60) as resp, open(dest, "wb") as f:
        while True:
            chunk = resp.read(65536)
            if not chunk:
                break
            f.write(chunk)


# ── Deploy ───────────────────────────────────────────────────────────────────

def deploy_platform(archive: Path, plat: str) -> None:
    target = PLATFORMS_ROOT / plat
    log_step(f"Deploying: {white(plat)}")
    log_detail(f"Archive: {cyan(archive.name)}")

    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        if archive.suffix == ".zip":
            with zipfile.ZipFile(archive) as zf:
                zf.extractall(tmp_path)
        else:
            with tarfile.open(archive) as tf:
                tf.extractall(tmp_path)

        include_dir = None
        for root, dirs, _ in os.walk(tmp_path):
            if "min" in dirs:
                min_dir = Path(root) / "min"
                if (min_dir / "include").is_dir() and (min_dir / "lib").is_dir():
                    include_dir = min_dir
                    break

        if include_dir is None:
            # Fallback: non-min structure (include/ and lib/ at top level)
            for root, dirs, _ in os.walk(tmp_path):
                if "include" in dirs and "lib" in dirs:
                    candidate = Path(root)
                    if "min" not in str(candidate):
                        include_dir = candidate
                        break
            if include_dir is None:
                log_err(f"Invalid artifact structure: {archive.name}")
                raise RuntimeError(f"No include/lib found in {archive.name}")

        shutil.move(str(include_dir / "include"), str(target / "include"))
        shutil.move(str(include_dir / "lib"), str(target / "lib"))
        log_ok(f"Installed: {plat}")


# ── Filename → filter matching ───────────────────────────────────────────────

def matches_filter(fname: str, user_filter: str) -> bool:
    if user_filter == "all":
        return True
    if user_filter == "aarch64-ios-pulley-min-c-api":
        return user_filter in fname and "sim" not in fname
    if user_filter in fname:
        return True
    return False


def fname_to_platform(fname: str) -> str | None:
    # All matched files are pulley-min-c-api; map filename to platform path.
    if "aarch64-ios-sim-pulley-min" in fname:
        return PLATFORM_MAP["aarch64-ios-sim-pulley-min-c-api"]
    if "aarch64-ios-pulley-min-c-api" in fname:
        return PLATFORM_MAP["aarch64-ios-pulley-min-c-api"]
    if "armv7-android-pulley-min" in fname:
        return PLATFORM_MAP["armv7-android"]
    if "x86_64-android-pulley-min" in fname:
        return PLATFORM_MAP["x86_64-android"]
    if "x86-android-pulley-min" in fname:
        return PLATFORM_MAP["x86-android"]
    if "aarch64-android-pulley-min" in fname:
        return PLATFORM_MAP["aarch64-android"]
    if "aarch64-linux-pulley-min" in fname:
        return PLATFORM_MAP["aarch64-linux"]
    if "x86_64-linux-pulley-min" in fname:
        return PLATFORM_MAP["x86_64-linux"]
    if "aarch64-macos-pulley-min" in fname:
        return PLATFORM_MAP["aarch64-macos"]
    if "x86_64-macos-pulley-min" in fname:
        return PLATFORM_MAP["x86_64-macos"]
    if "x86_64-windows-pulley-min" in fname:
        return PLATFORM_MAP["x86_64-windows"]
    return None


# ── Main ─────────────────────────────────────────────────────────────────────

def main() -> None:
    os.chdir(PROJECT_ROOT)
    PLATFORMS_ROOT.mkdir(parents=True, exist_ok=True)

    log_header("Wasmtime SDK Init")
    setup_proxy(sys.argv[1] if len(sys.argv) > 1 else None)

    # 1. Fetch release info
    log_info("Fetching releases...")
    try:
        data = api_get_json(f"https://api.github.com/repos/{REPO}/releases/latest")
    except (URLError, OSError) as exc:
        log_err(f"Fetch failed: {exc}")
        sys.exit(1)

    tag = data.get("tag_name", "")
    if not tag:
        log_err("Fetch failed: no tag_name in response.")
        sys.exit(1)
    log_info(f"Version: {green(tag)}")

    # 2. Interactive selections
    user_filter = select_target()
    max_concurrent = configure_concurrency()

    # 3. Collect download URLs
    log_info("Analyzing targets...")
    assets = data.get("assets", [])
    jobs: list[tuple[str, str, str]] = []  # (url, fname, plat)

    for asset in assets:
        url: str = asset.get("browser_download_url", "")
        fname = url.rsplit("/", 1)[-1] if url else ""
        if "-pulley-min-c-api" not in fname:
            continue
        if not matches_filter(fname, user_filter):
            continue
        plat = fname_to_platform(fname)
        if plat:
            jobs.append((url, fname, plat))

    if not jobs:
        log_warn("No assets found.")
        sys.exit(0)

    log_info(f"Ready. Queue: {len(jobs)} files (Max concurrent: {max_concurrent})")
    print("-" * 80)

    # 4. Download
    errors: list[str] = []

    def do_download(url: str, fname: str, plat: str) -> tuple[str, Path]:
        tmp = Path(tempfile.mkdtemp(dir=PLATFORMS_ROOT))
        dest = tmp / fname
        t0 = time.monotonic()
        try:
            download_file(url, dest)
            elapsed = time.monotonic() - t0
            size_str = format_size(dest.stat().st_size)
            log_ok(f"{plat:18s}  {size_str:>10s}  {elapsed:5.1f}s  {cyan(fname)}")
        except Exception as exc:
            errors.append(f"{fname}: {exc}")
            log_err(f"{plat:18s}  FAILED  {fname}")
            shutil.rmtree(tmp, ignore_errors=True)
            raise
        return plat, dest

    downloaded: list[tuple[str, Path]] = []
    with ThreadPoolExecutor(max_workers=max_concurrent) as pool:
        futures = {pool.submit(do_download, url, fname, plat): plat for url, fname, plat in jobs}
        for fut in as_completed(futures):
            try:
                downloaded.append(fut.result())
            except Exception:
                pass

    print("-" * 80)
    log_ok("All Downloads Finished.")
    print()

    # 5. Deploy
    log_info("Deploying...")
    for plat, archive in downloaded:
        try:
            deploy_platform(archive, plat)
        except Exception as exc:
            errors.append(str(exc))
        # Clean temp dir containing the archive
        shutil.rmtree(archive.parent, ignore_errors=True)
        print()

    if errors:
        log_err("Completed with errors:")
        for e in errors:
            print(f"  - {e}")
        sys.exit(1)

    log_header("Success")
    print(f"Location: {PLATFORMS_ROOT}/")


if __name__ == "__main__":
    main()
