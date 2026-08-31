"""Validate and synchronize the Wasmline native AOT compatibility catalog."""

from __future__ import annotations

import hashlib
import json
import os
import re
import stat
import sys
import tempfile
from pathlib import Path
from typing import Any, Mapping

from .aot_metadata import AotMetadataResolver, GitHubAotMetadataResolver
from .output import Console
from .paths import MANIFEST_PATH, PROJECT_ROOT

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
PUBLIC_CATALOG_PATH = PROJECT_ROOT / "aot-compatibility.json"
PACKAGED_PUBLIC_CATALOG_PATH = (
    PROJECT_ROOT
    / "wasmline-multiplatform"
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
    / "wasmline-multiplatform"
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
NATIVE_BRIDGE_ABI_VERSION = 1
PROFILE_DOMAIN = b"wasmline.aot-compatibility-profile\0"
BACKENDS = ("CRANELIFT", "PULLEY")
COMPILER_DISTRIBUTION = "FULL"
CURRENT_SERIALIZED_ARTIFACT_FORMAT_IDENTITY = "wasmtime-module-version-strategy-v1"
CURRENT_COMPILE_PROFILE_SCHEMA_VERSION = 1
CURRENT_ENGINE_CONFIGURATION_PROFILES = {
    "CRANELIFT": (
        "wasmline-aot-v1;component-model=y;collector=drc;gc=y;gc-support=y;"
        "reference-types=y;function-references=y;exceptions=y;threads=n;simd=n;"
        "relaxed-simd=n;concurrency-support=y;max-wasm-stack=524288;memory-guard-size=0;"
        "signals-based-traps=n;opt-level=0;cranelift-debug-verifier=n"
    ),
    "PULLEY": (
        "wasmline-aot-v1;component-model=y;collector=drc;gc=y;gc-support=y;"
        "reference-types=y;function-references=y;exceptions=y;threads=n;simd=n;"
        "relaxed-simd=n;concurrency-support=y;max-wasm-stack=524288;memory-guard-size=0;"
        "signals-based-traps=n;opt-level=0;cranelift-debug-verifier=n"
    ),
}
PROFILE_ID_PATTERN = re.compile(r"^sha256:[0-9a-f]{64}$")
DIGEST_PATTERN = re.compile(r"^[0-9a-f]{64}$")
SEMANTIC_VERSION_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
RELEASE_VERSION_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+\.[1-9][0-9]*$")


class AotCompatibilityError(RuntimeError):
    """Raised when the public AOT catalog or its generated lock is invalid."""


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


def _load_existing_lock() -> dict[str, Any]:
    """Load the detailed lock used as the generated profile metadata source."""

    lock = load_lock()
    if lock.get("sourceCatalog") != SOURCE_CATALOG:
        raise AotCompatibilityError("The AOT lock has an unsupported source catalog.")
    return lock


def _profile_for_generation(
    *,
    backend: str,
    distribution: str,
    source_revision: str,
    introduced_in_wasmline_version: str,
) -> dict[str, Any]:
    """Create the current canonical profile descriptor for one new generation."""

    profile: dict[str, Any] = {
        "id": "",
        "artifactBackend": backend,
        "wasmtimeVersion": distribution.rsplit(".", 1)[0],
        "wasmtimeDistributionVersion": distribution,
        "wasmtimeSourceRevision": source_revision,
        "serializedArtifactFormatIdentity": CURRENT_SERIALIZED_ARTIFACT_FORMAT_IDENTITY,
        "compileProfileSchemaVersion": CURRENT_COMPILE_PROFILE_SCHEMA_VERSION,
        "engineConfigurationProfile": CURRENT_ENGINE_CONFIGURATION_PROFILES[backend],
        "introducedInWasmlineVersion": introduced_in_wasmline_version,
    }
    profile["id"] = compatibility_id(profile)
    return profile


def prepare_metadata_for_catalog(
    source: Mapping[str, Any],
    existing_lock: Mapping[str, Any],
    resolver: AotMetadataResolver | None,
) -> dict[str, Any]:
    """Append detailed metadata required by newly declared AOT generations."""

    profiles = [dict(item) for item in existing_lock.get("profiles", []) if isinstance(item, Mapping)]
    assets = [dict(item) for item in existing_lock.get("compilerAssets", []) if isinstance(item, Mapping)]
    bindings = [
        dict(item)
        for item in existing_lock.get("profileCompilerBindings", [])
        if isinstance(item, Mapping)
    ]
    profiles_by_id = {
        str(profile.get("id")): profile
        for profile in profiles
        if isinstance(profile.get("id"), str)
    }
    asset_digests = {
        str(asset.get("archiveSha256"))
        for asset in assets
        if isinstance(asset.get("archiveSha256"), str)
    }
    binding_keys = {
        (str(binding.get("profileId")), str(binding.get("buildHost")))
        for binding in bindings
    }
    existing_catalog = existing_lock.get("releaseCatalog")
    existing_ranges = (
        existing_catalog.get("ranges", [])
        if isinstance(existing_catalog, Mapping)
        else []
    )
    previous_profile_ids: dict[str, str] | None = None

    for index, item in enumerate(source["ranges"]):
        if index < len(existing_ranges):
            existing_range = existing_ranges[index]
            if not isinstance(existing_range, Mapping):
                raise AotCompatibilityError("The existing AOT lock contains an invalid release range.")
            existing_profile_ids = existing_range.get("profileIdsByBackend")
            if not isinstance(existing_profile_ids, Mapping) or set(existing_profile_ids) != set(BACKENDS):
                raise AotCompatibilityError("The existing AOT lock has incomplete generation bindings.")
            previous_profile_ids = {
                backend: str(existing_profile_ids[backend])
                for backend in BACKENDS
            }
            continue

        distribution = str(item["wasmtimeDistributionVersion"])
        distribution_profiles = [
            profile
            for profile in profiles
            if profile.get("wasmtimeDistributionVersion") == distribution
        ]
        distribution_assets = [
            asset
            for asset in assets
            if _asset_distribution_and_host(asset)[0] == distribution
        ]
        revisions = {
            str(profile.get("wasmtimeSourceRevision"))
            for profile in distribution_profiles
            if isinstance(profile.get("wasmtimeSourceRevision"), str)
        }
        if not distribution_assets and not revisions:
            if resolver is None:
                raise AotCompatibilityError(
                    f"AOT generation {item['aotGeneration']} requires metadata for Wasmtime "
                    f"distribution {distribution}; run './scripts/wasmline aot sync'."
                )
            Console(sys.stdout).info(
                "AOT metadata",
                f"Resolving Wasmtime fork distribution {distribution}.",
            )
            resolved = resolver.resolve(distribution)
            revisions = {resolved.source_revision}
            for asset in resolved.compiler_assets:
                digest = str(asset.get("archiveSha256"))
                if digest not in asset_digests:
                    assets.append(dict(asset))
                    asset_digests.add(digest)
            distribution_assets = [
                asset
                for asset in assets
                if _asset_distribution_and_host(asset)[0] == distribution
            ]
        elif not distribution_assets or not revisions:
            raise AotCompatibilityError(
                f"The existing AOT lock has partial metadata for Wasmtime distribution {distribution}."
            )
        if len(revisions) != 1:
            raise AotCompatibilityError(
                f"Wasmtime distribution {distribution} must resolve to one source revision."
            )
        source_revision = next(iter(revisions))

        current_profile_ids: dict[str, str] = {}
        changed_backends = set(item["changedBackends"])
        for backend in BACKENDS:
            if previous_profile_ids is not None and backend not in changed_backends:
                profile_id = previous_profile_ids[backend]
                profile = profiles_by_id.get(profile_id)
                if profile is None:
                    raise AotCompatibilityError(
                        f"AOT generation {item['aotGeneration']} cannot reuse unknown {backend} profile {profile_id}."
                    )
                if profile.get("wasmtimeDistributionVersion") != distribution:
                    raise AotCompatibilityError(
                        f"AOT generation {item['aotGeneration']} changes distribution but does not mark {backend} as changed."
                    )
                if (
                    profile.get("serializedArtifactFormatIdentity")
                    != CURRENT_SERIALIZED_ARTIFACT_FORMAT_IDENTITY
                    or profile.get("compileProfileSchemaVersion")
                    != CURRENT_COMPILE_PROFILE_SCHEMA_VERSION
                    or profile.get("engineConfigurationProfile")
                    != CURRENT_ENGINE_CONFIGURATION_PROFILES[backend]
                ):
                    raise AotCompatibilityError(
                        f"AOT generation {item['aotGeneration']} changes the {backend} compile contract "
                        "but does not mark that backend as changed."
                    )
            else:
                profile = _profile_for_generation(
                    backend=backend,
                    distribution=distribution,
                    source_revision=source_revision,
                    introduced_in_wasmline_version=str(item["fromWasmlineVersion"]),
                )
                profile_id = str(profile["id"])
                existing_profile = profiles_by_id.get(profile_id)
                if existing_profile is None:
                    profiles.append(profile)
                    profiles_by_id[profile_id] = profile
                else:
                    profile = existing_profile
            current_profile_ids[backend] = profile_id
            for asset in distribution_assets:
                _, build_host = _asset_distribution_and_host(asset)
                binding_key = (profile_id, build_host)
                if binding_key not in binding_keys:
                    bindings.append(
                        {
                            "profileId": profile_id,
                            "artifactBackend": backend,
                            "buildHost": build_host,
                            "compilerArchiveSha256": asset["archiveSha256"],
                        }
                    )
                    binding_keys.add(binding_key)
        previous_profile_ids = current_profile_ids

    metadata = dict(existing_lock)
    metadata["profiles"] = profiles
    metadata["profileCompilerBindings"] = bindings
    metadata["compilerAssets"] = assets
    return metadata


def _profile_bindings_for_catalog(
    source: Mapping[str, Any],
    existing_lock: Mapping[str, Any],
) -> tuple[list[Mapping[str, Any]], dict[int, dict[str, str]]]:
    """Resolve each public generation to exactly one detailed profile per backend."""

    profiles = [item for item in existing_lock.get("profiles", []) if isinstance(item, Mapping)]
    by_generation: dict[int, dict[str, str]] = {}
    selected_ids: set[str] = set()
    for item in source["ranges"]:
        generation = int(item["aotGeneration"])
        start_version = str(item["fromWasmlineVersion"])
        distribution = item["wasmtimeDistributionVersion"]
        bindings: dict[str, str] = {}
        for backend in BACKENDS:
            candidates = [
                profile
                for profile in profiles
                if profile.get("artifactBackend") == backend
                and profile.get("wasmtimeDistributionVersion") == distribution
            ]
            eligible = [
                profile
                for profile in candidates
                if isinstance(profile.get("introducedInWasmlineVersion"), str)
                and _semantic_version_key(profile["introducedInWasmlineVersion"]) <= _semantic_version_key(start_version)
            ]
            if not eligible:
                raise AotCompatibilityError(
                    f"AOT generation {generation} requires one {backend} profile for "
                    f"Wasmtime distribution {distribution} introduced by {start_version}."
                )
            latest_introduced = max(
                _semantic_version_key(str(profile["introducedInWasmlineVersion"]))
                for profile in eligible
            )
            matches = [
                profile
                for profile in eligible
                if _semantic_version_key(str(profile["introducedInWasmlineVersion"])) == latest_introduced
            ]
            if len(matches) != 1:
                raise AotCompatibilityError(
                    f"AOT generation {generation} requires exactly one {backend} profile for "
                    f"Wasmtime distribution {distribution} at {start_version}; found {len(matches)}."
                )
            profile = matches[0]
            profile_id = profile.get("id")
            if not isinstance(profile_id, str):
                raise AotCompatibilityError("AOT profile metadata contains an invalid ID.")
            bindings[backend] = profile_id
            selected_ids.add(profile_id)
        by_generation[generation] = bindings
    selected_profiles = [profile for profile in profiles if profile.get("id") in selected_ids]
    return selected_profiles, by_generation


def _asset_records_for_profiles(
    selected_profiles: list[Mapping[str, Any]],
    existing_lock: Mapping[str, Any],
) -> tuple[list[Mapping[str, Any]], list[Mapping[str, Any]]]:
    """Keep only compiler assets and bindings reachable from selected profiles."""

    selected_ids = {str(profile["id"]) for profile in selected_profiles}
    bindings = [
        item
        for item in existing_lock.get("profileCompilerBindings", [])
        if isinstance(item, Mapping) and item.get("profileId") in selected_ids
    ]
    asset_digests = {item.get("compilerArchiveSha256") for item in bindings}
    assets = [
        item
        for item in existing_lock.get("compilerAssets", [])
        if isinstance(item, Mapping) and item.get("archiveSha256") in asset_digests
    ]
    if len(assets) != len(asset_digests):
        missing = sorted(str(value) for value in asset_digests - {item.get("archiveSha256") for item in assets})
        raise AotCompatibilityError("AOT compiler assets are missing from the lock: " + ", ".join(missing))
    return (
        sorted(bindings, key=lambda item: (str(item.get("profileId")), str(item.get("buildHost")))),
        sorted(assets, key=lambda item: (str(item.get("archiveSha256")), str(item.get("buildHost")))),
    )


def render_lock(
    catalog_or_manifest: Mapping[str, Any],
    versions: Mapping[str, str],
    existing_lock: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    """Render the detailed generated lock from the public catalog and metadata lock."""

    source = _source_from(catalog_or_manifest)
    validate_source(source, versions)
    existing = dict(existing_lock) if existing_lock is not None else _load_existing_lock()
    selected_profiles, profile_ids_by_generation = _profile_bindings_for_catalog(source, existing)
    bindings, assets = _asset_records_for_profiles(selected_profiles, existing)
    release_ranges: list[dict[str, Any]] = []
    previous: dict[str, str] | None = None
    for item in source["ranges"]:
        generation = int(item["aotGeneration"])
        profile_ids = profile_ids_by_generation[generation]
        changed = item["changedBackends"]
        if previous is not None:
            actual = [backend for backend in BACKENDS if profile_ids[backend] != previous[backend]]
            if set(actual) != set(changed):
                raise AotCompatibilityError(
                    f"AOT generation {generation} changedBackends does not match detailed profile metadata."
                )
        release_ranges.append(
            {
                "fromWasmlineVersion": item["fromWasmlineVersion"],
                "aotGeneration": generation,
                "wasmtimeDistributionVersion": item["wasmtimeDistributionVersion"],
                "changedBackends": list(changed),
                "profileIdsByBackend": dict(sorted(profile_ids.items())),
            }
        )
        previous = profile_ids
    release_catalog = {
        "schemaVersion": 1,
        "currentWasmlineVersion": source["currentWasmlineVersion"],
        "minimumSupportedWasmlineVersion": source["minimumSupportedWasmlineVersion"],
        "ranges": release_ranges,
    }
    return {
        "schemaVersion": 1,
        "generatedBy": GENERATOR,
        "sourceCatalog": SOURCE_CATALOG,
        "currentDefaultProfileIdsByBackend": dict(sorted(release_ranges[-1]["profileIdsByBackend"].items())),
        "releaseCatalog": release_catalog,
        "profiles": sorted(selected_profiles, key=lambda item: str(item.get("id"))),
        "profileCompilerBindings": bindings,
        "compilerAssets": assets,
    }


def render_lock_text(
    catalog_or_manifest: Mapping[str, Any],
    versions: Mapping[str, str],
    existing_lock: Mapping[str, Any] | None = None,
) -> str:
    """Render deterministic JSON for the generated AOT lock."""

    return json.dumps(
        render_lock(catalog_or_manifest, versions, existing_lock),
        ensure_ascii=True,
        indent=2,
    ) + "\n"


def _required_string(record: Mapping[str, Any], key: str, context: str) -> str:
    value = record.get(key)
    if not isinstance(value, str) or not value:
        raise AotCompatibilityError(f"{context} has an invalid {key}.")
    return value


def _validate_profile_record(
    profile: Mapping[str, Any],
    current_wasmline_version: str,
) -> tuple[str, str, str]:
    """Validate one detailed profile and return its ID, backend, and distribution."""

    context = f"AOT profile {profile.get('id', '<unknown>')!r}"
    expected_keys = {
        "id",
        "artifactBackend",
        "wasmtimeVersion",
        "wasmtimeDistributionVersion",
        "wasmtimeSourceRevision",
        "serializedArtifactFormatIdentity",
        "compileProfileSchemaVersion",
        "engineConfigurationProfile",
        "introducedInWasmlineVersion",
    }
    if set(profile) != expected_keys:
        missing = expected_keys - set(profile)
        unknown = set(profile) - expected_keys
        details = []
        if missing:
            details.append("missing " + ", ".join(sorted(missing)))
        if unknown:
            details.append("unsupported " + ", ".join(sorted(unknown)))
        raise AotCompatibilityError(f"{context} fields are invalid ({'; '.join(details)}).")

    profile_id = _required_string(profile, "id", "AOT profile")
    if not PROFILE_ID_PATTERN.fullmatch(profile_id):
        raise AotCompatibilityError(f"{context} has an invalid ID.")
    backend = _required_string(profile, "artifactBackend", context)
    if backend not in BACKENDS:
        raise AotCompatibilityError(f"{context} has an unknown backend {backend!r}.")
    wasmtime_version = _required_string(profile, "wasmtimeVersion", context)
    if not SEMANTIC_VERSION_PATTERN.fullmatch(wasmtime_version):
        raise AotCompatibilityError(f"{context} has an invalid Wasmtime version.")
    distribution = _required_string(profile, "wasmtimeDistributionVersion", context)
    if not RELEASE_VERSION_PATTERN.fullmatch(distribution):
        raise AotCompatibilityError(f"{context} has an invalid Wasmtime distribution version.")
    if distribution.rsplit(".", 1)[0] != wasmtime_version:
        raise AotCompatibilityError(f"{context} has inconsistent Wasmtime versions.")
    revision = _required_string(profile, "wasmtimeSourceRevision", context)
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise AotCompatibilityError(f"{context} has an invalid source revision.")
    identity = _required_string(profile, "serializedArtifactFormatIdentity", context)
    if len(identity) > 256:
        raise AotCompatibilityError(f"{context} serialized artifact identity is too long.")
    schema = profile.get("compileProfileSchemaVersion")
    if isinstance(schema, bool) or not isinstance(schema, int) or schema <= 0:
        raise AotCompatibilityError(f"{context} has an invalid compile profile schema version.")
    engine_profile = _required_string(profile, "engineConfigurationProfile", context)
    if len(engine_profile) > 4096:
        raise AotCompatibilityError(f"{context} engine configuration profile is too long.")
    introduced = _required_string(profile, "introducedInWasmlineVersion", context)
    if not SEMANTIC_VERSION_PATTERN.fullmatch(introduced):
        raise AotCompatibilityError(f"{context} has an invalid introduced Wasmline version.")
    if _semantic_version_key(introduced) > _semantic_version_key(current_wasmline_version):
        raise AotCompatibilityError(f"{context} is introduced after the current Wasmline release.")
    if profile_id != compatibility_id(profile):
        raise AotCompatibilityError(f"{context} does not match its canonical descriptor.")
    return profile_id, backend, distribution


def _asset_distribution_and_host(asset: Mapping[str, Any]) -> tuple[str, str]:
    """Infer the fork distribution and host encoded by one asset identity."""

    asset_id = _required_string(asset, "assetId", "AOT compiler asset")
    match = re.fullmatch(
        r"wasmtime-v(?P<distribution>[0-9]+\.[0-9]+\.[0-9]+\.[1-9][0-9]*)-(?P<host>[A-Za-z0-9][A-Za-z0-9._-]*)",
        asset_id,
    )
    if match is None:
        raise AotCompatibilityError(f"AOT compiler asset {asset_id!r} has an invalid assetId.")
    return match.group("distribution"), match.group("host")


def _validate_asset_record(asset: Mapping[str, Any]) -> tuple[str, str, str]:
    """Validate one compiler archive and return its digest, host, and distribution."""

    context = f"AOT compiler asset {asset.get('archiveSha256', '<unknown>')!r}"
    expected_keys = {
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
    }
    if set(asset) != expected_keys:
        missing = expected_keys - set(asset)
        unknown = set(asset) - expected_keys
        details = []
        if missing:
            details.append("missing " + ", ".join(sorted(missing)))
        if unknown:
            details.append("unsupported " + ", ".join(sorted(unknown)))
        raise AotCompatibilityError(f"{context} fields are invalid ({'; '.join(details)}).")

    build_host = _required_string(asset, "buildHost", context)
    distribution, encoded_host = _asset_distribution_and_host(asset)
    if encoded_host != build_host:
        raise AotCompatibilityError(f"{context} assetId host does not match buildHost.")
    if asset.get("distribution") != COMPILER_DISTRIBUTION:
        raise AotCompatibilityError("AOT compiler assets must use the FULL Wasmtime distribution.")
    archive_format = asset.get("archiveFormat")
    if archive_format not in {"TAR_GZ", "ZIP"}:
        raise AotCompatibilityError(f"{context} has an unsupported archive format.")
    asset_id = str(asset["assetId"])
    archive_name = _required_string(asset, "archiveName", context)
    extension = ".tar.gz" if archive_format == "TAR_GZ" else ".zip"
    if archive_name != asset_id + extension:
        raise AotCompatibilityError(f"{context} archiveName does not match assetId and archiveFormat.")
    urls = asset.get("downloadUrls")
    if not isinstance(urls, list) or not urls or len(set(urls)) != len(urls):
        raise AotCompatibilityError(f"{context} has invalid downloadUrls.")
    if any(not isinstance(url, str) or not url.startswith("https://") or any(char.isspace() for char in url) for url in urls):
        raise AotCompatibilityError(f"{context} downloadUrls must contain unique HTTPS URLs.")
    archive_digest = _required_string(asset, "archiveSha256", context)
    if not DIGEST_PATTERN.fullmatch(archive_digest):
        raise AotCompatibilityError(f"{context} has an invalid archiveSha256.")
    archive_size = asset.get("archiveSize")
    if isinstance(archive_size, bool) or not isinstance(archive_size, int) or archive_size <= 0:
        raise AotCompatibilityError(f"{context} has an invalid archiveSize.")
    executable_path = _required_string(asset, "executableRelativePath", context)
    path = Path(executable_path)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts) or path.parts[0] != asset_id:
        raise AotCompatibilityError(f"{context} has an unsafe executableRelativePath.")
    expected_executable = "wasmtime.exe" if build_host.endswith("windows") else "wasmtime"
    if path.name != expected_executable:
        raise AotCompatibilityError(f"{context} executableRelativePath must end in {expected_executable}.")
    executable_digest = _required_string(asset, "executableSha256", context)
    if not DIGEST_PATTERN.fullmatch(executable_digest):
        raise AotCompatibilityError(f"{context} has an invalid executableSha256.")
    return archive_digest, build_host, distribution


def validate_lock_against_source(
    lock: Mapping[str, Any],
    source: Mapping[str, Any],
    versions: Mapping[str, str],
) -> None:
    """Validate that a generated lock contains exactly the public catalog generations."""

    validate_source(source, versions)
    expected_lock_keys = {
        "schemaVersion",
        "generatedBy",
        "sourceCatalog",
        "currentDefaultProfileIdsByBackend",
        "releaseCatalog",
        "profiles",
        "profileCompilerBindings",
        "compilerAssets",
    }
    if set(lock) != expected_lock_keys:
        raise AotCompatibilityError("The generated AOT lock has missing or unsupported fields.")
    if lock.get("schemaVersion") != 1 or lock.get("generatedBy") != GENERATOR or lock.get("sourceCatalog") != SOURCE_CATALOG:
        raise AotCompatibilityError("The generated AOT lock has an invalid schema or source catalog.")
    release_catalog = lock.get("releaseCatalog")
    if not isinstance(release_catalog, Mapping):
        raise AotCompatibilityError("The generated AOT lock is missing releaseCatalog.")
    public = {
        key: release_catalog.get(key)
        for key in ("schemaVersion", "currentWasmlineVersion", "minimumSupportedWasmlineVersion")
    }
    expected_public = {
        key: source.get(key)
        for key in ("schemaVersion", "currentWasmlineVersion", "minimumSupportedWasmlineVersion")
    }
    if public != expected_public:
        raise AotCompatibilityError("The generated AOT lock does not match aot-compatibility.json.")
    lock_ranges = release_catalog.get("ranges")
    if not isinstance(lock_ranges, list) or len(lock_ranges) != len(source["ranges"]):
        raise AotCompatibilityError("The generated AOT lock has a different generation count.")
    if set(release_catalog) != {
        "schemaVersion",
        "currentWasmlineVersion",
        "minimumSupportedWasmlineVersion",
        "ranges",
    }:
        raise AotCompatibilityError("The generated AOT release catalog has unsupported fields.")
    profiles_value = lock.get("profiles")
    if not isinstance(profiles_value, list) or not profiles_value:
        raise AotCompatibilityError("The generated AOT lock must contain profiles.")
    profiles = [item for item in profiles_value if isinstance(item, Mapping)]
    if len(profiles) != len(profiles_value):
        raise AotCompatibilityError("AOT profile entries must be objects.")
    profile_ids: set[str] = set()
    profile_by_id: dict[str, Mapping[str, Any]] = {}
    profile_distribution_by_id: dict[str, str] = {}
    for profile in profiles:
        profile_id, _, distribution = _validate_profile_record(
            profile,
            str(source["currentWasmlineVersion"]),
        )
        if profile_id in profile_ids:
            raise AotCompatibilityError(f"Duplicate AOT compatibility profile ID: {profile_id}.")
        profile_ids.add(profile_id)
        profile_by_id[profile_id] = profile
        profile_distribution_by_id[profile_id] = distribution
    if [str(item["id"]) for item in profiles] != sorted(profile_ids):
        raise AotCompatibilityError("AOT profiles must be sorted by profile ID.")

    defaults = lock.get("currentDefaultProfileIdsByBackend")
    if not isinstance(defaults, Mapping) or set(defaults) != set(BACKENDS):
        raise AotCompatibilityError("Current AOT defaults must bind every backend exactly once.")
    if any(not isinstance(value, str) or value not in profile_ids for value in defaults.values()):
        raise AotCompatibilityError("Current AOT defaults reference an unknown profile.")

    referenced_profile_ids: set[str] = set()
    previous_profile_ids: dict[str, str] | None = None
    for source_range, lock_range in zip(source["ranges"], lock_ranges, strict=True):
        if not isinstance(lock_range, Mapping):
            raise AotCompatibilityError("The generated AOT lock contains an invalid range.")
        if set(lock_range) != {
            "fromWasmlineVersion",
            "aotGeneration",
            "wasmtimeDistributionVersion",
            "changedBackends",
            "profileIdsByBackend",
        }:
            raise AotCompatibilityError("The generated AOT range has missing or unsupported fields.")
        for key in ("fromWasmlineVersion", "aotGeneration", "wasmtimeDistributionVersion", "changedBackends"):
            if lock_range.get(key) != source_range.get(key):
                raise AotCompatibilityError(
                    f"The generated AOT lock differs from the public catalog at {key}."
                )
        profile_ids_by_backend = lock_range.get("profileIdsByBackend")
        if not isinstance(profile_ids_by_backend, Mapping) or set(profile_ids_by_backend) != set(BACKENDS):
            raise AotCompatibilityError("Each generated AOT range must bind CRANELIFT and PULLEY profiles.")
        if any(not isinstance(profile_id, str) or profile_id not in profile_ids for profile_id in profile_ids_by_backend.values()):
            raise AotCompatibilityError("The generated AOT range references an unknown profile.")
        start = str(source_range["fromWasmlineVersion"])
        distribution = str(source_range["wasmtimeDistributionVersion"])
        for backend in BACKENDS:
            profile_id = str(profile_ids_by_backend[backend])
            profile = profile_by_id[profile_id]
            if profile.get("artifactBackend") != backend:
                raise AotCompatibilityError(
                    f"AOT generation {source_range['aotGeneration']} binds {backend} to the wrong profile backend."
                )
            if profile_distribution_by_id[profile_id] != distribution:
                raise AotCompatibilityError(
                    f"AOT generation {source_range['aotGeneration']} profile distribution does not match its range."
                )
            if _semantic_version_key(str(profile["introducedInWasmlineVersion"])) > _semantic_version_key(start):
                raise AotCompatibilityError(
                    f"AOT generation {source_range['aotGeneration']} uses a profile introduced after its range start."
                )
            referenced_profile_ids.add(profile_id)
        current_profile_ids = {backend: str(profile_ids_by_backend[backend]) for backend in BACKENDS}
        actual_changes = [
            backend
            for backend in BACKENDS
            if previous_profile_ids is None or current_profile_ids[backend] != previous_profile_ids[backend]
        ]
        if actual_changes != list(source_range["changedBackends"]):
            raise AotCompatibilityError(
                f"AOT generation {source_range['aotGeneration']} changedBackends does not match profile bindings."
            )
        previous_profile_ids = current_profile_ids
    if referenced_profile_ids != profile_ids:
        unused = sorted(profile_ids - referenced_profile_ids)
        raise AotCompatibilityError("AOT profiles are not bound to a public generation: " + ", ".join(unused))
    if defaults != lock_ranges[-1].get("profileIdsByBackend"):
        raise AotCompatibilityError("Current AOT defaults do not match the final generation.")

    assets_value = lock.get("compilerAssets")
    if not isinstance(assets_value, list) or not assets_value:
        raise AotCompatibilityError("The generated AOT lock must contain compiler assets.")
    assets = [item for item in assets_value if isinstance(item, Mapping)]
    if len(assets) != len(assets_value):
        raise AotCompatibilityError("AOT compiler asset entries must be objects.")
    asset_by_digest: dict[str, Mapping[str, Any]] = {}
    asset_hosts_by_distribution: dict[str, set[str]] = {}
    for asset in assets:
        digest, host, distribution = _validate_asset_record(asset)
        if digest in asset_by_digest:
            raise AotCompatibilityError(f"Duplicate compiler archive digest: {digest}.")
        asset_by_digest[digest] = asset
        asset_hosts_by_distribution.setdefault(distribution, set()).add(host)
    if [str(item["archiveSha256"]) for item in assets] != sorted(asset_by_digest):
        raise AotCompatibilityError("AOT compiler assets must be sorted by archive digest.")

    bindings_value = lock.get("profileCompilerBindings")
    if not isinstance(bindings_value, list):
        raise AotCompatibilityError("The generated AOT lock must contain compiler bindings.")
    bindings = [item for item in bindings_value if isinstance(item, Mapping)]
    if len(bindings) != len(bindings_value):
        raise AotCompatibilityError("AOT compiler binding entries must be objects.")
    binding_keys: set[tuple[str, str]] = set()
    referenced_asset_digests: set[str] = set()
    hosts_by_profile: dict[str, set[str]] = {profile_id: set() for profile_id in profile_ids}
    for binding in bindings:
        expected_binding_keys = {"profileId", "artifactBackend", "buildHost", "compilerArchiveSha256"}
        if set(binding) != expected_binding_keys:
            raise AotCompatibilityError("AOT compiler binding has missing or unsupported fields.")
        profile_id = binding.get("profileId")
        backend = binding.get("artifactBackend")
        host = binding.get("buildHost")
        digest = binding.get("compilerArchiveSha256")
        if not isinstance(profile_id, str) or profile_id not in profile_ids:
            raise AotCompatibilityError("AOT compiler binding references an unknown profile.")
        if backend not in BACKENDS or profile_by_id[profile_id].get("artifactBackend") != backend:
            raise AotCompatibilityError("AOT compiler binding backend does not match its profile.")
        if not isinstance(host, str) or not host:
            raise AotCompatibilityError("AOT compiler binding has an invalid build host.")
        if not isinstance(digest, str) or digest not in asset_by_digest:
            raise AotCompatibilityError("AOT compiler binding references an unknown archive.")
        asset = asset_by_digest[digest]
        asset_distribution, asset_host = _asset_distribution_and_host(asset)
        if asset_host != host or asset_distribution != profile_distribution_by_id[profile_id]:
            raise AotCompatibilityError("AOT compiler binding host or distribution does not match its profile.")
        key = (profile_id, host)
        if key in binding_keys:
            raise AotCompatibilityError("AOT compiler bindings contain a duplicate profile/build-host pair.")
        binding_keys.add(key)
        hosts_by_profile[profile_id].add(host)
        referenced_asset_digests.add(digest)
    for profile_id, profile in profile_by_id.items():
        expected_hosts = asset_hosts_by_distribution[profile_distribution_by_id[profile_id]]
        if hosts_by_profile[profile_id] != expected_hosts:
            raise AotCompatibilityError(
                f"AOT profile {profile_id} does not provide exactly one compiler binding for every build host."
            )
    if referenced_asset_digests != set(asset_by_digest):
        raise AotCompatibilityError("AOT compiler assets must be referenced by a profile binding.")
    if [
        (str(item["profileId"]), str(item["buildHost"]))
        for item in bindings
    ] != sorted((str(item["profileId"]), str(item["buildHost"])) for item in bindings):
        raise AotCompatibilityError("AOT compiler bindings must be sorted by profile ID and build host.")


def validate_append_only(previous_lock: Mapping[str, Any], generated_lock: Mapping[str, Any]) -> None:
    """Reject mutation of records when a release lock is intentionally append-only."""

    previous_profiles = {
        item.get("id"): item
        for item in previous_lock.get("profiles", [])
        if isinstance(item, Mapping) and item.get("id")
    }
    generated_profiles = {
        item.get("id"): item
        for item in generated_lock.get("profiles", [])
        if isinstance(item, Mapping) and item.get("id")
    }
    for profile_id, previous in previous_profiles.items():
        current = generated_profiles.get(profile_id)
        if current is None:
            raise AotCompatibilityError(f"Published AOT compatibility profile {profile_id} cannot be removed.")
        if current != previous:
            raise AotCompatibilityError(f"Published AOT compatibility profile {profile_id} cannot be modified.")

    previous_assets = {
        item.get("archiveSha256"): item
        for item in previous_lock.get("compilerAssets", [])
        if isinstance(item, Mapping) and item.get("archiveSha256")
    }
    generated_assets = {
        item.get("archiveSha256"): item
        for item in generated_lock.get("compilerAssets", [])
        if isinstance(item, Mapping) and item.get("archiveSha256")
    }
    for digest, previous in previous_assets.items():
        current = generated_assets.get(digest)
        if current is None:
            raise AotCompatibilityError(f"Published AOT compiler asset {digest} cannot be removed.")
        previous_without_urls = {key: value for key, value in previous.items() if key != "downloadUrls"}
        current_without_urls = {key: value for key, value in current.items() if key != "downloadUrls"}
        if current_without_urls != previous_without_urls:
            raise AotCompatibilityError(f"Published AOT compiler asset {digest} cannot be modified.")
        if not set(previous.get("downloadUrls", [])) <= set(current.get("downloadUrls", [])):
            raise AotCompatibilityError(f"Published AOT compiler asset {digest} cannot remove download URLs.")

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
    if previous_bindings - generated_bindings:
        raise AotCompatibilityError("Published AOT profile compiler bindings cannot be removed or modified.")

    previous_catalog = previous_lock.get("releaseCatalog")
    generated_catalog = generated_lock.get("releaseCatalog")
    if isinstance(previous_catalog, Mapping):
        if not isinstance(generated_catalog, Mapping):
            raise AotCompatibilityError("Published AOT release catalog cannot be removed.")
        previous_current = str(previous_catalog.get("currentWasmlineVersion", ""))
        generated_current = str(generated_catalog.get("currentWasmlineVersion", ""))
        if _semantic_version_key(generated_current) < _semantic_version_key(previous_current):
            raise AotCompatibilityError("The current Wasmline version cannot move backward.")
        previous_minimum = str(previous_catalog.get("minimumSupportedWasmlineVersion", ""))
        generated_minimum = str(generated_catalog.get("minimumSupportedWasmlineVersion", ""))
        if _semantic_version_key(generated_minimum) < _semantic_version_key(previous_minimum):
            raise AotCompatibilityError("The minimum supported Wasmline version cannot move backward.")
        previous_ranges = previous_catalog.get("ranges")
        generated_ranges = generated_catalog.get("ranges")
        if not isinstance(previous_ranges, list) or not isinstance(generated_ranges, list):
            raise AotCompatibilityError("Invalid generated AOT release catalog history.")
        if generated_ranges[: len(previous_ranges)] != previous_ranges:
            raise AotCompatibilityError("Published AOT release ranges and generation bindings cannot be modified.")


def render_native_build_identity(
    catalog_or_manifest: Mapping[str, Any],
    versions: Mapping[str, str],
    lock: Mapping[str, Any] | None = None,
) -> str:
    """Render native release and current backend profile constants."""

    source = _source_from(catalog_or_manifest)
    validate_source(source, versions)
    detail = lock or _load_existing_lock()
    defaults = detail.get("currentDefaultProfileIdsByBackend")
    if not isinstance(defaults, Mapping) or set(defaults) != set(BACKENDS):
        raise AotCompatibilityError("The AOT lock does not define current backend profile IDs.")
    return f'''/**
 * Defines native engine identity values generated from the Wasmline AOT catalog.
 *
 * Date: 2026-08-29
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
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
internal object WasmlineReleaseIdentity {{
    const val RELEASE_VERSION: String = "{versions["wasmline_version"]}"
    const val NATIVE_BRIDGE_ABI_VERSION: Int = {NATIVE_BRIDGE_ABI_VERSION}
}}
'''


def load_lock() -> dict[str, Any]:
    """Load the checked-in generated AOT compatibility lock."""

    try:
        value = json.loads(AOT_LOCK_PATH.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError) as error:
        raise AotCompatibilityError(f"Invalid AOT compatibility lock: {error}") from error
    if not isinstance(value, dict):
        raise AotCompatibilityError("AOT compatibility lock must be an object.")
    return value


def _read_versions() -> dict[str, str]:
    """Read only the scalar version map without invoking version synchronization."""

    manifest_path = MANIFEST_PATH
    try:
        data = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError) as error:
        raise AotCompatibilityError(f"Invalid version manifest: {error}") from error
    versions = data.get("versions")
    if not isinstance(versions, Mapping):
        raise AotCompatibilityError("Version manifest must contain a versions object.")
    return {str(key): str(value) for key, value in versions.items()}


