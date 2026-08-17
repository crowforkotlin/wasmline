# Scripts

Repository automation is grouped by purpose:

| Path | Purpose |
| --- | --- |
| `lint/` | Language format checks and formatting commands |
| `lib/` | Shared Bash context, paths, and terminal output helpers |
| `samples/` | Sample build and run helpers |
| `init-wasmtime.*` | Wasmtime platform asset initialization |
| `build-native-assets.sh` | Native engine asset build and deployment |
| `compile-ios.sh` | iOS native bridge static-library compilation |
| `doctor.sh` | Local environment preflight |
| `versions.json` | Source of truth for managed project and toolchain versions |
| `sync_version.py` | Version-synchronization entry point and implementation |
| `toolchain_lock.py` | Internal GitHub release resolution and toolchain-lock validation |
| `test_sync_version.py` | Regression tests; not part of the update command |

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

Inspect or update versions with the singular entry point:

```bash
python3 scripts/sync_version.py --list

# Synchronize after editing scripts/versions.json.
python3 scripts/sync_version.py

# Update the manifest and synchronize in one command.
python3 scripts/sync_version.py \
  --set wasmtime_version=<new-version> \
  --set wasm_tools_version=<new-version> \
  --set wit_bindgen_version=<new-version>
```

Run validation separately when required:

```bash
python3 scripts/sync_version.py --check
python3 scripts/sync_version.py --verify-upstream
python3 scripts/test_sync_version.py
```

`sync_version.py` is the only supported update command. With no arguments, it
reads `versions.json` and synchronizes every managed reference. The `--set`
option updates the manifest before performing the same synchronization.
`test_sync_version.py` is an independent regression suite and does not update
repository versions.

When adding a duplicated version reference, add a narrow rule to
`sync_version.py` and synthetic coverage to `test_sync_version.py` in the same
change.

Changing any Component toolchain version causes normal synchronization to
resolve all required GitHub release assets before derived files are written.
Kotlin code and live tests read Component tool versions from `ToolchainCatalog`;
changes to `wasm_tools_version` or `wit_bindgen_version` do not rewrite `.kt`
files. Version strings used as independent test fixtures remain unmanaged.
The generated lock is packaged from
`wasmline-plugin-core/src/main/resources/META-INF/wasmline/toolchain/` and must
not be edited manually. `--check` validates local consistency without network
access or lock refresh. `--verify-upstream` compares the checked-in lock with
current GitHub release metadata.

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
