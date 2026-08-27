#!/usr/bin/env python3
"""Tests for the repository command-line interface."""

from __future__ import annotations

import contextlib
import io
import sys
import unittest
from pathlib import Path
from unittest import mock


PROJECT_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT_ROOT / "scripts" / "lib" / "python"))

from wasmline_tools import cli


class CommandLineTest(unittest.TestCase):
    def test_jni_without_subcommand_shows_available_commands(self) -> None:
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            result = cli.main(["jni"])

        self.assertEqual(0, result)
        self.assertIn("usage: ./scripts/wasmline jni", output.getvalue())
        self.assertIn("build", output.getvalue())
        self.assertNotIn("ERR", output.getvalue())

    def test_wasmtime_without_subcommand_shows_available_commands(self) -> None:
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            result = cli.main(["wasmtime"])

        self.assertEqual(0, result)
        self.assertIn("targets", output.getvalue())
        self.assertIn("download", output.getvalue())

    def test_download_defaults_to_all_targets_and_engines(self) -> None:
        with mock.patch.object(cli.wasmtime, "download", return_value=0) as download:
            result = cli.main(["wasmtime", "download"])

        self.assertEqual(0, result)
        download.assert_called_once_with(
            target_id="all",
            engine="all",
            jobs=None,
            proxy=None,
            force=False,
        )

    def test_download_help_has_a_bounded_width(self) -> None:
        parser = cli._parser()
        download = parser._subparsers._group_actions[0].choices["wasmtime"]
        download = download._subparsers._group_actions[0].choices["download"]
        help_text = download.format_help()

        self.assertLessEqual(max(map(len, help_text.splitlines())), 88)
        self.assertIn("--engine ENGINE", help_text)
        self.assertIn("pulley, cranelift, or all", help_text)
        self.assertIn("default: all", help_text)

    def test_kotlin_native_build_defaults_to_all_targets_and_engines(self) -> None:
        with mock.patch.object(cli, "_run_shell", return_value=0) as run_shell:
            result = cli.main(["kotlin-native", "build"])

        self.assertEqual(0, result)
        run_shell.assert_called_once_with("internal/native/build-kotlin-native.sh", ["all"])

    def test_kotlin_native_build_accepts_all_engines_for_all_targets(self) -> None:
        with mock.patch.object(cli, "_run_shell", return_value=0) as run_shell:
            result = cli.main(
                ["kotlin-native", "build", "--target", "all", "--engine", "all"]
            )

        self.assertEqual(0, result)
        run_shell.assert_called_once_with("internal/native/build-kotlin-native.sh", ["all"])

    def test_kotlin_native_build_all_engines_for_one_target_uses_configured_engines(
        self,
    ) -> None:
        with mock.patch.object(cli, "_run_shell", return_value=0) as run_shell:
            result = cli.main(
                ["kotlin-native", "build", "--target", "linuxX64", "--engine", "all"]
            )

        self.assertEqual(0, result)
        self.assertEqual(
            [
                mock.call("internal/native/build-kotlin-native.sh", ["linuxX64", "pulley"]),
                mock.call("internal/native/build-kotlin-native.sh", ["linuxX64", "cranelift"]),
            ],
            run_shell.call_args_list,
        )

    def test_kotlin_native_build_single_target_defaults_to_pulley(self) -> None:
        with mock.patch.object(cli, "_run_shell", return_value=0) as run_shell:
            result = cli.main(["kotlin-native", "build", "--target", "linuxX64"])

        self.assertEqual(0, result)
        run_shell.assert_called_once_with(
            "internal/native/build-kotlin-native.sh", ["linuxX64", "pulley"]
        )

    def test_kotlin_native_build_help_has_defaults_and_all_engine(self) -> None:
        parser = cli._parser()
        native = parser._subparsers._group_actions[0].choices["kotlin-native"]
        build = native._subparsers._group_actions[0].choices["build"]
        help_text = build.format_help()

        self.assertLessEqual(max(map(len, help_text.splitlines())), 88)
        self.assertIn("--target TARGET", help_text)
        self.assertIn("--engine {pulley,cranelift,all}", help_text)
        self.assertIn("Defaults to all engines for all targets", help_text)


if __name__ == "__main__":
    unittest.main()
