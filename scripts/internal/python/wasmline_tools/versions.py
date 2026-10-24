"""Version operations shared by the CLI handlers.

File rules live in `version_rules.py`; this module validates the manifest,
resolves releases, and synchronizes managed files.
"""

from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path
from typing import Any, Mapping

from urllib.request import Request, urlopen

from . import toolchain_lock
from .aot.catalog import KOTLIN_RELEASE_IDENTITY_PATH
from .aot.lock import render_kotlin_release_identity
from .terminal import Console
from .paths import MANIFEST_PATH, PROJECT_ROOT
from .support.text import write_text_atomic
from .version_rules import VersionMap, apply_rules, file_specs

KTLINT_LATEST_RELEASE_URL = "https://api.github.com/repos/ktlint/ktlint/releases/latest"

REQUIRED_KEYS = (
    "wasmline_version",
    "sample_plugin_version",
    "wasmtime_version",
    "wasmtime_release_version",
    "wasm_tools_version",
    "wit_bindgen_version",
    "kotlin_version",
    "ktlint_version",
    "dokka_version",
    "kotlin_min_version",
    "agp_version",
    "gradle_version",
    "zig_version",
    "jbr_version",
)

SEMANTIC_VERSION_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$")
WASMTIME_RELEASE_VERSION_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+\.[1-9][0-9]*$")


class KtlintReleaseError(RuntimeError):
    """Raised when the latest stable KtLint release cannot be resolved."""


def load_manifest() -> dict[str, Any]:
    try:
        data = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise SystemExit(f"Missing manifest: {MANIFEST_PATH}") from exc
    except json.JSONDecodeError as exc:
        raise SystemExit(f"Invalid JSON in {MANIFEST_PATH}: {exc}") from exc

    if "aotCompatibility" in data:
        raise SystemExit(
            "AOT compatibility is maintained in root aot-compatibility.json; "
            "remove aotCompatibility from versions.json."
        )

    versions = data.get("versions")
    if not isinstance(versions, dict):
        raise SystemExit("Manifest must contain a 'versions' object.")

    normalized = {str(key): str(value) for key, value in versions.items()}
    missing = [key for key in REQUIRED_KEYS if not normalized.get(key)]
    if missing:
        raise SystemExit(f"Manifest is missing required keys: {', '.join(missing)}")
    validate_versions(normalized)
    data["versions"] = normalized
    return data


def render_manifest(data: Mapping[str, Any]) -> str:
    """Render the version manifest in its canonical format."""

    return json.dumps(data, ensure_ascii=True, indent=2) + "\n"


def parse_updates(entries: list[str]) -> dict[str, str]:
    updates: dict[str, str] = {}
    for entry in entries:
        if "=" not in entry:
            raise SystemExit(f"Invalid --set value '{entry}'. Expected key=value.")
        key, value = entry.split("=", 1)
        key = key.strip()
        value = value.strip()
        if key not in REQUIRED_KEYS:
            raise SystemExit(
                f"Unknown version key '{key}'. Valid keys: {', '.join(REQUIRED_KEYS)}"
            )
        if not value:
            raise SystemExit(f"Version value for '{key}' cannot be empty.")
        validate_version(key, value)
        updates[key] = value
    return updates


def validate_version(key: str, value: str) -> None:
    if key == "jbr_version":
        if not re.fullmatch(r"[0-9]+", value):
            raise SystemExit(f"Invalid version value for '{key}': '{value}'. Expected an integer.")
        return
    if key == "wasmtime_release_version":
        if not WASMTIME_RELEASE_VERSION_PATTERN.fullmatch(value):
            raise SystemExit(
                f"Invalid version value for '{key}': '{value}'. "
                "Expected UPSTREAM_MAJOR.UPSTREAM_MINOR.UPSTREAM_PATCH.REVISION."
            )
        return
    if not SEMANTIC_VERSION_PATTERN.fullmatch(value):
        raise SystemExit(
            f"Invalid version value for '{key}': '{value}'. "
            "Expected MAJOR.MINOR.PATCH with an optional prerelease/build suffix."
        )
    if key == "ktlint_version" and not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", value):
        raise SystemExit(
            f"Invalid version value for '{key}': '{value}'. "
            "KtLint must use a stable release without prerelease or build metadata."
        )
    if key == "wasmtime_version":
        numeric_version = value.split("-", 1)[0].split("+", 1)[0]
        _, minor, patch = (int(part) for part in numeric_version.split("."))
        if minor > 9 or patch > 9:
            raise SystemExit(
                "Invalid version value for 'wasmtime_version': "
                f"'{value}'. Minor and patch must remain single digits while "
                "the release-tag encoding is in use."
            )


