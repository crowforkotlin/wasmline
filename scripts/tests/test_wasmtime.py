#!/usr/bin/env python3
"""Tests for Wasmtime target and asset resolution."""

from __future__ import annotations

import sys
import unittest
from io import StringIO
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT_ROOT / "scripts" / "lib" / "python"))

from wasmline_tools import doctor, wasmtime
from wasmline_tools.targets import load_targets, target_by_id


class WasmtimeConfigurationTest(unittest.TestCase):
    def test_all_targets_resolve_eighteen_engine_pairs(self) -> None:
        pairs = wasmtime._selected_pairs("all", "all")
        self.assertEqual(18, len(pairs))
        self.assertEqual(18, len({(target.id, engine) for target, engine in pairs}))

    def test_jni_configuration_resolves_sixteen_outputs(self) -> None:
        outputs = [
            doctor._jni_output(target, engine)
            for target in load_targets()
            for engine in target.engines
            if target.jni is not None
        ]
        self.assertEqual(16, len(outputs))
        self.assertTrue(all(output is not None for output in outputs))
        self.assertEqual(16, len(set(outputs)))

    def test_asset_names_distinguish_engines_and_archive_formats(self) -> None:
        linux = target_by_id("linux-x64")
        windows = target_by_id("windows-x64")
        self.assertEqual(
            "wasmtime-v12.3.4.5-x86_64-linux-min-c-api.tar.gz",
            wasmtime.expected_asset_name(linux, "cranelift", "12.3.4.5"),
        )
        self.assertEqual(
            "wasmtime-v12.3.4.5-x86_64-linux-pulley-min-c-api.tar.gz",
            wasmtime.expected_asset_name(linux, "pulley", "12.3.4.5"),
        )
        self.assertEqual(
            "wasmtime-v12.3.4.5-x86_64-windows-pulley-min-c-api.zip",
            wasmtime.expected_asset_name(windows, "pulley", "12.3.4.5"),
        )

    def test_downstream_release_tag_uses_v_prefix(self) -> None:
        self.assertEqual("v12.3.4.5", wasmtime.release_tag("12.3.4.5"))
        self.assertEqual("v12.3.4.5", wasmtime.release_tag("v12.3.4.5"))

    def test_release_checksums_are_parsed_by_asset_name(self) -> None:
        digest = "a" * 64
        self.assertEqual(
            {"wasmtime-v12.3.4.5-x86_64-linux-min-c-api.tar.gz": digest},
            wasmtime._parse_sha256sums(
                f"{digest}  wasmtime-v12.3.4.5-x86_64-linux-min-c-api.tar.gz\n"
            ),
        )

    def test_pulley_only_target_rejects_cranelift(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "does not provide"):
            wasmtime._selected_pairs("ios-arm64", "cranelift")

    def test_download_progress_reports_bytes_and_completed_files(self) -> None:
        class InteractiveStream(StringIO):
            def isatty(self) -> bool:
                return True

        stream = InteractiveStream()
        target = target_by_id("linux-x64")
        jobs = [
            wasmtime.Job(
                target,
                "pulley",
                "first.tar.gz",
                "https://example/first",
                100,
                "a" * 64,
            ),
            wasmtime.Job(
                target,
                "cranelift",
                "second.tar.gz",
                "https://example/second",
                300,
                "b" * 64,
            ),
        ]
        progress = wasmtime._DownloadProgress(wasmtime.Console(stream), jobs)

        progress.advance(100)
        progress.complete_file()
        progress.advance(300)
        progress.complete_file()
        progress.close()

        output = stream.getvalue()
        self.assertIn("25%", output)
        self.assertIn("1/2", output)
        self.assertIn("100%", output)
        self.assertIn("2/2", output)
        self.assertIn("400 B/400 B", output)
        self.assertNotIn("100%  1/2", output)


if __name__ == "__main__":
    unittest.main()
