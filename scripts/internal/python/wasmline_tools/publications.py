"""Verify the Maven artifacts consumed by the release workflow."""

from __future__ import annotations

import argparse
import hashlib
import json
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

from .manifest import load_versions


GROUP = "crow.wasmline"
MODULES = (
    "wasmline",
    "wasmline-loader",
    "wasmline-network-ktor",
    "wasmline-network-okhttp",
    "wasmline-kotlin-plugin",
    "wasmline-gradle-plugin",
    "wasmline-cli",
    "wasmline-engine-pulley",
    "wasmline-engine-cranelift",
    "wasmline-bom",
)
APPLE_TARGETS = {
    "wasmline": {"ios_arm64", "ios_simulator_arm64", "macos_arm64"},
    "wasmline-loader": {"ios_arm64", "ios_simulator_arm64", "macos_arm64"},
    "wasmline-network-ktor": {"ios_arm64", "ios_simulator_arm64", "macos_arm64"},
    "wasmline-engine-pulley": {"ios_arm64", "ios_simulator_arm64", "macos_arm64"},
    "wasmline-engine-cranelift": {"macos_arm64"},
}
JNI_PLATFORMS = {
    ("linux", "x86-64"): "jni/linux/x86_64/libwasmline.so",
    ("linux", "aarch64"): "jni/linux/aarch64/libwasmline.so",
    ("macos", "aarch64"): "jni/darwin/aarch64/libwasmline.dylib",
    ("macos", "x86-64"): "jni/darwin/x86_64/libwasmline.dylib",
    ("windows", "x86-64"): "jni/windows/x86_64/libwasmline.dll",
}


