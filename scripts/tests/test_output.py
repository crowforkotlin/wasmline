#!/usr/bin/env python3
"""Tests for terminal presentation rules."""

from __future__ import annotations

import io
import os
import sys
import unittest
from pathlib import Path
from unittest import mock


PROJECT_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT_ROOT / "scripts" / "internal" / "python"))

from wasmline_tools import terminal


class InteractiveStream(io.StringIO):
    def isatty(self) -> bool:
        return True


class ConsoleTest(unittest.TestCase):
    def test_narrow_terminal_keeps_every_detail(self) -> None:
        stream = InteractiveStream()
        details = "a very long path /opt/jetbrains/runtime/linux/21.0.10/jbr"
        headers = ("STATUS", "CHECK", "DETAILS", "EXTRA")
        rows = [("ERR", "Kotlin/Native libraries", details, "5/6 找到")]

        with mock.patch.object(
            terminal.shutil,
            "get_terminal_size",
            return_value=os.terminal_size((30, 24)),
        ):
            terminal.Console(stream).table(headers, rows)

        output = stream.getvalue()
        for word in ("Kotlin/Native", "libraries", "/opt/jetbrains", "5/6", "找到"):
            self.assertIn(word, output)
        self.assertGreater(len(output.splitlines()), 2)

    def test_redirected_output_has_no_control_sequences(self) -> None:
        stream = io.StringIO()
        console = terminal.Console(stream)
        console.ok("Version check", "All managed files are synchronized.")
        console.table(("TARGET", "ENGINES"), [("linux-x64", "pulley, cranelift")])

        output = stream.getvalue()
        self.assertIn("Version check", output)
        self.assertIn("linux-x64", output)
        self.assertNotIn("\033", output)

    def test_no_color_keeps_status_words(self) -> None:
        stream = InteractiveStream()
        with mock.patch.dict(os.environ, {"NO_COLOR": "1"}):
            terminal.Console(stream).error("AOT", "Generated files differ.")

        output = stream.getvalue()
        self.assertIn("ERR", output)
        self.assertIn("Generated files differ.", output)
        self.assertNotIn("\033", output)


if __name__ == "__main__":
    unittest.main()
