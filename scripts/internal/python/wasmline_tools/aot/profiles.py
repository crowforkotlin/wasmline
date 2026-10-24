"""Canonical AOT profile fields and their stable identifiers."""

from __future__ import annotations

import hashlib
from typing import Any, Mapping

from .catalog import AotCompatibilityError

BACKENDS = ("CRANELIFT", "PULLEY")
PROFILE_DOMAIN = b"wasmline.aot-compatibility-profile\0"
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


def profile_for_generation(
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
