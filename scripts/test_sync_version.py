#!/usr/bin/env python3
"""Regression tests for repository-wide version synchronization rules."""

from __future__ import annotations

import copy
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any, Mapping
from unittest import mock

import sync_version
import toolchain_lock


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
        self.assertTrue((sync_version.PROJECT_ROOT / "scripts/sync_version.py").is_file())
        for spec in sync_version.file_specs():
            self.assertTrue(
                (sync_version.PROJECT_ROOT / spec.path).is_file(),
                msg=f"Missing managed file: {spec.path}",
            )

    def test_rules_render_all_project_versions(self) -> None:
        """Synthetic values must reach project, sample, CLI, and docs targets."""
        versions = {
            "wasmline_version": "9.8.7",
            "sample_plugin_version": "6.5.4",
            "wasmtime_version": "99.8.7",
            "wasm_tools_version": "8.7.6",
            "wit_bindgen_version": "7.6.5",
            "kotlin_version": "9.9.9",
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
            "wasmline-multiplatform/wasmline-cli/cli.sh",
            "wasmline-multiplatform/gradle/libs.versions.toml",
            "docs/content/docs/installation.mdx",
            "docs/content/docs/installation.zh.mdx",
            "docs/content/docs/wasmtime-download.mdx",
            "docs/content/docs/wasmtime-download.zh.mdx",
            "wasmline-samples/kotlin/run-ios.sh",
            "wasmline-multiplatform/wasmline-engine-cranelift/build.gradle.kts",
            "wasmline-multiplatform/wasmline-engine-pulley/build.gradle.kts",
            "wasmline-multiplatform/wasmline-loader/src/commonTest/kotlin/crow/wasmline/ManifestTest.kt",
            "wasmline-multiplatform/wasmline-loader/src/jvmTest/kotlin/crow/wasmline/loader/WasmlineRemotePackageResolutionTest.kt",
            "scripts/sync_version.py",
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
            "wasm_tools_version": "8.7.6",
            "wit_bindgen_version": "7.6.5",
            "kotlin_version": "9.9.9",
            "dokka_version": "8.8.8",
            "kotlin_min_version": "9.8.0-RC1",
            "agp_version": "9.9.9",
            "zig_version": "9.9.9",
            "jbr_version": "99",
        }

        expected_fragments = {
            "scripts/sync_version.py": "--set wasmtime_version=99.8.7",
            "wasmline-multiplatform/gradle/libs.versions.toml": 'dokka = "8.8.8"',
            ".agents/skills/wasmline/references/development-guide.md": "Zig version (requires **9.9.9**)",
            "wasmline-multiplatform/wasmline-engine-cranelift/build.gradle.kts":
                "wasmline-engine-cranelift-jvm:9.8.7:",
            "wasmline-multiplatform/wasmline-engine-pulley/build.gradle.kts":
                "wasmline-engine-pulley-jvm:9.8.7:",
            "wasmline-multiplatform/wasmline-loader/src/commonTest/kotlin/crow/wasmline/ManifestTest.kt":
                'version = "6.5.4"',
            "wasmline-multiplatform/wasmline-loader/src/jvmTest/kotlin/crow/wasmline/loader/WasmlineRemotePackageResolutionTest.kt":
                'version = "6.5.4"',
            "wasmline-samples/kotlin/run-ios.sh": "release-v99.8.7",
            "wasmline-multiplatform/gradle/gradle-daemon-jvm.properties": "toolchainVersion=99",
            "wasmline-samples/kotlin/sample-apps/multiplatform/desktopApp/build.gradle.kts":
                "JavaLanguageVersion.of(99)",
            "wasmline-samples/kotlin/sample-apps/multiplatform/shared/src/desktopMain/Requirement.md":
                "JBR 99",
            "wasmline-multiplatform/wasmline-plugin-test/src/jvmTest/kotlin/crow/wasmline/test/wasmtime/NativePluginTestSupport.kt":
                'targetCompilerVersion = "wasmtime-99.8.7"',
            "wasmline-multiplatform/wasmline/src/jvmTest/kotlin/crow/wasmline/test/wasmtime/NativeWasmtimeIntegrationTest.kt":
                'assertEquals("99.8.7", capabilities.wasmtimeVersion)',
            "wasmline-multiplatform/wasmline-cli/src/test/kotlin/crow/wasmline/cli/ComponentCliIntegrationTest.kt":
                'File(compileRoot, "cli-compile-6.5.4")',
            "wasmline-multiplatform/wasmline-cli/src/test/kotlin/crow/wasmline/cli/CoreCliRegressionTest.kt":
                'File(outputRoot, "core-plugin-6.5.4/debug/',
            "wasmline-samples/kotlin/sample-component-fixture/README.md":
                "wasmline/output/crow.wasmline.component.fixture-6.5.4/",
            "ROADMAP.md": "Wasmtime C-API integration (v99.8.7)",
            "ROADMAP_zh.md": "Wasmtime C-API 集成（v99.8.7）",
        }

        rendered = self.render_managed_files(versions)
        for path, fragment in expected_fragments.items():
            self.assertIn(fragment, rendered[path], msg=f"Missing rendered value in {path}")

        manifest_test = (sync_version.PROJECT_ROOT / next(iter(
            path for path in expected_fragments if path.endswith("ManifestTest.kt")
        ))).read_text(encoding="utf-8")
        self.assertIn('version = "0.1.0"', manifest_test)

    def test_sample_versions_update_manifests_fallbacks_and_output_paths(self) -> None:
        """Sample manifests, Wasmtime fallbacks, and consumers stay synchronized."""
        versions = {
            "wasmline_version": "9.8.7",
            "sample_plugin_version": "6.5.4",
            "wasmtime_version": "99.8.7",
            "wasm_tools_version": "8.7.6",
            "wit_bindgen_version": "7.6.5",
            "kotlin_version": "9.9.9",
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

        for path in manifest_paths[:2]:
            self.assertIn('.orElse("99.8.7").get()', rendered[path], msg=path)

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
            "wasm_tools_version": "8.7.6",
            "wit_bindgen_version": "7.6.5",
            "kotlin_version": "9.9.9",
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
        self.assertIn(r"\`6.5.4\`", rendered["wasmline-multiplatform/wasmline-cli/cli.sh"])
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
            "wasm_tools_version": "8.7.6",
            "wit_bindgen_version": "7.6.5",
            "kotlin_version": "9.9.9",
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
        versions["wasmtime_version"] = "48.0.0"
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
        versions["wasmtime_version"] = "48.0.0"

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
        versions["wasmtime_version"] = "48.0.0"
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
            sync_version.parse_updates(["wasmtime_version=47.0"])
        with self.assertRaises(SystemExit):
            sync_version.parse_updates(["jbr_version=21.0"])
        with self.assertRaises(SystemExit):
            sync_version.parse_updates(["wasmtime_version=47.10.2"])
        with self.assertRaises(SystemExit):
            sync_version.parse_updates(["wasmtime_version=47.0.12"])

    def test_parse_updates_accepts_prerelease_versions(self) -> None:
        """Kotlin prerelease versions used by the repository remain valid."""
        updates = sync_version.parse_updates(["kotlin_min_version=2.3.0-RC2"])
        self.assertEqual("2.3.0-RC2", updates["kotlin_min_version"])

    def test_atomic_write_preserves_file_mode(self) -> None:
        """Atomic replacement must retain executable permissions."""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "managed.sh"
            path.write_text("before\n", encoding="utf-8")
            path.chmod(0o755)

            sync_version.write_text_atomic(path, "after\n")

            self.assertEqual("after\n", path.read_text(encoding="utf-8"))
            self.assertEqual(0o755, stat.S_IMODE(path.stat().st_mode))

    def test_public_entry_point_supports_module_execution(self) -> None:
        """The public entry point must resolve its implementation as a module."""
        result = subprocess.run(
            [sys.executable, "-m", "scripts.sync_version", "--list"],
            cwd=sync_version.PROJECT_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, result.returncode, msg=result.stderr)
        self.assertIn("wasmline_version=", result.stdout)

    def test_wasmtime_code_ignores_semver_metadata(self) -> None:
        """Wasmtime tag encoding uses the numeric core of a valid semantic version."""
        self.assertEqual(4702, sync_version.wasmtime_code("47.0.2-rc1"))
        self.assertEqual(4702, sync_version.wasmtime_code("47.0.2+build1"))


if __name__ == "__main__":
    unittest.main()
