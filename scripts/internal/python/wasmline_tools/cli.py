"""Entry point: parse the command line once, then dispatch a handler."""

from __future__ import annotations

from collections.abc import Sequence

from . import commands, handlers
from .commands import find_parser
from .terminal import Console


def _help_path(args: object) -> tuple[str, ...] | None:
    """Return the command path addressed by a `help` invocation, if any."""

    path = handlers.path_for(args)
    if "help" not in path:
        return None
    prefix = path[: path.index("help")]
    return tuple(prefix) + tuple(getattr(args, "path", ()) or ())


def run(argv: Sequence[str] | None = None) -> int:
    parser = commands.build_parser()
    handlers.install(parser)
    try:
        args = parser.parse_args(argv)
    except SystemExit as error:
        return error.code if isinstance(error.code, int) else 1

    wanted = _help_path(args)
    if wanted is not None:
        target = find_parser(parser, wanted)
        if target is None:
            console = Console()
            console.error("Argument", f"Unknown help topic: {' '.join(wanted)}")
            parser.print_usage(console.stream)
            return 2
        target.print_help()
        return 0

    handler = getattr(args, "handler", None)
    if handler is None:
        current = find_parser(parser, handlers.path_for(args))
        (current or parser).print_help()
        return 0

    try:
        return handler(args)
    except BrokenPipeError:
        return 0
    except SystemExit as error:
        if isinstance(error.code, int):
            return error.code
        Console().error("Command", str(error.code))
        return 1
    except BrokenPipeError:
        return 0
    except (OSError, RuntimeError) as error:
        Console().error("Command", str(error))
        return 1


def main(argv: Sequence[str] | None = None) -> int:
    return run(argv)


if __name__ == "__main__":
    raise SystemExit(main())
