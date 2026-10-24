"""Version file rules and their rendering.

Rules live here so `versions.py` can import them without the rule module
importing the version operations in return.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Callable

from .support.text import MissingMatches, replace_matches

VersionMap = dict[str, str]
Replacement = str | Callable[[VersionMap], str]

SEMANTIC_VERSION_TOKEN_PATTERN = r"[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?"


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

def apply_rules(text: str, spec: FileSpec, versions: VersionMap) -> str:
    """Apply every rule for one file in declaration order."""

    updated = text
    for rule in spec.rules:
        try:
            updated = replace_matches(
                updated,
                rule.pattern,
                render(rule.replacement, versions),
                flags=rule.flags,
                minimum=rule.min_count,
            )
        except MissingMatches as error:
            raise SystemExit(
                f"Pattern not found enough times in {spec.path}: {rule.pattern}"
            ) from error
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

    version_sync_docs_rules = (
        Rule(
            r"(--set wasmtime_version=)[0-9]+\.[0-9]+\.[0-9]+",
            lambda v: rf"\g<1>{v['wasmtime_version']}",
            min_count=0,
        ),
        Rule(
            r"(--set wasmtime_release_version=)[0-9]+(?:\.[0-9]+){3}",
            lambda v: rf"\g<1>{v['wasmtime_release_version']}",
            min_count=0,
        ),
        Rule(
            r"(--set wasmline_version=)[0-9A-Za-z.\-]+",
            lambda v: rf"\g<1>{v['wasmline_version']}",
            min_count=0,
        ),
    )

    development_guide_rules = (
        Rule(r"JBR [0-9]+", lambda v: f"JBR {v['jbr_version']}", min_count=0),
        Rule(
            r"The pre-check also reports Zig [0-9]+\.[0-9]+\.[0-9]+",
            lambda v: f"The pre-check also reports Zig {v['zig_version']}",
        ),
        Rule(r"Zig [0-9]+\.[0-9]+\.[0-9]+", lambda v: f"Zig {v['zig_version']}", min_count=0),
    )
    sample_manifest_rules = (
        Rule(
            r'(?m)^        version = "[0-9A-Za-z.\-]+"$',
            lambda v: f'        version = "{v["sample_plugin_version"]}"',
        ),
    )
    sample_output_rules = (
        Rule(
            r'(wasmline/output/[^"\s]*-)[0-9]+\.[0-9]+\.[0-9]+',
            lambda v: rf'\g<1>{v["sample_plugin_version"]}',
        ),
    )
    gradle_wrapper_rules = (
        Rule(
            r"(?m)^distributionUrl=https\\://services\.gradle\.org/distributions/gradle-[0-9A-Za-z.+-]+-bin\.zip$",
            lambda v: (
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-"
                f"{v['gradle_version']}-bin.zip"
            ),
        ),
    )
    gradle_wrapper_files = tuple(
        FileSpec(
            path,
            gradle_wrapper_rules,
        )
        for path in (
            "gradle/wrapper/gradle-wrapper.properties",
            "wasmline-plugin-test/gradle/wrapper/gradle-wrapper.properties",
            "samples/multiplatform-app/gradle/wrapper/gradle-wrapper.properties",
            "samples/minimal-raw-export/gradle/wrapper/gradle-wrapper.properties",
            "samples/minimal-service/gradle/wrapper/gradle-wrapper.properties",
        )
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
            "gradle/gradle-daemon-jvm.properties",
            "wasmline/build.gradle.kts",
            "wasmline-build-logic/app/src/main/kotlin/gradle/base/app.base.android.gradle.kts",
            "wasmline-build-logic/app/src/main/kotlin/gradle/base/app.base.multiplatform.library.gradle.kts",
            "wasmline-build-logic/app/src/main/kotlin/wasmline.engine.gradle.kts",
            "wasmline-gradle-plugin/build.gradle.kts",
            "wasmline-kotlin-plugin/build.gradle.kts",
            "wasmline-loader/build.gradle.kts",
            "wasmline-network-ktor/build.gradle.kts",
            "wasmline-network-okhttp/build.gradle.kts",
            "wasmline-plugin-core/build.gradle.kts",
            "wasmline-plugin-test/build.gradle.kts",
            "samples/multiplatform-app/gradle/gradle-daemon-jvm.properties",
            "samples/multiplatform-app/sample-apps/application/build.gradle.kts",
            "samples/multiplatform-app/sample-apps/multiplatform/desktopApp/build.gradle.kts",
            "samples/multiplatform-app/sample-apps/multiplatform/shared/build.gradle.kts",
            "samples/multiplatform-app/sample-apps/multiplatform/webApp/build.gradle.kts",
            "samples/multiplatform-app/sample-common/build.gradle.kts",
            "samples/multiplatform-app/plugins/kotlin-component-export/build.gradle.kts",
            "samples/multiplatform-app/plugins/component-package/build.gradle.kts",
            "samples/multiplatform-app/plugins/kotlin-component-service/build.gradle.kts",
            "samples/multiplatform-app/plugins/kotlin-service/build.gradle.kts",
            "samples/multiplatform-app/plugins/raw-export/build.gradle.kts",
        )
    )

    return gradle_wrapper_files + jbr_toolchain_files + (
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
            ".agents/skills/wasmline/references/development-guide.md",
            development_guide_rules,
        ),
        FileSpec(
            "gradle.properties",
            (
                Rule(
                    r"(?m)^wasmline\.version=.*$",
                    lambda v: f"wasmline.version={v['wasmline_version']}",
                ),
                Rule(
                    r"(?m)^wasmtime\.version\s*=.*$",
                    lambda v: f"wasmtime.version={v['wasmtime_version']}",
                ),
                Rule(
                    r"(?m)^wasmtime\.release\.version\s*=.*$",
                    lambda v: f"wasmtime.release.version={v['wasmtime_release_version']}",
                ),
            ),
        ),
        FileSpec(
            "samples/multiplatform-app/gradle.properties",
            (
                Rule(
                    r"(?m)^wasmline\.version=.*$",
                    lambda v: f"wasmline.version={v['wasmline_version']}",
                ),
                Rule(
                    r"(?m)^wasmtime\.version\s*=.*$",
                    lambda v: f"wasmtime.version={v['wasmtime_version']}",
                ),
                Rule(
                    r"(?m)^wasmtime\.release\.version\s*=.*$",
                    lambda v: f"wasmtime.release.version={v['wasmtime_release_version']}",
                ),
            ),
        ),
        FileSpec("samples/multiplatform-app/plugins/kotlin-service/build.gradle.kts", sample_manifest_rules),
        FileSpec("samples/multiplatform-app/plugins/raw-export/build.gradle.kts", sample_manifest_rules),
        FileSpec(
            "samples/multiplatform-app/plugins/kotlin-component-service/build.gradle.kts",
            sample_manifest_rules,
        ),
        FileSpec(
            "samples/multiplatform-app/plugins/kotlin-component-export/build.gradle.kts",
            sample_manifest_rules,
        ),
        FileSpec(
            "samples/multiplatform-app/plugins/component-package/build.gradle.kts",
            sample_manifest_rules,
        ),
        FileSpec(
            "wasmline-plugin-test/build.gradle.kts",
            (
                Rule(
                    r'(?m)^val testPluginVersion = "[0-9A-Za-z.\-]+"$',
                    lambda v: f'val testPluginVersion = "{v["sample_plugin_version"]}"',
                ),
            ),
        ),
        FileSpec(
            "samples/multiplatform-app/sample-apps/multiplatform/desktopApp/build.gradle.kts",
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
            "samples/multiplatform-app/sample-apps/android/build.gradle.kts",
            sample_output_rules,
        ),
        FileSpec(
            "samples/multiplatform-app/sample-apps/application/build.gradle.kts",
            sample_output_rules,
        ),
        FileSpec(
            "samples/multiplatform-app/sample-apps/multiplatform/androidApp/build.gradle.kts",
            sample_output_rules,
        ),
        FileSpec(
            "samples/multiplatform-app/sample-apps/multiplatform/webApp/build.gradle.kts",
            sample_output_rules,
        ),
        FileSpec("samples/multiplatform-app/README.md", sample_output_rules),
        FileSpec(
            "samples/multiplatform-app/sample-apps/README.md",
            sample_output_rules,
        ),
        FileSpec(
            "samples/multiplatform-app/plugins/component-package/README.md",
            sample_output_rules,
        ),
        FileSpec(
            "samples/multiplatform-app/run-ios.sh",
            (
                Rule(
                    r"(?:release-)?v[0-9]+(?:\.[0-9]+){2,3}",
                    lambda v: f"v{v['wasmtime_release_version']}",
                ),
            ),
        ),
        FileSpec(
            "gradle/libs.versions.toml",
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
                    r'(?m)^dokka = ".*"$',
                    lambda v: f'dokka = "{v["dokka_version"]}"',
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
            "docs/ir/box-ir.md",
            readme_en,
        ),
        FileSpec("README.md", readme_en),
        FileSpec("README_zh.md", readme_zh),
        FileSpec(
            "fumadocs/content/docs/(reference)/(repository-development)/building-from-source.mdx",
            building_from_source_en,
        ),
        FileSpec(
            "fumadocs/content/docs/(reference)/(repository-development)/building-from-source.zh.mdx",
            building_from_source_zh,
        ),
        FileSpec("fumadocs/content/docs/installation.mdx", installation_en),
        FileSpec("fumadocs/content/docs/installation.zh.mdx", installation_zh),
        FileSpec("fumadocs/content/docs/(reference)/(runtime)/architecture.mdx", architecture_rules),
        FileSpec("fumadocs/content/docs/(reference)/(runtime)/architecture.zh.mdx", architecture_rules),
        FileSpec(
            "fumadocs/content/docs/(reference)/(repository-development)/testing.mdx",
            version_sync_docs_rules,
        ),
        FileSpec(
            "fumadocs/content/docs/(reference)/(repository-development)/testing.zh.mdx",
            version_sync_docs_rules,
        ),
        FileSpec(
            "docs/native-library-loading.md",
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
                Rule(r"jbr-[0-9]+-", lambda v: f"jbr-{v['jbr_version']}-", min_count=0),
                Rule(
                    r"java-version: [0-9]+\.x",
                    lambda v: f"java-version: {v['jbr_version']}.x",
                    min_count=0,
                ),
                Rule(
                    r"(?:release-)?v[0-9]+(?:\.[0-9]+){2,3}",
                    lambda v: f"v{v['wasmtime_release_version']}",
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
            ".github/workflows/release.yml",
            (
                Rule(r"JBR [0-9]+", lambda v: f"JBR {v['jbr_version']}"),
                Rule(r"jbr-[0-9]+-", lambda v: f"jbr-{v['jbr_version']}-", min_count=0),
                Rule(
                    r"java-version: [0-9]+\.x",
                    lambda v: f"java-version: {v['jbr_version']}.x",
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
            "samples/multiplatform-app/sample-apps/android/build.gradle.kts",
            (
                Rule(
                    r'(?m)^        versionName = "[0-9A-Za-z.\-]+"',
                    lambda v: f'        versionName = "{v["wasmline_version"]}-release"',
                ),
            ),
        ),
        FileSpec(
            "docs/zig-build.md",
            (
                Rule(
                    r"Zig Version : [0-9]+\.[0-9]+\.[0-9]+",
                    lambda v: f"Zig Version : {v['zig_version']}",
                ),
            ),
        ),
        FileSpec(
            "docs/design-mind.md",
            (
                Rule(
                    r"Zig [0-9]+\.[0-9]+\.[0-9]+",
                    lambda v: f"Zig {v['zig_version']}",
                ),
            ),
        ),
        FileSpec(
            "wasmline-android/src/androidMain/CMakeLists.txt",
            (
                Rule(
                    r"(?:release-)?v[0-9]+(?:\.[0-9]+){2,3}",
                    lambda v: f"v{v['wasmtime_release_version']}",
                ),
            ),
        ),
        FileSpec(
            "wasmline/build.zig",
            (
                Rule(
                    r"(?:release-)?v[0-9]+(?:\.[0-9]+){2,3}",
                    lambda v: f"v{v['wasmtime_release_version']}",
                ),
            ),
        ),
        FileSpec(
            "wasmline-cli/src/main/kotlin/crow/wasmline/cli/Build.kt",
            (
                Rule(
                    r'(?m)^    private val version by option\("-v", "--version"\)\.default\("[0-9A-Za-z.\-]+"\)$',
                    lambda v: f'    private val version by option("-v", "--version").default("{v["sample_plugin_version"]}")',
                ),
            ),
        ),
        FileSpec(
            "wasmline-cli/src/main/kotlin/crow/wasmline/cli/Compile.kt",
            (
                Rule(
                    r'(?m)^    private val version by option\("-v", "--version"\)\.default\("[0-9A-Za-z.\-]+"\)$',
                    lambda v: f'    private val version by option("-v", "--version").default("{v["sample_plugin_version"]}")',
                ),
            ),
        ),
        FileSpec(
            "wasmline-cli/src/main/kotlin/crow/wasmline/cli/Manifest.kt",
            (
                Rule(
                    r'(?m)^    private val version by option\("--version"\)\.default\("[0-9A-Za-z.\-]+"\)$',
                    lambda v: f'    private val version by option("--version").default("{v["sample_plugin_version"]}")',
                ),
            ),
        ),
        FileSpec(
            "wasmline-cli/src/test/kotlin/crow/wasmline/cli/ComponentCliIntegrationTest.kt",
            (
                Rule(
                    r'(File\(compileRoot, "cli-compile-)[0-9A-Za-z.\-]+("\))',
                    lambda v: rf'\g<1>{v["sample_plugin_version"]}\g<2>',
                ),
            ),
        ),
        FileSpec(
            "wasmline-gradle-plugin/src/main/kotlin/crow/wasmline/WasmlinePlugin.kt",
            (
                Rule(
                    r'(?m)(^ \*         pluginId = "[^"]+"\n^ \*         version = ")[0-9A-Za-z.\-]+("$)',
                    lambda v: rf'\g<1>{v["sample_plugin_version"]}\g<2>',
                ),
            ),
        ),
        FileSpec(
            "wasmline-gradle-plugin/src/main/kotlin/crow/wasmline/gradle/extensions/ManifestExtension.kt",
            (
                Rule(
                    r'(?m)(^ \*         pluginId = "[^"]+"\n^ \*         version = ")[0-9A-Za-z.\-]+("$)',
                    lambda v: rf'\g<1>{v["sample_plugin_version"]}\g<2>',
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
            "wasmline-gradle-plugin/src/main/kotlin/crow/wasmline/gradle/extensions/WasmlineExtension.kt",
            (
                Rule(
                    r'(?m)(^ \*         pluginId = "[^"]+"\n^ \*         version = ")[0-9A-Za-z.\-]+("$)',
                    lambda v: rf'\g<1>{v["sample_plugin_version"]}\g<2>',
                ),
            ),
        ),
        FileSpec(
            "samples/multiplatform-app/plugins/c-component-service/CMakeLists.txt",
            (
                Rule(
                    rf'("wit-bindgen-cli ){SEMANTIC_VERSION_TOKEN_PATTERN}(")',
                    lambda v: rf'\g<1>{v["wit_bindgen_version"]}\g<2>',
                ),
                Rule(
                    rf'("wasm-tools ){SEMANTIC_VERSION_TOKEN_PATTERN}(")',
                    lambda v: rf'\g<1>{v["wasm_tools_version"]}\g<2>',
                ),
            ),
        ),
        FileSpec(
            "samples/multiplatform-app/plugins/cpp-component-service/CMakeLists.txt",
            (
                Rule(
                    rf'("wit-bindgen-cli ){SEMANTIC_VERSION_TOKEN_PATTERN}(")',
                    lambda v: rf'\g<1>{v["wit_bindgen_version"]}\g<2>',
                ),
                Rule(
                    rf'("wasm-tools ){SEMANTIC_VERSION_TOKEN_PATTERN}(")',
                    lambda v: rf'\g<1>{v["wasm_tools_version"]}\g<2>',
                ),
            ),
        ),
        FileSpec(
            "samples/multiplatform-app/plugins/c-component-service/README.md",
            (
                Rule(
                    rf'(`wit-bindgen ){SEMANTIC_VERSION_TOKEN_PATTERN}(`)',
                    lambda v: rf'\g<1>{v["wit_bindgen_version"]}\g<2>',
                ),
                Rule(
                    rf'(`wasm-tools ){SEMANTIC_VERSION_TOKEN_PATTERN}(`)',
                    lambda v: rf'\g<1>{v["wasm_tools_version"]}\g<2>',
                ),
            ),
        ),
        FileSpec(
            "samples/multiplatform-app/plugins/cpp-component-service/README.md",
            (
                Rule(
                    rf'(`wit-bindgen cpp` ){SEMANTIC_VERSION_TOKEN_PATTERN}',
                    lambda v: rf'\g<1>{v["wit_bindgen_version"]}',
                ),
                Rule(
                    rf'(`wit-bindgen ){SEMANTIC_VERSION_TOKEN_PATTERN}(`)',
                    lambda v: rf'\g<1>{v["wit_bindgen_version"]}\g<2>',
                ),
                Rule(
                    rf'(`wasm-tools ){SEMANTIC_VERSION_TOKEN_PATTERN}(`)',
                    lambda v: rf'\g<1>{v["wasm_tools_version"]}\g<2>',
                ),
            ),
        ),
        FileSpec(
            "samples/multiplatform-app/plugins/kotlin-component-service/README.md",
            (
                Rule(
                    rf'(`wit-bindgen` ){SEMANTIC_VERSION_TOKEN_PATTERN}',
                    lambda v: rf'\g<1>{v["wit_bindgen_version"]}',
                ),
                Rule(
                    rf'(`wasm-tools` ){SEMANTIC_VERSION_TOKEN_PATTERN}',
                    lambda v: rf'\g<1>{v["wasm_tools_version"]}',
                ),
            ),
        ),
        FileSpec(
            "fumadocs/content/docs/(reference)/(plugin-development)/component-service.mdx",
            (
                Rule(
                    rf'(C\+\+ fixtures enforce `wit-bindgen ){SEMANTIC_VERSION_TOKEN_PATTERN}'
                    rf'(` and `wasm-tools ){SEMANTIC_VERSION_TOKEN_PATTERN}(` at CMake)',
                    lambda v: (
                        rf'\g<1>{v["wit_bindgen_version"]}'
                        rf'\g<2>{v["wasm_tools_version"]}\g<3>'
                    ),
                ),
            ),
        ),
        FileSpec(
            "fumadocs/content/docs/(reference)/(plugin-development)/component-service.zh.mdx",
            (
                Rule(
                    rf'(CMake configure 阶段严格检查 `wit-bindgen ){SEMANTIC_VERSION_TOKEN_PATTERN}'
                    rf'(` 和\s+`wasm-tools ){SEMANTIC_VERSION_TOKEN_PATTERN}(`)',
                    lambda v: (
                        rf'\g<1>{v["wit_bindgen_version"]}'
                        rf'\g<2>{v["wasm_tools_version"]}\g<3>'
                    ),
                ),
            ),
        ),
        FileSpec(
            "wasmline/src/jvmTest/kotlin/crow/wasmline/test/wasmtime/NativeWasmtimeIntegrationTest.kt",
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
            "samples/multiplatform-app/sample-apps/multiplatform/shared/src/desktopMain/Requirement.md",
            (
                Rule(r"JBR [0-9]+", lambda v: f"JBR {v['jbr_version']}"),
            ),
        ),
        FileSpec(
            "ROADMAP.md",
            (
                Rule(
                    r'(Wasmtime C-API integration \(Wasmline Wasmtime )[0-9A-Za-z.\-]+(\))',
                    lambda v: rf'\g<1>{v["wasmtime_version"]}\g<2>',
                ),
            ),
        ),
        FileSpec(
            "ROADMAP_zh.md",
            (
                Rule(
                    r'(Wasmtime C-API 集成（Wasmline Wasmtime )[0-9A-Za-z.\-]+(）)',
                    lambda v: rf'\g<1>{v["wasmtime_version"]}\g<2>',
                ),
            ),
        ),
    )
