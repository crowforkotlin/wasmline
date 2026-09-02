# AOT Compatibility and Release Catalog

This reference defines the native AOT compatibility policy implemented by the
Wasmline repository. It applies to `.cwasm` and `.pwasm` artifacts only. A raw
Web `.wasm` artifact does not use a Wasmtime AOT profile and is outside the
compatibility warning.

## Contents

- [Sources and generated views](#sources-and-generated-views)
- [Generation policy](#generation-policy)
- [Gradle selection](#gradle-selection)
- [Advisory compatibility check](#advisory-compatibility-check)
- [Release procedure](#release-procedure)
- [Maintenance rules](#maintenance-rules)

## Sources and generated views

The root `aot-compatibility.json` is the only manually maintained AOT
compatibility source. It contains release range and generation metadata only.
The scalar project and toolchain versions remain in `versions.json`.

`./scripts/wasmline aot sync` validates the source and generates the internal
lock plus the packaged public resource:

- `wasmline-multiplatform/wasmline-plugin-core/src/main/resources/META-INF/wasmline/aot/aot-compatibility-lock.json`;
- `wasmline-multiplatform/wasmline-plugin-core/src/main/resources/META-INF/wasmline/aot/aot-compatibility.json`.

The same command also generates the runtime identity constants used by both
sides of the native bridge:

- `wasmline-core/include/wasmline/internal/runtime/NativeBuildIdentity.h`;
- `wasmline-multiplatform/wasmline/src/commonMain/kotlin/crow/wasmline/WasmlineReleaseIdentity.kt`.

The root `aot-compatibility.json` is edited by maintainers when a release range
or generation changes. The synchronizer never overwrites that source file.

When an appended generation names a fork distribution that is not present in
the detailed lock, `aot sync` resolves the exact `crowforkotlin/wasmtime`
release and tag revision. It verifies `SHA256SUMS`, downloads the five full CLI
archives concurrently, verifies each archive, hashes the contained executable,
and then writes the new immutable profile, asset, and build-host bindings. The
archives are temporary and are not retained. Existing distributions are
synchronized without network access. `aot check` is always offline.

The two public files must be byte-identical. They intentionally omit profile
digests and compiler assets. The packaged
`META-INF/wasmline/aot/aot-compatibility-lock.json` keeps the detailed binding
needed by the build. Consumers resolve a range through that local lock and pass
exact backend-specific profile IDs to the AOT compiler.

The public catalog has this shape:

```json
{
  "schemaVersion": 1,
  "currentWasmlineVersion": "1.0.0",
  "minimumSupportedWasmlineVersion": "1.0.0",
  "ranges": [
    {
      "fromWasmlineVersion": "1.0.0",
      "aotGeneration": 1,
      "wasmtimeDistributionVersion": "48.0.1.1",
      "changedBackends": ["CRANELIFT", "PULLEY"]
    }
  ]
}
```

Each range starts at `fromWasmlineVersion` and ends immediately before the next
range start. The final range ends at `currentWasmlineVersion`.

## Generation policy

`aotGeneration` is a sequential release label, not a runtime credential and not
a shortened Wasmtime version. Generation `1` is the first formal generation.
Generation numbers are continuous and append-only.

Create a new generation when either backend profile identity changes. A change
to the Wasmtime fork distribution, serialized artifact format, compiler profile
schema, engine configuration, or another field included in the canonical
profile identity requires a new generation. A Wasmline release that retains
both profile IDs stays in the existing generation, even when its Maven version
changes. Updating only a download mirror or an equivalent compiler asset does
not create a generation.

The first generation lists both `CRANELIFT` and `PULLEY` in `changedBackends`.
Later values are derived by the synchronizer from adjacent profile bindings and
must match the actual changed backend set. Previously published ranges,
profiles, compiler bindings, and generation numbers cannot be edited, removed,
or reused.

## Gradle selection

Native AOT builds require exactly one explicit selector in the Wasmtime DSL:

```kotlin
wasmline {
    wasmtime {
        aotCompatibility {
            current()
            // minimum()
            // all()
            // versionRanges {
            //     include(from = "1.0.0", through = "1.20.0")
            // }
        }
    }
}
```

- `current()` selects the generation bound to the current Wasmline release.
- `minimum()` selects generations from the effective minimum supported
  version to the current release.
- `all()` selects all formal generations retained in the local catalog.
- `versionRanges {}` selects the generations intersecting explicit inclusive
  `x.y.z` ranges. `from` and `through` are named endpoints.

Selectors are mutually exclusive. An omitted selector, a duplicate selector,
an empty range block, an invalid version, or a range outside the catalog is a
configuration error. Selection is resolved entirely from the packaged local
catalog; it never reads the network. The resolver maps selected generations to
unique, deterministically ordered backend profile IDs, so repeated Wasmline
versions in one generation do not cause repeated AOT compilation.

`suppressCompatibilityWarning` defaults to `false`:

```kotlin
aotCompatibility {
    current()
    suppressCompatibilityWarning.set(true)
}
```

This property suppresses only the log line. It does not bypass local validation,
change AOT inputs, disable the check task, alter the manifest, or change loader
selection.

## Advisory compatibility check

`wasmlineCheckAotCompatibility` is a verification task that can run directly or
after a successful `wasmlineAssembleDebug`/`wasmlineAssembleRelease`. It does
not depend on the AOT task and is not attached to ordinary compilation or test
tasks. Debug and release finalizers share one task, so one Gradle invocation runs
at most one check.

The task compares the local selection with the latest stable release catalog at:

```text
https://github.com/crowforkotlin/wasmline/releases/latest/download/aot-compatibility.json
```

The remote file is advisory only. It cannot select profiles, download compilers,
change a manifest, or affect loader behavior. The check uses HTTPS, a five
second total deadline, a 256 KiB response limit, at most one redirect, a
checksum sidecar, ETag/Last-Modified, a 24-hour cache, file locking, and atomic
writes. Offline mode performs local validation only. Network, checksum, HTTP,
schema, and cache failures produce a bounded unavailable result and do not fail
an otherwise successful package build.

The report is written to:

```text
build/reports/wasmline/aot-compatibility-check.json
```

It records the local and latest published Wasmline versions, selector, selected
generations, local/latest generation, generation gap, omitted ranges, affected
backends, source status, suppression state, and a documentation URL. Warnings
use stable codes `WLAOT001` (review required), `WLAOT002` (remote status
unavailable), and `WLAOT100` (missing or invalid explicit selector). The first
two are suppressible; `WLAOT100` is not.

## Release procedure

The release workflow is `.github/workflows/release.yml`. A push to `main` never
publishes. The workflow accepts only tags matching:

```text
release-x.y.z.v
```

The first three segments must equal `versions.wasmline_version`. The suffix is
the fixed encoding `major * 100 + minor * 10 + patch` of
`versions.wasmtime_version`; minor and patch must be single digits. The first
three segments of `wasmtime_release_version` must equal `wasmtime_version`, and
the catalog current version must equal the Wasmline version in the manifest.
The tag commit must be reachable from `main`.

After validation, the workflow downloads the locked Wasmtime assets, builds the
JVM and Android engine libraries, runs JVM verification tests, and invokes the
existing `publishToMavenCentral` aggregate task. Maven credentials and signing
material are supplied through the corresponding `ORG_GRADLE_PROJECT_*` secrets.
It then creates these release assets:

- `aot-compatibility.json`;
- `aot-compatibility.json.sha256`.

Release notes include the Wasmtime fork distribution, AOT generation, changed
backends, whether the current range introduces a generation, and the stable
compatibility documentation URL.

For local validation and asset preparation, use:

```bash
./scripts/release.sh verify-tag release-1.0.0.4801
./scripts/release.sh prepare-assets build/release
./scripts/release.sh write-notes build/release-notes.md
```

The script never creates tags or publishes Maven artifacts. Those operations
remain explicit workflow actions.

## Maintenance rules

When adding a generation, update the root `aot-compatibility.json` and provide
the matching scalar Wasmtime versions in `versions.json`. Run
`./scripts/wasmline versions sync`, then `./scripts/wasmline aot sync` and
`./scripts/wasmline aot check`. The AOT synchronizer resolves new detailed
metadata from the fork release; use `aot sync --proxy <url>` or `--jobs <count>`
when required by the maintenance environment. Do not put AOT records in
`versions.json` or hand-edit generated resources.
Do not expose Wasmtime version strings or arbitrary profile digests as the
normal Gradle selector. During the active development phase, remove obsolete
APIs and update all call sites instead of retaining compatibility annotations.
