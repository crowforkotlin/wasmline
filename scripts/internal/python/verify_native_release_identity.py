#!/usr/bin/env python3
"""Verify that linked native engines were built for the manifest release version.

A native engine embeds `WASMLINE_RELEASE_VERSION` from
`NativeBuildIdentity.h`, and the Kotlin runtime rejects any engine whose value
differs from `WasmlineReleaseIdentity.RELEASE_VERSION`. A stale build cache can
emit an engine for the previous release, which passes compilation and fails
only once a test loads the library. This check reads the version string out of
every staged engine asset and fails the release before publication.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

VERSION_PATTERN = re.compile(rb"^[0-9]+\.[0-9]+\.[0-9]+(?:-(?:alpha|beta)[1-9][0-9]*)?$")

ENGINE_ASSET_ROOTS = (
    "wasmline-engine-cranelift/src/jvmMain/resources/jni",
    "wasmline-engine-cranelift/src/androidMain/jniLibs",
    "wasmline-engine-pulley/src/jvmMain/resources/jni",
    "wasmline-engine-pulley/src/androidMain/jniLibs",
)


def manifest_version(root: Path) -> str:
    manifest = json.loads((root / "versions.json").read_text(encoding="utf-8"))
    return manifest["versions"]["wasmline_version"]


def embedded_versions(path: Path, expected: str) -> set[str]:
    """Return the release strings this engine binary embeds.

    The version lives in a C string literal, so read NUL-delimited segments the
    way `strings` does instead of scanning raw bytes: a byte scan also matches
    fragments inside unrelated string tables. A binary also carries the
    Wasmtime version, so keep only candidates that share the manifest's numeric
    base before comparing.
    """

    base = expected.split("-", 1)[0]
    found: set[str] = set()
    for segment in path.read_bytes().split(b"\x00"):
        if not VERSION_PATTERN.match(segment):
            continue
        value = segment.decode("ascii")
        if value == base or value.startswith(base + "-"):
            found.add(value)
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[3])
    args = parser.parse_args()
    root = args.root
    expected = manifest_version(root)

    mismatched: list[tuple[Path, set[str]]] = []
    checked = 0
    for relative in ENGINE_ASSET_ROOTS:
        for library in sorted((root / relative).rglob("libwasmline.*")):
            if library.suffix not in {".so", ".dylib", ".dll"}:
                continue
            checked += 1
            others = embedded_versions(library, expected) - {expected}
            if expected not in embedded_versions(library, expected) or others:
                mismatched.append((library.relative_to(root), embedded_versions(library, expected)))

    if mismatched:
        for path, values in mismatched:
            parser.exit(
                1,
                f"{path}: embedded release versions {sorted(values)} "
                f"do not match versions.json {expected!r}. Rebuild the engine assets.\n",
            )
    if checked == 0:
        parser.exit(1, "No engine assets were found; build them before releasing.\n")
    print(f"Checked {checked} engine assets for Wasmline {expected}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
