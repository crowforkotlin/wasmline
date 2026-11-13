# Wasmline Technical Mind Map

This document describes the runtime APIs, package format, build tools, and
release workflow implemented in this repository. File and symbol names below
identify the implementation.

The [English map](../fumadocs/public/images/wasmline_mind_en.png) and
[Chinese map](../fumadocs/public/images/wasmline_mind_zh.png) use the bilingual
summaries in [render-mind-map.py](../scripts/internal/python/render-mind-map.py). Update those summaries
when changing the behavior described here. To regenerate both PNGs, install
Pillow and Noto Sans CJK fonts, then run from the repository root:

```bash
python3 scripts/internal/python/render-mind-map.py
```

Use `--font` and `--bold-font` to select font files on another system.

## Three Independent Runtime Axes

`WasmlineArtifactDescriptor` carries an artifact's physical format, execution
model, and invocation protocol. A `.cwasm` or `.pwasm` extension does not select
`CORE_WASM` or `COMPONENT_MODEL`.

| Axis | Values | Selects |
| --- | --- | --- |
| Physical format | `RAW_WASM`, `CWASM`, `PWASM` | How bytes are loaded |
| Execution model | `CORE_WASM`, `COMPONENT_MODEL` | Wasmtime Core or Component runtime |
| Invocation protocol | `WASMLINE_SERVICE`, `RAW_EXPORT`, `COMPONENT_EXPORT` | How calls and values cross the boundary |

Valid runtime combinations:

| Execution model | Protocol | Boundary |
| --- | --- | --- |
| `CORE_WASM` | `WASMLINE_SERVICE` | Generated service bridge, action string, serialized byte payload, and Core response frame |
| `CORE_WASM` | `RAW_EXPORT` | Explicit Core numeric export ABI |
| `COMPONENT_MODEL` | `WASMLINE_SERVICE` | Fixed `wasmline:service@1.0.0` WIT envelope |
| `COMPONENT_MODEL` | `COMPONENT_EXPORT` | Typed Component values, host imports, instances, and resources |

`RAW_EXPORT` is invalid with `COMPONENT_MODEL`, and `COMPONENT_EXPORT` is invalid with `CORE_WASM`.

## Artifact Model

- Raw `.wasm` is a source/build format.
  - The browser executes it as `CORE_WASM + WASMLINE_SERVICE` or
    `CORE_WASM + RAW_EXPORT`.
  - Native loading rejects raw Core and Component artifacts.
- `.cwasm` is platform-specific Cranelift AOT output.
- `.pwasm` is Wasmtime Pulley bytecode, normally `pulley32` or `pulley64`.
- Both `.cwasm` and `.pwasm` can contain a Core module or a Component. The descriptor's execution model selects the native runtime path.
- Cranelift runtime distributions support matching `.cwasm` and Pulley fallback. Pulley distributions accept `.pwasm` only.
- iOS is Pulley-only and selects `pulley64` `.pwasm`.
- `WasmlineArtifactSelector.select()` matches the physical format, CPU, OS,
  pointer width, backend capability, and an exact backend-specific AOT profile
  ID. A matching Wasmtime version string alone does not establish compatibility.

## Signed Packages and Artifact Selection

`wasmline-plugin-core` signs `manifest.wlm` and stores each compiled artifact at
`artifacts/sha256/{prefix}/{digest}.{extension}`. One manifest can describe
several AOT generations and their Cranelift or Pulley variants. Browser Core
`.wasm` has no AOT profile and is stored once.

For package sources, the loader verifies the manifest signature against the
configured trusted keys, selects one compatible artifact, and checks its size
and SHA-256 before loading. Ed25519 is the only supported signature algorithm.
Remote resolution downloads the manifest and the selected artifact.

`WasmlineArtifactSelector.select()` first looks for a unique compatible CWASM
variant when the host supports Cranelift. If none matches, it considers PWASM
against the host's Pulley profile IDs. Equally compatible variants are an
invalid selection. iOS selects only `pulley64` PWASM; browsers select raw Core
Wasm. Each backend is matched against its own profile IDs.

