"""Command-line argument definitions for the repository tooling."""

from __future__ import annotations

import argparse
import shutil
import sys
from collections.abc import Sequence

from .targets import ENGINES


class HelpFormatter(argparse.RawDescriptionHelpFormatter):
    """Wrap help to the terminal width and keep example line breaks."""

    def __init__(self, prog: str) -> None:
        width = min(max(_terminal_width(), 60), 100)
        super().__init__(prog, max_help_position=28, width=width)


class ArgumentParser(argparse.ArgumentParser):
    def __init__(self, *args: object, **kwargs: object) -> None:
        kwargs.setdefault("formatter_class", HelpFormatter)
        super().__init__(*args, **kwargs)

    def error(self, message: str) -> None:
        from .terminal import Console

        console = Console(sys.stderr)
        console.error("Argument", message)
        self.print_usage(sys.stderr)
        raise SystemExit(2)


def _terminal_width() -> int:
    return shutil.get_terminal_size().columns


def _help_command(subparsers: argparse._SubParsersAction, name: str) -> None:
    """Register `help` so a command group can never reject it."""

    parser = subparsers.add_parser(name, help="Show help for a command.")
    parser.add_argument("path", nargs="*", metavar="COMMAND")


def _order_groups(parser: argparse.ArgumentParser) -> None:
    """Show commands before options, then recurse into every subcommand."""

    subparsers = next(
        (action for action in parser._actions if isinstance(action, argparse._SubParsersAction)),
        None,
    )
    if subparsers is None:
        return
    group = next(
        (item for item in parser._action_groups if item.title == "Commands"),
        None,
    )
    if group is not None:
        parser._action_groups.remove(group)
        options = next(
            (item for item in parser._action_groups if item.title == "options"),
            None,
        )
        position = parser._action_groups.index(options) if options else len(parser._action_groups)
        parser._action_groups.insert(position, group)
    for child in subparsers.choices.values():
        _order_groups(child)


def add_help_command(parser: argparse.ArgumentParser) -> None:
    """Attach the `help` navigation command to a parser with subcommands."""

    for action in parser._actions:
        if isinstance(action, argparse._SubParsersAction):
            _help_command(action, "help")
            return


