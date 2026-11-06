#!/usr/bin/env python3
"""Resolve the configured Android compileSdk to an installable SDK package name.

Android SDK packages gained a minor segment at API 36: the package for
compileSdk 37 is ``platforms;android-37.0``, and ``platforms;37`` does not
exist. The resolver reads ``sdkmanager --list`` from stdin and prints the
matching version without the ``platforms;android-`` prefix.
"""

from __future__ import annotations

import argparse
import re
import sys

PACKAGE_PATTERN = re.compile(r"platforms;android-([0-9]+(?:\.[0-9]+)?)")


def available_versions(listing: str) -> list[str]:
    """Return the installed and available platform versions, oldest first."""

    found = set(PACKAGE_PATTERN.findall(listing))
    return sorted(found, key=lambda value: tuple(int(part) for part in value.split(".")))


def resolve(compile_sdk: str, versions: list[str]) -> str:
    """Pick the package version that satisfies one compileSdk value."""

    for candidate in (compile_sdk, f"{compile_sdk}.0"):
        if candidate in versions:
            return candidate
    base = compile_sdk.split(".")[0]
    same_major = [value for value in versions if value.split(".")[0] == base]
    return same_major[-1] if same_major else ""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compile-sdk", required=True, help="configured android-compileSdk value")
    parser.add_argument(
        "--listing-file",
        help="file containing `sdkmanager --list` output; defaults to stdin",
    )
    args = parser.parse_args()

    if args.listing_file:
        with open(args.listing_file, encoding="utf-8", errors="replace") as handle:
            listing = handle.read()
    else:
        listing = sys.stdin.read()

    versions = available_versions(listing)
    package = resolve(args.compile_sdk, versions)
    if not package:
        available = ", ".join(versions) if versions else "none"
        print(
            f"No Android platform matches compileSdk {args.compile_sdk}; available: {available}",
            file=sys.stderr,
        )
        return 1
    print(package)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