def _write_text_atomic(path: Path, content: str) -> None:
    """Write one generated text file with an atomic same-directory replacement."""

    path.parent.mkdir(parents=True, exist_ok=True)
    mode = stat.S_IMODE(path.stat().st_mode) if path.exists() else 0o644
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            dir=path.parent,
            prefix=f".{path.name}.",
            suffix=".tmp",
            delete=False,
        ) as handle:
            handle.write(content)
            temporary = Path(handle.name)
        temporary.chmod(mode)
        os.replace(temporary, path)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def sync_aot(
    *,
    check: bool = False,
    proxy: str | None = None,
    jobs: int | None = None,
) -> int:
    """Validate the public catalog and synchronize generated AOT resources."""

    console = Console(sys.stdout)
    versions = _read_versions()
    source = load_public_catalog()
    existing = load_lock()
    resolver = None if check else GitHubAotMetadataResolver(proxy=proxy, jobs=jobs)
    metadata = prepare_metadata_for_catalog(source, existing, resolver)
    generated_lock = render_lock(source, versions, metadata)
    validate_lock_against_source(generated_lock, source, versions)
    validate_append_only(existing, generated_lock)
    generated = {
        AOT_LOCK_PATH: json.dumps(generated_lock, ensure_ascii=True, indent=2) + "\n",
        PACKAGED_PUBLIC_CATALOG_PATH: render_public_catalog_text(source, versions),
        NATIVE_BUILD_IDENTITY_PATH: render_native_build_identity(source, versions, generated_lock),
    }
    changed: list[str] = []
    for path, content in generated.items():
        current = path.read_text(encoding="utf-8") if path.is_file() else ""
        if current != content:
            changed.append(path.relative_to(PROJECT_ROOT).as_posix())
            if not check:
                _write_text_atomic(path, content)
    if changed and check:
        for item in changed:
            console.error("AOT file", item)
        return 1
    if changed:
        for item in changed:
            console.ok("AOT file", item)
    else:
        console.ok(
            "AOT check" if check else "AOT sync",
            "Generated files are synchronized.",
        )
    return 0


def check_aot() -> int:
    """Run an offline validation of the public catalog and generated resources."""

    return sync_aot(check=True)
