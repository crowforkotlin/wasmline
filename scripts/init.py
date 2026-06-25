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

# Ordered, grouped by platform family.  Each entry: key, display_name, filter_id, install_path.
TARGETS = [
    # Android
    {"key": "1", "name": "Android   arm64-v8a",              "filter": "aarch64-android",                "platform": "android/arm64-v8a"},
    {"key": "2", "name": "Android   armeabi-v7a  [pulley only]", "filter": "armv7-android",              "platform": "android/armeabi-v7a"},
    {"key": "3", "name": "Android   x86          [pulley only]", "filter": "x86-android",                "platform": "android/x86"},
    {"key": "4", "name": "Android   x86_64",                  "filter": "x86_64-android",                 "platform": "android/x86_64"},
    # iOS
    {"key": "5", "name": "iOS       arm64 (Device)   [pulley only]", "filter": "aarch64-ios-pulley-min-c-api",   "platform": "ios/arm64"},
    {"key": "6", "name": "iOS       arm64 (Simulator)[pulley only]", "filter": "aarch64-ios-sim-pulley-min-c-api","platform": "ios/simulator-arm64"},
    # Linux
    {"key": "7", "name": "Linux     aarch64",                 "filter": "aarch64-linux",                  "platform": "linux/aarch64"},
    {"key": "8", "name": "Linux     x64",                     "filter": "x86_64-linux",                   "platform": "linux/x64"},
    # macOS
    {"key": "9", "name": "macOS     aarch64",                 "filter": "aarch64-macos",                  "platform": "mac/aarch64"},
    {"key": "0", "name": "macOS     x64",                     "filter": "x86_64-macos",                   "platform": "mac/x64"},
    # Windows
    {"key": "x", "name": "Windows   x64",                     "filter": "x86_64-windows",                 "platform": "windows/x64"},
    # Other
    {"key": "a", "name": "All Platforms (cranelift + pulley)", "filter": "all",                           "platform": None},
]

# Group definitions for menu rendering: (label, [indices into TARGETS])
TARGET_GROUPS = [
    ("Android", [0, 1, 2, 3]),
    ("iOS",     [4, 5]),
    ("Linux",   [6, 7]),
    ("macOS",   [8, 9]),
    ("Windows", [10]),
    ("Other",   [11]),
]

TARGETS_BY_KEY = {target["key"]: target for target in TARGETS}

# Module-level state for variant/version selection flow
_current_filter: str = ""
_selected_version: str = ""

PLATFORM_MAP: dict[str, str] = {
    # Short keys — variant-agnostic (work for both pulley and cranelift assets)
    "aarch64-android":    "android/arm64-v8a",
    "aarch64-ios-sim":    "ios/simulator-arm64",
    "aarch64-ios":        "ios/arm64",
    "aarch64-linux":      "linux/aarch64",
    "x86_64-linux":       "linux/x64",
    "aarch64-macos":      "mac/aarch64",
    "x86_64-macos":       "mac/x64",
    "x86_64-windows":     "windows/x64",
    "armv7-android":      "android/armeabi-v7a",
    "x86-android":        "android/x86",
    "x86_64-android":     "android/x86_64",
}


def select_target() -> str:
    print()
    log_header("Platform & Architecture Selection")
    print()

    name_w = max(len(t["name"]) for t in TARGETS)
    name_w = max(name_w, 22)

    for group_label, indices in TARGET_GROUPS:
        print(f"  {gray(f'── {group_label} ──')}")
        for i in indices:
            t = TARGETS[i]
            padded = t["name"].ljust(name_w)
            path_str = f"build/platforms/{t['platform']}" if t["platform"] else "—"
            print(f"  {white(str(t['key']) + ')')} {padded}  {gray(f'→ {path_str}')}")
        print()

    while True:
        choice = input(f"  {cyan('Enter choice [1-9, 0, x, a]:')} ").strip().lower()
        target = TARGETS_BY_KEY.get(choice)
        if target is not None:
            print()
            log_ok(f"Target: {white(target['name'])}")
            return str(target["filter"])
        print(f"  {red('Invalid input, please try again.')}")


# Pulley-only platforms (iOS, armeabi-v7a, x86) — Cranelift not available.
PULLEY_ONLY_FILTERS = {
    "aarch64-ios-pulley-min-c-api",
    "aarch64-ios-sim-pulley-min-c-api",
    "armv7-android",
    "x86-android",
}


