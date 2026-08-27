"""Command-line interface for repository tooling."""

from __future__ import annotations

import argparse
import subprocess
import sys
from collections.abc import Callable, Sequence

from . import doctor, wasmtime
from .output import Console
from .paths import SCRIPTS_ROOT
from .targets import ENGINES, load_targets


Handler = Callable[[argparse.Namespace], int]


class HelpFormatter(argparse.HelpFormatter):
    def __init__(self, prog: str) -> None:
        super().__init__(prog, max_help_position=28, width=88)


class ArgumentParser(argparse.ArgumentParser):
    def __init__(self, *args: object, **kwargs: object) -> None:
        kwargs.setdefault("formatter_class", HelpFormatter)
        super().__init__(*args, **kwargs)

    def error(self, message: str) -> None:
        if message.startswith("the following arguments are required:"):
            self.print_help(sys.stderr)
            raise SystemExit(2)
        Console().error("Argument", message)
        raise SystemExit(2)


def _show_help(parser: argparse.ArgumentParser) -> Handler:
    def show_help(_: argparse.Namespace) -> int:
        parser.print_help()
        return 0

    return show_help


def _run_shell(relative_path: str, arguments: list[str]) -> int:
    command = ["bash", str(SCRIPTS_ROOT / relative_path), *arguments]
    return subprocess.run(command, check=False).returncode


def _doctor(_: argparse.Namespace) -> int:
    return doctor.run()


def _lint(args: argparse.Namespace) -> int:
    languages = args.languages or ["kotlin", "cpp", "zig"]
    arguments = ["--all" if args.all else "--changed"]
    if args.format:
        arguments.append("--format")
    for language in languages:
        result = _run_shell(f"internal/lint/{language}.sh", arguments)
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
    return _run_shell("internal/native/build-jni.sh", [args.engine])


def _kotlin_native_build(args: argparse.Namespace) -> int:
    if args.target == "all":
        arguments = [args.target]
        if args.engine and args.engine != "all":
            arguments.append(args.engine)
        return _run_shell("internal/native/build-kotlin-native.sh", arguments)

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
        result = _run_shell("internal/native/build-kotlin-native.sh", [args.target, engine])
        if result != 0:
            return result
    return 0


def _versions(args: argparse.Namespace) -> int:
    from . import versions

    command_arguments: list[str]
    if args.operation == "sync":
        command_arguments = [item for value in args.set for item in ("--set", value)]
    elif args.operation == "check":
        command_arguments = ["--check"]
    elif args.operation == "list":
        command_arguments = ["--list"]
    elif args.operation == "verify-upstream":
        command_arguments = ["--verify-upstream"]
    elif args.operation == "check-ktlint":
        command_arguments = ["--check-ktlint-latest"]
    elif args.operation == "update-ktlint":
        command_arguments = ["--update-ktlint"]
    else:
        raise RuntimeError(f"Unknown versions operation: {args.operation}")
    try:
        return versions.main(command_arguments)
    except SystemExit as error:
        if isinstance(error.code, int):
            return error.code
        Console().error("Versions", str(error.code))
        return 1


