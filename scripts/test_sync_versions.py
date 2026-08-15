#!/usr/bin/env python3
"""Regression tests for repository-wide version synchronization rules."""

from __future__ import annotations

import subprocess
import sys
import unittest

import sync_version
import sync_versions


class SyncVersionsTest(unittest.TestCase):
    """Checks that the manifest and all managed version targets stay aligned."""

    @staticmethod
    def render_managed_files(versions: dict[str, str]) -> dict[str, str]:
        """Applies all rules for each path in declaration order."""
        rendered: dict[str, str] = {}
        for spec in sync_versions.file_specs():
            path = sync_versions.PROJECT_ROOT / spec.path
            rendered.setdefault(spec.path, path.read_text(encoding="utf-8"))
            rendered[spec.path] = sync_versions.apply_rules(rendered[spec.path], spec, versions)
        return rendered

    def test_all_managed_files_exist(self) -> None:
        """Every rule must point to a source or documentation file."""
        self.assertTrue(sync_versions.MANIFEST_PATH.is_file())
        self.assertTrue((sync_versions.PROJECT_ROOT / "scripts/sync_version.py").is_file())
        for spec in sync_versions.file_specs():
            self.assertTrue(
                (sync_versions.PROJECT_ROOT / spec.path).is_file(),
                msg=f"Missing managed file: {spec.path}",
            )

    def test_rules_render_all_project_versions(self) -> None:
        """Synthetic values must reach project, sample, CLI, and docs targets."""
        versions = {
            "wasmline_version": "9.8.7",
            "sample_plugin_version": "6.5.4",
            "wasmtime_version": "99.8.7",
            "kotlin_version": "9.9.9",
            "kotlin_min_version": "9.8.0-RC1",
            "agp_version": "9.9.9",
            "zig_version": "9.9.9",
            "jbr_version": "99",
        }

        rendered = self.render_managed_files(versions)
        rendered_paths = {
            path
            for path, updated in rendered.items()
            if updated != (sync_versions.PROJECT_ROOT / path).read_text(encoding="utf-8")
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
            "scripts/sync_versions.py",
        }
        self.assertTrue(expected_paths.issubset(rendered_paths))

    def test_manifest_has_all_required_keys(self) -> None:
        """The manifest must contain every version consumed by the rules."""
        manifest = sync_versions.load_manifest()["versions"]
        for key in sync_versions.REQUIRED_KEYS:
            self.assertIn(key, manifest)

    def test_synthetic_values_update_newly_managed_targets(self) -> None:
        """New runtime, sample, guide, and synchronizer targets use the right keys."""
        versions = {
            "wasmline_version": "9.8.7",
            "sample_plugin_version": "6.5.4",
            "wasmtime_version": "99.8.7",
            "kotlin_version": "9.9.9",
            "kotlin_min_version": "9.8.0-RC1",
            "agp_version": "9.9.9",
            "zig_version": "9.9.9",
            "jbr_version": "99",
        }

        expected_fragments = {
            "scripts/sync_versions.py": "--set wasmtime_version=99.8.7",
            ".agents/skills/wasmline/development-guide.md": "Zig version (requires **9.9.9**)",
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
            "wasmline-multiplatform/wasmline-plugin-core/src/main/kotlin/crow/wasmline/plugin/core/toolchain/ToolchainCatalog.kt":
                'const val WASMTIME_VERSION = "99.8.7"',
            "wasmline-multiplatform/wasmline-plugin-core/src/test/kotlin/crow/wasmline/plugin/core/component/ComponentToolchainIntegrationTest.kt":
                'adapterVersion = "99.8.7"',
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

        manifest_test = (sync_versions.PROJECT_ROOT / next(iter(
            path for path in expected_fragments if path.endswith("ManifestTest.kt")
        ))).read_text(encoding="utf-8")
        self.assertIn('version = "0.1.0"', manifest_test)

    def test_sample_versions_update_manifests_fallbacks_and_output_paths(self) -> None:
        """Sample manifests, Wasmtime fallbacks, and consumers stay synchronized."""
        versions = {
            "wasmline_version": "9.8.7",
            "sample_plugin_version": "6.5.4",
            "wasmtime_version": "99.8.7",
            "kotlin_version": "9.9.9",
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
            "kotlin_version": "9.9.9",
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

    def test_parse_updates_rejects_unknown_keys(self) -> None:
        """Typos in --set must fail instead of creating an unsynchronized key."""
        with self.assertRaises(SystemExit):
            sync_versions.parse_updates(["wasmline_verison=1.2.3"])

    def test_parse_updates_rejects_invalid_version_shapes(self) -> None:
        """Malformed values must not create a partially synchronized manifest."""
        with self.assertRaises(SystemExit):
            sync_versions.parse_updates(["wasmtime_version=47.0"])
        with self.assertRaises(SystemExit):
            sync_versions.parse_updates(["jbr_version=21.0"])
        with self.assertRaises(SystemExit):
            sync_versions.parse_updates(["wasmtime_version=47.10.2"])
        with self.assertRaises(SystemExit):
            sync_versions.parse_updates(["wasmtime_version=47.0.12"])

    def test_parse_updates_accepts_prerelease_versions(self) -> None:
        """Kotlin prerelease versions used by the repository remain valid."""
        updates = sync_versions.parse_updates(["kotlin_min_version=2.3.0-RC2"])
        self.assertEqual("2.3.0-RC2", updates["kotlin_min_version"])

    def test_compatibility_entry_points_share_the_implementation(self) -> None:
        """The singular and plural commands must update the same implementation."""
        self.assertIs(sync_version.main, sync_versions.main)

    def test_public_entry_point_supports_module_execution(self) -> None:
        """The public entry point must resolve its implementation as a module."""
        result = subprocess.run(
            [sys.executable, "-m", "scripts.sync_version", "--list"],
            cwd=sync_versions.PROJECT_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, result.returncode, msg=result.stderr)
        self.assertIn("wasmline_version=", result.stdout)

    def test_wasmtime_code_ignores_semver_metadata(self) -> None:
        """Wasmtime tag encoding uses the numeric core of a valid semantic version."""
        self.assertEqual(4702, sync_versions.wasmtime_code("47.0.2-rc1"))
        self.assertEqual(4702, sync_versions.wasmtime_code("47.0.2+build1"))


if __name__ == "__main__":
    unittest.main()
