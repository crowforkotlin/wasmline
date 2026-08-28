"""Validate and render the immutable Wasmtime AOT compatibility catalog."""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from typing import Any, Mapping

from .paths import PROJECT_ROOT

AOT_LOCK_PATH = (
    PROJECT_ROOT
    / "wasmline-multiplatform"
    / "wasmline-plugin-core"
    / "src"
    / "main"
    / "resources"
    / "META-INF"
    / "wasmline"
    / "aot"
    / "aot-compatibility-lock.json"
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
    / "wasmline-multiplatform"
    / "wasmline"
    / "src"
    / "commonMain"
    / "kotlin"
    / "crow"
    / "wasmline"
    / "WasmlineReleaseIdentity.kt"
)
GENERATOR = "scripts/wasmline versions sync"
SOURCE_MANIFEST = "scripts/versions.json"
NATIVE_BRIDGE_ABI_VERSION = 1
PROFILE_DOMAIN = b"wasmline.aot-compatibility-profile\0"
BACKENDS = ("CRANELIFT", "PULLEY")
COMPILER_DISTRIBUTION = "FULL"
PROFILE_ID_PATTERN = re.compile(r"^sha256:[0-9a-f]{64}$")
DIGEST_PATTERN = re.compile(r"^[0-9a-f]{64}$")
SEMANTIC_VERSION_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
RELEASE_VERSION_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+\.[1-9][0-9]*$")


class AotCompatibilityError(RuntimeError):
    """Raised when the immutable AOT catalog violates its schema."""


def canonical_profile_bytes(profile: Mapping[str, Any]) -> bytes:
    """Encode compatibility-affecting profile fields in a frozen order."""

    values = {
        "artifactBackend": profile.get("artifactBackend"),
        "compileProfileSchemaVersion": profile.get("compileProfileSchemaVersion"),
        "engineConfigurationProfile": profile.get("engineConfigurationProfile"),
        "serializedArtifactFormatIdentity": profile.get("serializedArtifactFormatIdentity"),
        "wasmtimeDistributionVersion": profile.get("wasmtimeDistributionVersion"),
        "wasmtimeSourceRevision": profile.get("wasmtimeSourceRevision"),
        "wasmtimeVersion": profile.get("wasmtimeVersion"),
    }
    if any(value is None for value in values.values()):
        missing = sorted(key for key, value in values.items() if value is None)
        raise AotCompatibilityError(
            "AOT profile is missing canonical fields: " + ", ".join(missing)
        )
    lines = "".join(f"{key}={values[key]}\n" for key in sorted(values))
    return PROFILE_DOMAIN + lines.encode("utf-8")


def compatibility_id(profile: Mapping[str, Any]) -> str:
    """Calculate one backend-specific AOT compatibility identifier."""

    return "sha256:" + hashlib.sha256(canonical_profile_bytes(profile)).hexdigest()


