# Version Synchronization Guide

## Contents

- [Source of Truth](#source-of-truth)
- [Entry Points](#entry-points)
- [Change Procedure](#change-procedure)
- [Adding a Version Reference](#adding-a-version-reference)
- [Values That Must Remain Independent](#values-that-must-remain-independent)
- [Generated Toolchain Lock](#generated-toolchain-lock)
- [Required Verification](#required-verification)

## Source of Truth

`scripts/versions.json` is the repository manifest for duplicated project and toolchain versions.

| Key | Scope |
| --- | --- |
| `wasmline_version` | Maven version for every Wasmline module and application-facing project version references |
| `sample_plugin_version` | Sample manifests, package output paths, and sample-plugin defaults |
| `wasmtime_version` | Native assets, Wasmtime compiler/runtime defaults, documentation, and CI fallbacks |
| `wasm_tools_version` | Downloaded wasm-tools assets and Component build-tool defaults |
| `wit_bindgen_version` | Downloaded wit-bindgen CLI assets and Component binding-tool defaults |
| `kotlin_version` | Kotlin plugin and documentation references |
| `kotlin_min_version` | Minimum supported Kotlin version in documentation |
| `agp_version` | Android Gradle Plugin and documentation references |
| `zig_version` | Native build tooling and documentation references |
| `jbr_version` | Gradle daemon and Java toolchain references |

Do not add a second authoritative version file.

## Entry Points

Use the singular command in documentation and normal work:

```bash
python3 scripts/sync_version.py --list
python3 scripts/sync_version.py
python3 scripts/sync_version.py --check
python3 scripts/sync_version.py --set key=value
python3 scripts/sync_version.py --verify-upstream
```

`scripts/sync_version.py` is the only version-synchronization entry point. It contains the synchronization rules and uses `toolchain_lock.py` internally when release metadata is required.

## Change Procedure

1. Edit `scripts/versions.json` and run `python3 scripts/sync_version.py`, or supply every intended change through `--set`.
2. For a Component toolchain key, allow the synchronizer to resolve every required GitHub release asset.
3. Inspect every file listed by the synchronizer, including the generated toolchain lock.
4. Run `--check`.
5. Run `--verify-upstream` when network verification is required.
6. Run `python3 scripts/test_sync_version.py`.
7. Inspect the final diff for unrelated replacements.

Example:

```bash
# Synchronize values edited directly in scripts/versions.json.
python3 scripts/sync_version.py

# Alternatively, update the manifest and synchronize in one command.
python3 scripts/sync_version.py \
  --set wasmtime_version=<major.minor.patch> \
  --set wasm_tools_version=<major.minor.patch> \
  --set wit_bindgen_version=<major.minor.patch>
python3 scripts/sync_version.py --check
python3 scripts/test_sync_version.py
```

Direct manifest edits are supported. Normal synchronization refreshes the toolchain lock when its versions trail the manifest, then renders all managed files in memory before writing derived files. If release resolution or a synchronization rule fails, the edited manifest remains unchanged and derived files are not written.

## Adding a Version Reference

When code or documentation duplicates a manifest value:

1. Add or extend a `FileSpec` in `scripts/sync_version.py`.
2. Use a narrow pattern anchored to stable surrounding text.
3. Keep `min_count=1` for required references. Use `min_count=0` only when the reference is genuinely optional.
4. Add the path to the synthetic coverage in `scripts/test_sync_version.py`.
5. Assert a rendered fragment that proves the correct manifest key was used.
6. Run both synchronization checks.

Files can have more than one `FileSpec`; rules are applied in declaration order to the same in-memory text.

## Values That Must Remain Independent

Not every semantic-looking value belongs in `versions.json`. Keep these independent unless their owning protocol changes:

- WIT package and interface versions such as `wasmline:service@1.0.0`
- response-frame versions
- serialization protocol identifiers
- test values that intentionally compare two arbitrary versions
- third-party dependency versions not represented by a manifest key

Classify a value by meaning, not by its numeric shape.

Kotlin code obtains Component CLI tool versions from `ToolchainCatalog`. Do not add synchronization rules that rewrite `.kt` files for `wasm_tools_version` or `wit_bindgen_version`. Tests that exercise catalog defaults must read the catalog; unrelated fixture versions remain independent.

## Generated Toolchain Lock

The packaged lock at `wasmline-multiplatform/wasmline-plugin-core/src/main/resources/META-INF/wasmline/toolchain/toolchain-lock.json` is derived from `scripts/versions.json`. It records the GitHub release, asset ID, size, URL, and SHA-256 for every supported Component tool platform.

Do not edit the lock or `ToolchainCatalog.kt` to perform a version upgrade. Changing `wasmtime_version`, `wasm_tools_version`, or `wit_bindgen_version` in the manifest and running `sync_version.py` resolves and validates all three locked releases before writing derived files. The same behavior applies when versions are supplied through `--set`. The WASI Preview 1 adapter version remains derived from `wasmtime_version` and has no independent manifest key.

`--check` validates the checked-in manifest, managed references, and lock without network access or lock refresh. `--verify-upstream` performs the separate network check and fails if current GitHub release metadata differs from the checked-in lock.

## Required Verification

```bash
python3 scripts/sync_version.py --check
python3 scripts/test_sync_version.py
```

`--check` validates the repository's current values. The regression suite also renders synthetic values, which detects stale patterns that happen to match the current manifest but would fail during the next upgrade.