def validate_versions(versions: VersionMap) -> None:
    for key in REQUIRED_KEYS:
        validate_version(key, versions[key])
    release_upstream = versions["wasmtime_release_version"].rsplit(".", 1)[0]
    if release_upstream != versions["wasmtime_version"]:
        raise SystemExit(
            "wasmtime_release_version must use wasmtime_version as its first three segments: "
            f"{versions['wasmtime_release_version']} != {versions['wasmtime_version']}.<revision>"
        )


def parse_latest_ktlint_release(payload: Mapping[str, Any]) -> str:
    """Extract one stable semantic version from GitHub's latest-release payload."""

    if payload.get("draft") is not False or payload.get("prerelease") is not False:
        raise KtlintReleaseError("GitHub did not return a stable KtLint release.")
    tag = payload.get("tag_name")
    if not isinstance(tag, str) or not tag:
        raise KtlintReleaseError("GitHub's KtLint release payload has no tag_name.")
    version = tag.removeprefix("v")
    if not SEMANTIC_VERSION_PATTERN.fullmatch(version) or "-" in version or "+" in version:
        raise KtlintReleaseError(f"GitHub returned an invalid stable KtLint version: {tag!r}.")
    return version


def stable_version_key(value: str) -> tuple[int, int, int]:
    major, minor, patch = (int(part) for part in value.split("."))
    return major, minor, patch


def latest_ktlint_version() -> str:
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "wasmline-version-sync",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    if token := os.environ.get("GITHUB_TOKEN"):
        headers["Authorization"] = f"Bearer {token}"
    request = Request(
        KTLINT_LATEST_RELEASE_URL,
        headers=headers,
    )
    try:
        with urlopen(request, timeout=20) as response:
            payload = json.load(response)
    except (OSError, ValueError) as error:
        raise KtlintReleaseError(f"Failed to resolve the latest KtLint release: {error}") from error
    if not isinstance(payload, Mapping):
        raise KtlintReleaseError("GitHub returned an invalid KtLint release payload.")
    return parse_latest_ktlint_release(payload)


def check_ktlint_latest(versions: VersionMap) -> int:
    """Report whether a newer stable KtLint release is available."""

    current = versions["ktlint_version"]
    latest = latest_ktlint_version()
    current_key = stable_version_key(current)
    latest_key = stable_version_key(latest)
    console = Console(sys.stdout)
    if current_key == latest_key:
        console.ok("ktlint version", current)
        return 0
    if current_key > latest_key:
        console.info("ktlint version", f"Configured {current}; upstream {latest}.")
        return 0
    console.error("ktlint version", f"Update available: {current} -> {latest}.")
    console.info("Command", "./scripts/wasmline versions update-ktlint")
    return 1


def list_versions(versions: VersionMap) -> None:
    for key in REQUIRED_KEYS:
        print(f"{key}={versions[key]}")


AdditionalFile = tuple[str, Path, str]


def sync_files(
    versions: VersionMap,
    check: bool,
    additional_files: tuple[AdditionalFile, ...] = (),
) -> int:
    """Render all managed files before reporting or writing changes."""

    original_by_path: dict[Path, str] = {}
    updated_by_path: dict[Path, str] = {}
    path_order: list[tuple[str, Path]] = []
    for spec in file_specs():
        path = PROJECT_ROOT / spec.path
        if not path.is_file():
            raise SystemExit(f"Managed file does not exist: {spec.path}")
        if path not in original_by_path:
            original_by_path[path] = path.read_text(encoding="utf-8")
            updated_by_path[path] = original_by_path[path]
            path_order.append((spec.path, path))
        updated_by_path[path] = apply_rules(updated_by_path[path], spec, versions)

    for display_path, path, content in additional_files:
        if path in original_by_path:
            raise SystemExit(f"Managed file was registered more than once: {display_path}")
        original_by_path[path] = path.read_text(encoding="utf-8") if path.is_file() else ""
        updated_by_path[path] = content
        path_order.append((display_path, path))

    changed_files = [
        display_path
        for display_path, path in path_order
        if updated_by_path[path] != original_by_path[path]
    ]

    if not check:
        for _, path in path_order:
            if updated_by_path[path] != original_by_path[path]:
                write_text_atomic(path, updated_by_path[path])

    if check:
        if changed_files:
            console = Console(sys.stdout)
            for item in changed_files:
                console.error("Version file", item)
            return 1
        Console(sys.stdout).ok("Version check", "All managed files are synchronized.")
        return 0

    if changed_files:
        console = Console(sys.stdout)
        for item in changed_files:
            console.ok("Version file", item)
    else:
        Console(sys.stdout).ok("Version sync", "No changes were necessary.")
    return 0