def select_variant() -> str:
    """Ask user to choose runtime variant. Returns 'cranelift', 'pulley', or 'both'."""
    print()
    log_header("Runtime Variant Selection")
    global _current_filter

    if _current_filter == "all":
        variant = "both"
        log_info("All Platforms: downloading both Cranelift and Pulley assets.")
    elif _current_filter in PULLEY_ONLY_FILTERS:
        variant = "pulley"
        log_info("Platform requires Pulley runtime (no Cranelift support).")
    else:
        print(f"  {white('1)')} Cranelift — .pwasm + .cwasm AOT  {gray('(default, larger binary)')}")
        print(f"  {white('2)')} Pulley    — .pwasm only            {gray('(smaller binary)')}")
        print()
        while True:
            v = input(f"  {cyan('Choice [1/2] (default: 1):')} ").strip()
            if v in ("", "1"):
                variant = "cranelift"
                break
            if v == "2":
                variant = "pulley"
                break
            print(f"  {red('Invalid input, please try again.')}")

    print()
    log_ok(f"Variant: {white(variant)}")
    return variant


def select_version(releases: list[dict]) -> str:
    """Ask user to choose a Wasmtime release version."""
    print()
    log_header("Version Selection")

    tags = [r.get("tag_name", "") for r in releases if r.get("tag_name")]
    if not tags:
        log_err("No versions found.")
        sys.exit(1)
    if len(tags) == 1:
        log_info(f"Only one version available: {green(tags[0])}")
        return tags[0]

    print("  Available versions:")
    for i, tag in enumerate(tags):
        marker = f"{green('►')} " if i == 0 else "  "
        print(f"  {white(str(i + 1) + ')')} {marker}{tag}")
    print()

    while True:
        raw = input(f"  {cyan(f'Choice [1-{len(tags)}] (default: 1 = latest):')} ").strip()
        if raw == "":
            return tags[0]
        try:
            n = int(raw)
            if 1 <= n <= len(tags):
                return tags[n - 1]
        except ValueError:
            pass
        print(f"  {red('Invalid input, please try again.')}")


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

def deploy_platform(archive: Path, plat: str, variant: str) -> None:
    target = PLATFORMS_ROOT / _selected_version / variant / plat
    log_step(f"Deploying: {white(f'{variant}/{plat}')}")
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

def matches_filter(fname: str, user_filter: str, variant: str) -> bool:
    """Check if an asset filename matches the user's platform filter.

    Uses short filter IDs that are substrings of both pulley and cranelift asset names.
    """
    if user_filter == "all":
        return True
    # Map long filter IDs to short substrings for variant-agnostic matching
    short_map = {
        "aarch64-ios-pulley-min-c-api": "aarch64-ios",
        "aarch64-ios-sim-pulley-min-c-api": "aarch64-ios",
    }
    short = short_map.get(user_filter, user_filter)
    if short not in fname:
        return False
    # iOS: exclude simulator when selecting device, and vice versa
    if user_filter == "aarch64-ios-pulley-min-c-api" and "sim" in fname:
        return False
    if user_filter == "aarch64-ios-sim-pulley-min-c-api" and "sim" not in fname:
        return False
    return True


def fname_to_platform(fname: str) -> str | None:
    """Map an asset filename to a platform path, variant-agnostic.

    Strips the variant suffix (-pulley-min-c-api or -min-c-api) to get a
    core name, then matches against known platform patterns.
    """
    import re
    core = re.sub(r"(-pulley)?-min-c-api.*", "", fname)
    if "aarch64-ios-sim" in core:
        return PLATFORM_MAP["aarch64-ios-sim"]
    if "aarch64-ios" in core:
        return PLATFORM_MAP["aarch64-ios"]
    if "armv7-android" in core:
        return PLATFORM_MAP["armv7-android"]
    if "x86_64-android" in core:
        return PLATFORM_MAP["x86_64-android"]
    if "x86-android" in core:
        return PLATFORM_MAP["x86-android"]
    if "aarch64-android" in core:
        return PLATFORM_MAP["aarch64-android"]
    if "aarch64-linux" in core:
        return PLATFORM_MAP["aarch64-linux"]
    if "x86_64-linux" in core:
        return PLATFORM_MAP["x86_64-linux"]
    if "aarch64-macos" in core:
        return PLATFORM_MAP["aarch64-macos"]
    if "x86_64-macos" in core:
        return PLATFORM_MAP["x86_64-macos"]
    if "x86_64-windows" in core:
        return PLATFORM_MAP["x86_64-windows"]
    return None