class PublicationError(RuntimeError):
    """A publication cannot be resolved or contains inconsistent artifacts."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise PublicationError(message)


def _archive_member(path: Path, member: str) -> None:
    with zipfile.ZipFile(path) as archive:
        _require(
            member in archive.namelist() and archive.getinfo(member).file_size > 0,
            f"{path.name}: missing native archive member {member}",
        )


def verify_publications(repository: Path, version: str, require_apple: bool = False) -> tuple[int, int]:
    """Check published POMs, metadata redirects, artifact digests, and engine libraries."""
    repository = repository.resolve()
    checked_modules: set[str] = set()
    checked_artifacts: set[Path] = set()
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}

    def coordinate_path(module: str, extension: str) -> Path:
        return repository / "crow/wasmline" / module / version / f"{module}-{version}.{extension}"

    def verify_pom(module: str) -> None:
        path = coordinate_path(module, "pom")
        _require(path.is_file(), f"Missing Maven POM: {module}:{version}")
        pom = ET.parse(path).getroot()
        for field, expected in (("groupId", GROUP), ("artifactId", module), ("version", version)):
            _require(
                pom.findtext(f"m:{field}", namespaces=namespace) == expected,
                f"{module}: unexpected POM {field}; expected {expected}",
            )
        for dependency in pom.findall("m:dependencies/m:dependency", namespace):
            if dependency.findtext("m:groupId", namespaces=namespace) == GROUP:
                _require(
                    dependency.findtext("m:version", namespaces=namespace) == version,
                    f"{module}: Wasmline POM dependency does not use version {version}",
                )

    def verify_module(module: str) -> None:
        if module in checked_modules:
            return
        verify_pom(module)
        path = coordinate_path(module, "module")
        _require(path.is_file(), f"Missing Gradle module metadata: {module}:{version}")
        metadata = json.loads(path.read_text(encoding="utf-8"))
        _require(metadata["component"]["version"] == version, f"{module}: stale module metadata version")
        checked_modules.add(module)
        native_platforms: set[tuple[str, str]] = set()
        for variant in metadata["variants"]:
            attributes = variant.get("attributes", {})
            available = variant.get("available-at")
            if available:
                _require(
                    available["group"] == GROUP and available["version"] == version,
                    f"{module}: target publication does not use {GROUP}:{version}",
                )
                expected = coordinate_path(available["module"], "module")
                _require(
                    (path.parent / available["url"]).resolve() == expected,
                    f"{module}: incorrect target publication URL",
                )
                verify_module(available["module"])
            for dependency in variant.get("dependencies", []) + variant.get("dependencyConstraints", []):
                if dependency["group"] != GROUP:
                    continue
                # Java test fixtures use a separate capability and depend on the main variant.
                fixture_capability = {"group": GROUP, "name": f"{module}-test-fixtures", "version": version}
                fixtures_use_main = (
                    variant.get("capabilities") == [fixture_capability]
                    and not dependency.get("requestedCapabilities")
                )
                _require(
                    dependency["module"] != module or fixtures_use_main,
                    f"{module}: self dependency in module metadata",
                )
                selected_versions = dependency.get("version", {})
                _require(
                    bool(selected_versions) and all(
                        selected_versions.get(key, version) == version
                        for key in ("requires", "strictly", "prefers")
                    ),
                    f"{module}: Wasmline dependency does not use version {version}",
                )
            for artifact in variant.get("files", []):
                artifact_path = (path.parent / artifact["url"]).resolve()
                _require(
                    artifact_path.is_relative_to(repository) and artifact_path.is_file(),
                    f"{module}: missing artifact {artifact['url']}",
                )
                if artifact_path not in checked_artifacts:
                    content = artifact_path.read_bytes()
                    _require(bool(content), f"{module}: empty artifact {artifact_path.name}")
                    if "size" in artifact:
                        _require(len(content) == artifact["size"], f"{module}: incorrect artifact size")
                    for algorithm in ("sha256", "sha512"):
                        if algorithm in artifact:
                            _require(
                                hashlib.new(algorithm, content).hexdigest() == artifact[algorithm],
                                f"{module}: artifact {algorithm} mismatch",
                            )
                    checked_artifacts.add(artifact_path)
                if module in ("wasmline-engine-pulley-jvm", "wasmline-engine-cranelift-jvm"):
                    platform = (
                        attributes.get("org.gradle.native.operatingSystem"),
                        attributes.get("org.gradle.native.architecture"),
                    )
                    if platform in JNI_PLATFORMS:
                        _archive_member(artifact_path, JNI_PLATFORMS[platform])
                        native_platforms.add(platform)
                if module in ("wasmline-engine-pulley-android", "wasmline-engine-cranelift-android"):
                    if artifact_path.suffix == ".aar":
                        abis = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64") if "pulley" in module else ("arm64-v8a", "x86_64")
                        for abi in abis:
                            _archive_member(artifact_path, f"jni/{abi}/libwasmline.so")
                if module.startswith("wasmline-engine-") and artifact_path.name.endswith("-cinterop-wasmline.klib"):
                    with zipfile.ZipFile(artifact_path) as archive:
                        _require(
                            any(
                                name.endswith("/included/libwasmline_native.a") and archive.getinfo(name).file_size > 0
                                for name in archive.namelist()
                            ),
                            f"{module}: KLIB does not embed libwasmline_native.a",
                        )
            if not available and module.startswith("wasmline-engine-") and attributes.get("org.jetbrains.kotlin.platform.type") == "native":
                if attributes.get("org.gradle.category") == "library":
                    _require(
                        any(item["url"].endswith("-cinterop-wasmline.klib") for item in variant.get("files", [])),
                        f"{module}: missing Native engine cinterop artifact",
                    )
        if module in ("wasmline-engine-pulley-jvm", "wasmline-engine-cranelift-jvm"):
            _require(native_platforms == JNI_PLATFORMS.keys(), f"{module}: missing JVM native variants")
        if require_apple and module in APPLE_TARGETS:
            actual = {
                item.get("attributes", {}).get("org.jetbrains.kotlin.native.target")
                for item in metadata["variants"] if "available-at" in item
            }
            _require(
                APPLE_TARGETS[module] <= actual,
                f"{module}: missing Apple Maven variants {sorted(APPLE_TARGETS[module] - actual)}",
            )

    for module in MODULES:
        verify_module(module)
    verify_pom("crow.wasmline.gradle.plugin")
    return len(checked_modules), len(checked_artifacts)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=Path.home() / ".m2/repository")
    parser.add_argument("--require-apple", action="store_true")
    args = parser.parse_args()
    version = load_versions()["wasmline_version"]
    try:
        modules, artifacts = verify_publications(args.repository, version, args.require_apple)
    except (PublicationError, OSError, ValueError, KeyError, ET.ParseError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Publication verification failed: {error}\n")
    print(f"Verified {modules} Maven modules and {artifacts} artifacts for Wasmline {version}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
