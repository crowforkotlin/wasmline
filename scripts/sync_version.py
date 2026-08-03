#!/usr/bin/env python3
"""Compatibility entry point for the repository version synchronizer.

The implementation lives in :mod:`sync_versions` for existing CI and
contributor scripts. Both entry points accept the same arguments and update
the same manifest and managed files.
"""

from __future__ import annotations

import sys

from sync_versions import main


if __name__ == "__main__":
    sys.exit(main())
