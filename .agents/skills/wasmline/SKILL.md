---
name: wasmline
description: Repository-level skill spec for environment pre-check, platform asset initialization, module targeting, and Kotlin IR plugin constraints in the Wasmline repository.
---

# Wasmline Repository Skill Spec

When a task involves building, testing, debugging, troubleshooting, or making code changes in the `wasmline` repository, this skill spec **must** be loaded and followed.

## Directory Conventions

```
scripts/
└── doctor.sh                       # Environment pre-check script
```

The actual environment pre-check implementation lives in the repository-level `scripts/doctor.sh`. The skill spec, README, and repository scripts share the same entry point to ensure consistent environment detection logic.

## Objective

Before performing any compilation, testing, or code changes, complete environment verification and route the task to the correct module.

---

## Execution Constraints

The following constraints remain active throughout the entire session lifecycle and take priority over step-by-step instructions.

### Constraint 1: Environment Pre-check Runs Only Once

The environment pre-check (`bash ./scripts/doctor.sh`) **is allowed to run only once during the current session initialization**. Within the same session, **re-triggering** the pre-check is **forbidden**; results obtained must be reused for the entire session.

### Constraint 2: No Autonomous Compilation or Testing

Without an **explicit** compile or test instruction from the user, triggering any compilation or test flow in any form — including autonomous, implicit, or chained calls — is **forbidden**. All build and verification actions require explicit user instruction.

---

## Recommended Workflow

1. Run the pre-check script once during session initialization to confirm Gradle and test prerequisites.
2. Initialize platform runtime assets as needed.
3. Identify the target module before making code changes.
4. Strictly distinguish hand-written source code from generated artifacts, especially IR snapshots and test generation files.

### Current Execution Strategy (Windows Environment)

If the current workspace is **Windows** but the task involves `macOS` / `iOS`-specific implementation, integration testing, or verification, handle as follows:

- Mark Apple platform items as **Environment Deferred**; do not force execution on this machine.
- Prioritize work items that can run in the current environment, including `JNI`, `Loader`, `Runtime`, `IR`, public API consolidation, and documentation.
- If the task only involves updating plans, adding design notes, or organizing to-dos, continue modifying documents but **do not** mark status as "completed".
- Resume Apple platform implementation, regression verification, and blocked item closure only after switching to an available `macOS/iOS` environment.

---

## Step 1: Gradle Build Prerequisites Pre-check

Gradle builds in this repository require at least **Java 21**. Compose Desktop and some desktop samples explicitly configure `JvmVendorSpec.JETBRAINS`, so this skill uses **JBR 21** as the pre-check standard.

**Note**: Per execution constraints, this step runs only once per session.

Run:

```bash
bash ./scripts/doctor.sh
```

Pre-check details:

- **Do not run Gradle without first confirming the Java/JBR version.**
- `doctor.sh` checks the current `JAVA_HOME` first, then performs read-only verification using `java -version`, `<JAVA_HOME>/release`, and JBR/JAVA_HOME declarations in shell configuration files.
- `doctor.sh` reads `~/.zshrc`, `~/.bashrc`, and `~/.bash_profile` in read-only mode for JBR/JAVA_HOME declarations.
- These shell configuration files are **read-only; modification is forbidden**.
- If the current shell environment is not set to an available JBR 21, inform the user and **stop** any subsequent Gradle compilation/testing.
- **Do not** hardcode any local JBR installation path into skill docs, scripts, or repository documentation.
- `doctor.sh` checks known Wasmtime platform architecture directories under `build/platforms/` (e.g., `android/arm64-v8a`, `linux/x64`, `mac/aarch64`); missing items produce a `WARNING` but do not trigger the JBR 21 hard block.

`doctor.sh` also reports the desktop Zig version (requires **0.15.1**) and desktop JNI/native artifact status for Compose Desktop and desktop native troubleshooting.

---

