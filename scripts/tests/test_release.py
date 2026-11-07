#!/usr/bin/env python3
"""Regression tests for prerelease versions, release tags, and AOT rebuilds."""

from __future__ import annotations

import copy
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


PROJECT_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT_ROOT / "scripts" / "internal" / "python"))

from wasmline_tools import versions
from wasmline_tools.aot import catalog, lock
from wasmline_tools.aot import sync as aot_sync
from wasmline_tools.release_versions import wasmline_version_key


def synthetic_catalog() -> dict:
    return {
        "schemaVersion": 1,
        "currentWasmlineVersion": "12.3.4-beta10",
        "minimumSupportedWasmlineVersion": "12.3.4-alpha5",
        "ranges": [
            {
                "fromWasmlineVersion": "12.3.4-alpha5",
                "aotGeneration": 1,
                "wasmtimeDistributionVersion": "5.6.7.2",
                "changedBackends": ["CRANELIFT", "PULLEY"],
            }
        ],
    }


class ReleaseVersionTest(unittest.TestCase):
    def test_versions_order_base_then_stage_then_numeric_iteration(self) -> None:
        expected = [
            "12.3.3",
            "12.3.4-alpha1",
            "12.3.4-alpha5",
            "12.3.4-alpha10",
            "12.3.4-beta1",
            "12.3.4-beta2",
            "12.3.4-beta10",
            "12.3.4",
            "12.3.5-alpha1",
        ]
        self.assertEqual(expected, sorted(reversed(expected), key=wasmline_version_key))

    def test_manifest_accepts_only_the_wasmline_release_suffixes(self) -> None:
        for value in ("12.3.4", "12.3.4-alpha5", "12.3.4-beta1"):
            with self.subTest(value=value):
                self.assertEqual(
                    {"wasmline_version": value},
                    versions.parse_updates([f"wasmline_version={value}"]),
                )
        for value in (
            "12.3",
            "12.3.4.567",
            "12.3.4-beta.1",
            "12.3.4-beta0",
            "12.3.4-beta01",
            "12.3.4-alpha",
            "12.3.4-BETA1",
            "12.3.4-rc1",
            "12.3.4-SNAPSHOT",
            "12.3.4+build1",
            " 12.3.4",
        ):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    wasmline_version_key(value)
                with self.assertRaises(SystemExit):
                    versions.validate_version("wasmline_version", value)

    def test_aot_catalog_accepts_alpha_to_beta_ranges(self) -> None:
        catalog.validate_source(
            synthetic_catalog(),
            {"wasmline_version": "12.3.4-beta10", "wasmtime_release_version": "5.6.7.2"},
        )

    def test_aot_catalog_rejects_a_numerically_later_beta_minimum(self) -> None:
        source = synthetic_catalog()
        source["minimumSupportedWasmlineVersion"] = "12.3.4-beta10"
        source["currentWasmlineVersion"] = "12.3.4-beta2"
        with self.assertRaisesRegex(catalog.AotCompatibilityError, "cannot exceed"):
            catalog.validate_source(source)

    def test_beta_promotion_keeps_history_and_rejects_regression(self) -> None:
        previous = {"releaseCatalog": synthetic_catalog()}
        promoted = copy.deepcopy(previous)
        promoted["releaseCatalog"]["currentWasmlineVersion"] = "12.3.4"
        lock.validate_append_only(previous, promoted)
        regressed = copy.deepcopy(previous)
        regressed["releaseCatalog"]["currentWasmlineVersion"] = "12.3.4-beta2"
        with self.assertRaisesRegex(catalog.AotCompatibilityError, "cannot move backward"):
            lock.validate_append_only(previous, regressed)