`WasmlineLoader.load(descriptor)` accepts a caller-trusted direct artifact and
skips package signature verification. Native descriptor and AOT compatibility
checks still apply. A descriptor supplies `artifactFormat`, `executionModel`,
and `invocationProtocol`; a file suffix does not establish trust.

`manifestTtlMs` controls cached-manifest freshness. It does not expire a
signature or prevent rollback. `maxCacheBytes` limits the built-in file cache
on a best-effort basis.

## Repository Modules

- `wasmline-core`
  - C/C++ native runtime compiled through Zig 0.16.0
  - `include/wasmline/`: stable native API headers
  - `include/wasmline/internal/`: native implementation headers; not a compatibility surface
  - `Api.cpp`: delegates public native operations to `NativeRuntime`
  - `NativeRuntime.cpp`: creates and releases engines, artifacts, and sessions
  - `ServiceSessionRegistry.cpp`, `RawSessionRegistry.cpp`, `ComponentSessionRegistry.cpp`: session lookup, ownership, and artifact detachment
  - `Engine.cpp`: Cranelift/Pulley engine lifecycle
  - `Module.cpp`: deserialize-only Core artifact cache
  - `Component.cpp`: deserialize-only Component artifact cache
  - `Session.cpp`: Wasmline Service Core calls
  - `RawModuleSession.cpp`: raw Core exports
  - `ComponentSession.cpp`: Component exports, service envelope, host imports, instances, and resources
- `wasmline`
  - Public Kotlin runtime types and platform implementations
  - Generated service bridge contracts and serialization SPI
  - Host-side raw and Component invocation APIs
  - Core guest router and Component Service guest initialization
  - Browser Core Wasmline runtime
- `wasmline-loader`
  - Local and remote `.wlm` packages
  - Signature verification and trusted-key policy
  - Artifact selection and descriptor construction
- `wasmline-engine-cranelift`, `wasmline-engine-pulley`
  - Packaged native runtime distributions and JVM platform variants
- `wasmline-kotlin-plugin`
  - Service contract validation, IR bridge generation, `link`/`bind` rewriting, and guest initialization hooks
- `wasmline-plugin-core`
  - Shared tool downloads, Core/Component compilation, Component AOT, manifest signing, packaging, and WIT host bindings
- `wasmline-gradle-plugin`
  - Consumer DSL and Gradle task graph built on the compiler plugin and plugin core
- `wasmline-cli`
  - Command-line adapters for the same build and packaging services
- `wasmline-network-ktor`, `wasmline-network-okhttp`
  - Loader network implementations
- `wasmline-android`
  - Internal source project containing Android native build inputs
  - The Maven coordinate `wasmline-android` is the Android target publication of `:wasmline`, not a publication of this helper project
  - Engine modules publish the precompiled JNI libraries used by Android consumers
  - Its CMake project is a development and CI rebuild path for `libwasmline.so`
- `wasmline-bom`
  - A Gradle platform with strict same-version constraints for its declared runtime, loader, network, build-tool, and engine coordinates
  - Consumers use the platform where their Gradle configuration supports it; other configurations must assign the same Wasmline version explicitly
- `wasmline-native-test-fixtures`
  - Internal WAT, WIT, Component, and Rust fixture sources
  - `assembleNativeTestFixtures` generates AOT files and `fixture-index.json` below `build/`
- `wasmline-plugin-test`
  - End-to-end plugin build and native invocation verification

## Native Host Flow

```text
WasmlineLoader
  -> verified manifest or caller-trusted descriptor
  -> selected artifact and WasmlineArtifactDescriptor
  -> WasmlineRuntimeBridge
  -> Wasmline platform implementation (JNI or Kotlin/Native C interop)
  -> wasmline-core Api.cpp
  -> NativeRuntime
  -> Module or Component cache
  -> ServiceSessionRegistry, RawSessionRegistry, or ComponentSessionRegistry
  -> Session, RawModuleSession, or ComponentSession
  -> Wasmtime
  -> WasmlineCallResult
```

