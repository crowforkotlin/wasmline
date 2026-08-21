"""Read the repository version manifest."""

from __future__ import annotations

import json

from .paths import MANIFEST_PATH


def load_versions() -> dict[str, str]:
    try:
        data = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise RuntimeError(f"Missing version manifest: {MANIFEST_PATH}") from error
    except json.JSONDecodeError as error:
        raise RuntimeError(f"Invalid JSON in {MANIFEST_PATH}: {error}") from error

    versions = data.get("versions")
    if not isinstance(versions, dict):
        raise RuntimeError("scripts/versions.json must contain a versions object.")
    return {str(key): str(value) for key, value in versions.items()}


def version(key: str) -> str:
    value = load_versions().get(key, "")
    if not value:
        raise RuntimeError(f"Missing version key in scripts/versions.json: {key}")
    return value
