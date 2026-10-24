"""Public AOT compatibility catalog and generated-file paths."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any, Mapping

from ..paths import PROJECT_ROOT

AOT_LOCK_PATH = (
    PROJECT_ROOT
    / "wasmline-plugin-core"
    / "src"
    / "main"
    / "resources"
    / "META-INF"
    / "wasmline"
    / "aot"
    / "aot-compatibility-lock.json"
)
PUBLIC_CATALOG_PATH = PROJECT_ROOT / "aot-compatibility.json"
PACKAGED_PUBLIC_CATALOG_PATH = (
    PROJECT_ROOT
    / "wasmline-plugin-core"
    / "src"
    / "main"
    / "resources"
    / "META-INF"
    / "wasmline"
    / "aot"
    / "aot-compatibility.json"
)
NATIVE_BUILD_IDENTITY_PATH = (
    PROJECT_ROOT
    / "wasmline-core"
    / "include"
    / "wasmline"
    / "internal"
    / "runtime"
    / "NativeBuildIdentity.h"
)
KOTLIN_RELEASE_IDENTITY_PATH = (
    PROJECT_ROOT
    / "wasmline"
    / "src"
    / "commonMain"
    / "kotlin"
    / "crow"
    / "wasmline"
    / "WasmlineReleaseIdentity.kt"
)

GENERATOR = "scripts/wasmline aot sync"
SOURCE_CATALOG = "aot-compatibility.json"
NATIVE_BRIDGE_ABI_VERSION = 2

BACKENDS = ("CRANELIFT", "PULLEY")
COMPILER_DISTRIBUTION = "FULL"
CURRENT_SERIALIZED_ARTIFACT_FORMAT_IDENTITY = "wasmtime-module-version-strategy-v1"

PROFILE_ID_PATTERN = re.compile(r"^sha256:[0-9a-f]{64}$")
DIGEST_PATTERN = re.compile(r"^[0-9a-f]{64}$")
SEMANTIC_VERSION_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
RELEASE_VERSION_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+\.[1-9][0-9]*$")


class AotCompatibilityError(RuntimeError):
    """Raised when the public AOT catalog or its generated lock is invalid."""


def _semantic_version_key(value: str) -> tuple[int, int, int]:
    """Return a comparable key for one stable Wasmline version."""

    if not SEMANTIC_VERSION_PATTERN.fullmatch(value):
        raise AotCompatibilityError(f"Invalid stable Wasmline version: {value!r}.")
    return tuple(int(part) for part in value.split("."))


def _source_from(value: Mapping[str, Any] | None) -> Mapping[str, Any]:
    """Return a standalone catalog and reject the removed manifest embedding."""

    if value is None:
        return load_public_catalog()
    if "aotCompatibility" in value or "versions" in value:
        raise AotCompatibilityError(
            "AOT compatibility must be maintained in root aot-compatibility.json; "
            "versions.json must contain only the versions object."
        )
    return value


def load_public_catalog(path: Path = PUBLIC_CATALOG_PATH) -> dict[str, Any]:
    """Load and validate the manually maintained public AOT catalog."""

    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise AotCompatibilityError(f"Missing public AOT catalog: {path}") from error
    except json.JSONDecodeError as error:
        raise AotCompatibilityError(f"Invalid JSON in public AOT catalog: {error}") from error
    if not isinstance(value, dict):
        raise AotCompatibilityError("The public AOT catalog must be a JSON object.")
    validate_source(value)
    return value


def validate_source(
    catalog_or_manifest: Mapping[str, Any],
    versions: Mapping[str, str] | None = None,
) -> None:
    """Validate the compact root catalog and its relation to current versions."""

    source = _source_from(catalog_or_manifest)
    expected_keys = {
        "schemaVersion",
        "currentWasmlineVersion",
        "minimumSupportedWasmlineVersion",
        "ranges",
    }
    missing_keys = expected_keys - set(source)
    unknown_keys = set(source) - expected_keys
    if missing_keys:
        raise AotCompatibilityError(
            "aot-compatibility.json is missing fields: " + ", ".join(sorted(missing_keys))
        )
    if unknown_keys:
        raise AotCompatibilityError(
            "aot-compatibility.json contains unsupported fields: " + ", ".join(sorted(unknown_keys))
        )
    if source.get("schemaVersion") != 1:
        raise AotCompatibilityError("aot-compatibility.json schemaVersion must be 1.")
    current = source.get("currentWasmlineVersion")
    minimum = source.get("minimumSupportedWasmlineVersion")
    if not isinstance(current, str) or not SEMANTIC_VERSION_PATTERN.fullmatch(current):
        raise AotCompatibilityError("currentWasmlineVersion must use x.y.z.")
    if not isinstance(minimum, str) or not SEMANTIC_VERSION_PATTERN.fullmatch(minimum):
        raise AotCompatibilityError("minimumSupportedWasmlineVersion must use x.y.z.")
    if _semantic_version_key(minimum) > _semantic_version_key(current):
        raise AotCompatibilityError("minimumSupportedWasmlineVersion cannot exceed currentWasmlineVersion.")
    ranges = source.get("ranges")
    if not isinstance(ranges, list) or not ranges:
        raise AotCompatibilityError("aot-compatibility.json ranges must not be empty.")

    previous_start: tuple[int, int, int] | None = None
    for index, item in enumerate(ranges):
        if not isinstance(item, Mapping):
            raise AotCompatibilityError("AOT range entries must be objects.")
        start = item.get("fromWasmlineVersion")
        if not isinstance(start, str) or not SEMANTIC_VERSION_PATTERN.fullmatch(start):
            raise AotCompatibilityError(
                f"AOT range {index + 1} has an invalid fromWasmlineVersion."
            )
        start_key = _semantic_version_key(start)
        if start_key > _semantic_version_key(current):
            raise AotCompatibilityError(
                f"AOT range {index + 1} starts after currentWasmlineVersion."
            )
        if previous_start is not None and start_key <= previous_start:
            raise AotCompatibilityError("AOT range starts must be strictly increasing.")
        generation = item.get("aotGeneration")
        if isinstance(generation, bool) or not isinstance(generation, int) or generation != index + 1:
            raise AotCompatibilityError(
                f"AOT generations must start at 1 and increase without gaps; expected {index + 1}, got {generation!r}."
            )
        distribution = item.get("wasmtimeDistributionVersion")
        if not isinstance(distribution, str) or not RELEASE_VERSION_PATTERN.fullmatch(distribution):
            raise AotCompatibilityError(
                f"AOT generation {generation} has an invalid Wasmtime distribution version."
            )
        changed = item.get("changedBackends")
        if not isinstance(changed, list) or not changed:
            raise AotCompatibilityError(
                f"AOT generation {generation} must identify at least one changed backend."
            )
        if any(backend not in BACKENDS for backend in changed):
            raise AotCompatibilityError(
                f"AOT generation {generation} contains an unknown backend."
            )
        if len(set(changed)) != len(changed):
            raise AotCompatibilityError(
                f"AOT generation {generation} contains duplicate changed backends."
            )
        if changed != [backend for backend in BACKENDS if backend in changed]:
            raise AotCompatibilityError(
                f"AOT generation {generation} changedBackends must use canonical backend order."
            )
        if index == 0 and set(changed) != set(BACKENDS):
            raise AotCompatibilityError("The first AOT generation must mark CRANELIFT and PULLEY as changed.")
        previous_start = start_key

    if _semantic_version_key(str(ranges[0]["fromWasmlineVersion"])) > _semantic_version_key(minimum):
        raise AotCompatibilityError("The first AOT range starts after minimumSupportedWasmlineVersion.")
    if _semantic_version_key(str(ranges[-1]["fromWasmlineVersion"])) > _semantic_version_key(current):
        raise AotCompatibilityError("currentWasmlineVersion is outside the final AOT range.")
    if versions is not None:
        configured_current = versions.get("wasmline_version")
        if configured_current != current:
            raise AotCompatibilityError(
                "aot-compatibility.json currentWasmlineVersion must match "
                f"versions.json ({configured_current!r})."
            )
        configured_distribution = versions.get("wasmtime_release_version")
        final_distribution = ranges[-1]["wasmtimeDistributionVersion"]
        if configured_distribution != final_distribution:
            raise AotCompatibilityError(
                "The final AOT range distribution must match versions.json "
                f"({configured_distribution!r})."
            )


def render_public_catalog(
    catalog_or_manifest: Mapping[str, Any],
    versions: Mapping[str, str] | None = None,
) -> dict[str, Any]:
    """Return a validated copy of the compact public catalog."""

    source = _source_from(catalog_or_manifest)
    validate_source(source, versions)
    return json.loads(json.dumps(source, ensure_ascii=True))


def render_public_catalog_text(
    catalog_or_manifest: Mapping[str, Any],
    versions: Mapping[str, str] | None = None,
) -> str:
    """Render the public catalog using deterministic JSON formatting."""

    return json.dumps(
        render_public_catalog(catalog_or_manifest, versions),
        ensure_ascii=True,
        indent=2,
    ) + "\n"
