"""Render and synchronize the generated AOT compatibility resources."""

from __future__ import annotations

import json
import sys
from typing import Any, Mapping

from ..terminal import Console
from ..paths import MANIFEST_PATH, PROJECT_ROOT
from ..support.text import write_text_atomic
from .catalog import (
    AOT_LOCK_PATH,
    KOTLIN_RELEASE_IDENTITY_PATH,
    NATIVE_BUILD_IDENTITY_PATH,
    PACKAGED_PUBLIC_CATALOG_PATH,
    AotCompatibilityError,
    load_public_catalog,
    render_public_catalog_text,
)
from .lock import (
    _asset_distribution_and_host,
    load_lock,
    render_lock,
    render_kotlin_release_identity,
    render_native_build_identity,
    validate_append_only,
    validate_lock_against_source,
)
from .metadata import AotMetadataResolver, GitHubAotMetadataResolver
from .profiles import (
    BACKENDS,
    CURRENT_COMPILE_PROFILE_SCHEMA_VERSION,
    CURRENT_ENGINE_CONFIGURATION_PROFILES,
    CURRENT_SERIALIZED_ARTIFACT_FORMAT_IDENTITY,
    profile_for_generation,
)


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
                profile = profile_for_generation(
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
        KOTLIN_RELEASE_IDENTITY_PATH: render_kotlin_release_identity(versions),
    }
    changed: list[str] = []
    for path, content in generated.items():
        current = path.read_text(encoding="utf-8") if path.is_file() else ""
        if current != content:
            changed.append(path.relative_to(PROJECT_ROOT).as_posix())
            if not check:
                write_text_atomic(path, content)
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
