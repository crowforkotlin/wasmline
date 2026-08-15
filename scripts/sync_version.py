#!/usr/bin/env python3
"""Public entry point for the repository version synchronizer.

The implementation remains in the sibling ``sync_versions.py`` module so
existing automation can continue to use the plural command. Both entry points
expose the same ``main`` function and operate on the same manifest and managed
files.
"""

from __future__ import annotations

import sys

if __package__:
    from .sync_versions import main
else:
    from sync_versions import main


if __name__ == "__main__":
    sys.exit(main())
