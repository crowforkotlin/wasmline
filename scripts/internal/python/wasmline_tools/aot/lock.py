"""Generated AOT lock rendering, validation, and generated identity files."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any, Mapping

from .catalog import (
    AOT_LOCK_PATH,
    COMPILER_DISTRIBUTION,
    GENERATOR,
    NATIVE_BRIDGE_ABI_VERSION,
    DIGEST_PATTERN,
    PROFILE_ID_PATTERN,
    RELEASE_VERSION_PATTERN,
    SEMANTIC_VERSION_PATTERN,
    SOURCE_CATALOG,
    AotCompatibilityError,
    _semantic_version_key,
    _source_from,
    validate_source,
)
from .profiles import BACKENDS, compatibility_id


def _load_existing_lock() -> dict[str, Any]:
    """Load the detailed lock used as the generated profile metadata source."""

    lock = load_lock()
    if lock.get("sourceCatalog") != SOURCE_CATALOG:
        raise AotCompatibilityError("The AOT lock has an unsupported source catalog.")
    return lock


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
