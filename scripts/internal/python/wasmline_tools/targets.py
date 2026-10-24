"""Wasmtime target configuration."""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

from .paths import TARGETS_PATH


ENGINES = ("pulley", "cranelift")


@dataclass(frozen=True)
class Target:
    id: str
    asset: str
    install_path: str
    engines: tuple[str, ...]
    jni: dict[str, str] | None
    kotlin_native_target: str | None
    published_kotlin_native: bool


def load_targets() -> tuple[Target, ...]:
    try:
        raw = json.loads(TARGETS_PATH.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError) as error:
        raise RuntimeError(f"Cannot read target configuration: {error}") from error

    entries = raw.get("targets")
    if not isinstance(entries, list):
        raise RuntimeError("wasmtime-targets.json must contain a targets array.")

    result: list[Target] = []
    ids: set[str] = set()
    for entry in entries:
        if not isinstance(entry, dict):
            raise RuntimeError("Each Wasmtime target must be an object.")
        target_id = _string(entry, "id")
        if target_id in ids:
            raise RuntimeError(f"Duplicate Wasmtime target id: {target_id}")
        ids.add(target_id)

        engines_value = entry.get("engines")
        if not isinstance(engines_value, list) or not engines_value:
            raise RuntimeError(f"Target {target_id} must define at least one engine.")
        engines = tuple(str(value) for value in engines_value)
        invalid = [engine for engine in engines if engine not in ENGINES]
        if invalid:
            raise RuntimeError(f"Target {target_id} has invalid engines: {', '.join(invalid)}")

        jni_value = entry.get("jni")
        if jni_value is not None and not isinstance(jni_value, dict):
            raise RuntimeError(f"Target {target_id} has an invalid jni object.")

        result.append(
            Target(
                id=target_id,
                asset=_string(entry, "asset"),
                install_path=_string(entry, "installPath"),
                engines=engines,
                jni={str(key): str(value) for key, value in jni_value.items()}
                if jni_value is not None
                else None,
                kotlin_native_target=_optional_string(entry.get("kotlinNativeTarget")),
                published_kotlin_native=entry.get("publishedKotlinNative") is True,
            )
        )
    return tuple(result)


def target_by_id(target_id: str) -> Target:
    for target in load_targets():
        if target.id == target_id:
            return target
    raise RuntimeError(
        f"Unknown target '{target_id}'. Run ./scripts/wasmline wasmtime targets."
    )


def _string(value: dict[str, Any], key: str) -> str:
    result = value.get(key)
    if not isinstance(result, str) or not result:
        raise RuntimeError(f"Wasmtime target is missing {key}.")
    return result


def _optional_string(value: Any) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str) or not value:
        raise RuntimeError("Optional target values must be non-empty strings.")
    return value
