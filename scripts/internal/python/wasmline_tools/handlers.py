"""Handlers that connect parsed arguments to the repository operations."""

from __future__ import annotations

import argparse
import sys
from collections.abc import Callable, Sequence

from . import doctor, wasmtime
from .commands import find_parser
from .support.process import run_shell
from .targets import load_targets
from .terminal import Console

Handler = Callable[[argparse.Namespace], int]
LINT_LANGUAGES = ("kotlin", "cpp", "zig")


def _doctor(_: argparse.Namespace) -> int:
    return doctor.run()


def _lint(args: argparse.Namespace) -> int:
    languages = args.languages or list(LINT_LANGUAGES)
    scope = "all files" if args.all else "changed files"
    mode = "format" if args.format else "check"
    Console(sys.stderr).info("Lint", f"{mode} {scope} for {', '.join(languages)}.")
    arguments = ["--all" if args.all else "--changed"]
    if args.format:
        arguments.append("--format")
    for language in languages:
        result = run_shell(f"internal/lint/{language}.sh", arguments)
        if result != 0:
            return result
    return 0


def _wasmtime_targets(_: argparse.Namespace) -> int:
    wasmtime.list_target_rows(Console(sys.stdout))
    return 0


def _wasmtime_download(args: argparse.Namespace) -> int:
    return wasmtime.download(
        target_id=args.target,
        engine=args.engine,
        jobs=args.jobs,
        proxy=args.proxy,
        force=args.force,
    )


def _jni_build(args: argparse.Namespace) -> int:
    return run_shell("internal/native/build-jni.sh", [args.engine])


def _kotlin_native_build(args: argparse.Namespace) -> int:
    if args.target == "all":
        arguments = [args.target]
        if args.engine and args.engine != "all":
            arguments.append(args.engine)
        return run_shell("internal/native/build-kotlin-native.sh", arguments)

    configured = next(
        (target for target in load_targets() if target.kotlin_native_target == args.target),
        None,
    )
    if configured is None:
        raise RuntimeError(f"Unknown Kotlin/Native target: {args.target}")
    if configured.install_path.startswith(("ios/", "mac/")) and sys.platform != "darwin":
        raise RuntimeError(f"Target {args.target} requires macOS.")

    selected_engine = args.engine or "pulley"
    engines = configured.engines if selected_engine == "all" else (selected_engine,)
    for engine in engines:
        if engine not in configured.engines:
            raise RuntimeError(
                f"Target {args.target} does not provide the {engine} engine."
            )
        result = run_shell("internal/native/build-kotlin-native.sh", [args.target, engine])
        if result != 0:
            return result
    return 0


VERSION_OPERATIONS = {
    "check": "check_current",
    "list": "list_current",
    "verify-upstream": "verify_upstream",
    "check-ktlint": "check_ktlint",
    "update-ktlint": "update_ktlint",
}


def _versions(args: argparse.Namespace) -> int:
    """Run one version operation without a second argument parser."""

    from . import versions

    if args.operation == "sync":
        return versions.synchronize(versions.parse_updates(args.set))
    operation = VERSION_OPERATIONS.get(args.operation)
    if operation is None:
        raise RuntimeError(f"Unknown versions operation: {args.operation}")
    return getattr(versions, operation)()


def _aot(args: argparse.Namespace) -> int:
    from .aot.sync import AotCompatibilityError, check_aot, sync_aot

    try:
        if args.operation == "sync":
            return sync_aot(proxy=args.proxy, jobs=args.jobs)
        if args.operation == "check":
            return check_aot()
        raise RuntimeError(f"Unknown AOT operation: {args.operation}")
    except (OSError, RuntimeError, AotCompatibilityError) as error:
        Console().error("AOT", str(error))
        return 1


HANDLERS: dict[tuple[str, ...], Handler] = {
    ("doctor",): _doctor,
    ("lint",): _lint,
    ("wasmtime", "targets"): _wasmtime_targets,
    ("wasmtime", "download"): _wasmtime_download,
    ("jni", "build"): _jni_build,
    ("kotlin-native", "build"): _kotlin_native_build,
    ("versions", "sync"): _versions,
    ("versions", "check"): _versions,
    ("versions", "list"): _versions,
    ("versions", "verify-upstream"): _versions,
    ("versions", "check-ktlint"): _versions,
    ("versions", "update-ktlint"): _versions,
    ("aot", "sync"): _aot,
    ("aot", "check"): _aot,
}


def install(parser: argparse.ArgumentParser) -> None:
    """Bind each command path to its handler exactly once."""

    for path, handler in HANDLERS.items():
        target = find_parser(parser, path)
        if target is None:
            raise RuntimeError(f"Handler registered for an unknown command: {' '.join(path)}")
        target.set_defaults(handler=handler)


def path_for(args: argparse.Namespace) -> tuple[str, ...]:
    """Return the command path recorded by the parser defaults."""

    return tuple(value for value in (getattr(args, "command", None), *_operation(args)) if value)


def _operation(args: argparse.Namespace) -> Sequence[str]:
    for name in ("wasmtime_command", "jni_command", "native_command"):
        value = getattr(args, name, None)
        if value:
            return (value,)
    command = getattr(args, "command", None)
    if command in {"versions", "aot"}:
        operation = getattr(args, "operation", None)
        return (operation,) if operation else ()
    return ()
