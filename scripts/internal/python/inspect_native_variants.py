#!/usr/bin/env python3
"""Print the Native variants of the published engine modules.

Diagnostic helper for `verify_publications`. It reports, for every published
engine coordinate, whether the `library` variant lists a
`-cinterop-wasmline.klib`, which is what the release verification requires.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def describe(repository: Path) -> int:
    """Print one line per Native library variant and return a mismatch count."""

    mismatches = 0
    for coordinate in sorted(repository.glob("wasmline-engine-*")):
        for module in sorted(coordinate.glob("*/*.module")):
            data = json.loads(module.read_text(encoding="utf-8"))
            for variant in data.get("variants", []):
                attributes = variant.get("attributes", {})
                if attributes.get("org.gradle.category") != "library":
                    continue
                if attributes.get("org.jetbrains.kotlin.platform.type") != "native":
                    continue
                files = [item["url"] for item in variant.get("files", [])]
                has_cinterop = any(name.endswith("-cinterop-wasmline.klib") for name in files)
                if not has_cinterop:
                    mismatches += 1
                print(
                    f"{module.name}\t{variant['name']}\tcinterop={has_cinterop}\t"
                    + ",".join(files)
                )
    return mismatches


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repository",
        type=Path,
        default=Path.home() / ".m2/repository",
        help="Maven repository root containing io/github/crowforkotlin",
    )
    args = parser.parse_args()
    repository = args.repository / "io/github/crowforkotlin"
    if not repository.is_dir():
        parser.exit(1, f"Missing group directory: {repository}\n")
    mismatches = describe(repository)
    print(f"Native variants without a cinterop KLIB: {mismatches}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