def _parser() -> argparse.ArgumentParser:
    parser = ArgumentParser(prog="./scripts/wasmline")
    parser.set_defaults(handler=_show_help(parser))
    commands = parser.add_subparsers(dest="command", metavar="COMMAND")

    doctor_parser = commands.add_parser("doctor", help="Check required tools and generated libraries.")
    doctor_parser.set_defaults(handler=_doctor)

    lint_parser = commands.add_parser("lint", help="Check or format repository source files.")
    lint_scope = lint_parser.add_mutually_exclusive_group()
    lint_scope.add_argument("--all", action="store_true", help="Process every supported source file.")
    lint_scope.add_argument("--changed", action="store_true", help="Process changed and untracked files.")
    lint_parser.add_argument("--format", action="store_true", help="Format files instead of checking them.")
    lint_parser.add_argument(
        "languages",
        nargs="*",
        choices=("kotlin", "cpp", "zig"),
        help="Languages to process (default: all).",
    )
    lint_parser.set_defaults(handler=_lint)

    wasmtime_parser = commands.add_parser("wasmtime", help="Manage Wasmtime headers and libraries.")
    wasmtime_parser.set_defaults(handler=_show_help(wasmtime_parser))
    wasmtime_commands = wasmtime_parser.add_subparsers(
        dest="wasmtime_command", metavar="COMMAND"
    )
    targets_parser = wasmtime_commands.add_parser("targets", help="List supported download targets.")
    targets_parser.set_defaults(handler=_wasmtime_targets)
    download_parser = wasmtime_commands.add_parser("download", help="Download exact Wasmtime release assets.")
    download_parser.add_argument(
        "--target",
        default="all",
        metavar="TARGET",
        help="Target id from 'wasmtime targets' (default: all).",
    )
    download_parser.add_argument(
        "--engine",
        default="all",
        choices=(*ENGINES, "all"),
        metavar="ENGINE",
        help="Engine to download: pulley, cranelift, or all (default: all).",
    )
    download_parser.add_argument("--jobs", type=int, help="Limit concurrent downloads.")
    download_parser.add_argument("--proxy", help="HTTP proxy URL or host:port.")
    download_parser.add_argument("--force", action="store_true", help="Replace files that already exist.")
    download_parser.set_defaults(handler=_wasmtime_download)

    jni_parser = commands.add_parser("jni", help="Build JVM and Android engine libraries.")
    jni_parser.set_defaults(handler=_show_help(jni_parser))
    jni_commands = jni_parser.add_subparsers(dest="jni_command", metavar="COMMAND")
    jni_build_parser = jni_commands.add_parser(
        "build",
        help="Build configured JVM and Android libraries.",
    )
    jni_build_parser.add_argument(
        "--engine",
        choices=(*ENGINES, "all"),
        default="all",
        help="Engine to build (default: all).",
    )
    jni_build_parser.set_defaults(handler=_jni_build)

    native_parser = commands.add_parser("kotlin-native", help="Build Kotlin/Native engine libraries.")
    native_parser.set_defaults(handler=_show_help(native_parser))
    native_commands = native_parser.add_subparsers(
        dest="native_command", metavar="COMMAND"
    )
    native_build_parser = native_commands.add_parser(
        "build",
        help="Build Kotlin/Native static libraries.",
    )
    native_build_parser.add_argument(
        "--target",
        default="all",
        metavar="TARGET",
        help=(
            "linuxArm64, linuxX64, mingwX64, macosArm64, macosX64, "
            "iosArm64, iosSimulatorArm64, or all (default: all)."
        ),
    )
    native_build_parser.add_argument(
        "--engine",
        choices=(*ENGINES, "all"),
        help="Defaults to all engines for all targets and pulley for one target.",
    )
    native_build_parser.set_defaults(handler=_kotlin_native_build)

    versions_parser = commands.add_parser("versions", help="Read and synchronize managed versions.")
    versions_parser.set_defaults(handler=_show_help(versions_parser))
    version_commands = versions_parser.add_subparsers(
        dest="operation", metavar="COMMAND"
    )
    sync_parser = version_commands.add_parser("sync", help="Synchronize managed files.")
    sync_parser.add_argument("--set", action="append", default=[], metavar="KEY=VALUE")
    version_help = {
        "check": "Check managed files without changing them.",
        "list": "Print version values as key=value.",
        "verify-upstream": "Compare the toolchain lock with GitHub releases.",
        "check-ktlint": "Check for a newer stable ktlint release.",
        "update-ktlint": "Update ktlint and synchronize managed files.",
    }
    for operation, help_text in version_help.items():
        version_commands.add_parser(operation, help=help_text)
    for child in version_commands.choices.values():
        child.set_defaults(handler=_versions)

    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    handler: Handler = args.handler
    try:
        return handler(args)
    except (OSError, RuntimeError) as error:
        Console().error("Command", str(error))
        return 1
