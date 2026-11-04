"""Wasmline Maven release versions and their alpha/beta ordering."""

from __future__ import annotations

import re


WASMLINE_VERSION_PATTERN = re.compile(
    r"^(?P<major>[0-9]+)\.(?P<minor>[0-9]+)\.(?P<patch>[0-9]+)"
    r"(?:-(?P<stage>alpha|beta)(?P<iteration>[1-9][0-9]*))?$"
)


def wasmline_version_key(value: str) -> tuple[int, int, int, int, int]:
    """Compare numeric components, then alpha, beta, and the stable release."""

    match = WASMLINE_VERSION_PATTERN.fullmatch(value)
    if match is None:
        raise ValueError(
            f"Invalid Wasmline version: {value!r}. "
            "Expected x.y.z, x.y.z-alphaN, or x.y.z-betaN."
        )
    stage = match["stage"]
    return (
        int(match["major"]),
        int(match["minor"]),
        int(match["patch"]),
        {"alpha": 0, "beta": 1, None: 2}[stage],
        int(match["iteration"] or "0"),
    )
