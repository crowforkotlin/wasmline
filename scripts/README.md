# Repository Commands

Use `./scripts/wasmline` for repository checks, formatting, Wasmtime downloads,
native library builds, and version synchronization.

```text
scripts/
|-- wasmline                     Public command
|-- versions.json                Managed version source
|-- config/                      Platform and artifact configuration
|-- lib/
|   |-- python/wasmline_tools/   Command implementation
|   `-- shell/                   Shared Shell paths, output, and helpers
|-- internal/
|   |-- lint/                    Language-specific format commands
|   `-- native/                  JNI and Kotlin/Native build backends
`-- tests/                       Repository tooling tests
```

Files under `lib/` and `internal/` are implementation details. CI,
documentation, and normal development commands use the public entry point.
Gradle calls the Kotlin/Native build backend directly as part of its task graph.

## Environment Checks

```bash
./scripts/wasmline doctor
```

Doctor checks JBR 21, Zig 0.16.0, the downloaded Wasmtime files, the JVM and
Android JNI libraries, and the current host's Kotlin/Native libraries. The
required JBR, Zig, and Wasmtime versions come from `versions.json`.

## Wasmtime Files

The runtime-compatible `wasmtime_version` and the exact downstream
`wasmtime_release_version` come from `versions.json`. The download command uses
the four-segment release version for the GitHub tag and complete archive names;
it never selects the latest release. Every archive is verified against the
release's `SHA256SUMS` before extraction.

```bash
# Show target ids and supported engines.
./scripts/wasmline wasmtime targets

# Download every configured target and engine (the default).
./scripts/wasmline wasmtime download

# Download one target and engine instead.
./scripts/wasmline wasmtime download \
  --target linux-x64 \
  --engine pulley

# Use an HTTP proxy.
./scripts/wasmline wasmtime download \
  --target linux-x64 \
  --engine cranelift \
  --proxy 127.0.0.1:7890
```

Downloads are installed under
`build/platforms/v<release-version>/<engine>/<platform>/`. Both the target and
engine default to `all`; existing complete targets are reused unless `--force`
is supplied. Downloads run concurrently without a default limit; use `--jobs`
to impose one.

## Engine Libraries

Wasmtime files must be present before building engine libraries.

```bash
# Build and deploy JVM and Android JNI libraries.
./scripts/wasmline jni build --engine all
./scripts/wasmline jni build --engine pulley
./scripts/wasmline jni build --engine cranelift

# Build one Kotlin/Native library.
./scripts/wasmline kotlin-native build \
  --target linuxX64 \
  --engine pulley

# Build every target supported by the current host.
./scripts/wasmline kotlin-native build --target all
```

JNI builds use Android CMake and the NDK for Android libraries, and Zig for JVM
desktop libraries. Kotlin/Native builds use the target toolchains downloaded by
Kotlin/Native and write `libwasmline_native.a` under
`wasmline-multiplatform/wasmline/build/native/`.

## Formatting

The default scope contains changed and untracked files. Use `--all` for the CI
scope and `--format` to modify files.

```bash
./scripts/wasmline lint
./scripts/wasmline lint kotlin zig
./scripts/wasmline lint --all
./scripts/wasmline lint --format
./scripts/wasmline lint --all --format kotlin
```

Kotlin uses the exact `ktlint_version` in `versions.json`, reusing a matching
binary on `PATH` or downloading it under `build/tools/ktlint/`. C and C++ use
clang-format. Zig and ZON files use `zig fmt`.

## Versions

`versions.json` is the only source for duplicated project and tool versions.

```bash
./scripts/wasmline versions list
./scripts/wasmline versions sync
./scripts/wasmline versions sync \
  --set wasmtime_version=<upstream-version> \
  --set wasmtime_release_version=<downstream-release-version>
./scripts/wasmline versions check
./scripts/wasmline versions verify-upstream
./scripts/wasmline versions check-ktlint
./scripts/wasmline versions update-ktlint
```

The implementation is under `lib/python/wasmline_tools/`. Version and Wasmtime
tooling tests are under `tests/`; they are not part of normal commands.

## Output

Human-readable output uses the same status words in every command:

```text
INFO    Selected input or current operation
OK      Completed operation
ERR     Failed requirement or operation
SKIP    Nothing matched the requested scope
```

Colors are enabled only for a terminal and can be disabled with `NO_COLOR`.
Native build logs are written to stderr.
