#!/usr/bin/env python3
"""Generate and validate the checked-in Component toolchain lock."""

from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Protocol
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

from .paths import PROJECT_ROOT


GENERATOR = "scripts/wasmline versions sync"
SOURCE_MANIFEST = "versions.json"
LOCK_PATH = (
    PROJECT_ROOT
    / "wasmline-multiplatform/wasmline-plugin-core/src/main/resources"
    / "META-INF/wasmline/toolchain/toolchain-lock.json"
)

LOCK_SCHEMA_VERSION = 1
TOOLCHAIN_VERSION_KEYS = frozenset(
    {
        "wasmtime_version",
        "wasm_tools_version",
        "wit_bindgen_version",
    }
)

_LOCK_VERSION_KEYS = (
    "wit_bindgen_version",
    "wasm_tools_version",
    "wasmtime_version",
)
_SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")


class ToolchainLockError(RuntimeError):
    """Raised when release metadata or lock data is invalid."""


class ToolchainLockVersionMismatchError(ToolchainLockError):
    """Raised when locked tool versions differ from the version manifest."""


class ReleaseClient(Protocol):
    """Provides GitHub release metadata for one repository and tag."""

    def get_release(self, repository: str, tag: str) -> Mapping[str, Any]:
        """Return decoded release metadata."""

        ...


@dataclass(frozen=True)
class ExpectedAsset:
    """Defines one release asset required by the supported platform matrix."""

    platform: str
    archive_name: str
    distribution: str
    entry_file_name: str
    executable: bool


@dataclass(frozen=True)
class ToolRequest:
    """Defines one locked tool release and its required assets."""

    tool: str
    version_key: str
    repository: str
    version: str
    assets: tuple[ExpectedAsset, ...]

    @property
    def release_tag(self) -> str:
        return f"v{self.version}"


class GitHubReleaseClient:
    """Loads release metadata from the GitHub REST API."""

    def __init__(self, token: str | None = None, timeout_seconds: float = 30.0) -> None:
        self._token = token or os.environ.get("GITHUB_TOKEN")
        self._timeout_seconds = timeout_seconds

    def get_release(self, repository: str, tag: str) -> Mapping[str, Any]:
        encoded_tag = quote(tag, safe="")
        url = f"https://api.github.com/repos/{repository}/releases/tags/{encoded_tag}"
        headers = {
            "Accept": "application/vnd.github+json",
            "User-Agent": "wasmline-toolchain-lock",
            "X-GitHub-Api-Version": "2022-11-28",
        }
        if self._token:
            headers["Authorization"] = f"Bearer {self._token}"

        request = Request(url, headers=headers)
        try:
            with urlopen(request, timeout=self._timeout_seconds) as response:
                payload = response.read().decode("utf-8")
        except HTTPError as error:
            raise ToolchainLockError(
                f"GitHub release request failed for {repository}@{tag}: "
                f"HTTP {error.code} {error.reason}."
            ) from error
        except URLError as error:
            raise ToolchainLockError(
                f"GitHub release request failed for {repository}@{tag}: {error.reason}."
            ) from error

        try:
            data = json.loads(payload)
        except json.JSONDecodeError as error:
            raise ToolchainLockError(
                f"GitHub returned invalid JSON for {repository}@{tag}: {error}."
            ) from error
        if not isinstance(data, dict):
            raise ToolchainLockError(
                f"GitHub returned an invalid release object for {repository}@{tag}."
            )
        return data


