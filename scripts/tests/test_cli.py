#!/usr/bin/env python3
"""Tests for the repository command-line interface."""

from __future__ import annotations

import contextlib
import io
import sys
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT_ROOT / "scripts" / "internal" / "python"))

from wasmline_tools import cli


def run(*arguments: str) -> tuple[int, str, str]:
    """Run the CLI and capture its streams."""

    stdout = io.StringIO()
    stderr = io.StringIO()
    with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
        result = cli.main(list(arguments))
    return result, stdout.getvalue(), stderr.getvalue()


class HelpTest(unittest.TestCase):
    def test_root_without_arguments_shows_commands(self) -> None:
        result, stdout, stderr = run()

        self.assertEqual(0, result)
        for command in ("doctor", "lint", "wasmtime", "jni", "versions", "aot"):
            self.assertIn(command, stdout)
        self.assertNotIn("ERR", stderr)

    def test_help_command_matches_bare_invocation(self) -> None:
        self.assertEqual(run()[1], run("help")[1])

    def test_group_help_paths_agree(self) -> None:
        for group, child in (("jni", "build"), ("aot", "sync"), ("versions", "check")):
            bare = run(group)[1]
            explicit = run(group, "help")[1]
            option = run(group, "--help")[1]
            self.assertIn(child, bare)
            self.assertEqual(bare, explicit)
            self.assertEqual(bare, option)

    def test_help_navigates_to_a_leaf_command(self) -> None:
        result, stdout, _ = run("help", "jni", "build")

        self.assertEqual(0, result)
        self.assertIn("usage: ./scripts/wasmline jni build", stdout)
        self.assertIn("--engine", stdout)

    def test_help_does_not_run_the_command(self) -> None:
        result, stdout, _ = run("lint", "--help")

        self.assertEqual(0, result)
        self.assertIn("usage: ./scripts/wasmline lint", stdout)
        self.assertNotIn("Lint", stdout)

    def test_unknown_command_and_help_topic_fail_with_usage(self) -> None:
        for arguments in (("missing",), ("help", "missing")):
            result, _, stderr = run(*arguments)
            self.assertEqual(2, result, msg=str(arguments))
            self.assertIn("ERR", stderr)
            self.assertIn("usage:", stderr)


class CommandBehaviorTest(unittest.TestCase):
    def test_unsupported_kotlin_native_target_fails_without_building(self) -> None:
        result, _, stderr = run("kotlin-native", "build", "--target", "missing-target")

        self.assertEqual(1, result)
        self.assertIn("Unknown Kotlin/Native target", stderr)

    def test_version_list_prints_key_value_pairs(self) -> None:
        result, stdout, _ = run("versions", "list")

        self.assertEqual(0, result)
        self.assertIn("wasmline_version=", stdout)
        self.assertTrue(all("=" in line for line in stdout.splitlines()))


if __name__ == "__main__":
    unittest.main()
