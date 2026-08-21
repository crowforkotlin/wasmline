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


if __name__ == "__main__":
    unittest.main()