Cached session calls do not acquire the global lifecycle mutex. Each registry
retains the selected session under its own shared mutex, and each session owns
cloned Wasmtime engine and artifact handles. Artifact release and shutdown take
the lifecycle write lock, detach matching sessions first, release the cached
artifact and process engine, then destroy detached sessions after the global
lock is released.

Native loading is deserialize-only. `wasmtime_module_new` and `wasmtime_component_new` are intentionally rejected in the runtime path; compilation belongs to plugin-core, the Gradle plugin, or the CLI.

The selected physical format controls engine mode:

- `CWASM` initializes or selects the Cranelift engine.
- `PWASM` initializes or selects the Pulley engine.
- Selecting the current engine is idempotent.
- Switching engines is allowed only when no artifacts remain loaded. A conflicting warm-up or artifact load fails without releasing existing artifacts; only explicit runtime shutdown invalidates all live handles.

## Browser Host Flow

```text
WasmlineWeb.prefetch(url) or WasmlineWeb.registerBytes(key, bytes)
  -> Fetch API or caller-provided bytes
  -> WebWasmArtifacts byte cache
  -> WasmlineLoader.load(...) [suspend]
  -> WebAssembly.Module and WebAssembly.Instance
  -> Service path: __wasmline_wasi_init / __wasmline_wasi_entry / response frame
  -> Raw path: CoreWasmModule / CoreWasmSession / direct exports
```

Browser limits:

- raw `.wasm` only
- `CORE_WASM + WASMLINE_SERVICE` or `CORE_WASM + RAW_EXPORT`
- no native runtime identity
- no concurrent loading
- no Component instances or resources
- raw imports are synchronous and must be registered before instantiation
- `i64` crosses the JavaScript boundary as `BigInt`; threads/shared memory are not promised
- raw memory access is bounds-checked and refreshed after `memory.grow`
- an import invoked by the guest start function sees
  `RawImportContext.memory == null`, because the instance does not exist yet
- a produced-result count mismatch returns `ARGUMENT_COUNT_MISMATCH` and a
  produced `bigint`/`number` contradiction returns `ARGUMENT_TYPE_MISMATCH`;
  a genuine guest trap returns `WASM_TRAP`, and a failing host import keeps its
  own failure code
- browser offsets are signed 32-bit JS indices, so `RawMemory` accepts only
  offsets in `0..Int.MAX_VALUE`; length and current size are validated
  separately, and a near-2 GiB length is neither exercised nor claimed to work

For raw exports, `WasmlineLoader.loadCoreModule()` returns a `CoreWasmModule`
without instantiating it. Register its imports, then call `instantiate()` to
obtain a `CoreWasmSession`. Service guest initialization and raw import
registration use separate runtime implementations.

The shared `webMain` layer contains no platform interop. `jsMain` supplies typed JS externals; `wasmJsMain` supplies `JsAny` and constant `js()` helpers.

The Service/WASI lifecycle and the Raw Export lifecycle are intentionally
separate. Raw modules do not assume `__wasmline_wasi_init`,
`__wasmline_wasi_entry`, or `env.bridge_*`; they register only the imports
their module declares.

`CoreWasmCapabilities` is a supported feature-set declaration, not a probe of
the host browser. Every current backend reports `multiValue`, `i64`,
`bulkMemory`, and `referenceTypes` as supported and `simd` and `threads` as
unsupported. Instantiation rejects an uncovered
`runtimeContract.rawAbi.requiredFeatures` entry with
`WASM_FEATURE_UNSUPPORTED`. Plain JS numbers carry no wasm type, so result
decoding cannot distinguish a wrong declaration among `i32`, `f32`, and `f64`;
only the `bigint`/`number` split is observable.

## Core Wasmline Service Flow

Host to plugin:

```text
module.link<Service>().method(...)
  -> generated Service_WasmlineBridge
  -> selected serialization factory
  -> action = fully-qualified-contract + "#" + method-name
  -> Wasmline.callResult(action, payload)
  -> Session or WebWasmPlugin
  -> WasmlineRouter
  -> bound implementation
  -> WLMF response frame
  -> WasmlineCallResult
  -> typed return value
```

