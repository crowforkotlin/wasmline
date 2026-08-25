"""Repository environment and artifact checks."""

from __future__ import annotations

import os
import platform
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

from .manifest import load_versions
from .output import Console
from .paths import PLATFORMS_ROOT, PROJECT_ROOT
from .targets import Target, load_targets


@dataclass(frozen=True)
class CheckResult:
    ready: bool
    check: str
    details: str

    @property
    def status(self) -> str:
        return "OK" if self.ready else "ERR"


def _run(command: list[str]) -> str:
    try:
        result = subprocess.run(
            command,
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
    except OSError:
        return ""
    return result.stdout


def _java_home() -> Path | None:
    configured = os.environ.get("JAVA_HOME")
    if configured:
        return Path(configured).expanduser()

    java = shutil.which("java")
    if not java:
        return None
    output = _run([java, "-XshowSettings:properties", "-version"])
    match = re.search(r"^\s*java\.home\s*=\s*(.+)$", output, re.MULTILINE)
    return Path(match.group(1).strip()) if match else None


def _release_values(java_home: Path) -> dict[str, str]:
    release_file = java_home / "release"
    if not release_file.is_file():
        return {}

    values: dict[str, str] = {}
    for line in release_file.read_text(encoding="utf-8", errors="replace").splitlines():
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key] = value.strip().strip('"')
    return values


def _check_jbr(required_version: str) -> CheckResult:
    check = f"JBR {required_version}"
    java_home = _java_home()
    if java_home is None or not (java_home / "bin" / "java").is_file():
        return CheckResult(False, check, "JAVA_HOME does not point to a JBR installation.")

    output = _run([str(java_home / "bin" / "java"), "-version"])
    version_line = next(
        (line for line in output.splitlines() if line.startswith(("openjdk ", "java "))),
        "",
    )
    release = _release_values(java_home)
    runtime_version = release.get("JAVA_RUNTIME_VERSION", "")
    major_match = re.match(r"([0-9]+)", runtime_version)
    if major_match is None:
        major_match = re.search(r"^(?:openjdk|java)\s+[^0-9]*([0-9]+)", version_line)
    major = major_match.group(1) if major_match else ""

    identity = " ".join(
        (
            version_line,
            release.get("IMPLEMENTOR", ""),
            runtime_version,
            str(java_home),
        )
    ).lower()
    if major == required_version and ("jbr" in identity or "jetbrains" in identity):
        return CheckResult(True, check, str(java_home))

    return CheckResult(False, check, f"{java_home} is not JBR {required_version}.")


def _check_zig(required_version: str) -> CheckResult:
    check = f"Zig {required_version}"
    binary = shutil.which("zig")
    if not binary:
        return CheckResult(False, check, "Not found in PATH.")
    actual = _run([binary, "version"]).strip()
    if actual == required_version:
        return CheckResult(True, check, binary)
    return CheckResult(False, check, f"Found {actual or 'unknown'} at {binary}.")


def _wasmtime_pairs(targets: tuple[Target, ...]) -> list[tuple[Target, str]]:
    return [(target, engine) for target in targets for engine in target.engines]


def _check_wasmtime(release_version: str, targets: tuple[Target, ...]) -> CheckResult:
    tag = f"v{release_version}"
    pairs = _wasmtime_pairs(targets)
    found = 0
    for target, engine in pairs:
        root = PLATFORMS_ROOT / tag / engine / target.install_path
        if (root / "include" / "wasmtime.h").is_file() and (root / "lib" / "libwasmtime.a").is_file():
            found += 1
    details = f"{found}/{len(pairs)} under build/platforms/{tag}."
    return CheckResult(found == len(pairs), "Wasmtime files", details)


def _jni_output(target: Target, engine: str) -> Path | None:
    if target.jni is None:
        return None
    module = PROJECT_ROOT / "wasmline-multiplatform" / f"wasmline-engine-{engine}" / "src"
    if target.jni["kind"] == "android":
        return module / "androidMain" / "jniLibs" / target.jni["abi"] / "libwasmline.so"
    return (
        module
        / "jvmMain"
        / "resources"
        / "jni"
        / target.jni["platform"]
        / target.jni["arch"]
        / f"libwasmline.{target.jni['extension']}"
    )


def _check_jni(targets: tuple[Target, ...]) -> CheckResult:
    outputs = [
        output
        for target in targets
        for engine in target.engines
        if (output := _jni_output(target, engine)) is not None
    ]
    found = sum(path.is_file() for path in outputs)
    details = f"{found}/{len(outputs)} in wasmline-engine-* modules."
    return CheckResult(found == len(outputs), "JNI libraries", details)


def _required_kotlin_native_targets(targets: tuple[Target, ...]) -> tuple[Target, ...]:
    base = {"linuxArm64", "linuxX64", "mingwX64"}
    if platform.system() == "Darwin":
        base.update({"macosArm64", "iosArm64", "iosSimulatorArm64"})
    return tuple(
        target
        for target in targets
        if target.published_kotlin_native and target.kotlin_native_target in base
    )


def _check_kotlin_native(targets: tuple[Target, ...]) -> CheckResult:
    outputs: list[Path] = []
    for target in _required_kotlin_native_targets(targets):
        for engine in target.engines:
            outputs.append(
                PROJECT_ROOT
                / "wasmline-multiplatform"
                / "wasmline"
                / "build"
                / "native"
                / str(target.kotlin_native_target)
                / engine
                / "libwasmline_native.a"
            )
    found = sum(path.is_file() for path in outputs)
    details = f"{found}/{len(outputs)} under wasmline/build/native."
    return CheckResult(found == len(outputs), "Kotlin/Native libraries", details)


def run() -> int:
    versions = load_versions()
    targets = load_targets()
    console = Console(sys.stdout)
    jbr = _check_jbr(versions["jbr_version"])
    zig = _check_zig(versions["zig_version"])
    wasmtime = _check_wasmtime(versions["wasmtime_release_version"], targets)
    jni = _check_jni(targets)
    kotlin_native = _check_kotlin_native(targets)
    results = (jbr, zig, wasmtime, jni, kotlin_native)
    console.table([(result.status, result.check, result.details) for result in results])

    jbr_ready = jbr.ready
    zig_ready = zig.ready
    wasmtime_ready = wasmtime.ready
    jni_ready = jni.ready
    kotlin_native_ready = kotlin_native.ready
    checks = (jbr_ready, zig_ready, wasmtime_ready, jni_ready, kotlin_native_ready)

    if not all(checks):
        console.message()
        if not jbr_ready:
            console.message(f"Set JAVA_HOME to a JBR {versions['jbr_version']} directory.")
        if not zig_ready:
            console.message(f"Install Zig {versions['zig_version']} and add it to PATH.")
        if not wasmtime_ready:
            console.message("Run ./scripts/wasmline wasmtime download.")
        if not jni_ready:
            console.message("Run ./scripts/wasmline jni build --engine all.")
        if not kotlin_native_ready:
            console.message("Run ./scripts/wasmline kotlin-native build --target all.")
        return 2
    return 0
