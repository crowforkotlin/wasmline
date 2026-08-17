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
bash ./scripts/doctor.sh
```

Rules:

- Do not run it for read-only work, tasks unrelated to Wasmline, or changes that require no validation.
- Creating a goal or requesting edits does not trigger it by itself. Validation of the resulting repository changes is the trigger.
- If a later turn first introduces a change that must be validated, run it then.
- Never run it again in the same session.
- A failed JBR 21 gate blocks Gradle commands. Asset and desktop sections are advisory unless the requested command needs those assets.
- The script reads `~/.zshrc`, `~/.bashrc`, and `~/.bash_profile` without modifying them. Do not edit these files.
- Do not write local JBR paths into repository files.

The pre-check also reports the configured Wasmtime platform assets, Zig version (requires **0.16.0**), and desktop native-library status.

## Platform Runtime Assets

Use one initializer when the required target is absent from `build/platforms/`:

```bash
bash ./scripts/init-wasmtime.sh
python3 ./scripts/init-wasmtime.py
node ./scripts/init-wasmtime.mjs
```

The three entry points provide the same target and runtime-variant selection workflow. They support concurrent downloads, an optional proxy argument such as `127.0.0.1:7890`, and extraction under `build/platforms/release-v<wasmtime-version>/`.

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
| `wasmline-multiplatform/wasmline-cli/` | CLI adapters for download, compilation, Component tools, manifests, and packaging |
| `wasmline-multiplatform/wasmline-network-ktor/` | Ktor network adapter for the loader |
| `wasmline-multiplatform/wasmline-network-okhttp/` | OkHttp network adapter for the loader |
| `wasmline-multiplatform/wasmline-plugin-test/` | End-to-end Gradle-plugin and native-plugin integration tests |
| `wasmline-samples/` | Kotlin, Rust, C, and C++ examples and fixtures |
| `scripts/` | Repository automation, environment checks, lint, assets, and version synchronization |
| `docs/` | Documentation site |

## Key Source Maps

### Native Runtime

- Public headers: `wasmline-core/include/wasmline/`
- API entry: `wasmline-core/src/api/Api.cpp`
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

Read the [Component Service Guide](../../../../docs/content/docs/component-service.mdx) before changing WIT, Component build stages, generated host bindings, or cross-language fixtures.

## Generated Artifact Rules

Do not hand-edit:

- `wasmline-kotlin-plugin/test-gen/`
- `wasmline-kotlin-plugin/testData/box/*.fir.txt`
- `wasmline-kotlin-plugin/testData/box/*.fir.ir.txt`
- `**/build/`
- `build/platforms/`
- `.zig-cache/` and `zig-out/`
- generated WIT/Kotlin/C/C++ binding output under build directories

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
bash ./scripts/doctor.sh

# Version synchronization checks
python3 scripts/sync_version.py
python3 scripts/sync_version.py --check
python3 scripts/sync_version.py --verify-upstream
python3 scripts/test_sync_version.py

# Changed/untracked source formatting checks
bash scripts/lint.sh

# Full source formatting checks used by CI
bash scripts/lint.sh --all

# Format changed sources, or all supported sources
bash scripts/lint.sh format
bash scripts/lint.sh --all format

# Build native engine assets after platform initialization
bash scripts/build-native-assets.sh [pulley|cranelift|all]

# IR runner generation and box tests
cd wasmline-multiplatform
./gradlew :wasmline-kotlin-plugin:generateTests
./gradlew :wasmline-kotlin-plugin:test \
  --tests 'crow.wasmline.kotlin.runners.JvmBoxTestGenerated'

# Browser tests
./gradlew :wasmline:jsBrowserTest :wasmline:wasmJsBrowserTest

# Wasm/WASI Node tests
./gradlew :wasmline:wasmWasiNodeTest
```

## Formatting Scope

| Language | Tool | Repository scope |
| --- | --- | --- |
| Kotlin | ktlint | Supported Kotlin files under `wasmline-multiplatform/`, excluding generated and platform-specific exclusions defined by the script |
| C/C++ | clang-format | Supported sources under `wasmline-core/` |
| Zig/ZON | `zig fmt` | Zig build files under `wasmline-multiplatform/wasmline/` |

Use `bash scripts/lint.sh`; language-specific scripts under `scripts/lint/` are implementation details.

## CI Pipeline

Workflow: `.github/workflows/ci.yml`

Pushes and pull requests targeting `main` ignore `docs/**`, root `*.md`, and `.agents/**`. Manual `workflow_dispatch` runs the same jobs.

| Job | Role | Dependency |
| --- | --- | --- |
| `lint-kotlin`, `lint-clang`, `lint-zig` | Full repository formatting checks | None |
| `build-assets` | Build and cache native Wasmtime assets | None |
| `test-jvm` | Compiler plugin, loader, runtime, and CLI JVM tests | `build-assets` |
| `test-web` | Runtime and loader JS/WasmJS browser tests | `build-assets` |
| `test-node` | Wasm/WASI Node tests | `build-assets` |
| `test-ios` | Runtime and loader iOS simulator tests | `build-assets` |
| `test-plugin` | Gradle-plugin integration tests | `build-assets` |

The CI workflow performs validation only. It does not publish artifacts or create a GitHub release.

## Cross-Environment Work

If the current host cannot execute Apple-specific validation, complete host-independent work and report the unavailable Apple checks as deferred. Documentation work can still be completed; only claims that require an unavailable platform must remain unverified.