def load_or_refresh_toolchain_lock(
    versions: VersionMap,
    updated_keys: set[str],
    *,
    allow_refresh: bool,
) -> dict[str, Any]:
    """Load the toolchain lock and refresh it when manifest versions changed."""

    refresh_required = bool(updated_keys & toolchain_lock.TOOLCHAIN_VERSION_KEYS)
    if not refresh_required:
        lock = toolchain_lock.load_lock()
        try:
            toolchain_lock.validate_lock(lock, versions)
        except toolchain_lock.ToolchainLockVersionMismatchError:
            if not allow_refresh:
                raise
            refresh_required = True
        else:
            return lock

    Console(sys.stdout).info("Toolchain lock", "Resolving GitHub release metadata.")
    return toolchain_lock.generate_lock(versions)


def synchronize(
    updates: Mapping[str, str] | None = None,
    *,
    check: bool = False,
    verify: bool = False,
) -> int:
    """Synchronize every managed file from the manifest and optional updates."""

    data = load_manifest()
    versions = data["versions"]
    if updates:
        versions.update(updates)
        validate_versions(versions)
        data["versions"] = versions

    try:
        lock = load_or_refresh_toolchain_lock(
            versions,
            set(updates or ()),
            allow_refresh=not (check or verify),
        )
        if verify:
            toolchain_lock.verify_upstream(lock, versions)
            Console(sys.stdout).ok("Toolchain lock", "GitHub metadata matches.")
            if not check:
                return 0
    except toolchain_lock.ToolchainLockError as error:
        raise SystemExit(str(error)) from error

    additional_files: tuple[AdditionalFile, ...] = (
        (
            toolchain_lock.LOCK_PATH.relative_to(PROJECT_ROOT).as_posix(),
            toolchain_lock.LOCK_PATH,
            toolchain_lock.render_lock(lock),
        ),
        (
            KOTLIN_RELEASE_IDENTITY_PATH.relative_to(PROJECT_ROOT).as_posix(),
            KOTLIN_RELEASE_IDENTITY_PATH,
            render_kotlin_release_identity(versions),
        ),
        (
            MANIFEST_PATH.relative_to(PROJECT_ROOT).as_posix(),
            MANIFEST_PATH,
            render_manifest(data),
        ),
    )
    return sync_files(versions, check=check, additional_files=additional_files)


def list_current() -> int:
    """Print the checked-in version values as key=value."""

    list_versions(load_manifest()["versions"])
    return 0


def check_current() -> int:
    """Fail when any managed file differs from the manifest values."""

    return synchronize(check=True)


def verify_upstream() -> int:
    """Compare the checked-in toolchain lock with GitHub release metadata."""

    return synchronize(verify=True)


def update_ktlint() -> int:
    """Move ktlint_version to the latest stable release and synchronize."""

    try:
        latest = latest_ktlint_version()
    except KtlintReleaseError as error:
        raise SystemExit(str(error)) from error
    current = load_manifest()["versions"]["ktlint_version"]
    current_key = stable_version_key(current)
    latest_key = stable_version_key(latest)
    console = Console(sys.stdout)
    if current_key == latest_key:
        console.ok("ktlint version", current)
        return 0
    if current_key > latest_key:
        console.info("ktlint version", f"Configured {current}; upstream {latest}.")
        return 0
    console.info("ktlint version", f"Updating {current} -> {latest}.")
    return synchronize({"ktlint_version": latest})


def check_ktlint() -> int:
    """Fail when a newer stable KtLint release is available."""

    try:
        return check_ktlint_latest(load_manifest()["versions"])
    except KtlintReleaseError as error:
        raise SystemExit(str(error)) from error
