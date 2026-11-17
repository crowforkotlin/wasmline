#!/usr/bin/env python3
"""Regression tests for the native release identity gate."""

from __future__ import annotations

import io
import json
import sys
import tempfile
import unittest
import contextlib
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT_ROOT / "scripts/internal/python"))

import verify_native_release_identity as gate


def engine_binary(release: str, wasmtime: str = "9.8.7") -> bytes:
    """Build a minimal stand-in carrying the same C strings an engine embeds."""

    return b"\x7fELF\x00" + wasmtime.encode() + b"\x00" + release.encode() + b"\x00"


class ReleaseIdentityGateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "versions.json").write_text(
            json.dumps({"versions": {"wasmline_version": "1.0.0-beta2"}}) + "\n",
            encoding="utf-8",
        )
        self.assets = self.root / "wasmline-engine-cranelift/src/jvmMain/resources/jni/linux/x86_64"
        self.assets.mkdir(parents=True)

    def write(self, release: str) -> None:
        (self.assets / "libwasmline.so").write_bytes(engine_binary(release))

    def run_gate(self) -> tuple[int, str]:
        """Invoke main() with the fixture root and capture its status."""

        stderr = io.StringIO()
        argv = sys.argv
        sys.argv = ["gate", "--root", str(self.root)]
        try:
            with contextlib.redirect_stderr(stderr):
                code = gate.main()
        except SystemExit as exit_signal:
            code = exit_signal.code if isinstance(exit_signal.code, int) else 1
        finally:
            sys.argv = argv
        return code, stderr.getvalue()

    def test_matching_release_passes(self) -> None:
        self.write("1.0.0-beta2")
        code, message = self.run_gate()
        self.assertEqual(0, code, message)

    def test_previous_release_is_rejected(self) -> None:
        """A stale engine is the defect this gate exists for."""

        self.write("1.0.0-beta1")
        code, message = self.run_gate()
        self.assertEqual(1, code)
        self.assertIn("1.0.0-beta1", message)
        self.assertIn("Rebuild the engine assets", message)

    def test_wasmtime_version_is_not_treated_as_the_release(self) -> None:
        """The binary also carries the Wasmtime version, which must not fail the gate."""

        self.write("1.0.0-beta2")
        found = gate.embedded_versions(self.assets / "libwasmline.so", "1.0.0-beta2")
        self.assertEqual({"1.0.0-beta2"}, found)

    def test_missing_assets_fail_instead_of_passing(self) -> None:
        code, message = self.run_gate()
        self.assertEqual(1, code)
        self.assertIn("No engine assets", message)


if __name__ == "__main__":
    unittest.main()