Plugin to host uses the same generated bridge shape through the outbound host dispatcher. Overloaded service methods are forbidden, so the current action identifier remains unique within a contract.

Normal call failures are values:

- `ACTION_NOT_BOUND`
- invalid payload or response
- trap or transport failure
- handler failure

`throwOnFailure()` is an explicit adapter; the result API does not use exceptions for normal call failures.

Native Core Service rejects synchronous reuse of a session that is already
invoking on the same thread. The check reads a per-thread invocation-frame stack
before the session mutex, so `A -> A` and `A -> B -> A` return
`REENTRANT_CALL` instead of deadlocking. Invoking a different, inactive session
`B` from an `A` callback is allowed.

Guest-provided `memory32` ranges are validated against the current linear
memory before any view or copy is created, and a negative guest length traps
with `CORE_TRAP`. The host narrows its own payload lengths to the signed `i32`
bridge ABI only after the `INT32_MAX` check succeeds.

## Component Model Flow

### Build

```text
WIT and guest source
  -> wit-bindgen where required
  -> Core Wasm guest
  -> wasm-tools component embed/new
  -> raw Component Wasm
  -> compile-capable Wasmtime CLI
  -> Component CWASM/PWASM targets
  -> signed manifest and package
```

The raw Component is an intermediate build result, not a native runtime
artifact. Component AOT compilation resolves immutable backend-specific
profiles and digest-locked compiler assets from the shared catalog. It rejects
iOS CWASM targets in favor of `pulley64` and does not accept arbitrary compiler
executables.

`ComponentPipeline.componentize()` owns the `component embed`, `component new`,
optional validation, and WIT inspection steps. `ToolchainCatalog` supplies the
locked `wasm-tools`, `wit-bindgen`, and WASI Preview 1 adapter inputs.

`WitDigest.sha256(path)` delegates to `WitSources.load(path).sha256`. Component
metadata, generated Kotlin host bindings, and CLI WIT export use this same WIT
source identity. `WasmlineComponentContract.requireMatches()` compares the
binding's package ID, world, and WIT digest with the loaded descriptor. The CLI
`wit generate` command accepts `--language`; Kotlin remains its default guest
binding language. See the [Component Service reference](<../fumadocs/content/docs/(reference)/(repository-development)/(plugin)/component-service.mdx>)
for the WIT digest algorithm and language-specific build steps.

### Runtime

- `WASMLINE_SERVICE`
  - Uses the fixed `wasmline:service@1.0.0` host/plugin interfaces.
  - Keeps the selected Wasmline serialization bytes inside `list<u8>` payloads.
  - Supports Component-to-host service callbacks.
- `COMPONENT_EXPORT`
  - Uses `WasmlineComponentValue` for typed parameters and results.
  - Supports generated host bindings, imported host functions, explicit instances, and owned/borrowed resources.
  - Does not use Core linear-memory bridge imports or the `WLMF` response frame.

Component host resource constraints:

- An `own<T>` wrapper belongs to the instance that created it; a borrowed value
  is call-scoped and cannot be dropped or transferred.
- `WasmlineComponentResource.close()` distinguishes a rejected request from a
  failed drop. When the request is rejected before the reference is consumed,
  the wrapper keeps its carrier and stays open, so the value can still be
  inspected or transferred. When the drop consumes the reference and resource
  destruction or a host callback then fails, the wrapper is closed, that
  cleanup failure surfaces as a `WasmlineException` carrying `HANDLER_FAILED`
  with the original message, a second `close()` is a no-op, and the destructor
  is not retried. No claim is made that a failure discards the owning instance.
- `transferToComponent()` consumes the wrapper before the call is sent, and an
  interrupted call is never rolled back: the carrier is not restored to the
  wrapper. Because the call then serializes that value, a caller must not reuse
  the wrapper after transfer.
- One `WasmlineComponentInstance` serializes its host-side mutations —
  `invoke`, `link`, `dropResource`, `createHostResource`, and `close` — through
  a per-instance lock. On Kotlin/Native that lock is a recursive native mutex,
  so a same-thread guest callback re-entering the same Component session reaches
  the native session, which rejects it with `COMPONENT_CALL_FAILED`. Do not
  assume concurrent use of one wrapper or instance is supported.

