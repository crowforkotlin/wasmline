"""Run the existing Bash backends with argument lists."""

from __future__ import annotations

import subprocess
from collections.abc import Sequence

from ..paths import SCRIPTS_ROOT


def run_shell(relative_path: str, arguments: Sequence[str] = ()) -> int:
    """Run one repository backend and return its exit status unchanged."""

    command = ["bash", str(SCRIPTS_ROOT / relative_path), *arguments]
    return subprocess.run(command, check=False).returncode
