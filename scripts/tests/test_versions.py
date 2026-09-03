#!/usr/bin/env python3
"""Regression tests for repository-wide version synchronization rules."""

from __future__ import annotations

import copy
import hashlib
import io
import json
import re
import stat
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path
from typing import Any, Mapping
from unittest import mock

PROJECT_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT_ROOT / "scripts" / "lib" / "python"))

from wasmline_tools import aot_compatibility, aot_metadata, toolchain_lock
from wasmline_tools import versions as sync_version


class LockedReleaseClient:
    """Reconstructs GitHub release responses from checked-in lock data."""

    def __init__(self, lock: Mapping[str, Any]) -> None:
        self.releases: dict[str, dict[str, Any]] = {}
        tools = lock["tools"]
        for tool in tools:
            assets = [
                {
                    "id": asset["assetId"],
                    "name": asset["archiveName"],
                    "size": asset["size"],
                    "updated_at": asset["updatedAt"],
                    "browser_download_url": asset["downloadUrl"],
                    "digest": "sha256:" + asset["sha256"],
                }
                for asset in tool["assets"]
            ]
            self.releases[tool["repository"]] = {
                "id": tool["releaseId"],
                "tag_name": tool["releaseTag"],
                "draft": False,
                "assets": assets,
            }

    def get_release(self, repository: str, tag: str) -> Mapping[str, Any]:
        release = copy.deepcopy(self.releases[repository])
        if release["tag_name"] != tag:
            raise AssertionError(f"Unexpected release tag for {repository}: {tag}")
        return release


class FixedAotMetadataResolver:
    """Returns deterministic metadata for one synthetic fork distribution."""

    def __init__(self, distribution: str) -> None:
        self.distribution = distribution
        self.requested: list[str] = []

    def resolve(self, distribution_version: str) -> aot_metadata.ResolvedAotDistribution:
        self.requested.append(distribution_version)
        if distribution_version != self.distribution:
            raise AssertionError(f"Unexpected AOT distribution: {distribution_version}")
        assets = []
        for build_host in aot_metadata.AOT_COMPILER_BUILD_HOSTS:
            extension = "zip" if build_host.endswith("windows") else "tar.gz"
            archive_format = "ZIP" if extension == "zip" else "TAR_GZ"
            asset_id = f"wasmtime-v{distribution_version}-{build_host}"
            archive_name = f"{asset_id}.{extension}"
            assets.append(
                {
                    "buildHost": build_host,
                    "distribution": "FULL",
                    "archiveFormat": archive_format,
                    "assetId": asset_id,
                    "archiveName": archive_name,
                    "downloadUrls": [
                        f"https://example.invalid/v{distribution_version}/{archive_name}"
                    ],
                    "archiveSha256": hashlib.sha256(archive_name.encode()).hexdigest(),
                    "archiveSize": 1024,
                    "executableRelativePath": (
                        f"{asset_id}/wasmtime.exe"
                        if build_host.endswith("windows")
                        else f"{asset_id}/wasmtime"
                    ),
                    "executableSha256": hashlib.sha256(asset_id.encode()).hexdigest(),
                }
            )
        return aot_metadata.ResolvedAotDistribution(
            source_revision="b" * 40,
            compiler_assets=tuple(assets),
        )