def build_parser() -> argparse.ArgumentParser:
    """Build the single command tree used by both entry points."""

    parser = ArgumentParser(
        prog="./scripts/wasmline",
        description="Wasmline repository commands.",
        epilog=(
            "Examples:\n"
            "  ./scripts/wasmline doctor\n"
            "  ./scripts/wasmline lint --changed kotlin\n"
            "  ./scripts/wasmline versions check\n"
            "  ./scripts/wasmline help jni build"
        ),
    )
    commands = parser.add_subparsers(dest="command", metavar="COMMAND", title="Commands")
    add_help_command(parser)

    commands.add_parser("doctor", help="Check required tools and generated libraries.")

    lint = commands.add_parser(
        "lint",
        help="Check or format repository source files.",
        epilog="Example: ./scripts/wasmline lint --changed kotlin",
    )
    scope = lint.add_mutually_exclusive_group()
    scope.add_argument("--all", action="store_true", help="Process every supported source file.")
    scope.add_argument("--changed", action="store_true", help="Process changed and untracked files.")
    lint.add_argument("--format", action="store_true", help="Format files instead of checking them.")
    lint.add_argument(
        "languages",
        nargs="*",
        choices=("kotlin", "cpp", "zig"),
        help="Languages to process (default: all).",
    )

    wasmtime = commands.add_parser("wasmtime", help="Manage Wasmtime headers and libraries.")
    wasmtime_commands = wasmtime.add_subparsers(
        dest="wasmtime_command", metavar="COMMAND", title="Commands"
    )
    add_help_command(wasmtime)
    wasmtime_commands.add_parser("targets", help="List supported download targets.")
    download = wasmtime_commands.add_parser("download", help="Download exact Wasmtime release assets.")
    download.add_argument(
        "--target",
        default="all",
        metavar="TARGET",
        help="Target id from 'wasmtime targets' (default: all).",
    )
    download.add_argument(
        "--engine",
        default="all",
        choices=(*ENGINES, "all"),
        metavar="ENGINE",
        help="Engine to download: pulley, cranelift, or all (default: all).",
    )
    download.add_argument("--jobs", type=int, help="Limit concurrent downloads.")
    download.add_argument("--proxy", help="HTTP proxy URL or host:port.")
    download.add_argument("--force", action="store_true", help="Replace files that already exist.")

    jni = commands.add_parser("jni", help="Build JVM and Android engine libraries.")
    jni_commands = jni.add_subparsers(dest="jni_command", metavar="COMMAND", title="Commands")
    add_help_command(jni)
    jni_build = jni_commands.add_parser("build", help="Build configured JVM and Android libraries.")
    jni_build.add_argument(
        "--engine",
        choices=(*ENGINES, "all"),
        default="all",
        help="Engine to build (default: all).",
    )

    native = commands.add_parser("kotlin-native", help="Build Kotlin/Native engine libraries.")
    native_commands = native.add_subparsers(
        dest="native_command", metavar="COMMAND", title="Commands"
    )
    add_help_command(native)
    native_build = native_commands.add_parser(
        "build",
        help="Build Kotlin/Native static libraries.",
    )
    native_build.add_argument(
        "--target",
        default="all",
        metavar="TARGET",
        help=(
            "linuxArm64, linuxX64, mingwX64, macosArm64, macosX64, "
            "iosArm64, iosSimulatorArm64, or all (default: all)."
        ),
    )
    native_build.add_argument(
        "--engine",
        choices=(*ENGINES, "all"),
        help="Defaults to all engines for all targets and pulley for one target.",
    )

    versions = commands.add_parser("versions", help="Read and synchronize managed versions.")
    version_commands = versions.add_subparsers(
        dest="operation", metavar="COMMAND", title="Commands"
    )
    add_help_command(versions)
    sync = version_commands.add_parser("sync", help="Synchronize managed files.")
    sync.add_argument("--set", action="append", default=[], metavar="KEY=VALUE")
    for operation, help_text in (
        ("check", "Check managed files without changing them."),
        ("list", "Print version values as key=value."),
        ("verify-upstream", "Compare the toolchain lock with GitHub releases."),
        ("check-ktlint", "Check for a newer stable ktlint release."),
        ("update-ktlint", "Update ktlint and synchronize managed files."),
    ):
        version_commands.add_parser(operation, help=help_text)

    aot = commands.add_parser(
        "aot",
        help="Validate and synchronize the native AOT compatibility catalog.",
    )
    aot_commands = aot.add_subparsers(dest="operation", metavar="COMMAND", title="Commands")
    add_help_command(aot)
    aot_sync = aot_commands.add_parser(
        "sync",
        help="Generate the packaged AOT lock, public resource, and native identity.",
    )
    aot_sync.add_argument("--proxy", help="HTTP proxy URL or host:port for new release metadata.")
    aot_sync.add_argument(
        "--jobs",
        type=int,
        help="Limit concurrent compiler archive verification for a new distribution.",
    )
    aot_commands.add_parser(
        "check",
        help="Validate the public catalog and generated AOT files without writing.",
    )
    _order_groups(parser)
    return parser


def find_parser(parser: argparse.ArgumentParser, path: Sequence[str]) -> argparse.ArgumentParser | None:
    """Walk the command tree and return the parser addressed by `path`."""

    current = parser
    for name in path:
        subparsers = next(
            (action for action in current._actions if isinstance(action, argparse._SubParsersAction)),
            None,
        )
        if subparsers is None or name not in subparsers.choices:
            return None
        current = subparsers.choices[name]
    return current