def tool_requests(versions: Mapping[str, str]) -> tuple[ToolRequest, ...]:
    """Build the required release asset matrix from manifest versions."""

    wit_bindgen_version = versions["wit_bindgen_version"]
    wasm_tools_version = versions["wasm_tools_version"]
    wasmtime_version = versions["wasmtime_version"]

    wit_bindgen_platforms = (
        "aarch64-linux",
        "aarch64-macos",
        "aarch64-windows",
        "riscv64gc-linux",
        "x86_64-linux",
        "x86_64-macos",
        "x86_64-windows",
    )
    wasm_tools_platforms = (
        "aarch64-linux",
        "aarch64-macos",
        "aarch64-musl",
        "aarch64-windows",
        "riscv64-linux",
        "wasm32-wasip1",
        "x86_64-linux",
        "x86_64-macos",
        "x86_64-musl",
        "x86_64-windows",
    )

    return (
        ToolRequest(
            tool="WIT_BINDGEN",
            version_key="wit_bindgen_version",
            repository="crowforkotlin/wit-bindgen",
            version=wit_bindgen_version,
            assets=tuple(
                _archive_asset("wit-bindgen", wit_bindgen_version, platform)
                for platform in wit_bindgen_platforms
            ),
        ),
        ToolRequest(
            tool="WASM_TOOLS",
            version_key="wasm_tools_version",
            repository="bytecodealliance/wasm-tools",
            version=wasm_tools_version,
            assets=tuple(
                _archive_asset("wasm-tools", wasm_tools_version, platform)
                for platform in wasm_tools_platforms
            ),
        ),
        ToolRequest(
            tool="WASI_PREVIEW1_REACTOR_ADAPTER",
            version_key="wasmtime_version",
            repository="bytecodealliance/wasmtime",
            version=wasmtime_version,
            assets=(
                ExpectedAsset(
                    platform="universal",
                    archive_name="wasi_snapshot_preview1.reactor.wasm",
                    distribution="FILE",
                    entry_file_name="wasi_snapshot_preview1.reactor.wasm",
                    executable=False,
                ),
            ),
        ),
    )


def generate_lock(
    versions: Mapping[str, str],
    client: ReleaseClient | None = None,
) -> dict[str, Any]:
    """Resolve all required release assets and return validated lock data."""

    release_client = client or GitHubReleaseClient()
    tools: list[dict[str, Any]] = []
    for tool in tool_requests(versions):
        release = release_client.get_release(tool.repository, tool.release_tag)
        tools.append(_lock_tool(tool, release))

    lock = {
        "schemaVersion": LOCK_SCHEMA_VERSION,
        "generatedBy": GENERATOR,
        "sourceManifest": SOURCE_MANIFEST,
        "versions": {key: versions[key] for key in _LOCK_VERSION_KEYS},
        "tools": tools,
    }
    validate_lock(lock, versions)
    return lock


def load_lock(path: Path = LOCK_PATH) -> dict[str, Any]:
    """Load lock data from disk."""

    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise ToolchainLockError(f"Missing toolchain lock: {path}.") from error
    except json.JSONDecodeError as error:
        raise ToolchainLockError(f"Invalid JSON in {path}: {error}.") from error
    if not isinstance(data, dict):
        raise ToolchainLockError(f"Toolchain lock must contain a JSON object: {path}.")
    return data


def render_lock(lock: Mapping[str, Any]) -> str:
    """Render lock data in a deterministic format."""

    rendered = dict(lock)
    rendered["generatedBy"] = GENERATOR
    rendered["sourceManifest"] = SOURCE_MANIFEST
    return json.dumps(rendered, ensure_ascii=True, indent=2) + "\n"


