#!/usr/bin/env python3
"""
Synchronize repository version references from a single manifest.

Usage:
    python3 scripts/sync_versions.py
    python3 scripts/sync_versions.py --check
    python3 scripts/sync_versions.py --set wasmtime_version=46.0.0
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

    missing = [key for key in REQUIRED_KEYS if not versions.get(key)]
    if missing:
        raise SystemExit(f"Manifest is missing required keys: {', '.join(missing)}")
    return {"versions": {key: str(versions[key]) for key in REQUIRED_KEYS}}


def write_manifest(data: dict[str, VersionMap]) -> None:
    MANIFEST_PATH.write_text(
        json.dumps(data, ensure_ascii=True, indent=2) + "\n",
        encoding="utf-8",
    )


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
        updates[key] = value
    return updates


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
    )

    architecture_rules = (
        Rule(r"Zig [0-9]+\.[0-9]+\.[0-9]+", lambda v: f"Zig {v['zig_version']}"),
        Rule(
            r"wasmtime C-API\s+v[0-9]+\.[0-9]+\.[0-9]+",
            lambda v: f"wasmtime C-API  v{v['wasmtime_version']}",
        ),
    )

    cli_docs_rules = (
        Rule(
            r"download -v v[0-9]+\.[0-9]+\.[0-9]+",
            lambda v: f"download -v v{v['wasmtime_version']}",
        ),
        Rule(
            r"wasmtime-v[0-9]+\.[0-9]+\.[0-9]+-aarch64-macos",
            lambda v: f"wasmtime-v{v['wasmtime_version']}-aarch64-macos",
        ),
    )

    return (
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
                Rule(r"Java [0-9]+", lambda v: f"Java {v['jbr_version']}"),
                Rule(r"JBR [0-9]+", lambda v: f"JBR {v['jbr_version']}"),
                Rule(
                    r"Zig version \(requires \*\*[0-9.]+\*\*\)",
                    lambda v: f"Zig version (requires **{v['zig_version']}**)",
                ),
                Rule(
                    r"Zig version \*\*[0-9.]+\*\*",
                    lambda v: f"Zig version **{v['zig_version']}**",
                ),
                Rule(
                    r"requires Zig [0-9]+\.[0-9]+\.[0-9]+",
                    lambda v: f"requires Zig {v['zig_version']}",
                ),
            ),
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
            ),
        ),
        FileSpec(
            "wasmline-multiplatform/wasmline-cli/cli.sh",
            (
                Rule(
                    r'(?m)^WASMTIME_VERSION="v[0-9.]+"$',
                    lambda v: f'WASMTIME_VERSION="v{v["wasmtime_version"]}"',
                ),
            ),
        ),
        FileSpec("BOX_IR.md", readme_en),
        FileSpec("README_zh.md", readme_zh),
        FileSpec("docs/content/docs/building-from-source.mdx", building_from_source_en),
        FileSpec("docs/content/docs/building-from-source.zh.mdx", building_from_source_zh),
        FileSpec("docs/content/docs/installation.mdx", installation_en),
        FileSpec("docs/content/docs/installation.zh.mdx", installation_zh),
        FileSpec("docs/content/docs/architecture.mdx", architecture_rules),
        FileSpec("docs/content/docs/architecture.zh.mdx", architecture_rules),
        FileSpec("docs/content/docs/cli.mdx", cli_docs_rules),
        FileSpec("docs/content/docs/cli.zh.mdx", cli_docs_rules),
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
    )


def list_versions(versions: VersionMap) -> None:
    for key in REQUIRED_KEYS:
        print(f"{key}={versions[key]}")


def sync_files(versions: VersionMap, check: bool) -> int:
    changed_files: list[str] = []
    for spec in file_specs():
        path = PROJECT_ROOT / spec.path
        original = path.read_text(encoding="utf-8")
        updated = apply_rules(original, spec, versions)
        if updated != original:
            changed_files.append(spec.path)
            if not check:
                path.write_text(updated, encoding="utf-8")

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
        write_manifest(data)

    return sync_files(versions, check=args.check)


if __name__ == "__main__":
    sys.exit(main())