# ── Main ─────────────────────────────────────────────────────────────────────

def main() -> None:
    os.chdir(PROJECT_ROOT)
    PLATFORMS_ROOT.mkdir(parents=True, exist_ok=True)

    log_header("Wasmtime SDK Init")
    setup_proxy(sys.argv[1] if len(sys.argv) > 1 else None)

    # 1. Fetch releases
    log_info("Fetching releases...")
    try:
        all_releases = api_get_json(f"https://api.github.com/repos/{REPO}/releases?per_page=10")
    except (URLError, OSError) as exc:
        log_err(f"Fetch failed: {exc}")
        sys.exit(1)

    # 2. Version selection
    global _selected_version
    _selected_version = select_version(all_releases)

    # Fetch the specific version's release details for asset URLs
    try:
        data = api_get_json(f"https://api.github.com/repos/{REPO}/releases/tags/{_selected_version}")
    except (URLError, OSError) as exc:
        log_err(f"Failed to fetch release: {exc}")
        sys.exit(1)
    tag = data.get("tag_name", "")
    if not tag:
        log_err("Fetch failed: no tag_name in response.")
        sys.exit(1)
    log_info(f"Version: {green(tag)}")

    # 3. Interactive selections
    global _current_filter
    user_filter = select_target()
    _current_filter = user_filter
    variant = select_variant()
    max_concurrent = configure_concurrency()

    # 3. Collect download URLs
    log_info("Analyzing targets...")
    assets = data.get("assets", [])
    jobs: list[tuple[str, str, str, str]] = []  # (url, fname, plat, variant)

    for asset in assets:
        url: str = asset.get("browser_download_url", "")
        fname = url.rsplit("/", 1)[-1] if url else ""
        # Must be a min-c-api asset
        if "-min-c-api" not in fname:
            continue
        # Determine per-asset variant from filename
        asset_variant = "pulley" if "-pulley-min-c-api" in fname else "cranelift"
        # Filter by variant (skip when "both")
        if variant != "both":
            if variant == "pulley" and asset_variant != "pulley":
                continue
            if variant == "cranelift" and asset_variant != "cranelift":
                continue
        if not matches_filter(fname, user_filter, variant):
            continue
        plat = fname_to_platform(fname)
        if plat:
            jobs.append((url, fname, plat, asset_variant))

    if not jobs:
        log_warn("No assets found.")
        sys.exit(0)

    log_info(f"Ready. Queue: {len(jobs)} files (Max concurrent: {max_concurrent})")
    print("-" * 80)

    # 4. Download
    errors: list[str] = []

    def do_download(url: str, fname: str, plat: str, asset_variant: str) -> tuple[str, Path, str]:
        tmp = Path(tempfile.mkdtemp(dir=PLATFORMS_ROOT))
        dest = tmp / fname
        t0 = time.monotonic()
        try:
            download_file(url, dest)
            elapsed = time.monotonic() - t0
            size_str = format_size(dest.stat().st_size)
            label = f"{asset_variant}/{plat}"
            log_ok(f"{label:28s}  {size_str:>10s}  {elapsed:5.1f}s  {cyan(fname)}")
        except Exception as exc:
            errors.append(f"{fname}: {exc}")
            log_err(f"{plat:18s}  FAILED  {fname}")
            shutil.rmtree(tmp, ignore_errors=True)
            raise
        return plat, dest, asset_variant

    downloaded: list[tuple[str, Path, str]] = []
    with ThreadPoolExecutor(max_workers=max_concurrent) as pool:
        futures = {pool.submit(do_download, url, fname, plat, av): plat for url, fname, plat, av in jobs}
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
    for plat, archive, asset_variant in downloaded:
        try:
            deploy_platform(archive, plat, asset_variant)
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
    print(f"Location: {PLATFORMS_ROOT / _selected_version}/")


if __name__ == "__main__":
    main()