def validate_lock(lock: Mapping[str, Any], versions: Mapping[str, str]) -> None:
    """Validate lock structure and its relationship to the version manifest."""

    expected_top_level = {
        "schemaVersion",
        "generatedBy",
        "sourceManifest",
        "versions",
        "tools",
    }
    if set(lock) != expected_top_level:
        raise ToolchainLockError("Toolchain lock contains an unexpected top-level structure.")
    if lock.get("schemaVersion") != LOCK_SCHEMA_VERSION:
        raise ToolchainLockError(
            f"Unsupported toolchain lock schema: {lock.get('schemaVersion')}."
        )
    if lock.get("generatedBy") != GENERATOR:
        raise ToolchainLockError("Toolchain lock has an invalid generator identifier.")
    if lock.get("sourceManifest") != SOURCE_MANIFEST:
        raise ToolchainLockError("Toolchain lock has an invalid source manifest.")

    locked_versions = _validated_lock_versions(lock)
    tools = lock.get("tools")
    if not isinstance(tools, list):
        raise ToolchainLockError("Toolchain lock 'tools' must be a list.")
    locked_by_name = _index_objects(tools, "tool", "toolchain lock tools")
    expected_tools = {tool.tool: tool for tool in tool_requests(locked_versions)}
    if set(locked_by_name) != set(expected_tools):
        raise ToolchainLockError("Toolchain lock does not contain the required tools.")

    for tool_name, expected_tool in expected_tools.items():
        _validate_locked_tool(locked_by_name[tool_name], expected_tool)

    expected_versions = {key: versions[key] for key in _LOCK_VERSION_KEYS}
    if locked_versions != expected_versions:
        raise ToolchainLockVersionMismatchError(
            "Toolchain lock versions do not match versions.json. "
            "Run ./scripts/wasmline versions sync to regenerate the lock."
        )


def verify_upstream(
    lock: Mapping[str, Any],
    versions: Mapping[str, str],
    client: ReleaseClient | None = None,
) -> None:
    """Confirm that checked-in asset metadata still matches GitHub releases."""

    validate_lock(lock, versions)
    current = generate_lock(versions, client)
    if current == lock:
        return

    differences = _describe_differences(lock, current)
    details = "\n".join(f"  - {difference}" for difference in differences)
    raise ToolchainLockError(f"Upstream toolchain metadata changed:\n{details}")


def _archive_asset(prefix: str, version: str, platform: str) -> ExpectedAsset:
    windows = platform.endswith("-windows")
    extension = "zip" if windows else "tar.gz"
    executable_suffix = ".exe" if windows else ""
    return ExpectedAsset(
        platform=platform,
        archive_name=f"{prefix}-{version}-{platform}.{extension}",
        distribution="ZIP" if windows else "TAR_GZ",
        entry_file_name=f"{prefix}{executable_suffix}",
        executable=True,
    )


def _lock_tool(tool: ToolRequest, release: Mapping[str, Any]) -> dict[str, Any]:
    release_tag = _required_string(release, "tag_name", f"{tool.repository} release")
    if release_tag != tool.release_tag:
        raise ToolchainLockError(
            f"Expected release tag {tool.release_tag} for {tool.repository}, found {release_tag}."
        )
    if release.get("draft") is True:
        raise ToolchainLockError(f"Release {tool.repository}@{tool.release_tag} is a draft.")

    release_id = _required_positive_int(release, "id", f"{tool.repository} release")
    raw_assets = release.get("assets")
    if not isinstance(raw_assets, list):
        raise ToolchainLockError(
            f"Release {tool.repository}@{tool.release_tag} does not contain an asset list."
        )
    assets_by_name = _index_objects(raw_assets, "name", f"{tool.repository} release assets")

    assets: list[dict[str, Any]] = []
    for expected in tool.assets:
        raw_asset = assets_by_name.get(expected.archive_name)
        if raw_asset is None:
            raise ToolchainLockError(
                f"Release {tool.repository}@{tool.release_tag} is missing "
                f"{expected.archive_name}."
            )
        assets.append(_lock_asset(tool, expected, raw_asset))

    return {
        "tool": tool.tool,
        "version": tool.version,
        "versionKey": tool.version_key,
        "repository": tool.repository,
        "releaseTag": tool.release_tag,
        "releaseId": release_id,
        "assets": assets,
    }


