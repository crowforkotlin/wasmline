# Development and Verification Guide

Operational reference for the Wasmline repository. All paths are relative to the repository root.

## Contents

- [Environment Pre-check](#environment-pre-check)
- [Platform Runtime Assets](#platform-runtime-assets)
- [Module Ownership](#module-ownership)
- [Key Source Maps](#key-source-maps)
- [Generated Artifact Rules](#generated-artifact-rules)
- [Commands](#commands)
- [Formatting Scope](#formatting-scope)
- [CI Pipeline](#ci-pipeline)
- [Cross-Environment Work](#cross-environment-work)

## Environment Pre-check

Gradle work requires **JBR 21**. Run the pre-check once per session, immediately before the first validation of changes to files in this repository:

```bash
./scripts/wasmline doctor
```

Rules:

- Do not run it for read-only work, tasks unrelated to Wasmline, or changes that require no validation.
- Creating a goal or requesting edits does not trigger it by itself. Validation of the resulting repository changes is the trigger.
- If a later turn first introduces a change that must be validated, run it then.
- Never run it again in the same session.
- A failed JBR 21 check blocks Gradle commands. Other failed checks matter only when the requested command uses the reported tool or files.
- Do not write local JBR paths into repository files.

The pre-check also reports Zig 0.16.0, configured Wasmtime files, JNI libraries, and Kotlin/Native libraries.

## Platform Runtime Assets

Download only the targets required by the requested build:

```bash
./scripts/wasmline wasmtime targets
./scripts/wasmline wasmtime download --target <target> --engine <pulley|cranelift|all>
```

The command reads the exact downstream release from `versions.json`, matches complete release asset names, verifies `SHA256SUMS`, supports bounded concurrent downloads and an optional `--proxy`, and extracts files under `build/platforms/v<wasmtime-release-version>/`.

Do not initialize assets merely because the directory exists or is absent. Confirm that the requested build needs them and that the user authorized the download or build.

## Module Ownership

| Path | Responsibility |
| --- | --- |
| `wasmline-core/` | Native C/C++ Wasmtime bridge for Core Wasm and Component Model execution |
| `wasmline-multiplatform/wasmline/` | Kotlin runtime API, host runtime, platform actuals, browser runtime, and guest runtime |
| `wasmline-multiplatform/wasmline-loader/` | Local and remote manifests, signatures, artifact selection, and network-neutral loading |
| `wasmline-multiplatform/wasmline-engine-cranelift/` | Cranelift native runtime distribution |
| `wasmline-multiplatform/wasmline-engine-pulley/` | Pulley native runtime distribution |
| `wasmline-multiplatform/wasmline-android/` | Android native build integration |
| `wasmline-multiplatform/wasmline-kotlin-plugin/` | Kotlin IR validation, bridge generation, entry-point rewriting, and WASI/Component hooks |
| `wasmline-multiplatform/wasmline-plugin-core/` | Shared plugin build pipeline, Component tooling, manifest signing, packaging, and host WIT generation |
| `wasmline-multiplatform/wasmline-gradle-plugin/` | Consumer DSL and Gradle tasks built on the compiler plugin and plugin core |
| `wasmline-multiplatform/wasmline-cli/` | CLI adapters for catalog-backed AOT builds, Component tools, manifests, and packaging |
| `wasmline-multiplatform/wasmline-network-ktor/` | Ktor network adapter for the loader |
| `wasmline-multiplatform/wasmline-network-okhttp/` | OkHttp network adapter for the loader |
| `wasmline-multiplatform/wasmline-plugin-test/` | End-to-end Gradle-plugin and native-plugin integration tests |
| `wasmline-multiplatform/wasmline-native-test-fixtures/` | Internal native AOT fixture sources, generation task, and fixture index model |
| `wasmline-samples/` | Kotlin, Rust, C, and C++ examples and fixtures |
| `scripts/` | Repository automation, environment checks, lint, assets, and version synchronization |
| `docs/` | Documentation site |

## Key Source Maps

### Native Runtime

- Stable native API headers: `wasmline-core/include/wasmline/`, excluding `internal/`
- Native implementation headers: `wasmline-core/include/wasmline/internal/`
- API entry: `wasmline-core/src/api/Api.cpp`
- Runtime coordinator: `wasmline-core/src/runtime/NativeRuntime.cpp`
- Session registries: `ServiceSessionRegistry.cpp`, `RawSessionRegistry.cpp`, `ComponentSessionRegistry.cpp`
- Engine and artifacts: `wasmline-core/src/runtime/Engine.cpp`, `Module.cpp`, `Component.cpp`
- Invocation sessions: `Session.cpp`, `RawModuleSession.cpp`, `ComponentSession.cpp`
- Typed Component values: `wasmline-core/src/value/ComponentValue.cpp`
- Kotlin JNI/iOS bridges: `wasmline-multiplatform/wasmline/src/jniMain/` and `iosMain/`

### Kotlin Runtime

- `commonMain`: artifact descriptors, execution models, invocation protocols, service contracts, result types, serialization, and bridge contracts
- `hostMain`: `Wasmline`, loader bridge, service registration, raw invocation, Component invocation, Component instances, and host imports
- `wasmWasiMain`: Core guest router and Component Service guest initialization
- `webMain`, `jsMain`, `wasmJsMain`: browser host implementation
- Internal generated-bridge contracts: `wasmline/src/commonMain/kotlin/crow/wasmline/internal/bridge/`

The host runtime keeps public API types in `crow.wasmline`. New implementation
code must use the narrowest matching internal package:

| Package | Responsibility |
| --- | --- |
| `crow.wasmline.internal.core` | Core Wasm backend contracts, module and session lifecycle, import dispatch, memory access, and native Core Wasm codec. |
| `crow.wasmline.internal.component` | Component instance state, Host import dispatch, resource state, and Component-specific validation. |
| `crow.wasmline.internal.service` | Wasmline Service request/response adapter and per-instance host service registrations. |
| `crow.wasmline.internal.invocation` | Typed raw and Component carrier encoding and decoding. |
| `crow.wasmline.internal.runtime` | Platform runtime bridge and cross-platform runtime-state synchronization. |
| `crow.wasmline.internal.bridge` | Shared generated bridge contracts and platform dispatcher interfaces. |
| `crow.wasmline.internal.protocol` | Stable byte protocol codecs used by the Wasmline Service implementation. |

Do not move public `CoreWasm*`, `Raw*`, `WasmlineComponent*`, `Wasmline`, or
result types into an internal package. Platform `actual` implementations may
depend on internal types through explicit imports, but public callers must only
need `crow.wasmline` imports.

The `wasmline/` paths in this section are relative to `wasmline-multiplatform/`.

### Kotlin Compiler Plugin

- Orchestration: `WasmlineIrGenerationExtension.kt`
- Validation: `WasmlineServiceContractValidator.kt`
- Bridge generation: `WasmlineBridgeGenerator.kt`
- `link()` and `bind()` rewriting: `WasmlineTypedEntryPointRewriter.kt`
- Core WASI export: `WasmlineWasiEntryExportGenerator.kt`
- Component Service initializer: `WasmlineComponentServiceInitGenerator.kt`
- Utilities: `WasmlineRuntimeSymbols.kt`, `WasmlineIrDiagnostics.kt`, `SignatureHash.kt`, `Ir.kt`, `TypeToString.kt`, and `package.kt`
- Fixtures: `wasmline-kotlin-plugin/testData/box/`
- Generated runner: `wasmline-kotlin-plugin/test-gen/`

The compiler plugin performs IR transformation; it is not a source generator. Treat runtime bridge contracts, IR logic, fixtures, generated runners, and snapshots as one system.

### Web Runtime

Read [`web-bindings-guide.md`](./web-bindings-guide.md) before changing `webMain`, `jsMain`, `wasmJsMain`, or browser tests.

### Component Model

Read the [Component Service Guide](<../../../../docs/content/docs/(reference)/(plugin-development)/component-service.mdx>) before changing WIT, Component build stages, generated host bindings, or cross-language fixtures.

## Generated Artifact Rules

Do not hand-edit:

- `wasmline-kotlin-plugin/test-gen/`
- `wasmline-kotlin-plugin/testData/box/*.fir.txt`
- `wasmline-kotlin-plugin/testData/box/*.fir.ir.txt`
- `**/build/`
- `build/platforms/`
- `.zig-cache/` and `zig-out/`
- generated WIT/Kotlin/C/C++ binding output under build directories
- native fixture `.cwasm`, `.pwasm`, and `fixture-index.json` files below `wasmline-native-test-fixtures/build/`

Native AOT fixture source files belong in
`wasmline-native-test-fixtures/src/fixtures/`. The fixture task writes compiled
artifacts and its index below that module's `build/` directory. Do not commit
those generated files or add environment variables that point tests at
hand-built artifacts.

For an IR fixture change:

1. Edit the `.kt` fixture under `testData/box/`.
2. Generate the runner through the Gradle task.
3. Run the box suite so snapshots are produced and compared.
4. Review every generated change; never repair a logic failure by editing a snapshot.

The repository currently generates only `JvmBoxTestGenerated`. Diagnostic runner infrastructure exists, but the diagnostics model is disabled and no `testData/diagnostics/` suite is registered.

## Commands

Compilation and test commands below require explicit user instruction.

```bash
# Conditional environment pre-check; see the rules above
./scripts/wasmline doctor

# Version synchronization checks
./scripts/wasmline versions sync
./scripts/wasmline versions check
./scripts/wasmline versions verify-upstream
./scripts/wasmline versions check-ktlint
./scripts/wasmline versions update-ktlint
./scripts/wasmline aot sync
./scripts/wasmline aot check
python3 scripts/tests/test_versions.py
python3 scripts/tests/test_wasmtime.py

# Changed/untracked source formatting checks
./scripts/wasmline lint

# Full source formatting checks used by CI
./scripts/wasmline lint --all

# Format changed sources, or all supported sources
./scripts/wasmline lint --format
./scripts/wasmline lint --all --format

# Build native engine assets after platform initialization
./scripts/wasmline jni build --engine <pulley|cranelift|all>
./scripts/wasmline kotlin-native build
./scripts/wasmline kotlin-native build --target <target> [--engine <pulley|cranelift|all>]

# IR runner generation and box tests
cd wasmline-multiplatform
./gradlew :wasmline-kotlin-plugin:generateTests
./gradlew :wasmline-kotlin-plugin:test \
  --tests 'crow.wasmline.kotlin.runners.JvmBoxTestGenerated'

# Browser tests
./gradlew :wasmline:jsBrowserTest :wasmline:wasmJsBrowserTest

# Wasm/WASI Node tests
./gradlew :wasmline:wasmWasiNodeTest

# Native AOT fixture generation and JVM runtime tests
../scripts/wasmline jni build --engine cranelift
rustup target add wasm32-wasip2
./gradlew :wasmline:nativeAotJvmTest \
  -Pwasmline.native.fixtures.targets=x86_64-linux,pulley64 \
  --no-daemon

# macOS only: iOS simulator Pulley fixture tests
../scripts/wasmline wasmtime download --target ios-simulator-arm64 --engine pulley
rustup target add wasm32-wasip2
./gradlew :wasmline:iosSimulatorArm64Test \
  :wasmline-loader:iosSimulatorArm64Test \
  -Pwasmline.native.fixtures.targets=pulley64 \
  --no-daemon
```

## Formatting Scope

| Language | Tool | Repository scope |
| --- | --- | --- |
| Kotlin | ktlint | Supported Kotlin files under `wasmline-multiplatform/`, excluding generated and platform-specific exclusions defined by the script |
| C/C++ | clang-format | Supported sources under `wasmline-core/` |
| Zig/ZON | `zig fmt` | Zig build files under `wasmline-multiplatform/wasmline/` |

Use `./scripts/wasmline lint`; language-specific scripts under `scripts/internal/lint/` are implementation details.

## CI Pipeline

Workflow: `.github/workflows/ci.yml`

The separate `.github/workflows/check-ktlint-update.yml` workflow checks the
latest stable ktlint release weekly and opens or updates a reviewable PR.

Pushes and pull requests targeting `main` ignore `docs/**`, root `*.md`, and `.agents/**`. Manual `workflow_dispatch` runs the same jobs.

| Job | Role | Dependency |
| --- | --- | --- |
| `lint-kotlin`, `lint-clang`, `lint-zig` | Full repository formatting checks | None |
| `build-cranelift-assets` | Build Cranelift Android/JVM native assets and upload the Linux x64 JNI library | None |
| `build-pulley-assets` | Build Pulley Android/JVM native assets | None |
| `test-jvm` | Compiler plugin, loader, runtime, and CLI JVM tests using the Cranelift JNI artifact | `build-cranelift-assets` |
| `test-native-aot-jvm` | Generated Cranelift and `pulley64` Core, Component, and Service AOT fixtures with JVM runtime integration tests | `build-cranelift-assets` |
| `test-kotlin-native` | Kotlin/Native sample using an independently cached Linux x64 Pulley platform asset | None |
| `test-web` | Runtime and loader JS/WasmJS browser tests | None |
| `test-node` | Wasm/WASI Node tests | None |
| `test-ios` | Runtime and loader iOS simulator tests, including generated `pulley64` AOT fixtures | None |
| `test-plugin` | Gradle-plugin integration tests using the Cranelift JNI artifact | `build-cranelift-assets` |

Cranelift and Pulley native asset builds run in parallel and have independent
Wasmtime platform caches. The Cranelift Linux x64 JNI library is transferred to
the owning engine module with a run-scoped workflow artifact. Source-based JVM
tests set `WASMLINE_NATIVE_LIBRARY_PATH` to that engine-owned file because the
platform-neutral JVM API JAR excludes JNI resources; published consumers receive
the selected platform classifier instead. Platform downloads remain cross-run
caches. Browser, Node, iOS, and Kotlin/Native jobs do not wait for JNI asset builds
they do not consume.

The CI workflow performs validation only. It does not publish artifacts or create a GitHub release.

`test-native-aot-jvm` generates `x86_64-linux` Cranelift and `pulley64` Pulley
fixtures from committed WAT, Component, WIT, and Rust sources. It passes the
generated absolute index path to `nativeAotJvmTest`; the test process validates
the indexed file path, size, and SHA-256 digest before loading an artifact. The
JVM suite includes a direct Core `RAW_EXPORT` PWASM load and invocation. `test-ios`
uses the same generation task with its existing `pulley64` subset and validates
selected Core `RAW_EXPORT` and Component fixtures before the iOS runtime loads
them. Cached tool and compiler directories only reduce download and compile
time. Missing fixture output still fails the test task.

## Cross-Environment Work

If the current host cannot execute Apple-specific validation, complete host-independent work and report the unavailable Apple checks as deferred. Documentation work can still be completed; only claims that require an unavailable platform must remain unverified.