## Step 2: Initialize Platform Runtime Assets as Needed

This repository depends on per-platform Wasmtime C-API runtime assets. If `build/platforms/` is not yet ready, choose one of the following initialization methods:

```bash
# Bash (requires bash + curl + tar/unzip)
sh ./scripts/init-wasmtime.sh

# Python 3 (requires Python 3.9+, no third-party dependencies)
python3 ./scripts/init-wasmtime.py

# Node.js (requires Node.js 18+, no third-party dependencies)
node ./scripts/init-wasmtime.mjs
```

All three scripts are functionally equivalent and support:

- Interactive target platform and architecture selection
- Configurable concurrent download count
- Optional proxy parameter (first argument, e.g., `127.0.0.1:7890`)
- Automatic extraction and deployment to `build/platforms/` after download

Notes:

- `build/platforms/` stores downloaded or extracted platform runtime assets (headers + static/shared libraries).
- **Do not** assume these assets exist on any given machine.
- If `doctor.sh` confirms the target platform assets are present, this step can be skipped.

---

## Step 3: Identify Target Module Before Making Changes

### Repository Structure Overview

| Directory | Description |
|---|---|
| `wasmline-core/` | Native Wasmtime Bridge written in C/C++ (Engine, Module, Session, Api) |
| `wasmline-multiplatform/` | Kotlin Multiplatform main project (standalone Gradle project) |
| `wasmline-multiplatform/wasmline/` | Core runtime library (commonMain / hostMain / wasmWasiMain / jniMain / jvmMain / iosMain / jsMain / wasmJsMain / webMain, etc.) |
| `wasmline-multiplatform/wasmline-kotlin-plugin/` | Kotlin IR compiler plugin |
| `wasmline-multiplatform/wasmline-cli/` | CLI tool |
| `wasmline-multiplatform/wasmline-loader/` | Loader module |
| `wasmline-multiplatform/wasmline-gradle-plugin/` | Gradle plugin |
| `wasmline-multiplatform/wasmline-android/` | Android-specific JNI wrapper module |
| `wasmline-multiplatform/wasmline-build-logic/` | Build logic (Convention Plugins) |
| `wasmline-samples/kotlin/` | Sample project as standalone Gradle project (sample-apps / sample-common / sample-plugin) |
| `wasmline-samples/kotlin/sample-apps/android/` | Android-only sample app |
| `wasmline-samples/kotlin/sample-apps/application/` | JVM/Desktop-only sample app |
| `wasmline-samples/kotlin/sample-apps/multiplatform/` | Compose Multiplatform sample (androidApp / desktopApp / shared / webApp) |
| `wasmline-samples/kotlin/sample-common/` | Shared logic for sample projects |
| `wasmline-samples/kotlin/sample-plugin/` | Sample Wasmline plugin project |
| `wasmline-multiplatform/ci/` | CI build and test scripts |
| `wasmline-multiplatform/docs/` | Module-level documentation (build guides, design docs, etc.) |
| `scripts/` | Repository-level initialization and utility scripts |
| `build/platforms/` | Platform runtime assets (initialized by `scripts/init-wasmtime.sh`) |
| `docs/` | Documentation site resources |

> **Note**: `wasmline-samples/kotlin/` is a standalone Gradle Composite Build that depends on `wasmline-multiplatform` via `includeBuild`; it is not a sub-module of the `wasmline-multiplatform` project. The original `wasmline-multiplatform/wasmline-sample/` has been deprecated and removed from `settings.gradle.kts`.

### Runtime / Bridge

For tasks involving Wasm loading, session lifecycle, host-to-Wasm call chains, or Runtime Bridge behavior, refer to these files first:

**C/C++ Bridge Layer:**

- `wasmline-core/include/Engine.h`
- `wasmline-core/src/Engine.cpp`
- `wasmline-core/src/Module.cpp`
- `wasmline-core/src/Session.cpp`
- `wasmline-core/src/Api.cpp`

