#!/usr/bin/env python3
"""Tests for terminal output formatting."""

from __future__ import annotations

import io
import sys
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT_ROOT / "scripts" / "lib" / "python"))

from wasmline_tools.output import Console


class ConsoleTest(unittest.TestCase):
    def test_table_aligns_headers_and_variable_width_rows(self) -> None:
        output = io.StringIO()
        Console(output).table(
            [
                ("OK", "JBR 21", "/opt/jbr"),
                ("ERR", "Kotlin/Native libraries", "5/6 found."),
            ]
        )

        self.assertEqual(
            [
                "STATUS  CHECK                    DETAILS",
                "OK      JBR 21                   /opt/jbr",
                "ERR     Kotlin/Native libraries  5/6 found.",
            ],
            output.getvalue().splitlines(),
        )


if __name__ == "__main__":
    unittest.main()