def validate_source(manifest: Mapping[str, Any], versions: Mapping[str, str]) -> None:
    """Validate source entries without network access or historical mutation."""

    source = manifest.get("aotCompatibility")
    if not isinstance(source, Mapping):
        raise AotCompatibilityError("scripts/versions.json must contain aotCompatibility.")
    if source.get("schemaVersion") != 1:
        raise AotCompatibilityError("aotCompatibility.schemaVersion must be 1.")

    profiles = source.get("profiles")
    assets = source.get("compilerAssets")
    defaults = source.get("currentDefaultProfileIdsByBackend")
    if not isinstance(profiles, list) or not profiles:
        raise AotCompatibilityError("aotCompatibility.profiles must not be empty.")
    if not isinstance(assets, list) or not assets:
        raise AotCompatibilityError("aotCompatibility.compilerAssets must not be empty.")
    if not isinstance(defaults, Mapping):
        raise AotCompatibilityError(
            "aotCompatibility.currentDefaultProfileIdsByBackend must be an object."
        )

    profiles_by_id: dict[str, Mapping[str, Any]] = {}
    default_candidates: dict[str, list[str]] = {backend: [] for backend in BACKENDS}
    for item in profiles:
        if not isinstance(item, Mapping):
            raise AotCompatibilityError("AOT profile entries must be objects.")
        profile_id = item.get("id")
        if not isinstance(profile_id, str) or not PROFILE_ID_PATTERN.fullmatch(profile_id):
            raise AotCompatibilityError(f"Invalid AOT compatibility profile ID: {profile_id!r}.")
        if profile_id in profiles_by_id:
            raise AotCompatibilityError(f"Duplicate AOT compatibility profile ID: {profile_id}.")
        calculated = compatibility_id(item)
        if profile_id != calculated:
            raise AotCompatibilityError(
                f"AOT compatibility profile ID mismatch for {profile_id}: expected {calculated}."
            )
        backend = item.get("artifactBackend")
        if backend not in BACKENDS:
            raise AotCompatibilityError(f"Invalid AOT artifact backend: {backend!r}.")
        wasmtime_version = item.get("wasmtimeVersion")
        distribution_version = item.get("wasmtimeDistributionVersion")
        if not isinstance(wasmtime_version, str) or not SEMANTIC_VERSION_PATTERN.fullmatch(wasmtime_version):
            raise AotCompatibilityError(f"Invalid AOT Wasmtime version: {wasmtime_version!r}.")
        if not isinstance(distribution_version, str) or not RELEASE_VERSION_PATTERN.fullmatch(distribution_version):
            raise AotCompatibilityError(
                f"Invalid AOT Wasmtime distribution version: {distribution_version!r}."
            )
        if distribution_version.rsplit(".", 1)[0] != wasmtime_version:
            raise AotCompatibilityError(
                f"AOT profile {profile_id} has inconsistent Wasmtime versions."
            )
        revision = item.get("wasmtimeSourceRevision")
        if not isinstance(revision, str) or not re.fullmatch(r"[0-9a-f]{40}", revision):
            raise AotCompatibilityError(f"AOT profile {profile_id} has an invalid source revision.")
        schema_version = item.get("compileProfileSchemaVersion")
        if not isinstance(schema_version, int) or schema_version <= 0:
            raise AotCompatibilityError(f"AOT profile {profile_id} has an invalid schema version.")
        introduced = item.get("introducedInWasmlineVersion")
        if not isinstance(introduced, str) or not SEMANTIC_VERSION_PATTERN.fullmatch(introduced):
            raise AotCompatibilityError(f"AOT profile {profile_id} has an invalid introduced version.")
        profiles_by_id[profile_id] = item
        if (
            wasmtime_version == versions["wasmtime_version"]
            and distribution_version == versions["wasmtime_release_version"]
        ):
            default_candidates[backend].append(profile_id)

    for backend in BACKENDS:
        configured = defaults.get(backend)
        candidates = default_candidates[backend]
        if len(candidates) != 1:
            raise AotCompatibilityError(
                f"Current Wasmtime release must resolve exactly one {backend} profile; found {len(candidates)}."
            )
        if configured != candidates[0]:
            raise AotCompatibilityError(
                f"Current default {backend} profile must be {candidates[0]}, not {configured!r}."
            )

    assets_by_digest: dict[str, Mapping[str, Any]] = {}
    asset_keys: set[tuple[str, str]] = set()
    hosts_by_distribution: dict[str, set[str]] = {}
    for item in assets:
        if not isinstance(item, Mapping):
            raise AotCompatibilityError("Compiler asset entries must be objects.")
        digest = item.get("archiveSha256")
        if not isinstance(digest, str) or not DIGEST_PATTERN.fullmatch(digest):
            raise AotCompatibilityError(f"Invalid compiler archive digest: {digest!r}.")
        if digest in assets_by_digest:
            raise AotCompatibilityError(f"Duplicate compiler archive digest: {digest}.")
        build_host = item.get("buildHost")
        distribution_version = item.get("wasmtimeDistributionVersion")
        if not isinstance(build_host, str) or not build_host:
            raise AotCompatibilityError("Compiler asset buildHost must not be blank.")
        if not isinstance(distribution_version, str) or not RELEASE_VERSION_PATTERN.fullmatch(distribution_version):
            raise AotCompatibilityError("Compiler asset has an invalid Wasmtime distribution version.")
        if item.get("distribution") != COMPILER_DISTRIBUTION:
            raise AotCompatibilityError(
                "AOT compiler assets must use the FULL Wasmtime distribution."
            )
        key = (distribution_version, build_host)
        if key in asset_keys:
            raise AotCompatibilityError(
                f"Duplicate compiler asset for {distribution_version}/{build_host}."
            )
        asset_keys.add(key)
        hosts_by_distribution.setdefault(distribution_version, set()).add(build_host)
        for field in ("assetId", "archiveName", "executableRelativePath"):
            if not isinstance(item.get(field), str) or not item[field]:
                raise AotCompatibilityError(f"Compiler asset {digest} has invalid {field}.")
        urls = item.get("downloadUrls")
        if not isinstance(urls, list) or not urls or not all(
            isinstance(value, str) and value.startswith("https://") for value in urls
        ):
            raise AotCompatibilityError(f"Compiler asset {digest} has invalid downloadUrls.")
        if not isinstance(item.get("archiveSize"), int) or item["archiveSize"] <= 0:
            raise AotCompatibilityError(f"Compiler asset {digest} has invalid archiveSize.")
        executable_digest = item.get("executableSha256")
        if not isinstance(executable_digest, str) or not DIGEST_PATTERN.fullmatch(executable_digest):
            raise AotCompatibilityError(f"Compiler asset {digest} has invalid executableSha256.")
        assets_by_digest[digest] = item

    for profile in profiles_by_id.values():
        distribution_version = str(profile["wasmtimeDistributionVersion"])
        if not hosts_by_distribution.get(distribution_version):
            raise AotCompatibilityError(
                f"AOT profile {profile['id']} has no compiler assets."
            )