def _lock_asset(
    tool: ToolRequest,
    expected: ExpectedAsset,
    raw_asset: Mapping[str, Any],
) -> dict[str, Any]:
    context = f"{tool.repository} asset {expected.archive_name}"
    digest = _required_string(raw_asset, "digest", context)
    prefix = "sha256:"
    if not digest.startswith(prefix) or not _SHA256_PATTERN.fullmatch(digest[len(prefix) :]):
        raise ToolchainLockError(f"{context} does not contain a valid SHA-256 digest.")

    download_url = _required_string(raw_asset, "browser_download_url", context)
    expected_url = (
        f"https://github.com/{tool.repository}/releases/download/"
        f"{tool.release_tag}/{expected.archive_name}"
    )
    if download_url != expected_url:
        raise ToolchainLockError(
            f"{context} has an unexpected download URL: {download_url}."
        )

    return {
        "platform": expected.platform,
        "assetId": _required_positive_int(raw_asset, "id", context),
        "size": _required_positive_int(raw_asset, "size", context),
        "updatedAt": _required_string(raw_asset, "updated_at", context),
        "archiveName": expected.archive_name,
        "downloadUrl": download_url,
        "sha256": digest[len(prefix) :],
        "distribution": expected.distribution,
        "entryFileName": expected.entry_file_name,
        "executable": expected.executable,
    }


def _validate_locked_tool(locked: Mapping[str, Any], expected: ToolRequest) -> None:
    expected_keys = {
        "tool",
        "version",
        "versionKey",
        "repository",
        "releaseTag",
        "releaseId",
        "assets",
    }
    if set(locked) != expected_keys:
        raise ToolchainLockError(f"Locked tool {expected.tool} has an invalid structure.")

    expected_values = {
        "tool": expected.tool,
        "version": expected.version,
        "versionKey": expected.version_key,
        "repository": expected.repository,
        "releaseTag": expected.release_tag,
    }
    for key, value in expected_values.items():
        if locked.get(key) != value:
            raise ToolchainLockError(f"Locked tool {expected.tool} has an invalid {key} value.")
    _positive_int_value(locked.get("releaseId"), f"Locked tool {expected.tool} releaseId")

    assets = locked.get("assets")
    if not isinstance(assets, list):
        raise ToolchainLockError(f"Locked tool {expected.tool} assets must be a list.")
    locked_by_platform = _index_objects(
        assets,
        "platform",
        f"locked tool {expected.tool} assets",
    )
    expected_by_platform = {asset.platform: asset for asset in expected.assets}
    if set(locked_by_platform) != set(expected_by_platform):
        raise ToolchainLockError(
            f"Locked tool {expected.tool} does not contain the required platforms."
        )
    for platform, expected_asset in expected_by_platform.items():
        _validate_locked_asset(locked_by_platform[platform], expected, expected_asset)


def _validated_lock_versions(lock: Mapping[str, Any]) -> dict[str, str]:
    locked_versions = lock.get("versions")
    if not isinstance(locked_versions, dict) or set(locked_versions) != set(
        _LOCK_VERSION_KEYS
    ):
        raise ToolchainLockError("Toolchain lock contains an invalid version map.")

    result: dict[str, str] = {}
    for key in _LOCK_VERSION_KEYS:
        value = locked_versions.get(key)
        if not isinstance(value, str) or not value:
            raise ToolchainLockError(
                f"Toolchain lock contains an invalid version for {key}."
            )
        result[key] = value
    return result


