# Scripts

Repository automation is grouped by purpose:

| Path | Purpose |
| --- | --- |
| `lint/` | Language format checks and formatting commands |
| `lib/` | Shared Bash context, paths, and terminal output helpers |
| `samples/` | Sample build and run helpers |
| `init-wasmtime.*` | Wasmtime platform asset initialization |
| `build-native-assets.sh` | Native engine asset build and deployment |
| `doctor.sh` | Local environment preflight |
| `versions.json` | Source of truth for managed project and toolchain versions |
| `sync_version.py` | Public version-synchronization entry point |
| `sync_versions.py` | Synchronizer implementation and compatibility entry point |
| `test_sync_versions.py` | Regression coverage for managed files and replacement rules |

C and C++ Component fixture commands live with their language samples:

```bash
bash wasmline-samples/c/configure.sh
bash wasmline-samples/c/build.sh
bash wasmline-samples/cpp/configure.sh
bash wasmline-samples/cpp/build.sh
```

They require `WASI_SDK_PATH` for WASI SDK 33, plus pinned `wit-bindgen` and
`wasm-tools` tools. The result is a Component Wasm file, not a native program,
so there is intentionally no `run.sh`.

## Version Synchronization

Use the singular entry point for normal work:

```bash
python3 scripts/sync_version.py --list
python3 scripts/sync_version.py --check
python3 scripts/sync_version.py --set wasmtime_version=<new-version>
python3 scripts/test_sync_versions.py
```

The plural entry point accepts the same arguments for existing automation. When
adding a duplicated version reference, add a narrow rule to `sync_versions.py`
and synthetic coverage to `test_sync_versions.py` in the same change.

## Linting

`bash scripts/lint.sh` is the main lint entry point. It defaults to changed
and untracked source files, so normal local work does not scan the entire
repository.

```bash
# Check changed Kotlin, C/C++, and Zig sources.
bash scripts/lint.sh

# Check only selected languages.
bash scripts/lint.sh kotlin zig

# Check every supported source file, as used by CI.
bash scripts/lint.sh --all

# Format changed sources, or format every supported source file.
bash scripts/lint.sh format
bash scripts/lint.sh --all format
```

Language-specific commands are available under `scripts/lint/`:

```bash
bash scripts/lint.sh [--changed|--all] [check|format] [kotlin|cpp|zig ...]
```

Kotlin uses `ktlint` and `wasmline-multiplatform/.editorconfig` across the
existing `wasmline-multiplatform/` lint domain. C/C++ uses `clang-format` with
`wasmline-core/.clang-format` across the existing `wasmline-core/` lint domain.
Zig and ZON use Zig's built-in `zig fmt` formatter. The language-specific
implementations are internal to `scripts/lint/`; use `scripts/lint.sh`.