class AotRebuildTest(unittest.TestCase):
    def test_rebuild_requires_no_local_and_origin_release_tags(self) -> None:
        with mock.patch.object(
            aot_sync.subprocess,
            "run",
            return_value=subprocess.CompletedProcess([], 0, stdout=""),
        ) as run:
            aot_sync._require_unpublished_rebuild()
        self.assertEqual(2, run.call_count)
        self.assertIn("for-each-ref", run.call_args_list[0].args[0])
        self.assertIn("ls-remote", run.call_args_list[1].args[0])

    def test_local_release_tag_stops_rebuild_before_origin_lookup(self) -> None:
        with mock.patch.object(
            aot_sync.subprocess,
            "run",
            return_value=subprocess.CompletedProcess([], 0, stdout="refs/tags/release-12.3.4.567\n"),
        ) as run:
            with self.assertRaisesRegex(catalog.AotCompatibilityError, "local repository"):
                aot_sync._require_unpublished_rebuild()
        self.assertEqual(1, run.call_count)

    def test_origin_release_tag_stops_rebuild(self) -> None:
        with mock.patch.object(
            aot_sync.subprocess,
            "run",
            side_effect=[
                subprocess.CompletedProcess([], 0, stdout=""),
                subprocess.CompletedProcess([], 0, stdout="abc\trefs/tags/release-12.3.4-beta1.567\n"),
            ],
        ):
            with self.assertRaisesRegex(catalog.AotCompatibilityError, "release tags exist in origin"):
                aot_sync._require_unpublished_rebuild()

    def test_origin_lookup_failure_does_not_authorize_rebuild(self) -> None:
        with mock.patch.object(
            aot_sync.subprocess,
            "run",
            side_effect=[
                subprocess.CompletedProcess([], 0, stdout=""),
                subprocess.CalledProcessError(128, ["git", "ls-remote"]),
            ],
        ):
            with self.assertRaisesRegex(catalog.AotCompatibilityError, "Cannot read release tags from origin"):
                aot_sync._require_unpublished_rebuild()

    def test_refused_rebuild_does_not_download_or_write_generated_files(self) -> None:
        with (
            mock.patch.object(
                aot_sync,
                "_read_versions",
                return_value={"wasmline_version": "12.3.4-beta10", "wasmtime_release_version": "5.6.7.2"},
            ),
            mock.patch.object(aot_sync, "load_public_catalog", return_value=synthetic_catalog()),
            mock.patch.object(
                aot_sync,
                "_require_unpublished_rebuild",
                side_effect=catalog.AotCompatibilityError("Published history"),
            ),
            mock.patch.object(aot_sync, "GitHubAotMetadataResolver") as resolver,
            mock.patch.object(aot_sync, "write_text_atomic") as write,
        ):
            with self.assertRaisesRegex(catalog.AotCompatibilityError, "Published history"):
                aot_sync.sync_aot(rebuild=True)
        resolver.assert_not_called()
        write.assert_not_called()