Calls into a guest are synchronous, and the public API exposes no cancellation
token. A long-running or spinning guest invocation cannot be interrupted from
another thread; host timeouts must be enforced by isolating the work in a
separate process or by not invoking untrusted code that has no bound.

## AOT Catalog and Generation Selection

An AOT profile ID identifies the serialized-artifact compatibility requirements
for one backend. An AOT generation is a sequential catalog number grouping
Wasmline releases with the same Cranelift and Pulley profile identities.

| Source | Maintained values | Command that writes derived files |
| --- | --- | --- |
| Root `versions.json` | Shared Wasmline Maven version and project/toolchain versions | `./scripts/wasmline versions sync` |
| Root `aot-compatibility.json` | Supported Wasmline interval, generation range starts, fork distributions, and changed backends | `./scripts/wasmline aot sync` |

The AOT synchronizer generates the packaged public catalog and detailed
`META-INF/wasmline/aot/aot-compatibility-lock.json`, plus
`NativeBuildIdentity.h` and `WasmlineReleaseIdentity.kt`. The detailed lock
contains backend profiles, compiler archive/executable digests, and build-host
bindings. Build tasks resolve from this packaged data. The remote release
catalog is not an AOT task input.

Select native output in the Gradle DSL with `AotGenerations`:

```kotlin
import crow.wasmline.plugin.core.aot.AotGenerations

wasmline {
    wasmtime {
        aotCompatibility {
            generations(AotGenerations.Current)
        }
    }
}
```

- An omitted, empty, or warning-only block selects `Current`, the generation
  containing the build tool's Wasmline version.
- `Latest`, `Earliest`, and `All` select the newest, oldest, or all locally
  retained generations.
- `Current(before = N, after = M)`, `Latest(before = N)`, and
  `Earliest(after = N)` include the anchor and that many additional adjacent
  generations. The counts refer to generations, not Maven versions.
- `generations(version)`, `generations(versions = listOf(...))`, and
  `generations(min = ..., max = ...)` accept concrete Wasmline releases; the
  last form includes both endpoints. `AotGenerations.Versions` provides the
  equivalent typed forms.
- The first explicit call replaces the implicit `Current` selection. Later
  calls add to a union that is deduplicated into backend profile IDs.
- Versions must lie within the packaged catalog's supported interval. Missing
  neighbors, inverted ranges, malformed versions, and symbolic version strings
  fail selection. Resolution does not query the network or clip invalid ranges.

The CLI exposes the same selection through repeatable `--aot-generations`
options. Tokens include `current`, `latest`, `earliest`, `all`, concrete release
points, closed `MIN..MAX` intervals, and windows such as `latest:before=1`.
Omitting the option selects `current`.

`wasmlineCheckAotCompatibility` runs after a successful Wasmline assemble and
writes `build/reports/wasmline/aot-compatibility-check.json`. It compares the
selection with the latest stable release catalog for an advisory warning.
An unavailable remote catalog does not fail packaging.
`suppressCompatibilityWarning.set(true)` suppresses the log message only;
invalid local selection still fails, including configuration-time `WLAOT100`.

## Maven Versions and Release Workflow

All published Wasmline modules use the complete `wasmline_version` from root
`versions.json`: `x.y.z`, `x.y.z-alphaN`, or `x.y.z-betaN`. `N` is positive and
has no leading zeros. Maven groups use `io.github.crowforkotlin`; the Gradle
plugin ID is `io.github.crowforkotlin.wasmline`. Engine Maven versions use the
same value as the runtime and loader. The sample-plugin version and WIT
protocol version are separate inputs.

`gradle/libs.versions.toml` exposes the runtime dependency as `libs.wasmline`.
`libs.versions.wasmline` supplies the version, and `libs.plugins.wasmline`
supplies the Gradle plugin. These are separate version-catalog sections.
Compose Desktop's `packageVersion` reads that shared Wasmline version and
removes its alpha/beta suffix because `jpackage` accepts a numeric version.

