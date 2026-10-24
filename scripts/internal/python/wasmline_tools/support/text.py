"""Shared regular-expression and text-file operations."""

from __future__ import annotations

import re
import stat
import tempfile
from collections.abc import Iterator
from pathlib import Path


class MissingMatches(RuntimeError):
    """Raised when a replacement matches fewer patterns than required."""

    def __init__(self, pattern: str, count: int, minimum: int) -> None:
        super().__init__(f"Pattern {pattern!r} matched {count} time(s), expected {minimum}.")
        self.pattern = pattern
        self.count = count
        self.minimum = minimum


def find_matches(
    text: str,
    pattern: str,
    flags: int = re.MULTILINE,
) -> Iterator[re.Match[str]]:
    """Iterate the matches of one pattern without copying the text."""

    return re.finditer(pattern, text, flags)


def replace_matches(
    text: str,
    pattern: str,
    replacement: str,
    *,
    flags: int = re.MULTILINE,
    minimum: int = 1,
) -> str:
    """Return the replaced text and reject too few matches without writing."""

    updated, count = re.subn(pattern, replacement, text, flags=flags)
    if count < minimum:
        raise MissingMatches(pattern, count, minimum)
    return updated


def write_text_atomic(path: Path, content: str) -> None:
    """Replace one text file from a temporary file in the same directory."""

    path.parent.mkdir(parents=True, exist_ok=True)
    target_mode = stat.S_IMODE(path.stat().st_mode) if path.exists() else 0o644
    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            dir=path.parent,
            prefix=f".{path.name}.",
            suffix=".tmp",
            delete=False,
        ) as temporary_file:
            temporary_file.write(content)
            temporary_path = Path(temporary_file.name)
        temporary_path.chmod(target_mode)
        temporary_path.replace(path)
    finally:
        if temporary_path is not None and temporary_path.exists():
            temporary_path.unlink()
