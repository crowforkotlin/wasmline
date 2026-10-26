# AOT Compatibility and Release Catalog

The repository Execution Policy applies to all local procedures below,
including Wasmtime asset checks, synchronization, generation, compilation,
release verification, and tests. Without explicit authorization, implement the
source changes and provide pending commands for manual execution. Descriptions
of CI or runtime checks do not authorize the assistant to run them.

This reference defines the native AOT compatibility policy implemented by the
Wasmline repository. It applies to `.cwasm` and `.pwasm` artifacts only. A raw
Web `.wasm` artifact does not use a Wasmtime AOT profile and is outside the
compatibility warning.

## Contents

- [Sources and generated views](#sources-and-generated-views)
- [Generation policy](#generation-policy)
- [Gradle and CLI selection](#gradle-and-cli-selection)
- [Advisory compatibility check](#advisory-compatibility-check)
- [Release procedure](#release-procedure)
- [Maintenance rules](#maintenance-rules)

## Sources and generated views

The root `aot-compatibility.json` is the only manually maintained AOT
compatibility source. It contains release range and generation metadata only.
The scalar project and toolchain versions remain in `versions.json`.

`./scripts/wasmline aot sync` validates the source and generates the internal
lock plus the packaged public resource:

- `wasmline-plugin-core/src/main/resources/META-INF/wasmline/aot/aot-compatibility-lock.json`;
- `wasmline-plugin-core/src/main/resources/META-INF/wasmline/aot/aot-compatibility.json`.

The same command also generates the runtime identity constants used by both
sides of the native bridge:

- `wasmline-core/include/wasmline/internal/runtime/NativeBuildIdentity.h`;
- `wasmline/src/commonMain/kotlin/crow/wasmline/WasmlineReleaseIdentity.kt`.

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

## Gradle and CLI selection

Native AOT selection is expressed with `generations` calls in the Wasmtime DSL:

```kotlin
wasmline {
    wasmtime {
        aotCompatibility {
            generations("1.0.0")
            generations(from = "1.0.1", through = "latest")
            // suppressCompatibilityWarning.set(true)
        }
    }
}
```

- `generations("x.y.z")` selects the generation containing that stable Wasmline
  release version.
- `generations(from = "x.y.z", through = "x.y.z" | "latest")` selects a closed
  inclusive range. `from` must be a concrete stable version; `through` may be
  `"latest"`.
- `"latest"` means the local catalog `currentWasmlineVersion`; it is valid only
  as a point version or as the range end.

Omitting `aotCompatibility`, or leaving the block empty, selects every
generation retained in the packaged local catalog. The first explicit call
replaces that default; later calls add their generations to the union. The
resolver maps the union to unique, deterministically ordered backend profile
IDs, so repeated Wasmline versions in one generation do not cause repeated AOT
compilation.

Both endpoints must lie within the locally supported catalog interval
(`minimumSupportedWasmlineVersion..currentWasmlineVersion`). An inverted range,
an endpoint outside that interval, a symbolic range start, or a malformed token
is a selection error; the build does not clip a range to its intersecting part.
A project whose WASI/component assemble graph resolves during configuration
fails there with `WLAOT100`; other projects fail in the AOT or check task action.
The CLI reports removed flags as usage errors and validates grammar, inverted
bounds, and out-of-range tokens before any tool download, compilation, or
signing. Selection is resolved entirely from the packaged local catalog and
never reads the network. The public input is a Wasmline release version:
Wasmtime distribution versions and profile digests are internal catalog bindings
and diagnostics, not DSL inputs. The build record and the compatibility report
keep the resolved selection under the diagnostic name `generations`.

The CLI exposes the same selection as a repeatable option:

```bash
./wasmline-cli/cli.sh compile --input core.wasm --aot-generations 1.0.0 \
  --aot-generations 1.0.1..latest --target pulley64
```

`--aot-generations` accepts one stable `x.y.z` point, the exact lowercase
`latest`, or one closed `FROM..THROUGH` range per occurrence; omitting the option
selects every local generation. Ranges require two non-empty endpoints, `FROM`
must be a stable version, and `THROUGH` may be `latest`. Tokens are not
normalized: comma lists, padded whitespace, an empty range endpoint, and
multiple `..` separators are rejected, as are the removed
`--aot-compatibility`, `--aot-version-range`, and `--min-sdk` flags.

`suppressCompatibilityWarning` defaults to `false`:

```kotlin
aotCompatibility {
    generations("latest")
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

It records the local and latest published Wasmline versions, the diagnostic name
`generations`, selected generations, local/latest generation, generation gap,
omitted ranges, affected backends, source status, suppression state, and a
documentation URL. The check emits `WLAOT001` (review required) when the remote
catalog is available and `WLAOT002` (remote status unavailable) otherwise; both
are suppressible through `suppressCompatibilityWarning`. `WLAOT100` is not an
advisory warning: it tags the local selection or catalog resolution failure
reported by the configuration-time preflight, such as an out-of-bounds,
inverted, or malformed generation input, and it is never suppressible.

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

After validation, the Linux native AOT job downloads the locked Wasmtime assets,
builds the JVM and Android engine libraries, and runs native AOT, JVM, and dynamic
plugin tests. A separate macOS job runs the iOS simulator Pulley gate. Both gates
must succeed before one macOS publisher generates root metadata for every declared
target and verifies its Apple variants. It consumes the verified JVM/Android
libraries and invokes the existing `publishToMavenCentral` aggregate task once.
Maven credentials and signing material are supplied through the corresponding
`ORG_GRADLE_PROJECT_*` secrets. Linux local publication does not verify Apple
artifacts. The workflow then creates these release assets:

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
normal Gradle selection input; use Wasmline release versions. During the active
development phase, remove obsolete APIs and update all call sites instead of
retaining compatibility annotations.