def render_lock(manifest: Mapping[str, Any], versions: Mapping[str, str]) -> dict[str, Any]:
    """Render a deterministic lock with explicit profile-to-asset bindings."""

    validate_source(manifest, versions)
    source = manifest["aotCompatibility"]
    profiles = sorted(source["profiles"], key=lambda item: item["id"])
    assets = sorted(
        source["compilerAssets"],
        key=lambda item: (item["archiveSha256"], item["buildHost"]),
    )
    assets_by_distribution: dict[str, list[Mapping[str, Any]]] = {}
    for asset in assets:
        assets_by_distribution.setdefault(
            asset["wasmtimeDistributionVersion"], []
        ).append(asset)
    bindings = []
    for profile in profiles:
        for asset in assets_by_distribution[profile["wasmtimeDistributionVersion"]]:
            bindings.append(
                {
                    "profileId": profile["id"],
                    "artifactBackend": profile["artifactBackend"],
                    "buildHost": asset["buildHost"],
                    "compilerArchiveSha256": asset["archiveSha256"],
                }
            )
    lock_assets = [
        {
            key: asset[key]
            for key in (
                "buildHost",
                "distribution",
                "archiveFormat",
                "assetId",
                "archiveName",
                "downloadUrls",
                "archiveSha256",
                "archiveSize",
                "executableRelativePath",
                "executableSha256",
            )
        }
        for asset in assets
    ]
    return {
        "schemaVersion": 1,
        "generatedBy": GENERATOR,
        "sourceManifest": SOURCE_MANIFEST,
        "currentDefaultProfileIdsByBackend": dict(
            sorted(source["currentDefaultProfileIdsByBackend"].items())
        ),
        "profiles": profiles,
        "profileCompilerBindings": bindings,
        "compilerAssets": lock_assets,
    }


def render_lock_text(manifest: Mapping[str, Any], versions: Mapping[str, str]) -> str:
    """Render canonical JSON for the checked-in AOT compatibility lock."""

    return json.dumps(render_lock(manifest, versions), ensure_ascii=True, indent=2) + "\n"