**Kotlin Bridge Layer:**

- `wasmline-multiplatform/wasmline/src/commonMain/kotlin/crow/wasmline/internal/bridge/GeneratedBridge.kt`
- `wasmline-multiplatform/wasmline/src/commonMain/kotlin/crow/wasmline/internal/bridge/GeneratedSerialization.kt`
- `wasmline-multiplatform/wasmline/src/commonMain/kotlin/crow/wasmline/internal/bridge/Endpoint.kt`
- `wasmline-multiplatform/wasmline/src/commonMain/kotlin/crow/wasmline/internal/bridge/HostDispatcher.kt`
- `wasmline-multiplatform/wasmline/src/commonMain/kotlin/crow/wasmline/internal/bridge/Payload.kt`

**Tests:**

- `wasmline-multiplatform/wasmline/src/commonTest/kotlin/crow/wasmline/WasmlineServiceRuntimeTest.kt`

### Kotlin Multiplatform Runtime API

For tasks involving public API, binding, generated bridge integration, or platform runtime implementation, refer to these files first:

- `wasmline-multiplatform/wasmline/src/commonMain/kotlin/crow/wasmline/WasmlineService.kt` — Service definition entry point
- `wasmline-multiplatform/wasmline/src/commonMain/kotlin/crow/wasmline/WasmlineConfig.kt` — Global configuration
- `wasmline-multiplatform/wasmline/src/hostMain/kotlin/crow/wasmline/Wasmline.kt` — Host-side main API
- `wasmline-multiplatform/wasmline/src/hostMain/kotlin/crow/wasmline/WasmlineServices.host.kt` — Host-side service registration
- `wasmline-multiplatform/wasmline/src/hostMain/kotlin/crow/wasmline/WasmlineLoader.kt` — Host-side loader
- `wasmline-multiplatform/wasmline/src/hostMain/kotlin/crow/wasmline/WasmlineLoadState.kt` — Load state definition
- `wasmline-multiplatform/wasmline/src/hostMain/kotlin/crow/wasmline/WasmlineWarmupMode.kt` — Engine warmup mode (`PULLEY` / `AOT`)
- `wasmline-multiplatform/wasmline/src/hostMain/kotlin/crow/wasmline/BrowserPayloadEncoding.kt` — Browser-side payload encoding
- `wasmline-multiplatform/wasmline/src/wasmWasiMain/kotlin/crow/wasmline/WasmlineServices.wasmWasi.kt` — WASI-side service registration
- `wasmline-multiplatform/wasmline/src/wasmWasiMain/kotlin/crow/wasmline/WasmlineWasmBridge.kt` — WASI-side Wasm bridge
- `wasmline-multiplatform/wasmline/src/wasmWasiMain/kotlin/crow/wasmline/WasmlineRouter.kt` — WASI-side router
- `wasmline-multiplatform/wasmline/src/wasmWasiMain/kotlin/crow/wasmline/Wasmline.wasmWasi.kt` — WASI-side platform implementation
- `wasmline-multiplatform/wasmline/src/wasmWasiMain/kotlin/crow/wasmline/WasmMain.kt` — WASI-side entry point
- `wasmline-multiplatform/wasmline/src/iosMain/kotlin/crow/wasmline/Wasmline.ios.kt` — iOS platform implementation
- `wasmline-multiplatform/wasmline/src/jsMain/kotlin/crow/wasmline/Wasmline.js.kt` — JS platform implementation
- `wasmline-multiplatform/wasmline/src/wasmJsMain/kotlin/crow/wasmline/Wasmline.wasmJs.kt` — WasmJs platform implementation
- `wasmline-multiplatform/wasmline/src/webMain/kotlin/crow/wasmline/Wasmline.web.kt` — Web (shared JS+WasmJs) platform implementation
- `wasmline-multiplatform/wasmline/src/jniMain/kotlin/crow/wasmline/Wasmline.jni.kt` — JNI-side platform implementation