@unittest.skipUnless(shutil.which("bash") and shutil.which("jq"), "Release script tests require bash and jq.")
class ReleaseScriptTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        scripts = self.root / "scripts"
        scripts.mkdir()
        self.script = scripts / "release.sh"
        shutil.copyfile(PROJECT_ROOT / "scripts" / "release.sh", self.script)
        # Isolate the release script's tag validation from the separate version/AOT suites.
        tooling = scripts / "wasmline"
        tooling.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
        tooling.chmod(0o700)

    def write_inputs(self, version: str) -> None:
        (self.root / "versions.json").write_text(
            json.dumps(
                {
                    "versions": {
                        "wasmline_version": version,
                        "wasmtime_version": "5.6.7",
                        "wasmtime_release_version": "5.6.7.2",
                    }
                }
            ),
            encoding="utf-8",
        )
        (self.root / "aot-compatibility.json").write_text(
            json.dumps({"currentWasmlineVersion": version}),
            encoding="utf-8",
        )

    def run_script(self, *arguments: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["bash", str(self.script), *arguments],
            capture_output=True,
            text=True,
            timeout=10,
        )

    def test_stable_alpha_and_beta_tags_match_the_maven_version(self) -> None:
        for version in ("12.3.4", "12.3.4-alpha5", "12.3.4-beta1"):
            with self.subTest(version=version):
                self.write_inputs(version)
                expected = f"release-{version}.567"
                result = self.run_script("expected-tag")
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(expected, result.stdout.strip())
                result = self.run_script("verify-tag", expected)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn(f"Release metadata is valid for {expected}.", result.stdout)

    def test_release_metadata_marks_only_stable_versions_as_latest(self) -> None:
        for version, prerelease, make_latest in (
            ("12.3.4", "false", "true"),
            ("12.3.4-alpha5", "true", "false"),
            ("12.3.4-beta1", "true", "false"),
        ):
            with self.subTest(version=version):
                self.write_inputs(version)
                result = self.run_script("github-release-metadata")
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(
                    {
                        "version": version,
                        "tag": f"release-{version}.567",
                        "prerelease": prerelease,
                        "make_latest": make_latest,
                    },
                    dict(line.split("=", 1) for line in result.stdout.splitlines()),
                )

    def test_tag_stage_and_wasmtime_code_must_match_inputs(self) -> None:
        self.write_inputs("12.3.4-beta1")
        for tag in (
            "release-12.3.4.567",
            "release-12.3.4-beta2.567",
            "release-12.3.4-beta1.568",
            "release-12.3.4-beta.1.567",
            "release-12.3.4.567-beta1",
        ):
            with self.subTest(tag=tag):
                self.assertNotEqual(0, self.run_script("verify-tag", tag).returncode)

    def test_notes_include_changelog_with_and_without_a_new_generation(self) -> None:
        (self.root / "CHANGELOG.md").write_text(
            "# Changelog\n\n## Unreleased\n\nA synthetic candidate.\n\n"
            "- Consumer verification.\n\n## 12.3.3\n\nOlder release text.\n",
            encoding="utf-8",
        )
        output_file = self.root / "build" / "release-notes.md"
        for version, first_version, added in (
            ("12.3.4-alpha5", "12.3.4-alpha5", "true"),
            ("12.3.4-beta1", "12.3.4-beta1", "true"),
            ("12.3.4-beta2", "12.3.4-beta1", "false"),
            ("12.3.4", "12.3.4-beta1", "false"),
        ):
            with self.subTest(version=version):
                self.write_inputs(version)
                source = synthetic_catalog()
                source["currentWasmlineVersion"] = version
                source["minimumSupportedWasmlineVersion"] = first_version
                source["ranges"][0]["fromWasmlineVersion"] = first_version
                (self.root / "aot-compatibility.json").write_text(
                    json.dumps(source),
                    encoding="utf-8",
                )
                result = self.run_script("write-notes", str(output_file))
                self.assertEqual(0, result.returncode, result.stderr)
                notes = output_file.read_text(encoding="utf-8")
                # GitHub renders the release name as the heading, so the body
                # must not repeat it.
                self.assertFalse(
                    any(line.startswith("#") and "wasmline" in line.lower() and version in line
                        for line in notes.splitlines()),
                    notes,
                )
                self.assertIn("A synthetic candidate.", notes)
                self.assertIn("- Consumer verification.", notes)
                self.assertIn(f"- New AOT generation in this release: `{added}`", notes)
                self.assertIn("- Wasmtime fork distribution: `5.6.7.2`", notes)
                self.assertNotIn("Older release text.", notes)

    def test_catalog_version_must_include_the_same_prerelease_suffix(self) -> None:
        self.write_inputs("12.3.4-beta1")
        (self.root / "aot-compatibility.json").write_text(
            json.dumps({"currentWasmlineVersion": "12.3.4"}),
            encoding="utf-8",
        )
        result = self.run_script("verify-tag", "release-12.3.4-beta1.567")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("AOT catalog current version", result.stderr)


if __name__ == "__main__":
    unittest.main()