def _validate_locked_asset(
    locked: Mapping[str, Any],
    tool: ToolRequest,
    expected: ExpectedAsset,
) -> None:
    expected_keys = {
        "platform",
        "assetId",
        "size",
        "updatedAt",
        "archiveName",
        "downloadUrl",
        "sha256",
        "distribution",
        "entryFileName",
        "executable",
    }
    if set(locked) != expected_keys:
        raise ToolchainLockError(
            f"Locked asset {tool.tool}/{expected.platform} has an invalid structure."
        )

    expected_values = {
        "platform": expected.platform,
        "archiveName": expected.archive_name,
        "downloadUrl": (
            f"https://github.com/{tool.repository}/releases/download/"
            f"{tool.release_tag}/{expected.archive_name}"
        ),
        "distribution": expected.distribution,
        "entryFileName": expected.entry_file_name,
        "executable": expected.executable,
    }
    for key, value in expected_values.items():
        if locked.get(key) != value:
            raise ToolchainLockError(
                f"Locked asset {tool.tool}/{expected.platform} has an invalid {key} value."
            )

    _positive_int_value(
        locked.get("assetId"),
        f"Locked asset {tool.tool}/{expected.platform} assetId",
    )
    _positive_int_value(
        locked.get("size"),
        f"Locked asset {tool.tool}/{expected.platform} size",
    )
    updated_at = locked.get("updatedAt")
    if not isinstance(updated_at, str) or not updated_at:
        raise ToolchainLockError(
            f"Locked asset {tool.tool}/{expected.platform} has an invalid updatedAt value."
        )
    sha256 = locked.get("sha256")
    if not isinstance(sha256, str) or not _SHA256_PATTERN.fullmatch(sha256):
        raise ToolchainLockError(
            f"Locked asset {tool.tool}/{expected.platform} has an invalid SHA-256 digest."
        )


def _index_objects(
    values: list[Any],
    key: str,
    context: str,
) -> dict[str, Mapping[str, Any]]:
    indexed: dict[str, Mapping[str, Any]] = {}
    for value in values:
        if not isinstance(value, dict):
            raise ToolchainLockError(f"{context} must contain JSON objects.")
        name = value.get(key)
        if not isinstance(name, str) or not name:
            raise ToolchainLockError(f"{context} contains an invalid {key} value.")
        if name in indexed:
            raise ToolchainLockError(f"{context} contains duplicate {key} value '{name}'.")
        indexed[name] = value
    return indexed


def _required_string(value: Mapping[str, Any], key: str, context: str) -> str:
    result = value.get(key)
    if not isinstance(result, str) or not result:
        raise ToolchainLockError(f"{context} is missing a valid {key} value.")
    return result


def _required_positive_int(value: Mapping[str, Any], key: str, context: str) -> int:
    return _positive_int_value(value.get(key), f"{context} {key}")


def _positive_int_value(value: Any, context: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise ToolchainLockError(f"{context} must be a positive integer.")
    return value


def _describe_differences(
    locked: Mapping[str, Any],
    current: Mapping[str, Any],
) -> list[str]:
    locked_assets = _asset_index(locked)
    current_assets = _asset_index(current)
    differences: list[str] = []
    for key in sorted(set(locked_assets) | set(current_assets)):
        old = locked_assets.get(key)
        new = current_assets.get(key)
        if old is None:
            differences.append(f"{key[0]}/{key[1]} was added upstream")
            continue
        if new is None:
            differences.append(f"{key[0]}/{key[1]} is missing upstream")
            continue
        for field in ("assetId", "size", "updatedAt", "downloadUrl", "sha256"):
            if old.get(field) != new.get(field):
                differences.append(
                    f"{key[0]}/{key[1]} {field}: {old.get(field)} -> {new.get(field)}"
                )
    return differences or ["release metadata differs from the checked-in lock"]


def _asset_index(lock: Mapping[str, Any]) -> dict[tuple[str, str], Mapping[str, Any]]:
    result: dict[tuple[str, str], Mapping[str, Any]] = {}
    tools = lock.get("tools")
    if not isinstance(tools, list):
        return result
    for tool in tools:
        if not isinstance(tool, dict) or not isinstance(tool.get("tool"), str):
            continue
        assets = tool.get("assets")
        if not isinstance(assets, list):
            continue
        for asset in assets:
            if not isinstance(asset, dict) or not isinstance(asset.get("platform"), str):
                continue
            result[(tool["tool"], asset["platform"])] = asset
    return result