class SyncVersionTest(unittest.TestCase):
    """Checks that the manifest and all managed version targets stay aligned."""

    @staticmethod
    def render_managed_files(versions: dict[str, str]) -> dict[str, str]:
        """Applies all rules for each path in declaration order."""
        rendered: dict[str, str] = {}
        for spec in sync_version.file_specs():
            path = sync_version.PROJECT_ROOT / spec.path
            rendered.setdefault(spec.path, path.read_text(encoding="utf-8"))
            rendered[spec.path] = sync_version.apply_rules(rendered[spec.path], spec, versions)
        return rendered

    def test_all_managed_files_exist(self) -> None:
        """Every rule must point to a source or documentation file."""
        self.assertTrue(sync_version.MANIFEST_PATH.is_file())
        self.assertEqual(sync_version.PROJECT_ROOT / "versions.json", sync_version.MANIFEST_PATH)
        self.assertFalse((sync_version.PROJECT_ROOT / "scripts" / "versions.json").exists())
        self.assertTrue((sync_version.PROJECT_ROOT / "scripts/wasmline").is_file())
        for spec in sync_version.file_specs():
            self.assertTrue(
                (sync_version.PROJECT_ROOT / spec.path).is_file(),
                msg=f"Missing managed file: {spec.path}",
            )

    def test_active_wasmtime_versions_appear_only_in_managed_files(self) -> None:
        """The active runtime and release versions must not leak into independent fixtures."""
        versions = dict(sync_version.load_manifest()["versions"])
        active_version = versions["wasmtime_version"]
        result = subprocess.run(
            ["git", "grep", "-l", "-F", active_version, "--", "."],
            cwd=sync_version.PROJECT_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertIn(result.returncode, (0, 1), msg=result.stderr)

        versions["wasmtime_version"] = "99.8.7"
        versions["wasmtime_release_version"] = "99.8.7.6"
        rendered = self.render_managed_files(versions)
        managed_paths = {
            path
            for path, content in rendered.items()
            if content != (sync_version.PROJECT_ROOT / path).read_text(encoding="utf-8")
        }
        manifest_path = sync_version.MANIFEST_PATH.relative_to(sync_version.PROJECT_ROOT).as_posix()
        lock_path = toolchain_lock.LOCK_PATH.relative_to(sync_version.PROJECT_ROOT).as_posix()
        aot_lock_path = aot_compatibility.AOT_LOCK_PATH.relative_to(sync_version.PROJECT_ROOT).as_posix()
        public_catalog_path = aot_compatibility.PUBLIC_CATALOG_PATH.relative_to(
            sync_version.PROJECT_ROOT
        ).as_posix()
        packaged_public_catalog_path = aot_compatibility.PACKAGED_PUBLIC_CATALOG_PATH.relative_to(
            sync_version.PROJECT_ROOT
        ).as_posix()
        aot_compatibility_reference_path = ".agents/skills/wasmline/references/aot-compatibility.md"
        self.assertNotIn(aot_compatibility_reference_path, rendered)
        allowed_paths = managed_paths | {
            manifest_path,
            lock_path,
            aot_lock_path,
            public_catalog_path,
            packaged_public_catalog_path,
            aot_compatibility_reference_path,
        }
        unexpected_paths = sorted(set(result.stdout.splitlines()) - allowed_paths)

        self.assertEqual(
            [],
            unexpected_paths,
            msg=(
                "The active Wasmtime version is duplicated outside version-managed files: "
                + ", ".join(unexpected_paths)
            ),
        )

    def test_rules_render_all_project_versions(self) -> None:
        """Synthetic values must reach project, sample, CLI, and docs targets."""
        versions = {
            "wasmline_version": "9.8.7",
            "sample_plugin_version": "6.5.4",
            "wasmtime_version": "99.8.7",
            "wasmtime_release_version": "99.8.7.6",
            "wasm_tools_version": "8.7.6",
            "wit_bindgen_version": "7.6.5",
            "kotlin_version": "9.9.9",
            "ktlint_version": "7.6.5",
            "dokka_version": "8.8.8",
            "kotlin_min_version": "9.8.0-RC1",
            "agp_version": "9.9.9",
            "zig_version": "9.9.9",
            "jbr_version": "99",
        }

        rendered = self.render_managed_files(versions)
        rendered_paths = {
            path
            for path, updated in rendered.items()
            if updated != (sync_version.PROJECT_ROOT / path).read_text(encoding="utf-8")
        }

        expected_paths = {
            "wasmline-multiplatform/wasmline-plugin-test/build.gradle.kts",
            "wasmline-multiplatform/gradle/gradle-daemon-jvm.properties",
            "wasmline-samples/kotlin/sample-plugin/build.gradle.kts",
            "wasmline-multiplatform/gradle/libs.versions.toml",
            "docs/content/docs/installation.mdx",
            "docs/content/docs/installation.zh.mdx",
            "docs/content/docs/(reference)/(repository-development)/building-from-source.mdx",
            "docs/content/docs/(reference)/(repository-development)/building-from-source.zh.mdx",
            "docs/content/docs/(reference)/(runtime)/architecture.mdx",
            "docs/content/docs/(reference)/(runtime)/architecture.zh.mdx",
            "docs/content/docs/(reference)/(plugin-development)/component-service.mdx",
            "docs/content/docs/(reference)/(plugin-development)/component-service.zh.mdx",
            "wasmline-samples/kotlin/run-ios.sh",
            "wasmline-multiplatform/wasmline-build-logic/app/src/main/kotlin/wasmline.engine.gradle.kts",
            ".github/workflows/release.yml",
        }
        self.assertTrue(expected_paths.issubset(rendered_paths))

    def test_manifest_has_all_required_keys(self) -> None:
        """The manifest must contain every version consumed by the rules."""
        manifest = sync_version.load_manifest()["versions"]
        for key in sync_version.REQUIRED_KEYS:
            self.assertIn(key, manifest)

    def test_synthetic_values_update_newly_managed_targets(self) -> None:
        """New runtime, sample, guide, and synchronizer targets use the right keys."""
        versions = {
            "wasmline_version": "9.8.7",
            "sample_plugin_version": "6.5.4",
            "wasmtime_version": "99.8.7",
            "wasmtime_release_version": "99.8.7.6",
            "wasm_tools_version": "8.7.6",
            "wit_bindgen_version": "7.6.5",
            "kotlin_version": "9.9.9",
            "ktlint_version": "7.6.5",
            "dokka_version": "8.8.8",
            "kotlin_min_version": "9.8.0-RC1",
            "agp_version": "9.9.9",
            "zig_version": "9.9.9",
            "jbr_version": "99",
        }

        expected_fragments = {
            "wasmline-multiplatform/gradle/libs.versions.toml": 'dokka = "8.8.8"',
            ".agents/skills/wasmline/references/development-guide.md": "The pre-check also reports Zig 9.9.9",
            "wasmline-multiplatform/wasmline-build-logic/app/src/main/kotlin/wasmline.engine.gradle.kts":
                "JavaLanguageVersion.of(99)",
            "wasmline-samples/kotlin/run-ios.sh": "v99.8.7.6",
            "wasmline-multiplatform/gradle/gradle-daemon-jvm.properties": "toolchainVersion=99",
            "wasmline-samples/kotlin/sample-apps/multiplatform/desktopApp/build.gradle.kts":
                "JavaLanguageVersion.of(99)",
            "wasmline-samples/kotlin/sample-apps/multiplatform/shared/src/desktopMain/Requirement.md":
                "JBR 99",
            "wasmline-multiplatform/wasmline/src/jvmTest/kotlin/crow/wasmline/test/wasmtime/NativeWasmtimeIntegrationTest.kt":
                'assertEquals("99.8.7", capabilities.wasmtimeVersion)',
            "wasmline-multiplatform/wasmline-cli/src/test/kotlin/crow/wasmline/cli/ComponentCliIntegrationTest.kt":
                'File(compileRoot, "cli-compile-6.5.4")',
            "wasmline-samples/kotlin/sample-component-fixture/README.md":
                "wasmline/output/crow.wasmline.component.fixture-6.5.4/",
            "ROADMAP.md": "Wasmtime C-API integration (v99.8.7)",
            "ROADMAP_zh.md": "Wasmtime C-API 集成（v99.8.7）",
        }

        rendered = self.render_managed_files(versions)
        for path, fragment in expected_fragments.items():
            self.assertIn(fragment, rendered[path], msg=f"Missing rendered value in {path}")

    def test_sample_versions_update_manifests_selectors_and_output_paths(self) -> None:
        """Sample manifests and consumers stay synchronized."""
        versions = {
            "wasmline_version": "9.8.7",
            "sample_plugin_version": "6.5.4",
            "wasmtime_version": "99.8.7",
            "wasmtime_release_version": "99.8.7.6",
            "wasm_tools_version": "8.7.6",
            "wit_bindgen_version": "7.6.5",
            "kotlin_version": "9.9.9",
            "ktlint_version": "7.6.5",
            "dokka_version": "8.8.8",
            "kotlin_min_version": "9.8.0-RC1",
            "agp_version": "9.9.9",
            "zig_version": "9.9.9",
            "jbr_version": "99",
        }
        rendered = self.render_managed_files(versions)

        manifest_paths = (
            "wasmline-samples/kotlin/sample-plugin/build.gradle.kts",
            "wasmline-samples/kotlin/sample-raw-export-plugin/build.gradle.kts",
            "wasmline-samples/kotlin/sample-component-plugin/build.gradle.kts",
            "wasmline-samples/kotlin/sample-component-export-plugin/build.gradle.kts",
            "wasmline-samples/kotlin/sample-component-fixture/build.gradle.kts",
        )
        for path in manifest_paths:
            self.assertIn('version = "6.5.4"', rendered[path], msg=path)

        output_paths = (
            "wasmline-samples/kotlin/sample-apps/android/build.gradle.kts",
            "wasmline-samples/kotlin/sample-apps/application/build.gradle.kts",
            "wasmline-samples/kotlin/sample-apps/multiplatform/androidApp/build.gradle.kts",
            "wasmline-samples/kotlin/sample-apps/multiplatform/desktopApp/build.gradle.kts",
            "wasmline-samples/kotlin/sample-apps/multiplatform/webApp/build.gradle.kts",
            "wasmline-samples/kotlin/README.md",
            "wasmline-samples/kotlin/sample-apps/README.md",
            "wasmline-samples/kotlin/sample-component-fixture/README.md",
        )
        for path in output_paths:
            self.assertIn("wasmline/output/", rendered[path], msg=path)
            self.assertIn("-6.5.4", rendered[path], msg=path)

    def test_rules_compose_for_files_with_multiple_version_families(self) -> None:
        """JBR and project version rules must update the same file together."""
        versions = {
            "wasmline_version": "9.8.7",
            "sample_plugin_version": "6.5.4",
            "wasmtime_version": "99.8.7",
            "wasmtime_release_version": "99.8.7.6",
            "wasm_tools_version": "8.7.6",
            "wit_bindgen_version": "7.6.5",
            "kotlin_version": "9.9.9",
            "ktlint_version": "7.6.5",
            "dokka_version": "8.8.8",
            "kotlin_min_version": "9.8.0-RC1",
            "agp_version": "9.9.9",
            "zig_version": "9.9.9",
            "jbr_version": "99",
        }
        rendered = self.render_managed_files(versions)

        plugin_test = rendered["wasmline-multiplatform/wasmline-plugin-test/build.gradle.kts"]
        self.assertIn("JavaLanguageVersion.of(99)", plugin_test)
        self.assertIn('val testPluginVersion = "6.5.4"', plugin_test)
        self.assertIn(
            'version = "6.5.4"',
            rendered["wasmline-samples/kotlin/sample-plugin/build.gradle.kts"],
        )
        self.assertIn(
            "wasmline-engine-pulley-jvm-9.8.7.jar",
            rendered["wasmline-multiplatform/docs/native-library-loading.md"],
        )

    def test_toolchain_versions_update_external_component_tooling(self) -> None:
        """Toolchain versions must reach external build checks and guidance."""
        versions = {
            "wasmline_version": "9.8.7",
            "sample_plugin_version": "6.5.4",
            "wasmtime_version": "99.8.7",
            "wasmtime_release_version": "99.8.7.6",
            "wasm_tools_version": "8.7.6",
            "wit_bindgen_version": "7.6.5",
            "kotlin_version": "9.9.9",
            "ktlint_version": "7.6.5",
            "dokka_version": "8.8.8",
            "kotlin_min_version": "9.8.0-RC1",
            "agp_version": "9.9.9",
            "zig_version": "9.9.9",
            "jbr_version": "99",
        }
        rendered = self.render_managed_files(versions)

        for path in (
            "wasmline-samples/c/sample-component-plugin/CMakeLists.txt",
            "wasmline-samples/cpp/sample-component-plugin/CMakeLists.txt",
        ):
            self.assertIn("wit-bindgen-cli 7.6.5", rendered[path], msg=path)
            self.assertIn("wasm-tools 8.7.6", rendered[path], msg=path)

    def test_component_tool_versions_do_not_rewrite_kotlin_sources(self) -> None:
        """Component CLI tool upgrades must not modify Kotlin source files."""
        versions = dict(sync_version.load_manifest()["versions"])
        versions["wasm_tools_version"] = "8.7.6"
        versions["wit_bindgen_version"] = "7.6.5"

        rendered = self.render_managed_files(versions)
        changed_kotlin = sorted(
            path
            for path, content in rendered.items()
            if path.endswith(".kt")
            and content != (sync_version.PROJECT_ROOT / path).read_text(encoding="utf-8")
        )

        self.assertEqual([], changed_kotlin)

    def test_checked_in_toolchain_lock_matches_manifest(self) -> None:
        """The packaged lock must derive from the current manifest versions."""
        versions = sync_version.load_manifest()["versions"]
        lock = toolchain_lock.load_lock()
        toolchain_lock.validate_lock(lock, versions)

    def test_manifest_contains_only_scalar_versions(self) -> None:
        """The version manifest must not contain the AOT catalog database."""
        manifest = sync_version.load_manifest()
        self.assertEqual({"versions"}, set(manifest))
        self.assertNotIn("aotCompatibility", manifest)

    def test_checked_in_aot_compatibility_lock_matches_public_catalog(self) -> None:
        """The generated AOT lock must exactly match the standalone catalog."""
        source = aot_compatibility.load_public_catalog()
        versions = sync_version.load_manifest()["versions"]
        expected = aot_compatibility.render_lock(source, versions)

        self.assertEqual(expected, aot_compatibility.load_lock())

    def test_aot_sync_and_check_use_the_standard_console_labels(self) -> None:
        """AOT maintenance commands use Console output and distinguish check mode."""
        with mock.patch.object(sys, "stdout", new_callable=io.StringIO) as stdout:
            self.assertEqual(0, aot_compatibility.sync_aot())
            self.assertEqual(0, aot_compatibility.check_aot())

        self.assertEqual(
            "OK      AOT sync    Generated files are synchronized.\n"
            "OK      AOT check    Generated files are synchronized.\n",
            stdout.getvalue(),
        )

    def test_public_aot_catalog_matches_packaged_resource_and_versions(self) -> None:
        """The public catalog and packaged resource must be byte-identical."""
        source = aot_compatibility.load_public_catalog()
        versions = sync_version.load_manifest()["versions"]
        expected = aot_compatibility.render_public_catalog_text(source, versions)
        self.assertEqual(expected.encode("utf-8"), aot_compatibility.PUBLIC_CATALOG_PATH.read_bytes())
        self.assertEqual(
            aot_compatibility.PUBLIC_CATALOG_PATH.read_bytes(),
            aot_compatibility.PACKAGED_PUBLIC_CATALOG_PATH.read_bytes(),
        )

    def test_public_catalog_excludes_internal_profile_bindings(self) -> None:
        """The public catalog exposes generations without compiler or profile identities."""
        catalog = json.loads(aot_compatibility.PUBLIC_CATALOG_PATH.read_text(encoding="utf-8"))
        self.assertNotIn("profiles", catalog)
        self.assertNotIn("profileIdsByBackend", catalog["ranges"][0])

    def test_aot_release_history_is_append_only(self) -> None:
        """Published range records cannot be changed or removed during synchronization."""
        previous = aot_compatibility.load_lock()
        generated = copy.deepcopy(previous)
        generated["releaseCatalog"]["ranges"][0]["wasmtimeDistributionVersion"] = "49.0.0.1"

        with self.assertRaisesRegex(
            aot_compatibility.AotCompatibilityError,
            "cannot be modified",
        ):
            aot_compatibility.validate_append_only(previous, generated)

        generated = copy.deepcopy(previous)
        generated["releaseCatalog"]["currentWasmlineVersion"] = "0.9.0"
        with self.assertRaisesRegex(
            aot_compatibility.AotCompatibilityError,
            "current Wasmline version cannot move backward",
        ):
            aot_compatibility.validate_append_only(previous, generated)

        generated = copy.deepcopy(previous)
        generated["releaseCatalog"]["ranges"][0]["aotGeneration"] = 2
        with self.assertRaisesRegex(
            aot_compatibility.AotCompatibilityError,
            "cannot be modified",
        ):
            aot_compatibility.validate_append_only(previous, generated)

    def test_aot_source_rejects_generation_gaps_and_unknown_backends(self) -> None:
        """Generation numbering and backend names remain structurally complete."""
        source = copy.deepcopy(aot_compatibility.load_public_catalog())
        source["ranges"][0]["aotGeneration"] = 2
        with self.assertRaisesRegex(aot_compatibility.AotCompatibilityError, "without gaps"):
            aot_compatibility.validate_source(source, sync_version.load_manifest()["versions"])

        source = copy.deepcopy(aot_compatibility.load_public_catalog())
        source["ranges"][0]["changedBackends"].append("EXTRA")
        with self.assertRaisesRegex(aot_compatibility.AotCompatibilityError, "unknown backend"):
            aot_compatibility.validate_source(source, sync_version.load_manifest()["versions"])

        source = copy.deepcopy(aot_compatibility.load_public_catalog())
        source["ranges"][0]["changedBackends"].reverse()
        with self.assertRaisesRegex(aot_compatibility.AotCompatibilityError, "canonical backend order"):
            aot_compatibility.validate_source(source, sync_version.load_manifest()["versions"])

    def test_aot_sync_resolves_metadata_for_a_new_distribution(self) -> None:
        """A new generation obtains detailed metadata without expanding versions.json."""
        source = copy.deepcopy(aot_compatibility.load_public_catalog())
        source["currentWasmlineVersion"] = "1.1.0"
        source["ranges"].append(
            {
                "fromWasmlineVersion": "1.1.0",
                "aotGeneration": 2,
                "wasmtimeDistributionVersion": "49.0.0.1",
                "changedBackends": ["CRANELIFT", "PULLEY"],
            }
        )
        versions = dict(sync_version.load_manifest()["versions"])
        versions["wasmline_version"] = "1.1.0"
        versions["wasmtime_version"] = "49.0.0"
        versions["wasmtime_release_version"] = "49.0.0.1"
        previous = aot_compatibility.load_lock()
        resolver = FixedAotMetadataResolver("49.0.0.1")

        metadata = aot_compatibility.prepare_metadata_for_catalog(source, previous, resolver)
        generated = aot_compatibility.render_lock(source, versions, metadata)
        aot_compatibility.validate_lock_against_source(generated, source, versions)
        aot_compatibility.validate_append_only(previous, generated)

        self.assertEqual(["49.0.0.1"], resolver.requested)
        self.assertEqual(4, len(generated["profiles"]))
        self.assertEqual(10, len(generated["compilerAssets"]))
        self.assertEqual(20, len(generated["profileCompilerBindings"]))
        self.assertEqual(2, generated["releaseCatalog"]["ranges"][-1]["aotGeneration"])

    def test_aot_sync_reuses_one_backend_for_a_new_generation(self) -> None:
        """A generation can replace one backend profile without duplicating the other."""
        source = copy.deepcopy(aot_compatibility.load_public_catalog())
        source["currentWasmlineVersion"] = "1.1.0"
        current_distribution = source["ranges"][-1]["wasmtimeDistributionVersion"]
        source["ranges"].append(
            {
                "fromWasmlineVersion": "1.1.0",
                "aotGeneration": 2,
                "wasmtimeDistributionVersion": current_distribution,
                "changedBackends": ["CRANELIFT"],
            }
        )
        versions = dict(sync_version.load_manifest()["versions"])
        versions["wasmline_version"] = "1.1.0"
        previous = aot_compatibility.load_lock()
        previous_range = previous["releaseCatalog"]["ranges"][-1]

        with mock.patch.dict(
            aot_compatibility.CURRENT_ENGINE_CONFIGURATION_PROFILES,
            {
                "CRANELIFT": (
                    aot_compatibility.CURRENT_ENGINE_CONFIGURATION_PROFILES["CRANELIFT"]
                    + ";synthetic-profile-change=y"
                )
            },
        ):
            metadata = aot_compatibility.prepare_metadata_for_catalog(
                source,
                previous,
                resolver=None,
            )
            generated = aot_compatibility.render_lock(source, versions, metadata)
            aot_compatibility.validate_lock_against_source(generated, source, versions)
            aot_compatibility.validate_append_only(previous, generated)

        current_range = generated["releaseCatalog"]["ranges"][-1]
        self.assertNotEqual(
            previous_range["profileIdsByBackend"]["CRANELIFT"],
            current_range["profileIdsByBackend"]["CRANELIFT"],
        )
        self.assertEqual(
            previous_range["profileIdsByBackend"]["PULLEY"],
            current_range["profileIdsByBackend"]["PULLEY"],
        )
        self.assertEqual(3, len(generated["profiles"]))
        self.assertEqual(5, len(generated["compilerAssets"]))
        self.assertEqual(15, len(generated["profileCompilerBindings"]))

    def test_aot_sync_rejects_an_unmarked_backend_contract_change(self) -> None:
        """A backend contract change must be declared by changedBackends."""
        source = copy.deepcopy(aot_compatibility.load_public_catalog())
        source["currentWasmlineVersion"] = "1.1.0"
        current_distribution = source["ranges"][-1]["wasmtimeDistributionVersion"]
        source["ranges"].append(
            {
                "fromWasmlineVersion": "1.1.0",
                "aotGeneration": 2,
                "wasmtimeDistributionVersion": current_distribution,
                "changedBackends": ["PULLEY"],
            }
        )

        with (
            mock.patch.dict(
                aot_compatibility.CURRENT_ENGINE_CONFIGURATION_PROFILES,
                {
                    "CRANELIFT": (
                        aot_compatibility.CURRENT_ENGINE_CONFIGURATION_PROFILES["CRANELIFT"]
                        + ";synthetic-profile-change=y"
                    )
                },
            ),
            self.assertRaisesRegex(
                aot_compatibility.AotCompatibilityError,
                "does not mark that backend as changed",
            ),
        ):
            aot_compatibility.prepare_metadata_for_catalog(
                source,
                aot_compatibility.load_lock(),
                resolver=None,
            )

    def test_aot_sync_rejects_a_declared_backend_without_identity_change(self) -> None:
        """changedBackends cannot create a generation when its profile ID is unchanged."""
        source = copy.deepcopy(aot_compatibility.load_public_catalog())
        source["currentWasmlineVersion"] = "1.1.0"
        current_distribution = source["ranges"][-1]["wasmtimeDistributionVersion"]
        source["ranges"].append(
            {
                "fromWasmlineVersion": "1.1.0",
                "aotGeneration": 2,
                "wasmtimeDistributionVersion": current_distribution,
                "changedBackends": ["CRANELIFT"],
            }
        )
        versions = dict(sync_version.load_manifest()["versions"])
        versions["wasmline_version"] = "1.1.0"
        metadata = aot_compatibility.prepare_metadata_for_catalog(
            source,
            aot_compatibility.load_lock(),
            resolver=None,
        )

        with self.assertRaisesRegex(
            aot_compatibility.AotCompatibilityError,
            "changedBackends does not match detailed profile metadata",
        ):
            aot_compatibility.render_lock(source, versions, metadata)

    def test_github_aot_metadata_resolver_verifies_full_archives(self) -> None:
        """Fork release metadata maps five full archives to verified executables."""
        distribution = "49.0.0.1"
        tag = f"v{distribution}"
        checksum_url = f"https://github.com/crowforkotlin/wasmtime/releases/download/{tag}/SHA256SUMS"
        response_bytes: dict[str, bytes] = {}
        executable_bytes: dict[str, bytes] = {}
        archive_bytes: dict[str, bytes] = {}
        release_assets: list[dict[str, Any]] = [
            {
                "name": "SHA256SUMS",
                "browser_download_url": checksum_url,
                "size": 1,
            }
        ]

        for build_host in aot_metadata.AOT_COMPILER_BUILD_HOSTS:
            asset_id = f"wasmtime-v{distribution}-{build_host}"
            executable_name = "wasmtime.exe" if build_host.endswith("windows") else "wasmtime"
            executable_path = f"{asset_id}/{executable_name}"
            executable = f"verified executable for {build_host}".encode()
            buffer = io.BytesIO()
            if build_host.endswith("windows"):
                with zipfile.ZipFile(buffer, mode="w", compression=zipfile.ZIP_DEFLATED) as archive:
                    archive.writestr(executable_path, executable)
                extension = "zip"
            else:
                with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
                    member = tarfile.TarInfo(executable_path)
                    member.size = len(executable)
                    archive.addfile(member, io.BytesIO(executable))
                extension = "tar.gz"
            archive_name = f"{asset_id}.{extension}"
            download_url = (
                f"https://github.com/crowforkotlin/wasmtime/releases/download/{tag}/{archive_name}"
            )
            content = buffer.getvalue()
            executable_bytes[build_host] = executable
            archive_bytes[build_host] = content
            response_bytes[download_url] = content
            release_assets.append(
                {
                    "name": archive_name,
                    "browser_download_url": download_url,
                    "size": len(content),
                }
            )

        checksums = "".join(
            f"{hashlib.sha256(archive_bytes[host]).hexdigest()}  "
            f"wasmtime-v{distribution}-{host}.{'zip' if host.endswith('windows') else 'tar.gz'}\n"
            for host in aot_metadata.AOT_COMPILER_BUILD_HOSTS
        )
        response_bytes[checksum_url] = checksums.encode()
        release_url = f"{aot_metadata.WASMTIME_API_ROOT}/releases/tags/{tag}"
        commit_url = f"{aot_metadata.WASMTIME_API_ROOT}/commits/{tag}"
        response_bytes[release_url] = json.dumps(
            {
                "tag_name": tag,
                "draft": False,
                "prerelease": False,
                "assets": release_assets,
            }
        ).encode()
        response_bytes[commit_url] = json.dumps({"sha": "c" * 40}).encode()
        resolver = aot_metadata.GitHubAotMetadataResolver(jobs=3, token="test-token")

        with mock.patch.object(
            resolver,
            "_open",
            side_effect=lambda url: io.BytesIO(response_bytes[url]),
        ):
            resolved = resolver.resolve(distribution)

        self.assertEqual("c" * 40, resolved.source_revision)
        self.assertEqual(
            set(aot_metadata.AOT_COMPILER_BUILD_HOSTS),
            {asset["buildHost"] for asset in resolved.compiler_assets},
        )
        for asset in resolved.compiler_assets:
            build_host = asset["buildHost"]
            self.assertEqual(
                hashlib.sha256(archive_bytes[build_host]).hexdigest(),
                asset["archiveSha256"],
            )
            self.assertEqual(
                hashlib.sha256(executable_bytes[build_host]).hexdigest(),
                asset["executableSha256"],
            )
            self.assertEqual("FULL", asset["distribution"])

    def test_github_aot_metadata_token_is_limited_to_the_api_origin(self) -> None:
        """GitHub credentials must not be forwarded to release asset URLs."""
        resolver = aot_metadata.GitHubAotMetadataResolver(token="test-token")
        opener = mock.Mock()
        opener.open.return_value = object()

        with mock.patch.object(aot_metadata, "build_opener", return_value=opener):
            resolver._open(f"{aot_metadata.WASMTIME_API_ROOT}/commits/v49.0.0.1")
            api_request = opener.open.call_args.args[0]
            self.assertEqual("Bearer test-token", api_request.get_header("Authorization"))

            resolver._open(
                "https://github.com/crowforkotlin/wasmtime/releases/download/"
                "v49.0.0.1/wasmtime-v49.0.0.1-x86_64-linux.tar.gz"
            )
            asset_request = opener.open.call_args.args[0]
            self.assertIsNone(asset_request.get_header("Authorization"))

    def test_github_aot_metadata_rejects_malformed_asset_urls(self) -> None:
        """Malformed release asset URLs produce the stable resolver error type."""
        with self.assertRaises(aot_metadata.AotMetadataResolutionError):
            aot_metadata.GitHubAotMetadataResolver._required_https_url(
                {"browser_download_url": "https://["},
                "invalid-asset",
            )

    def test_aot_check_does_not_resolve_missing_metadata(self) -> None:
        """Offline validation reports missing generated metadata without network access."""
        source = copy.deepcopy(aot_compatibility.load_public_catalog())
        source["currentWasmlineVersion"] = "1.1.0"
        source["ranges"].append(
            {
                "fromWasmlineVersion": "1.1.0",
                "aotGeneration": 2,
                "wasmtimeDistributionVersion": "49.0.0.1",
                "changedBackends": ["CRANELIFT", "PULLEY"],
            }
        )

        with self.assertRaisesRegex(aot_compatibility.AotCompatibilityError, "aot sync"):
            aot_compatibility.prepare_metadata_for_catalog(
                source,
                aot_compatibility.load_lock(),
                resolver=None,
            )

    def test_python_profile_descriptor_matches_frozen_kotlin_options(self) -> None:
        """Repository synchronization and the AOT compiler use one profile descriptor."""
        kotlin_path = (
            PROJECT_ROOT
            / "wasmline-multiplatform/wasmline-plugin-core/src/main/kotlin"
            / "crow/wasmline/plugin/core/aot/WasmlineAotCompileOptions.kt"
        )
        kotlin = kotlin_path.read_text(encoding="utf-8")
        match = re.search(
            r"const val FROZEN_DESCRIPTOR: String =(?P<body>.*?)\n\s*}\n}",
            kotlin,
            flags=re.DOTALL,
        )
        self.assertIsNotNone(match)
        descriptor = "".join(re.findall(r'"([^"]*)"', match.group("body")))

        self.assertEqual(
            {descriptor},
            set(aot_compatibility.CURRENT_ENGINE_CONFIGURATION_PROFILES.values()),
        )
        self.assertIn(
            f"val schemaVersion: Int = {aot_compatibility.CURRENT_COMPILE_PROFILE_SCHEMA_VERSION}",
            kotlin,
        )

    def test_native_build_identity_uses_format_stable_profile_macros(self) -> None:
        """Generated profile macros must remain stable under clang-format."""
        source = aot_compatibility.load_public_catalog()
        versions = sync_version.load_manifest()["versions"]
        rendered = aot_compatibility.render_native_build_identity(source, versions)
        defaults = aot_compatibility.load_lock()["currentDefaultProfileIdsByBackend"]

        for backend in aot_compatibility.BACKENDS:
            self.assertIn(
                f'#define WASMLINE_{backend}_AOT_COMPATIBILITY_PROFILE_ID "{defaults[backend]}"',
                rendered,
            )

    def test_aot_catalog_contains_only_current_distribution(self) -> None:
        """The initial catalog contains only the active Wasmtime fork distribution."""
        source = aot_compatibility.load_public_catalog()
        lock = aot_compatibility.load_lock()
        active_distribution = "48" + ".0.1.1"
        self.assertEqual({active_distribution}, {item["wasmtimeDistributionVersion"] for item in source["ranges"]})
        self.assertEqual({active_distribution}, {item["wasmtimeDistributionVersion"] for item in lock["profiles"]})
        self.assertEqual(2, len(lock["profiles"]))
        self.assertEqual(5, len(lock["compilerAssets"]))
        self.assertEqual(10, len(lock["profileCompilerBindings"]))

    def test_aot_catalog_rejects_runtime_only_compiler_distribution(self) -> None:
        """AOT catalog assets must contain the Wasmtime compile command."""
        source = aot_compatibility.load_public_catalog()
        versions = sync_version.load_manifest()["versions"]
        generated = aot_compatibility.render_lock(source, versions)
        generated["compilerAssets"][0]["distribution"] = "MINIMAL"

        with self.assertRaisesRegex(
            aot_compatibility.AotCompatibilityError,
            "FULL Wasmtime distribution",
        ):
            aot_compatibility.validate_lock_against_source(generated, source, versions)

    def test_aot_catalog_rejects_historical_profile_mutation(self) -> None:
        """Synchronization must not rewrite a profile that appeared in an earlier lock."""
        previous = aot_compatibility.load_lock()
        generated = copy.deepcopy(previous)
        generated["profiles"][0]["wasmtimeSourceRevision"] = "0" * 40

        with self.assertRaisesRegex(
            aot_compatibility.AotCompatibilityError,
            "cannot be modified",
        ):
            aot_compatibility.validate_append_only(previous, generated)

    def test_aot_catalog_allows_new_records_and_additional_mirrors(self) -> None:
        """Append-only updates may add records and equivalent download mirrors."""
        previous = aot_compatibility.load_lock()
        generated = copy.deepcopy(previous)
        generated["compilerAssets"][0]["downloadUrls"].append(
            "https://mirror.example.invalid/wasmtime-compiler"
        )
        generated["profiles"].append(
            {"id": "sha256:" + "f" * 64, "artifactBackend": "PULLEY"}
        )

        aot_compatibility.validate_append_only(previous, generated)

    def test_toolchain_lock_generation_is_deterministic(self) -> None:
        """Equivalent release metadata must reproduce the checked-in lock."""
        versions = sync_version.load_manifest()["versions"]
        lock = toolchain_lock.load_lock()
        client = LockedReleaseClient(lock)

        self.assertEqual(lock, toolchain_lock.generate_lock(versions, client))

    def test_upstream_verification_rejects_changed_digest(self) -> None:
        """An upstream digest change must not be accepted automatically."""
        versions = sync_version.load_manifest()["versions"]
        lock = toolchain_lock.load_lock()
        client = LockedReleaseClient(lock)
        client.releases["crowforkotlin/wit-bindgen"]["assets"][0]["digest"] = (
            "sha256:" + "0" * 64
        )

        with self.assertRaises(toolchain_lock.ToolchainLockError):
            toolchain_lock.verify_upstream(lock, versions, client)

    def test_manual_toolchain_version_change_refreshes_lock(self) -> None:
        """Normal synchronization must refresh a lock that trails the manifest."""
        versions = dict(sync_version.load_manifest()["versions"])
        versions["wasmtime_version"] = "49.0.0"
        refreshed_lock = {"refreshed": True}

        with mock.patch.object(
            toolchain_lock,
            "generate_lock",
            return_value=refreshed_lock,
        ) as generate_lock:
            result = sync_version.load_or_refresh_toolchain_lock(
                versions,
                set(),
                allow_refresh=True,
            )

        self.assertIs(refreshed_lock, result)
        generate_lock.assert_called_once_with(versions)

    def test_check_mode_rejects_stale_toolchain_lock(self) -> None:
        """Check mode must report version drift without network access."""
        versions = dict(sync_version.load_manifest()["versions"])
        versions["wasmtime_version"] = "49.0.0"

        with mock.patch.object(toolchain_lock, "generate_lock") as generate_lock:
            with self.assertRaises(toolchain_lock.ToolchainLockVersionMismatchError):
                sync_version.load_or_refresh_toolchain_lock(
                    versions,
                    set(),
                    allow_refresh=False,
                )

        generate_lock.assert_not_called()

    def test_invalid_stale_toolchain_lock_is_not_replaced(self) -> None:
        """Automatic refresh must not conceal invalid checked-in metadata."""
        versions = dict(sync_version.load_manifest()["versions"])
        versions["wasmtime_version"] = "49.0.0"
        invalid_lock = copy.deepcopy(toolchain_lock.load_lock())
        invalid_lock["tools"][0]["assets"][0]["sha256"] = "invalid"

        with (
            mock.patch.object(toolchain_lock, "load_lock", return_value=invalid_lock),
            mock.patch.object(toolchain_lock, "generate_lock") as generate_lock,
            self.assertRaises(toolchain_lock.ToolchainLockError),
        ):
            sync_version.load_or_refresh_toolchain_lock(
                versions,
                set(),
                allow_refresh=True,
            )

        generate_lock.assert_not_called()

    def test_parse_updates_rejects_unknown_keys(self) -> None:
        """Typos in --set must fail instead of creating an unsynchronized key."""
        with self.assertRaises(SystemExit):
            sync_version.parse_updates(["wasmline_verison=1.2.3"])

    def test_parse_updates_rejects_invalid_version_shapes(self) -> None:
        """Malformed values must not create a partially synchronized manifest."""
        with self.assertRaises(SystemExit):
            sync_version.parse_updates(["wasmtime_version=49.0"])
        with self.assertRaises(SystemExit):
            sync_version.parse_updates(["jbr_version=21.0"])
        with self.assertRaises(SystemExit):
            sync_version.parse_updates(["wasmtime_version=49.10.2"])
        with self.assertRaises(SystemExit):
            sync_version.parse_updates(["wasmtime_version=49.0.0.12"])
        with self.assertRaises(SystemExit):
            sync_version.parse_updates(["wasmtime_release_version=49.0.0"])
        with self.assertRaises(SystemExit):
            sync_version.parse_updates(["wasmtime_release_version=49.0.0.0"])
        with self.assertRaises(SystemExit):
            sync_version.parse_updates(["ktlint_version=1.8.0-RC1"])

    def test_wasmtime_release_must_match_runtime_version(self) -> None:
        versions = dict(sync_version.load_manifest()["versions"])
        versions["wasmtime_release_version"] = "49.0.0.1"
        with self.assertRaisesRegex(SystemExit, "must use wasmtime_version"):
            sync_version.validate_versions(versions)

    def test_parse_updates_accepts_prerelease_versions(self) -> None:
        """Kotlin prerelease versions used by the repository remain valid."""
        updates = sync_version.parse_updates(["kotlin_min_version=2.3.0-RC2"])
        self.assertEqual("2.3.0-RC2", updates["kotlin_min_version"])

    def test_parse_latest_ktlint_release_accepts_only_stable_tags(self) -> None:
        """Only stable semantic release tags may become the formatter pin."""
        self.assertEqual(
            "1.8.0",
            sync_version.parse_latest_ktlint_release(
                {"tag_name": "1.8.0", "draft": False, "prerelease": False}
            ),
        )
        for payload in (
            {"tag_name": "1.8.1-RC1", "draft": False, "prerelease": True},
            {"tag_name": "release-1.8.1", "draft": False, "prerelease": False},
            {"tag_name": "1.8.1"},
            {"draft": False, "prerelease": False},
        ):
            with self.assertRaises(sync_version.KtlintReleaseError):
                sync_version.parse_latest_ktlint_release(payload)

    def test_check_ktlint_latest_reports_an_available_upgrade(self) -> None:
        """The check command reports a newer release and returns failure for CI."""
        with (
            mock.patch.object(sync_version, "latest_ktlint_version", return_value="1.8.1"),
            mock.patch.object(sys, "stdout", new_callable=io.StringIO) as stdout,
        ):
            result = sync_version.check_ktlint_latest({"ktlint_version": "1.8.0"})

        self.assertEqual(1, result)
        self.assertIn("1.8.0 -> 1.8.1", stdout.getvalue())
        self.assertIn("versions update-ktlint", stdout.getvalue())

    def test_update_ktlint_synchronizes_the_manifest(self) -> None:
        """The update command passes the discovered release through synchronization."""
        with (
            mock.patch.object(sync_version, "latest_ktlint_version", return_value="1.8.1"),
            mock.patch.object(sync_version, "sync_files", return_value=0) as sync_files,
        ):
            result = sync_version.main(["--update-ktlint"])

        self.assertEqual(0, result)
        versions = sync_files.call_args.args[0]
        self.assertEqual("1.8.1", versions["ktlint_version"])
        additional_files = sync_files.call_args.kwargs["additional_files"]
        manifest = next(
            content for path, _, content in additional_files if path == "versions.json"
        )
        self.assertIn('"ktlint_version": "1.8.1"', manifest)

    def test_atomic_write_preserves_file_mode(self) -> None:
        """Atomic replacement must retain executable permissions."""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "managed.sh"
            path.write_text("before\n", encoding="utf-8")
            path.chmod(0o755)

            sync_version.write_text_atomic(path, "after\n")

            self.assertEqual("after\n", path.read_text(encoding="utf-8"))
            self.assertEqual(0o755, stat.S_IMODE(path.stat().st_mode))

    def test_public_entry_point_lists_versions(self) -> None:
        """The public command must resolve the internal version implementation."""
        result = subprocess.run(
            [str(sync_version.PROJECT_ROOT / "scripts" / "wasmline"), "versions", "list"],
            cwd=sync_version.PROJECT_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, result.returncode, msg=result.stderr)
        self.assertIn("wasmline_version=", result.stdout)

    def test_wasmtime_code_ignores_semver_metadata(self) -> None:
        """Wasmtime tag encoding uses the numeric core of a valid semantic version."""
        self.assertEqual(1234, sync_version.wasmtime_code("12.3.4-rc1"))
        self.assertEqual(1234, sync_version.wasmtime_code("12.3.4+build1"))


if __name__ == "__main__":
    unittest.main()