def validate_append_only(
    previous_lock: Mapping[str, Any],
    generated_lock: Mapping[str, Any],
) -> None:
    """Reject removal or mutation of compatibility records already present in the lock."""

    previous_profiles = {
        item["id"]: item for item in previous_lock.get("profiles", []) if isinstance(item, Mapping)
    }
    generated_profiles = {
        item["id"]: item for item in generated_lock.get("profiles", []) if isinstance(item, Mapping)
    }
    for profile_id, previous in previous_profiles.items():
        current = generated_profiles.get(profile_id)
        if current is None:
            raise AotCompatibilityError(
                f"Published AOT compatibility profile {profile_id} cannot be removed."
            )
        if current != previous:
            raise AotCompatibilityError(
                f"Published AOT compatibility profile {profile_id} cannot be modified."
            )

    previous_assets = {
        item["archiveSha256"]: item
        for item in previous_lock.get("compilerAssets", [])
        if isinstance(item, Mapping)
    }
    generated_assets = {
        item["archiveSha256"]: item
        for item in generated_lock.get("compilerAssets", [])
        if isinstance(item, Mapping)
    }
    for digest, previous in previous_assets.items():
        current = generated_assets.get(digest)
        if current is None:
            raise AotCompatibilityError(
                f"Published AOT compiler asset {digest} cannot be removed."
            )
        previous_without_urls = {
            key: value for key, value in previous.items() if key != "downloadUrls"
        }
        current_without_urls = {
            key: value for key, value in current.items() if key != "downloadUrls"
        }
        if current_without_urls != previous_without_urls:
            raise AotCompatibilityError(
                f"Published AOT compiler asset {digest} cannot be modified."
            )
        if not set(previous.get("downloadUrls", [])).issubset(current.get("downloadUrls", [])):
            raise AotCompatibilityError(
                f"Published AOT compiler asset {digest} cannot remove download URLs."
            )

    previous_bindings = {
        (
            item.get("profileId"),
            item.get("artifactBackend"),
            item.get("buildHost"),
            item.get("compilerArchiveSha256"),
        )
        for item in previous_lock.get("profileCompilerBindings", [])
        if isinstance(item, Mapping)
    }
    generated_bindings = {
        (
            item.get("profileId"),
            item.get("artifactBackend"),
            item.get("buildHost"),
            item.get("compilerArchiveSha256"),
        )
        for item in generated_lock.get("profileCompilerBindings", [])
        if isinstance(item, Mapping)
    }
    missing_bindings = previous_bindings - generated_bindings
    if missing_bindings:
        raise AotCompatibilityError(
            "Published AOT profile compiler bindings cannot be removed or modified."
        )


def render_native_build_identity(manifest: Mapping[str, Any], versions: Mapping[str, str]) -> str:
    """Render native release, bridge ABI, and current backend profile constants."""

    validate_source(manifest, versions)
    defaults = manifest["aotCompatibility"]["currentDefaultProfileIdsByBackend"]
    return f'''/**
 * Defines native engine identity values generated from the Wasmline version manifest.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */

#pragma once

#define WASMLINE_RELEASE_VERSION "{versions["wasmline_version"]}"
#define WASMLINE_NATIVE_BRIDGE_ABI_VERSION {NATIVE_BRIDGE_ABI_VERSION}
#define WASMLINE_CRANELIFT_AOT_COMPATIBILITY_PROFILE_ID "{defaults["CRANELIFT"]}"
#define WASMLINE_PULLEY_AOT_COMPATIBILITY_PROFILE_ID "{defaults["PULLEY"]}"
'''


def render_kotlin_release_identity(versions: Mapping[str, str]) -> str:
    """Render the Kotlin identity checked against every linked native engine."""

    return f'''package crow.wasmline

/**
 * Defines the Kotlin runtime identity that must match every linked native engine.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
internal object WasmlineReleaseIdentity {{
    const val RELEASE_VERSION: String = "{versions["wasmline_version"]}"
    const val NATIVE_BRIDGE_ABI_VERSION: Int = {NATIVE_BRIDGE_ABI_VERSION}
}}
'''


def load_lock() -> dict[str, Any]:
    """Load the checked-in AOT compatibility lock."""

    try:
        value = json.loads(AOT_LOCK_PATH.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError) as error:
        raise AotCompatibilityError(f"Invalid AOT compatibility lock: {error}") from error
    if not isinstance(value, dict):
        raise AotCompatibilityError("AOT compatibility lock must be an object.")
    return value
