"""Terminal presentation shared by every repository command.

Colour and progress need an interactive stream; redirected output stays plain
text. Narrow terminals switch to a vertical layout instead of cutting text.
"""

from __future__ import annotations

import os
import shutil
import sys
import unicodedata
from collections.abc import Sequence
from threading import Lock
from typing import TextIO

STATUS_WORDS = ("OK", "ERR", "SKIP", "INFO")
COLORS = {"OK": "1;32", "ERR": "1;31", "SKIP": "1;33", "INFO": "1;36"}
MIN_COLUMN = 8
COMFORTABLE = 16
GAP = "  "


def display_width(text: str) -> int:
    """Measure one line, counting wide characters as two columns."""

    width = 0
    for character in text:
        if unicodedata.combining(character):
            continue
        width += 2 if unicodedata.east_asian_width(character) in {"W", "F"} else 1
    return width


def _split(text: str, width: int) -> list[str]:
    """Wrap one cell on spaces, falling back to a hard cut for long tokens."""

    lines: list[str] = []
    for paragraph in text.splitlines() or [""]:
        remaining = paragraph
        while display_width(remaining) > width:
            consumed = 0
            last_space = 0
            hard_cut = len(remaining)
            for index, character in enumerate(remaining):
                consumed += 2 if unicodedata.east_asian_width(character) in {"W", "F"} else 1
                if consumed > width:
                    hard_cut = index
                    break
                if character == " ":
                    last_space = index
            cut = max(last_space or hard_cut, 1)
            lines.append(remaining[:cut].rstrip())
            remaining = remaining[cut:].lstrip()
        lines.append(remaining)
    return lines


class Console:
    """Write statuses, tables, and progress to one stream."""

    STATUS_WIDTH = 4

    def __init__(self, stream: TextIO | None = None) -> None:
        self.stream = sys.stderr if stream is None else stream
        self.color = (
            self.stream.isatty()
            and os.environ.get("TERM") != "dumb"
            and "NO_COLOR" not in os.environ
        )
        self._lock = Lock()
        self._progress_width = 0

    @property
    def interactive(self) -> bool:
        return self.stream.isatty()

    @property
    def width(self) -> int:
        if not self.interactive:
            return 0
        return max(shutil.get_terminal_size().columns, 20)

    def _status(self, value: str, width: int = STATUS_WIDTH) -> str:
        code = COLORS.get(value) if self.color else None
        body = f"\033[{code}m{value}\033[0m" if code else value
        return body + " " * max(0, width - len(value))

    def _write(self, text: str = "") -> None:
        print(text, file=self.stream)

    def title(self, text: str) -> None:
        with self._lock:
            self._write(text)
            self._write()

    def message(self, text: str = "") -> None:
        with self._lock:
            self._write(text)

    def table(self, headers: Sequence[str], rows: Sequence[Sequence[str]]) -> None:
        """Render one table, switching to labelled blocks when space is short."""

        cells = [[str(value) for value in row] for row in rows]
        widths = [
            max([display_width(headers[index])] + [display_width(row[index]) for row in cells])
            for index in range(len(headers))
        ]
        with self._lock:
            if self.interactive and sum(widths) + 2 * (len(headers) - 1) > self.width:
                if self.width < self._minimum_table(headers, widths) or not self._shrink(widths):
                    self._vertical(cells)
                    return
            header_line = GAP.join(
                header + " " * max(0, widths[index] - display_width(header))
                for index, header in enumerate(headers)
            )
            self._write(header_line.rstrip())
            for row in cells:
                self._table_row(row, widths)

    def _minimum_table(self, headers: Sequence[str], widths: list[int]) -> int:
        """Width below which a table can no longer hold readable columns."""

        floors = [
            min(widths[index], max(COMFORTABLE, display_width(headers[index])))
            for index in range(len(widths))
        ]
        return sum(floors) + 2 * (len(widths) - 1)

    def _shrink(self, widths: list[int]) -> bool:
        limit = self.width
        while sum(widths) + 2 * (len(widths) - 1) > limit:
            widest = max(range(len(widths)), key=widths.__getitem__)
            if widths[widest] <= MIN_COLUMN:
                return False
            widths[widest] -= 1
        return True

    def _table_row(self, row: list[str], widths: list[int]) -> None:
        wrapped = [_split(cell, widths[index]) for index, cell in enumerate(row)]
        for line in range(max(len(cell) for cell in wrapped)):
            parts: list[str] = []
            for index, cell in enumerate(wrapped):
                text = cell[line] if line < len(cell) else ""
                if index == 0 and text in STATUS_WORDS:
                    parts.append(self._status(text, widths[0]))
                else:
                    parts.append(text + " " * max(0, widths[index] - display_width(text)))
            self._write(GAP.join(parts).rstrip())

    def _vertical(self, rows: Sequence[list[str]]) -> None:
        limit = max(self.width - 4, MIN_COLUMN)
        for row in rows:
            if row[0] in STATUS_WORDS:
                status, label, details = row[0], row[1], " ".join(row[2:])
                indent = " " * (self.STATUS_WIDTH + 2)
                head = _split(label, max(self.width - len(indent), MIN_COLUMN))
                self._write(f"{self._status(status)}  {head[0]}".rstrip())
                for line in head[1:]:
                    self._write(indent + line)
            else:
                head = _split(row[0], self.width)
                details = " ".join(row[1:])
                self._write(head[0])
                for line in head[1:]:
                    self._write(line)
            for line in _split(details, limit):
                self._write("    " + line)

    def row(self, status: str, label: str, details: str) -> None:
        with self._lock:
            if self.interactive and display_width(f"{status}    {label}    {details}") > self.width:
                self._write(f"{self._status(status)}  {label}".rstrip())
                for line in _split(details, max(self.width - 4, MIN_COLUMN)):
                    self._write("    " + line)
                return
            self._write(f"{self._status(status)}    {label}    {details}")

    def ok(self, label: str, details: str) -> None:
        self.row("OK", label, details)

    def error(self, label: str, details: str) -> None:
        self.row("ERR", label, details)

    def info(self, label: str, details: str) -> None:
        self.row("INFO", label, details)

    def skip(self, label: str, details: str) -> None:
        self.row("SKIP", label, details)

    def progress(self, label: str, details: str) -> None:
        if not self.interactive:
            return
        plain = f"{'INFO':<{self.STATUS_WIDTH}}    {label}    {details}"
        rendered = f"{self._status('INFO')}    {label}    {details}"
        limit = self.width
        if display_width(plain) > limit:
            plain = plain[: limit - 1] + "…"
            rendered = rendered[: limit - 1] + "…"
        with self._lock:
            padding = " " * max(0, self._progress_width - display_width(plain))
            print(f"\r{rendered}{padding}", end="", file=self.stream, flush=True)
            self._progress_width = display_width(plain)

    def end_progress(self) -> None:
        if not self.interactive:
            return
        with self._lock:
            print(file=self.stream, flush=True)
            self._progress_width = 0
