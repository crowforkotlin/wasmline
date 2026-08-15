#!/usr/bin/env python3
"""
Synchronize repository version references from a single manifest.

Usage:
    python3 scripts/sync_version.py
    python3 scripts/sync_version.py --check
    python3 scripts/sync_version.py --set wasmtime_version=47.0.2

The plural script remains a compatibility entry point for existing automation.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parent
MANIFEST_PATH = SCRIPT_DIR / "versions.json"

VersionMap = dict[str, str]
Replacement = str | Callable[[VersionMap], str]

REQUIRED_KEYS = (
    "wasmline_version",
    "sample_plugin_version",
    "wasmtime_version",
    "kotlin_version",
    "kotlin_min_version",
    "agp_version",
    "zig_version",
    "jbr_version",
)

SEMANTIC_VERSION_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$")


@dataclass(frozen=True)
class Rule:
    pattern: str
    replacement: Replacement
    min_count: int = 1
    flags: int = re.MULTILINE


@dataclass(frozen=True)
class FileSpec:
    path: str
    rules: tuple[Rule, ...]


def badge_value(value: str) -> str:
    return value.replace("-", "--")


def wasmtime_code(value: str) -> int:
    numeric_version = value.split("-", 1)[0].split("+", 1)[0]
    major, minor, patch = (int(part) for part in numeric_version.split("."))
    return major * 100 + minor * 10 + patch


def render(value: Replacement, versions: VersionMap) -> str:
    return value(versions) if callable(value) else value


def load_manifest() -> dict[str, VersionMap]:
    try:
        data = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise SystemExit(f"Missing manifest: {MANIFEST_PATH}") from exc
    except json.JSONDecodeError as exc:
        raise SystemExit(f"Invalid JSON in {MANIFEST_PATH}: {exc}") from exc

    versions = data.get("versions")
    if not isinstance(versions, dict):
        raise SystemExit("Manifest must contain a 'versions' object.")

    normalized = {str(key): str(value) for key, value in versions.items()}
    missing = [key for key in REQUIRED_KEYS if not normalized.get(key)]
    if missing:
        raise SystemExit(f"Manifest is missing required keys: {', '.join(missing)}")
    validate_versions(normalized)
    return {"versions": normalized}


def write_manifest(data: dict[str, VersionMap]) -> None:
    temporary_path = MANIFEST_PATH.with_suffix(".json.tmp")
    temporary_path.write_text(
        json.dumps(data, ensure_ascii=True, indent=2) + "\n",
        encoding="utf-8",
    )
    temporary_path.replace(MANIFEST_PATH)


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
    if not SEMANTIC_VERSION_PATTERN.fullmatch(value):
        raise SystemExit(
            f"Invalid version value for '{key}': '{value}'. "
            "Expected MAJOR.MINOR.PATCH with an optional prerelease/build suffix."
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


def apply_rules(text: str, spec: FileSpec, versions: VersionMap) -> str:
    updated = text
    for rule in spec.rules:
        replacement = render(rule.replacement, versions)
        updated, count = re.subn(rule.pattern, replacement, updated, flags=rule.flags)
        if count < rule.min_count:
            raise SystemExit(
                f"Pattern not found enough times in {spec.path}: {rule.pattern}"
            )
    return updated


def badge_rules() -> tuple[Rule, ...]:
    return (
        Rule(
            r"https://img\.shields\.io/badge/Kotlin-.*?-7F52FF\?style=flat-square&logo=kotlin&logoColor=white",
            lambda v: (
                "https://img.shields.io/badge/Kotlin-"
                f"{badge_value(v['kotlin_version'])}-7F52FF"
                "?style=flat-square&logo=kotlin&logoColor=white"
            ),
            min_count=0,
        ),
        Rule(
            r"https://img\.shields\.io/badge/wasmtime-.*?-5C9BD6\?style=flat-square",
            lambda v: (
                "https://img.shields.io/badge/wasmtime-"
                f"{v['wasmtime_version']}-5C9BD6?style=flat-square"
            ),
            min_count=0,
        ),
        Rule(
            r"https://img\.shields\.io/badge/AGP-.*?-3DDC84\?style=flat-square&logo=android&logoColor=white",
            lambda v: (
                "https://img.shields.io/badge/AGP-"
                f"{v['agp_version']}-3DDC84?style=flat-square&logo=android&logoColor=white"
            ),
            min_count=0,
        ),
    )


def file_specs() -> tuple[FileSpec, ...]:
    readme_en = badge_rules() + (
        Rule(
            r"minimum required Kotlin version is \*\*[0-9A-Za-z.\-]+\*\*",
            lambda v: f"minimum required Kotlin version is **{v['kotlin_min_version']}**",
            min_count=0,
        ),
    )
    readme_zh = badge_rules() + (
        Rule(
            r"最低需要 \*\*Kotlin [0-9A-Za-z.\-]+\*\* 版本",
            lambda v: f"最低需要 **Kotlin {v['kotlin_min_version']}** 版本",
        ),
    )

    building_from_source_en = (
        Rule(
            r"\*\*[0-9]+\*\*(?= \(\[JBR [0-9]+\])",
            lambda v: f"**{v['jbr_version']}**",
        ),
        Rule(r"JBR [0-9]+", lambda v: f"JBR {v['jbr_version']}"),
        Rule(
            r"\*\*[0-9A-Za-z.\-]+\*\* minimum",
            lambda v: f"**{v['kotlin_min_version']}** minimum",
        ),
        Rule(r"AGP [0-9]+\.[0-9]+\.[0-9]+", lambda v: f"AGP {v['agp_version']}"),
        Rule(
            r"(\| Zig \| )\*\*[0-9.]+\*\*",
            lambda v: rf"\1**{v['zig_version']}**",
        ),
        Rule(r"Zig [0-9]+\.[0-9]+\.[0-9]+", lambda v: f"Zig {v['zig_version']}"),
    )
    building_from_source_zh = (
        Rule(
            r"\*\*[0-9]+\*\*(?=（Compose Desktop 必须使用 \[JBR [0-9]+\])",
            lambda v: f"**{v['jbr_version']}**",
        ),
        Rule(r"JBR [0-9]+", lambda v: f"JBR {v['jbr_version']}"),
        Rule(
            r"最低 \*\*[0-9A-Za-z.\-]+\*\*",
            lambda v: f"最低 **{v['kotlin_min_version']}**",
        ),
        Rule(r"AGP [0-9]+\.[0-9]+\.[0-9]+", lambda v: f"AGP {v['agp_version']}"),
        Rule(
            r"(\| Zig \| )\*\*[0-9.]+\*\*",
            lambda v: rf"\1**{v['zig_version']}**",
        ),
        Rule(r"Zig [0-9]+\.[0-9]+\.[0-9]+", lambda v: f"Zig {v['zig_version']}"),
    )

    installation_en = (
        Rule(r"JBR \*\*[0-9]+\*\*", lambda v: f"JBR **{v['jbr_version']}**"),
        Rule(
            r"Kotlin \*\*[0-9A-Za-z.\-]+\*\* or later",
            lambda v: f"Kotlin **{v['kotlin_min_version']}** or later",
        ),
        Rule(
            r"minimum required Kotlin version is \*\*[0-9A-Za-z.\-]+\*\*",
            lambda v: f"minimum required Kotlin version is **{v['kotlin_min_version']}**",
        ),
        Rule(
            r'(?m)(id\("crow\.wasmline"\) version ")[0-9A-Za-z.\-]+(")',
            lambda v: rf"\g<1>{v['wasmline_version']}\g<2>",
        ),
        Rule(
            r"(crow\.wasmline:[A-Za-z0-9_.-]+:)[0-9A-Za-z.\-]+",
            lambda v: rf"\g<1>{v['wasmline_version']}",
            min_count=0,
        ),
    )
    installation_zh = (
        Rule(r"JBR \*\*[0-9]+\*\*", lambda v: f"JBR **{v['jbr_version']}**"),
        Rule(
            r"Kotlin \*\*[0-9A-Za-z.\-]+\*\* 或更高版本",
            lambda v: f"Kotlin **{v['kotlin_min_version']}** 或更高版本",
        ),
        Rule(
            r"最低需要 \*\*Kotlin [0-9A-Za-z.\-]+\*\* 版本",
            lambda v: f"最低需要 **Kotlin {v['kotlin_min_version']}** 版本",
        ),
        Rule(
            r'(?m)(id\("crow\.wasmline"\) version ")[0-9A-Za-z.\-]+(")',
            lambda v: rf"\g<1>{v['wasmline_version']}\g<2>",
        ),
        Rule(
            r"(crow\.wasmline:[A-Za-z0-9_.-]+:)[0-9A-Za-z.\-]+",
            lambda v: rf"\g<1>{v['wasmline_version']}",
            min_count=0,
        ),
    )

    architecture_rules = (
        Rule(r"Zig [0-9]+\.[0-9]+\.[0-9]+", lambda v: f"Zig {v['zig_version']}", min_count=0),
        Rule(
            r"wasmtime C-API\s+v[0-9]+\.[0-9]+\.[0-9]+",
            lambda v: f"wasmtime C-API  v{v['wasmtime_version']}",
            min_count=0,
        ),
    )

    cli_docs_rules = (
        Rule(
            r"download -v v[0-9]+\.[0-9]+\.[0-9]+",
            lambda v: f"download -v v{v['wasmtime_version']}",
            min_count=0,
        ),
        Rule(
            r"wasmtime-v[0-9]+\.[0-9]+\.[0-9]+-aarch64-macos",
            lambda v: f"wasmtime-v{v['wasmtime_version']}-aarch64-macos",
            min_count=0,
        ),
    )
    version_sync_docs_rules = (
        Rule(
            r"(--set wasmtime_version=)[0-9]+\.[0-9]+\.[0-9]+",
            lambda v: rf"\g<1>{v['wasmtime_version']}",
            min_count=0,
        ),
        Rule(
            r"(--set wasmline_version=)[0-9A-Za-z.\-]+",
            lambda v: rf"\g<1>{v['wasmline_version']}",
            min_count=0,
        ),
    )

    cli_version_rules = (
        Rule(
            r'(?m)^VERSION="[0-9A-Za-z.\-]+"$',
            lambda v: f'VERSION="{v["sample_plugin_version"]}"',
        ),
        Rule(
            r'(?m)^VERSION_ALT="[0-9A-Za-z.\-]+"$',
            lambda v: f'VERSION_ALT="{v["sample_plugin_version"]}"',
        ),
        Rule(
            r'(?m)^VERSION_CODE_ALT="[0-9]+"$',
            lambda v: f'VERSION_CODE_ALT="{v["sample_plugin_version"].split(".")[0]}"',
        ),
        Rule(
            r'(?m)^WASMTIME_VERSION="v[0-9.]+"$',
            lambda v: f'WASMTIME_VERSION="v{v["wasmtime_version"]}"',
        ),
        Rule(
            r'wasmtime-v[0-9]+\.[0-9]+\.[0-9]+',
            lambda v: f'wasmtime-v{v["wasmtime_version"]}',
            min_count=0,
        ),
        Rule(
            r'(?m)(\\`)[0-9]+\.[0-9]+\.[0-9]+(\\`)(?=\s+\|\s+(?:Semantic version|Version string))',
            lambda v: rf'\g<1>{v["sample_plugin_version"]}\g<2>',
            min_count=0,
        ),
        Rule(
            r'(?m)(\\`)[0-9]+\.[0-9]+\.[0-9]+(\\`)(?=\s+\|\s+Version string for output directory)',
            lambda v: rf'\g<1>{v["sample_plugin_version"]}\g<2>',
            min_count=0,
        ),
        Rule(
            r'(?m)(\\`)[0-9]+\.[0-9]+\.[0-9]+(\\`)(?=\s+\|\s+Integer version code)',
            lambda v: rf'\g<1>{v["sample_plugin_version"].split(".")[0]}\g<2>',
            min_count=0,
        ),
        Rule(
            r'(?m)(\\`)[0-9]+\.[0-9]+\.[0-9]+(\\`)(?=\s+\|\s+Semantic version\s*$)',
            lambda v: rf'\g<1>{v["sample_plugin_version"]}\g<2>',
            min_count=0,
        ),
        Rule(
            r'(?m)(download -v )v[0-9]+\.[0-9]+\.[0-9]+',
            lambda v: rf'\g<1>v{v["wasmtime_version"]}',
            min_count=0,
        ),
    )
    development_guide_rules = (
        Rule(r"JBR [0-9]+", lambda v: f"JBR {v['jbr_version']}", min_count=0),
        Rule(
            r"Zig version \(requires \*\*[0-9.]+\*\*\)",
            lambda v: f"Zig version (requires **{v['zig_version']}**)",
        ),
        Rule(r"Zig [0-9]+\.[0-9]+\.[0-9]+", lambda v: f"Zig {v['zig_version']}", min_count=0),
    )
    sync_script_rules = (
        Rule(
            r"(--set wasmtime_version=)[0-9]+\.[0-9]+\.[0-9]+",
            lambda v: rf"\g<1>{v['wasmtime_version']}",
        ),
    )
    engine_build_rules = (
        Rule(
            r'(wasmline-engine-(?:cranelift|pulley)-jvm:)[0-9A-Za-z.\-]+(:)',
            lambda v: rf'\g<1>{v["wasmline_version"]}\g<2>',
        ),
    )
    manifest_test_rules = (
        Rule(
            r'(?m)(private fun createTestManifest\([^\n]*\): WasmlineManifest = WasmlineManifest\(\n\s+pluginId = "[^"]+",\n\s+version = ")[0-9A-Za-z.\-]+(")',
            lambda v: rf'\g<1>{v["sample_plugin_version"]}\g<2>',
        ),
    )
    sample_manifest_rules = (
        Rule(
            r'(?m)^        version = "[0-9A-Za-z.\-]+"$',
            lambda v: f'        version = "{v["sample_plugin_version"]}"',
        ),
    )
    sample_wasmtime_fallback_rules = (
        Rule(
            r'(providers\.gradleProperty\("wasmtime\.version"\)\.orElse\(")[0-9A-Za-z.\-]+("\)\.get\(\))',
            lambda v: rf'\g<1>{v["wasmtime_version"]}\g<2>',
        ),
    )
    sample_output_rules = (
        Rule(
            r'(wasmline/output/[^"\s]*-)[0-9]+\.[0-9]+\.[0-9]+',
            lambda v: rf'\g<1>{v["sample_plugin_version"]}',
        ),
    )

    jbr_toolchain_rules = (
        Rule(
            r"JavaLanguageVersion\.of\([0-9]+\)",
            lambda v: f"JavaLanguageVersion.of({v['jbr_version']})",
            min_count=0,
        ),
        Rule(
            r"jvmToolchain\([0-9]+\)",
            lambda v: f"jvmToolchain({v['jbr_version']})",
            min_count=0,
        ),
        Rule(
            r"JavaVersion\.VERSION_[0-9]+",
            lambda v: f"JavaVersion.VERSION_{v['jbr_version']}",
            min_count=0,
        ),
        Rule(
            r"(?m)^toolchainVersion=[0-9]+$",
            lambda v: f"toolchainVersion={v['jbr_version']}",
            min_count=0,
        ),
    )
    jbr_toolchain_files = tuple(
        FileSpec(
            path,
            jbr_toolchain_rules,
        )
        for path in (
            "wasmline-multiplatform/gradle/gradle-daemon-jvm.properties",
            "wasmline-multiplatform/wasmline/build.gradle.kts",
            "wasmline-multiplatform/wasmline-build-logic/app/src/main/kotlin/gradle/base/app.base.android.gradle.kts",
            "wasmline-multiplatform/wasmline-build-logic/app/src/main/kotlin/gradle/base/app.base.multiplatform.library.gradle.kts",
            "wasmline-multiplatform/wasmline-engine-cranelift/build.gradle.kts",
            "wasmline-multiplatform/wasmline-engine-pulley/build.gradle.kts",
            "wasmline-multiplatform/wasmline-gradle-plugin/build.gradle.kts",
            "wasmline-multiplatform/wasmline-kotlin-plugin/build.gradle.kts",
            "wasmline-multiplatform/wasmline-loader/build.gradle.kts",
            "wasmline-multiplatform/wasmline-network-ktor/build.gradle.kts",
            "wasmline-multiplatform/wasmline-network-okhttp/build.gradle.kts",
            "wasmline-multiplatform/wasmline-plugin-core/build.gradle.kts",
            "wasmline-multiplatform/wasmline-plugin-test/build.gradle.kts",
            "wasmline-samples/kotlin/gradle/gradle-daemon-jvm.properties",
            "wasmline-samples/kotlin/sample-apps/application/build.gradle.kts",
            "wasmline-samples/kotlin/sample-apps/multiplatform/desktopApp/build.gradle.kts",
            "wasmline-samples/kotlin/sample-apps/multiplatform/shared/build.gradle.kts",
            "wasmline-samples/kotlin/sample-apps/multiplatform/webApp/build.gradle.kts",
            "wasmline-samples/kotlin/sample-common/build.gradle.kts",
            "wasmline-samples/kotlin/sample-component-export-plugin/build.gradle.kts",
            "wasmline-samples/kotlin/sample-component-fixture/build.gradle.kts",
            "wasmline-samples/kotlin/sample-component-plugin/build.gradle.kts",
            "wasmline-samples/kotlin/sample-plugin/build.gradle.kts",
            "wasmline-samples/kotlin/sample-raw-export-plugin/build.gradle.kts",
        )
    )

    return jbr_toolchain_files + (
        FileSpec("scripts/sync_versions.py", sync_script_rules),
        FileSpec(
            "scripts/doctor.sh",
            (
                Rule(
                    r'(?m)^REQUIRED_JBR_VERSION="[0-9]+"\nREQUIRED_ZIG_VERSION="[0-9.]+"$',
                    lambda v: (
                        f'REQUIRED_JBR_VERSION="{v["jbr_version"]}"\n'
                        f'REQUIRED_ZIG_VERSION="{v["zig_version"]}"'
                    ),
                ),
            ),
        ),
        FileSpec(
            ".agents/skills/wasmline/SKILL.md",
            (
                Rule(r"JBR [0-9]+", lambda v: f"JBR {v['jbr_version']}", min_count=0),
                Rule(
                    r"\([0-9]+\.[0-9]+\.[0-9]+ → `[0-9]+`\)",
                    lambda v: f"({v['wasmtime_version']} → `{wasmtime_code(v['wasmtime_version'])}`)",
                    min_count=0,
                ),
                Rule(
                    r"Zig version \(requires \*\*[0-9.]+\*\*\)",
                    lambda v: f"Zig version (requires **{v['zig_version']}**)",
                    min_count=0,
                ),
                Rule(
                    r"Zig version \*\*[0-9.]+\*\*",
                    lambda v: f"Zig version **{v['zig_version']}**",
                    min_count=0,
                ),
                Rule(
                    r"requires Zig [0-9]+\.[0-9]+\.[0-9]+",
                    lambda v: f"requires Zig {v['zig_version']}",
                    min_count=0,
                ),
            ),
        ),
        FileSpec(
            ".agents/skills/wasmline/development-guide.md",
            development_guide_rules,
        ),
        FileSpec(
            "wasmline-multiplatform/gradle.properties",
            (
                Rule(
                    r"(?m)^wasmline\.version=.*$",
                    lambda v: f"wasmline.version={v['wasmline_version']}",
                ),
                Rule(
                    r"(?m)^wasmtime\.version\s*=.*$",
                    lambda v: f"wasmtime.version={v['wasmtime_version']}",
                ),
            ),
        ),
        FileSpec(
            "wasmline-samples/kotlin/gradle.properties",
            (
                Rule(
                    r"(?m)^wasmline\.version=.*$",
                    lambda v: f"wasmline.version={v['wasmline_version']}",
                ),
                Rule(
                    r"(?m)^wasmtime\.version\s*=.*$",
                    lambda v: f"wasmtime.version={v['wasmtime_version']}",
                ),
            ),
        ),
        FileSpec(
            "wasmline-samples/kotlin/sample-plugin/build.gradle.kts",
            sample_manifest_rules + sample_wasmtime_fallback_rules,
        ),
        FileSpec(
            "wasmline-samples/kotlin/sample-raw-export-plugin/build.gradle.kts",
            sample_manifest_rules + sample_wasmtime_fallback_rules,
        ),
        FileSpec(
            "wasmline-samples/kotlin/sample-component-plugin/build.gradle.kts",
            sample_manifest_rules,
        ),
        FileSpec(
            "wasmline-samples/kotlin/sample-component-export-plugin/build.gradle.kts",
            sample_manifest_rules,
        ),
        FileSpec(
            "wasmline-samples/kotlin/sample-component-fixture/build.gradle.kts",
            sample_manifest_rules,
        ),
        FileSpec(
            "wasmline-multiplatform/wasmline-plugin-test/build.gradle.kts",
            (
                Rule(
                    r'(?m)^val testPluginVersion = "[0-9A-Za-z.\-]+"$',
                    lambda v: f'val testPluginVersion = "{v["sample_plugin_version"]}"',
                ),
                Rule(
                    r'(?m)^    val wasmtimeVersion = "[0-9.]+"$',
                    lambda v: f'    val wasmtimeVersion = "{v["wasmtime_version"]}"',
                ),
            ),
        ),
        FileSpec(
            "wasmline-samples/kotlin/sample-apps/multiplatform/desktopApp/build.gradle.kts",
            sample_output_rules
            + (
                Rule(
                    r'(?m)^            packageVersion = project\.findProperty\("wasmline\.version"\) as\? String \?: "[0-9A-Za-z.\-]+"$',
                    lambda v: (
                        '            packageVersion = project.findProperty("wasmline.version") as? '
                        f'String ?: "{v["wasmline_version"]}"'
                    ),
                ),
            ),
        ),
        FileSpec(
            "wasmline-samples/kotlin/sample-apps/android/build.gradle.kts",
            sample_output_rules,
        ),
        FileSpec(
            "wasmline-samples/kotlin/sample-apps/application/build.gradle.kts",
            sample_output_rules,
        ),
        FileSpec(
            "wasmline-samples/kotlin/sample-apps/multiplatform/androidApp/build.gradle.kts",
            sample_output_rules,
        ),
        FileSpec(
            "wasmline-samples/kotlin/sample-apps/multiplatform/webApp/build.gradle.kts",
            sample_output_rules,
        ),
        FileSpec(
            "wasmline-samples/kotlin/README.md",
            sample_output_rules,
        ),
        FileSpec(
            "wasmline-samples/kotlin/sample-apps/README.md",
            sample_output_rules,
        ),
        FileSpec(
            "wasmline-samples/kotlin/sample-component-fixture/README.md",
            sample_output_rules,
        ),
        FileSpec(
            "wasmline-samples/kotlin/run-ios.sh",
            (
                Rule(
                    r"release-v[0-9]+\.[0-9]+\.[0-9]+",
                    lambda v: f"release-v{v['wasmtime_version']}",
                ),
            ),
        ),
        FileSpec(
            "wasmline-multiplatform/gradle/libs.versions.toml",
            (
                Rule(
                    r'(?m)^agp = ".*"$',
                    lambda v: f'agp = "{v["agp_version"]}"',
                ),
                Rule(
                    r'(?m)^kotlin = ".*"$',
                    lambda v: f'kotlin = "{v["kotlin_version"]}"',
                ),
                Rule(
                    r'(?m)^wasmline = ".*"$',
                    lambda v: f'wasmline = "{v["wasmline_version"]}"',
                ),
                Rule(
                    r'(?m)^wasmline = \{ id = "crow\.wasmline", version = ".*" \}$',
                    lambda v: f'wasmline = {{ id = "crow.wasmline", version = "{v["wasmline_version"]}" }}',
                ),
            ),
        ),
        FileSpec(
            "wasmline-multiplatform/wasmline-cli/cli.sh",
            cli_version_rules,
        ),
        FileSpec(
            "wasmline-multiplatform/docs/ir/box-ir.md",
            readme_en,
        ),
        FileSpec("README.md", readme_en),
        FileSpec("README_zh.md", readme_zh),
        FileSpec("docs/content/docs/building-from-source.mdx", building_from_source_en),
        FileSpec("docs/content/docs/building-from-source.zh.mdx", building_from_source_zh),
        FileSpec("docs/content/docs/installation.mdx", installation_en),
        FileSpec("docs/content/docs/installation.zh.mdx", installation_zh),
        FileSpec("docs/content/docs/architecture.mdx", architecture_rules),
        FileSpec("docs/content/docs/architecture.zh.mdx", architecture_rules),
        FileSpec("docs/content/docs/cli.mdx", cli_docs_rules),
        FileSpec("docs/content/docs/cli.zh.mdx", cli_docs_rules),
        FileSpec("docs/content/docs/testing.mdx", version_sync_docs_rules),
        FileSpec("docs/content/docs/testing.zh.mdx", version_sync_docs_rules),
        FileSpec(
            "docs/content/docs/wasmtime-download.mdx",
            (
                Rule(
                    r'(version = "v)[0-9A-Za-z.\-]+(")',
                    lambda v: rf'\g<1>{v["wasmtime_version"]}\g<2>',
                ),
            ),
        ),
        FileSpec(
            "docs/content/docs/wasmtime-download.zh.mdx",
            (
                Rule(
                    r'(version = "v)[0-9A-Za-z.\-]+(")',
                    lambda v: rf'\g<1>{v["wasmtime_version"]}\g<2>',
                ),
            ),
        ),
        FileSpec(
            "wasmline-multiplatform/docs/native-library-loading.md",
            (
                Rule(
                    r"(?<![0-9.])[0-9]+\.[0-9]+\.[0-9]+(?![0-9])",
                    lambda v: v["wasmline_version"],
                    min_count=0,
                ),
            ),
        ),
        FileSpec(
            ".github/workflows/ci.yml",
            (
                Rule(r"JBR [0-9]+", lambda v: f"JBR {v['jbr_version']}"),
                Rule(r"jbr-[0-9]+-", lambda v: f"jbr-{v['jbr_version']}-"),
                Rule(r"java-version: [0-9]+\.x", lambda v: f"java-version: {v['jbr_version']}.x"),
                Rule(
                    r"release-v[0-9]+\.[0-9]+\.[0-9]+",
                    lambda v: f"release-v{v['wasmtime_version']}",
                    min_count=0,
                ),
                Rule(
                    r'(?m)^  ZIG_VERSION: "[0-9.]+"$',
                    lambda v: f'  ZIG_VERSION: "{v["zig_version"]}"',
                    min_count=0,
                ),
            ),
        ),
        FileSpec(
            "wasmline-multiplatform/ci/compile-ios.sh",
            (
                Rule(
                    r"release-v[0-9]+\.[0-9]+\.[0-9]+",
                    lambda v: f"release-v{v['wasmtime_version']}",
                    min_count=0,
                ),
            ),
        ),
        FileSpec(
            "wasmline-samples/kotlin/sample-apps/android/build.gradle.kts",
            (
                Rule(
                    r'(?m)^        versionName = "[0-9A-Za-z.\-]+"',
                    lambda v: f'        versionName = "{v["wasmline_version"]}-release"',
                ),
            ),
        ),
        FileSpec(
            "wasmline-multiplatform/docs/zig-build.md",
            (
                Rule(
                    r"Zig Version : [0-9]+\.[0-9]+\.[0-9]+",
                    lambda v: f"Zig Version : {v['zig_version']}",
                ),
            ),
        ),
        FileSpec(
            "wasmline-multiplatform/docs/design-mind.md",
            (
                Rule(
                    r"Zig [0-9]+\.[0-9]+\.[0-9]+",
                    lambda v: f"Zig {v['zig_version']}",
                ),
            ),
        ),
        FileSpec(
            "scripts/build-native-assets.sh",
            (
                Rule(
                    r'(?m)^    echo "[0-9]+\.[0-9]+\.[0-9]+"$',
                    lambda v: f'    echo "{v["wasmtime_version"]}"',
                ),
            ),
        ),
        FileSpec(
            "scripts/init-wasmtime-ci.sh",
            (
                Rule(
                    r"release-v[0-9]+\.[0-9]+\.[0-9]+",
                    lambda v: f"release-v{v['wasmtime_version']}",
                ),
            ),
        ),
        FileSpec(
            "scripts/lib/context.sh",
            (
                Rule(
                    r"release-v[0-9]+\.[0-9]+\.[0-9]+",
                    lambda v: f"release-v{v['wasmtime_version']}",
                ),
            ),
        ),
        FileSpec(
            "wasmline-multiplatform/wasmline-android/src/androidMain/CMakeLists.txt",
            (
                Rule(
                    r"release-v[0-9]+\.[0-9]+\.[0-9]+",
                    lambda v: f"release-v{v['wasmtime_version']}",
                ),
            ),
        ),
        FileSpec(
            "wasmline-multiplatform/wasmline/build.zig",
            (
                Rule(
                    r"release-v[0-9]+\.[0-9]+\.[0-9]+",
                    lambda v: f"release-v{v['wasmtime_version']}",
                ),
            ),
        ),
        FileSpec(
            "wasmline-multiplatform/wasmline-cli/src/main/kotlin/crow/wasmline/cli/Build.kt",
            (
                Rule(
                    r'(?m)^    private val version by option\("-v", "--version"\)\.default\("[0-9A-Za-z.\-]+"\)$',
                    lambda v: f'    private val version by option("-v", "--version").default("{v["sample_plugin_version"]}")',
                ),
            ),
        ),
        FileSpec(
            "wasmline-multiplatform/wasmline-cli/src/main/kotlin/crow/wasmline/cli/Compile.kt",
            (
                Rule(
                    r'(?m)^    private val version by option\("-v", "--version"\)\.default\("[0-9A-Za-z.\-]+"\)$',
                    lambda v: f'    private val version by option("-v", "--version").default("{v["sample_plugin_version"]}")',
                ),
            ),
        ),
        FileSpec(
            "wasmline-multiplatform/wasmline-cli/src/main/kotlin/crow/wasmline/cli/Manifest.kt",
            (
                Rule(
                    r'(?m)^    private val version by option\("--version"\)\.default\("[0-9A-Za-z.\-]+"\)$',
                    lambda v: f'    private val version by option("--version").default("{v["sample_plugin_version"]}")',
                ),
            ),
        ),
        FileSpec(
            "wasmline-multiplatform/wasmline-cli/src/test/kotlin/crow/wasmline/cli/ComponentCliIntegrationTest.kt",
            (
                Rule(
                    r'(File\(compileRoot, "cli-compile-)[0-9A-Za-z.\-]+("\))',
                    lambda v: rf'\g<1>{v["sample_plugin_version"]}\g<2>',
                ),
            ),
        ),
        FileSpec(
            "wasmline-multiplatform/wasmline-cli/src/test/kotlin/crow/wasmline/cli/CoreCliRegressionTest.kt",
            (
                Rule(
                    r'(File\(outputRoot, "core-plugin-)[0-9A-Za-z.\-]+(/debug/)',
                    lambda v: rf'\g<1>{v["sample_plugin_version"]}\g<2>',
                ),
            ),
        ),
        FileSpec(
            "wasmline-multiplatform/wasmline-gradle-plugin/src/main/kotlin/crow/wasmline/WasmlinePlugin.kt",
            (
                Rule(
                    r'(?m)^ \*         version = "[0-9A-Za-z.\-]+"$',
                    lambda v: f' *         version = "{v["sample_plugin_version"]}"',
                ),
                Rule(
                    r'v[0-9]+\.[0-9]+\.[0-9]+',
                    lambda v: f"v{v['wasmtime_version']}",
                    min_count=0,
                ),
            ),
        ),
        FileSpec(
            "wasmline-multiplatform/wasmline-gradle-plugin/src/main/kotlin/crow/wasmline/gradle/extensions/ManifestExtension.kt",
            (
                Rule(
                    r'(?m)^ \*         version = "[0-9A-Za-z.\-]+"$',
                    lambda v: f' *         version = "{v["sample_plugin_version"]}"',
                ),
                Rule(
                    r'(?m)^ \*         minSdkVersion = "[0-9A-Za-z.\-]+"$',
                    lambda v: f' *         minSdkVersion = "{v["wasmline_version"]}"',
                ),
                Rule(
                    r'(?m)^    /\*\* Semantic version string\. Default: "[0-9A-Za-z.\-]+"\. \*/$',
                    lambda v: f'    /** Semantic version string. Default: "{v["sample_plugin_version"]}". */',
                ),
                Rule(
                    r'(?m)^(    (?:public )?val version: Property<String> = objects\.property\(String::class\.java\)\.convention\(")[0-9A-Za-z.\-]+("\))$',
                    lambda v: rf'\g<1>{v["sample_plugin_version"]}\g<2>',
                ),
                Rule(
                    r'(?m)^(    (?:public )?val minSdkVersion: Property<String> = objects\.property\(String::class\.java\)\.convention\(")[0-9A-Za-z.\-]+("\))$',
                    lambda v: rf'\g<1>{v["wasmline_version"]}\g<2>',
                ),
            ),
        ),
        FileSpec(
            "wasmline-multiplatform/wasmline-gradle-plugin/src/main/kotlin/crow/wasmline/gradle/extensions/WasmlineExtension.kt",
            (
                Rule(
                    r'(?m)^ \*         version = "[0-9A-Za-z.\-]+"$',
                    lambda v: f' *         version = "{v["sample_plugin_version"]}"',
                ),
                Rule(
                    r'v[0-9]+\.[0-9]+\.[0-9]+',
                    lambda v: f"v{v['wasmtime_version']}",
                    min_count=0,
                ),
            ),
        ),
        FileSpec(
            "wasmline-multiplatform/wasmline-gradle-plugin/src/main/kotlin/crow/wasmline/gradle/extensions/WasmtimeExtension.kt",
            (
                Rule(
                    r'v[0-9]+\.[0-9]+\.[0-9]+',
                    lambda v: f"v{v['wasmtime_version']}",
                ),
                Rule(
                    r'release-v[0-9]+\.[0-9]+\.[0-9]+',
                    lambda v: f"release-v{v['wasmtime_version']}",
                ),
            ),
        ),
        FileSpec(
            "wasmline-multiplatform/wasmline-engine-pulley/src/commonMain/kotlin/crow/wasmline/engine/pulley/PulleyEngine.kt",
            (
                Rule(
                    r'(crow\.wasmline:[A-Za-z0-9_.-]+:)[0-9A-Za-z.\-]+',
                    lambda v: rf'\g<1>{v["wasmline_version"]}',
                ),
            ),
        ),
        FileSpec(
            "wasmline-multiplatform/wasmline-engine-cranelift/build.gradle.kts",
            engine_build_rules,
        ),
        FileSpec(
            "wasmline-multiplatform/wasmline-engine-pulley/build.gradle.kts",
            engine_build_rules,
        ),
        FileSpec(
            "wasmline-multiplatform/wasmline-engine-cranelift/src/commonMain/kotlin/crow/wasmline/engine/cranelift/CraneliftEngine.kt",
            (
                Rule(
                    r'(crow\.wasmline:[A-Za-z0-9_.-]+:)[0-9A-Za-z.\-]+',
                    lambda v: rf'\g<1>{v["wasmline_version"]}',
                ),
            ),
        ),
        FileSpec(
            "wasmline-multiplatform/wasmline-loader/src/commonTest/kotlin/crow/wasmline/ManifestTest.kt",
            manifest_test_rules
            + (
                Rule(
                    r'(?m)(pluginId = "test",\n\s+version = ")[0-9A-Za-z.\-]+(")',
                    lambda v: rf'\g<1>{v["sample_plugin_version"]}\g<2>',
                ),
            ),
        ),
        FileSpec(
            "wasmline-multiplatform/wasmline-loader/src/jvmTest/kotlin/crow/wasmline/loader/WasmlineRemotePackageResolutionTest.kt",
            manifest_test_rules,
        ),
        FileSpec(
            "wasmline-multiplatform/wasmline-plugin-core/src/main/kotlin/crow/wasmline/plugin/core/toolchain/ToolchainCatalog.kt",
            (
                Rule(
                    r'(?m)^    const val WASMTIME_VERSION = "[0-9A-Za-z.\-]+"$',
                    lambda v: f'    const val WASMTIME_VERSION = "{v["wasmtime_version"]}"',
                ),
            ),
        ),
        FileSpec(
            "wasmline-multiplatform/wasmline-plugin-core/src/test/kotlin/crow/wasmline/plugin/core/component/ComponentToolchainIntegrationTest.kt",
            (
                Rule(
                    r'(?m)^                    adapterVersion = "[0-9A-Za-z.\-]+",$',
                    lambda v: f'                    adapterVersion = "{v["wasmtime_version"]}",',
                ),
            ),
        ),
        FileSpec(
            "wasmline-multiplatform/wasmline-plugin-test/src/jvmTest/kotlin/crow/wasmline/test/wasmtime/NativePluginTestSupport.kt",
            (
                Rule(
                    r'(?m)^                    targetCompilerVersion = "wasmtime-[0-9A-Za-z.\-]+",$',
                    lambda v: (
                        '                    targetCompilerVersion = '
                        f'"wasmtime-{v["wasmtime_version"]}",'
                    ),
                ),
            ),
        ),
        FileSpec(
            "wasmline-multiplatform/wasmline/src/jvmTest/kotlin/crow/wasmline/test/wasmtime/NativeWasmtimeIntegrationTest.kt",
            (
                Rule(
                    r'(?m)^        assertEquals\("[0-9A-Za-z.\-]+", capabilities\.wasmtimeVersion\)$',
                    lambda v: (
                        f'        assertEquals("{v["wasmtime_version"]}", '
                        'capabilities.wasmtimeVersion)'
                    ),
                ),
            ),
        ),
        FileSpec(
            "wasmline-samples/kotlin/sample-apps/multiplatform/shared/src/desktopMain/Requirement.md",
            (
                Rule(r"JBR [0-9]+", lambda v: f"JBR {v['jbr_version']}"),
            ),
        ),
        FileSpec(
            "ROADMAP.md",
            (
                Rule(
                    r'(Wasmtime C-API integration \(v)[0-9A-Za-z.\-]+(\))',
                    lambda v: rf'\g<1>{v["wasmtime_version"]}\g<2>',
                ),
            ),
        ),
        FileSpec(
            "ROADMAP_zh.md",
            (
                Rule(
                    r'(Wasmtime C-API 集成（v)[0-9A-Za-z.\-]+(）)',
                    lambda v: rf'\g<1>{v["wasmtime_version"]}\g<2>',
                ),
            ),
        ),
    )


def list_versions(versions: VersionMap) -> None:
    for key in REQUIRED_KEYS:
        print(f"{key}={versions[key]}")


def sync_files(versions: VersionMap, check: bool) -> int:
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

    changed_files = [
        display_path
        for display_path, path in path_order
        if updated_by_path[path] != original_by_path[path]
    ]

    if not check:
        for _, path in path_order:
            if updated_by_path[path] != original_by_path[path]:
                path.write_text(updated_by_path[path], encoding="utf-8")

    if check:
        if changed_files:
            print("Version drift detected:")
            for item in changed_files:
                print(f"  - {item}")
            return 1
        print("All managed files are synchronized.")
        return 0

    if changed_files:
        print("Updated files:")
        for item in changed_files:
            print(f"  - {item}")
    else:
        print("No version changes were necessary.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Synchronize managed version references.")
    parser.add_argument(
        "--check",
        action="store_true",
        help="Fail if managed files are out of sync.",
    )
    parser.add_argument(
        "--set",
        action="append",
        default=[],
        metavar="KEY=VALUE",
        help=f"Update a manifest version key ({', '.join(REQUIRED_KEYS)}).",
    )
    parser.add_argument(
        "--list",
        action="store_true",
        help="Print the current manifest values and exit.",
    )
    args = parser.parse_args()

    if args.check and args.set:
        raise SystemExit("--check cannot be used together with --set.")

    data = load_manifest()
    versions = data["versions"]

    if args.list:
        list_versions(versions)
        return 0

    if args.set:
        versions.update(parse_updates(args.set))
        data["versions"] = versions
    result = sync_files(versions, check=args.check)
    if args.set and result == 0:
        write_manifest(data)
    return result


if __name__ == "__main__":
    sys.exit(main())
