"""Consistent terminal output for repository commands."""

from __future__ import annotations

import os
import sys
from collections.abc import Sequence
from threading import Lock
from typing import TextIO


class Console:
    STATUS_WIDTH = 4

    def __init__(self, stream: TextIO = sys.stderr) -> None:
        self.stream = stream
        self.color = stream.isatty() and "NO_COLOR" not in os.environ
        self._lock = Lock()
        self._progress_width = 0

    @property
    def interactive(self) -> bool:
        return self.stream.isatty()

    def _status(self, value: str, width: int = STATUS_WIDTH) -> str:
        padding = " " * max(0, width - len(value))
        if not self.color:
            return value + padding
        code = {"OK": "1;32", "ERR": "1;31", "SKIP": "1;33", "INFO": "1;36"}.get(value)
        if code is None:
            return value + padding
        return f"\033[{code}m{value}\033[0m{padding}"

    def title(self, text: str) -> None:
        with self._lock:
            print(text, file=self.stream)
            print(file=self.stream)

    def table(self, rows: Sequence[tuple[str, str, str]]) -> None:
        status_width = max((len(status) for status, _, _ in rows), default=0)
        status_width = max(status_width, len("STATUS"))
        label_width = max((len(label) for _, label, _ in rows), default=0)
        label_width = max(label_width, len("CHECK"))

        with self._lock:
            print(
                f"{'STATUS':<{status_width}}  {'CHECK':<{label_width}}  DETAILS",
                file=self.stream,
            )
            for status, label, details in rows:
                print(
                    f"{self._status(status, status_width)}  "
                    f"{label:<{label_width}}  {details}",
                    file=self.stream,
                )

    def row(self, status: str, label: str, details: str) -> None:
        with self._lock:
            print(f"{self._status(status)}    {label}    {details}", file=self.stream)

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
        with self._lock:
            padding = " " * max(0, self._progress_width - len(plain))
            print(f"\r{rendered}{padding}", end="", file=self.stream, flush=True)
            self._progress_width = len(plain)

    def end_progress(self) -> None:
        if not self.interactive:
            return
        with self._lock:
            print(file=self.stream, flush=True)
            self._progress_width = 0

    def message(self, text: str = "") -> None:
        with self._lock:
            print(text, file=self.stream)