Release tags use `release-<Maven-version>.<Wasmtime-code>`, where
`Wasmtime-code = major * 100 + minor * 10 + patch`. Wasmtime minor and patch
must each fit one decimal digit. The fourth fork-distribution component is
recorded in the AOT catalog and release notes; it is not part of this code.

```text
Candidate commit on main
  -> normal CI (validation; no Maven publication)
  -> push matching release-* tag
  -> validate-release: tag, managed versions, catalog, main ancestry, secrets
  -> validate-native-aot-jvm and validate-native-aot-ios in parallel
  -> publish-maven on macOS
       publish locally, inspect all target publications, test published consumer
       publishToMavenCentral once
  -> create-github-release
       CHANGELOG.md Unreleased text and generated runtime metadata
       aot-compatibility.json and its SHA-256 sidecar
```

The publisher runs on macOS because Apple targets are declared only there.
It consumes the JVM and Android engine files produced by the Linux job.
Alpha/beta GitHub Releases are marked Pre-release and do not replace Latest;
stable releases become Latest. Tags and Maven versions are immutable and each
release has one matching tag.

Before the first publication, `aot sync --rebuild` may replace unpublished
history after maintainers confirm that no Maven release or local/origin
`release-*` tag exists. It resolves and verifies the fork compiler archives
again. After publication, use ordinary `aot sync` and retain released range
starts, profiles, and compiler bindings. Advancing a beta number or moving to
stable with unchanged backend profiles updates the catalog's current version
without adding a generation.

The [Releasing guide](<../fumadocs/content/docs/(reference)/(repository-development)/releasing.mdx>)
contains the candidate preparation commands, Maven secrets, signing setup, and
Central consumer verification. The [AOT Catalog and Versions reference](<../fumadocs/content/docs/(reference)/(repository-development)/aot-and-versioning.mdx>)
defines the catalog fields and generated-file boundaries.

## Kotlin IR Pipeline

```text
WasmlineCompilerPluginRegistrar
  -> WasmlineCommandLineProcessor options
  -> WasmlineIrGenerationExtension
       1. discover WasmlineService interfaces
       2. validate contracts and functions
       3. generate *_WasmlineBridge classes
       4. rewrite link() and bind() calls
       5a. generate Core WASI exports, or
       5b. wire the Component Service init hook
```

Current validation rejects:

- generic service interfaces or generic service methods
- service properties
- overloaded method names
- non-public or suspend methods
- extension receivers
- default or vararg parameters
- service contracts used as parameter or return types

Multiple regular parameters are supported. Generated bridges build the required serializer array and parameter descriptor for those methods.

The plugin is an IR transformer, not a source generator. `link()` and `bind()` deliberately fail at runtime when the compiler plugin did not rewrite them.

## Test Sources and Generated Fixtures

- Native runtime: `wasmline/src/jvmTest/`, `iosTest/`, and `wasmline-core` integration paths
- Native AOT inputs: `wasmline-native-test-fixtures/src/main/`; `assembleNativeTestFixtures` writes CWASM, PWASM, and `fixture-index.json` below `build/`
- Browser runtime: `wasmline/src/webTest/`
- Loader: common, JVM, Web, and Kotlin/Native tests under `wasmline-loader/src/`
- Plugin build pipeline: `wasmline-plugin-core/src/test/` and `wasmline-plugin-test/`
- IR plugin: `wasmline-kotlin-plugin/testData/box/`, generated `test-gen/`, and FIR/IR snapshots

`:wasmline:nativeAotJvmTest` depends on fixture assembly and receives the
generated index through a JVM system property. iOS simulator tests receive it
through `WASMLINE_NATIVE_FIXTURE_INDEX` and select matching `pulley64` PWASM
fixtures. Tests check indexed paths, sizes, and SHA-256 before loading.
The source-based `:wasmline:jvmTest` task supplies the engine-owned JNI library
path; published consumers obtain the platform classifier because the engine's
base JVM JAR excludes JNI resources.

Generated runners, snapshots, build outputs, and platform assets must not be
edited manually.