Core concepts:

- `WasmlineEndpoint`
- `WasmlineGeneratedBridge`
- `bindGeneratedBridgeAction(...)`
- `requireGeneratedImplementation(...)`
- `unknownGeneratedAction(...)`

### Kotlin Compiler Plugin / IR

For tasks involving `link()`, `bind()`, `bindAs()`, bridge code generation, IR transformation, or plugin behavior, refer to these files first:

**Plugin Registration and Entry:**

- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineCompilerPluginRegistrar.kt`
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineCommandLineProcessor.kt`

**IR Transformation Core:**

- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineIrGenerationExtension.kt` — IR generation extension entry
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineBridgeGenerator.kt` — Bridge code generation
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineTypedEntryPointRewriter.kt` — Typed entry point rewriting
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineServiceContractValidator.kt` — Service contract validation
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineWasiEntryExportGenerator.kt` — WASI-side entry export generation

**Symbol Resolution and Utilities:**

- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineRuntimeSymbols.kt` — Runtime symbol resolution
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineIrDiagnostics.kt` — Diagnostics
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/SignatureHash.kt` — Signature hash
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/ir.kt` — IR utilities
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/typeToString.kt` — Type serialization
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/package.kt` — Package-level declarations

**Test Data:**

- `wasmline-multiplatform/wasmline-kotlin-plugin/testData/box/` — Box test cases
- `wasmline-multiplatform/wasmline-kotlin-plugin/testData/diagnostics/` — Diagnostic test cases

**Design Documents:**

- `wasmline-multiplatform/wasmline-kotlin-plugin/testData/box/README_zh.md`
- `.github/plans/ir-planv2.md`

Important notes:

- This module is an **IR plugin**, not a simple source code generator.
- Many behaviors require systematic verification combining IR output, generation tests, and runtime behavior.
- Reading a single file is usually insufficient to understand the full behavior; the Runtime Helper, plugin code, and box tests must be understood as a unified system.

### CLI / Loader / Packaging

For tasks involving manifests, signing, packaging, or CLI pipelines, refer to these directories first:

- `wasmline-multiplatform/wasmline-loader/`
- `wasmline-multiplatform/wasmline-cli/`
- `wasmline-multiplatform/wasmline-gradle-plugin/`

### Desktop Native

For tasks involving Compose Desktop, JNI, or native libraries, refer to these files first:

- `wasmline-multiplatform/docs/zig-build.md`
- `wasmline-multiplatform/wasmline/build.zig`
- `wasmline-samples/kotlin/sample-apps/multiplatform/shared/src/desktopMain/Requirement.md`
- `wasmline-multiplatform/wasmline/src/jniMain/native/`
- `wasmline-multiplatform/wasmline/src/jvmMain/native/`

Typical build command (**run only when the user explicitly requests compilation**):

```bash
cd wasmline-multiplatform/wasmline
zig build --release=small -p src/jvmMain/resources
```

Additional notes:

- `src/jvmMain/resources/jni/` serves as the Zig installation output directory, not a stable source reading entry.
- The default output path is `zig-out/jni/`; if `-p <directory>` is explicitly provided, JNI artifacts are installed to the `jni/` subdirectory under that directory.
- The repository requires Zig version **0.15.1**.

---

## Step 4: Strictly Observe Generated Artifact Constraints

Unless the task explicitly requires regeneration, **manual modification of the following is forbidden**:

- `wasmline-multiplatform/wasmline-kotlin-plugin/test-gen/` — Auto-generated test runners
- `wasmline-multiplatform/wasmline-kotlin-plugin/testData/box/*.fir.txt` — FIR snapshots
- `wasmline-multiplatform/wasmline-kotlin-plugin/testData/box/*.fir.ir.txt` — FIR IR snapshots
- `wasmline-multiplatform/wasmline-kotlin-plugin/testData/diagnostics/*.fir.txt` — Diagnostic snapshots
- `build/platforms/` — Platform runtime assets
- `**/build/` — Build outputs

IR test notes:

- `*.fir.txt` and `*.fir.ir.txt` are **auto-generated, auto-compared** snapshot files.
- The first test run may report failures due to missing snapshots or IR changes.
- The second run typically passes once correct snapshots are generated.
- If only implementation logic changes, **do not** manually edit the IR snapshot files.

To add or update box tests, follow this standard workflow:

1. Create a `.kt` test source file under `testData/box/`.
2. Run `./gradlew :wasmline-kotlin-plugin:generateTests` to generate test runners.
3. Run the tests; snapshot files will be auto-generated.
4. Verify snapshot content before committing.

---

## Common Commands

### Environment Pre-check

```bash
bash ./scripts/doctor.sh
```

### Initialize Platform Runtime

```bash
sh ./scripts/init-wasmtime.sh            # Bash
python3 ./scripts/init-wasmtime.py       # Python 3.9+
node ./scripts/init-wasmtime.mjs         # Node.js 18+
```

### Generate Plugin Tests and Run Box Tests

> **Prerequisite**: An explicit test instruction from the user must have been received.

```bash
cd wasmline-multiplatform
./gradlew :wasmline-kotlin-plugin:generateTests
./gradlew :wasmline-kotlin-plugin:test --tests 'crow.wasmline.kotlin.runners.JvmBoxTestGenerated'
```

### Run Diagnostic Tests

> **Prerequisite**: An explicit test instruction from the user must have been received.

```bash
cd wasmline-multiplatform
./gradlew :wasmline-kotlin-plugin:test --tests 'crow.wasmline.kotlin.runners.JvmDiagnosticsTestGenerated'
```

### Build Desktop JNI Artifacts (requires Zig 0.15.1)

> **Prerequisite**: An explicit compile instruction from the user must have been received.

```bash
cd wasmline-multiplatform/wasmline
zig build --release=small -p src/jvmMain/resources
```

---

## Recommended Reading Order

When approaching this repository for the first time, follow this order:

1. `README_zh.md` / `README.md` — Project overview
2. `.github/skills/wasmline/SKILL.md` — This file
3. `scripts/init-wasmtime.sh` — Platform asset initialization flow
4. `wasmline-multiplatform/settings.gradle.kts` — Main project module structure
5. `wasmline-samples/kotlin/settings.gradle.kts` — Sample project Composite Build structure
6. `wasmline-core/` — C/C++ Bridge layer implementation
7. `wasmline-multiplatform/wasmline/` — Kotlin core runtime
8. `wasmline-multiplatform/wasmline-kotlin-plugin/` — IR compiler plugin
9. `wasmline-multiplatform/wasmline-kotlin-plugin/testData/box/README_zh.md` — Box test documentation
10. `.github/plans/ir-planv2.md` — IR transformation design plan

---

## Working Principles

1. **Environment pre-check first.** Confirm JBR 21 is ready before running Gradle; pre-check results remain valid for the entire session; do not re-trigger.
2. **Asset verification before compilation.** Confirm runtime asset integrity before running platform-specific builds.
3. **Module targeting before changes.** Identify the target module corresponding to the requirement before making code changes.
4. **Never hand-edit generated artifacts.** IR snapshots, `test-gen/`, and `build/` are all generated output; manual editing is forbidden.
5. **Execute compilation and testing only on explicit instruction.** Compilation and test flows require explicit user instruction; autonomous or implicit triggering is forbidden.
6. **Understand IR plugin behavior systematically.** IR plugin behavior must be verified holistically using the Runtime Helper, plugin code, and box tests.

Core principle: **Environment Pre-check (once) -> Asset Verification -> Module Targeting -> Execute on Instruction.**
