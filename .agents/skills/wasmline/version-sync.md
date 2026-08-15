# Version Synchronization Guide

## Source of Truth

`scripts/versions.json` is the repository manifest for duplicated project and toolchain versions.

| Key | Scope |
| --- | --- |
| `wasmline_version` | Maven version for every Wasmline module and application-facing project version references |
| `sample_plugin_version` | Sample manifests, package output paths, and sample-plugin defaults |
| `wasmtime_version` | Native assets, Wasmtime compiler/runtime defaults, documentation, and CI fallbacks |
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
python3 scripts/sync_version.py --check
python3 scripts/sync_version.py --set key=value
```

`scripts/sync_version.py` delegates to the implementation in `scripts/sync_versions.py`. The plural command remains available for existing automation and accepts the same arguments.

## Change Procedure

1. Run `--set` for each intended manifest change.
2. Inspect every file listed by the synchronizer.
3. Update any version-coupled checksum or release metadata that cannot be derived from the version string.
4. Run `--check`.
5. Run `python3 scripts/test_sync_versions.py`.
6. Inspect the final diff for unrelated replacements.

Example:

```bash
python3 scripts/sync_version.py --set wasmtime_version=<major.minor.patch>
python3 scripts/sync_version.py --check
python3 scripts/test_sync_versions.py
```

Do not edit `scripts/versions.json` first and repair duplicates manually. The synchronizer renders all managed files in memory before writing them, which prevents an unmatched rule from producing a partial managed-file update.

## Adding a Version Reference

When code or documentation duplicates a manifest value:

1. Add or extend a `FileSpec` in `scripts/sync_versions.py`.
2. Use a narrow pattern anchored to stable surrounding text.
3. Keep `min_count=1` for required references. Use `min_count=0` only when the reference is genuinely optional.
4. Add the path to the synthetic coverage in `scripts/test_sync_versions.py`.
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

## Wasmtime-coupled Assets

`ToolchainCatalog.WASMTIME_VERSION` follows `wasmtime_version`, but the WASI Preview 1 adapter SHA-256 is a release-specific security value. The synchronizer cannot derive that checksum. A Wasmtime upgrade must update and verify the adapter digest in `ToolchainCatalog.kt` before the change is complete.

The same rule applies to any future pinned download asset: synchronize its version reference, then update its trusted digest from the authoritative release metadata.

## Required Verification

```bash
python3 scripts/sync_version.py --check
python3 scripts/test_sync_versions.py
```

`--check` validates the repository's current values. The regression suite also renders synthetic values, which detects stale patterns that happen to match the current manifest but would fail during the next upgrade.
