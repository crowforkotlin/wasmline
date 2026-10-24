"""Repository paths used by Wasmline tooling."""

from pathlib import Path


PACKAGE_ROOT = Path(__file__).resolve().parent
SCRIPTS_ROOT = PACKAGE_ROOT.parents[2]
PROJECT_ROOT = SCRIPTS_ROOT.parent
BUILD_ROOT = PROJECT_ROOT / "build"
PLATFORMS_ROOT = BUILD_ROOT / "platforms"
MANIFEST_PATH = PROJECT_ROOT / "versions.json"
TARGETS_PATH = SCRIPTS_ROOT / "config" / "wasmtime-targets.json"
