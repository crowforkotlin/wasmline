# Development Guide

Detailed operational reference for the wasmline repository. This document is referenced by `SKILL.md` — read it when performing builds, tests, or code changes.

---

## Environment Pre-check

Gradle builds require **JBR 21** (JetBrains Runtime). Run once per session:

```bash
bash ./scripts/doctor.sh
```

Details:

- Checks `JAVA_HOME`, `java -version`, `<JAVA_HOME>/release`, and shell config declarations.
- Reads `~/.zshrc`, `~/.bashrc`, `~/.bash_profile` in **read-only** mode. Modification forbidden.
- If JBR 21 is unavailable → stop all Gradle operations.
- Do not hardcode local JBR paths into any file.
- Reports Wasmtime platform asset status under `build/platforms/` (WARNING only, not a hard block).
- Reports Zig version (requires **0.16.0**) and desktop JNI artifact status.

---

## Platform Runtime Asset Initialization

If `build/platforms/` is not ready:

```bash
sh ./scripts/init-wasmtime.sh            # Bash
python3 ./scripts/init-wasmtime.py       # Python 3.9+
node ./scripts/init-wasmtime.mjs         # Node.js 18+
```

All three are functionally equivalent. Support:

- Interactive platform/architecture selection
- Configurable concurrent downloads
- Optional proxy (first argument, e.g., `127.0.0.1:7890`)
- Auto-extraction to `build/platforms/`

Do not assume assets exist on any machine. Skip if `doctor.sh` confirms presence.

---

## Repository Structure

| Directory | Description |
| --- | --- |
| `wasmline-core/` | Native Wasmtime Bridge (C/C++): Engine, Module, Session, Api |
| `wasmline-multiplatform/` | Kotlin Multiplatform main project (standalone Gradle) |
| `wasmline-multiplatform/wasmline/` | Core runtime library (multi-platform source sets) |
| `wasmline-multiplatform/wasmline-kotlin-plugin/` | Kotlin IR compiler plugin |
| `wasmline-multiplatform/wasmline-cli/` | CLI tool |
| `wasmline-multiplatform/wasmline-loader/` | Loader module |
| `wasmline-multiplatform/wasmline-gradle-plugin/` | Gradle plugin |
| `wasmline-multiplatform/wasmline-android/` | Android JNI wrapper |
| `wasmline-multiplatform/wasmline-build-logic/` | Convention Plugins |
| `wasmline-samples/kotlin/` | Sample project (standalone Composite Build via `includeBuild`) |
| `scripts/` | Repository-level utility scripts |
| `build/platforms/` | Platform runtime assets |
| `docs/` | Documentation site |

---

## Module File References

### Runtime / Bridge

**C/C++ Layer:**

- `wasmline-core/include/Engine.h`, `src/Engine.cpp`, `src/Module.cpp`, `src/Session.cpp`, `src/Api.cpp`

**Kotlin Bridge Layer:**

- `wasmline/src/commonMain/kotlin/crow/wasmline/internal/bridge/` — GeneratedBridge, GeneratedSerialization, Endpoint, HostDispatcher, Payload

**Tests:**

- `wasmline/src/commonTest/kotlin/crow/wasmline/WasmlineServiceRuntimeTest.kt`

### Kotlin Runtime API

- `commonMain/` — WasmlineService, WasmlineConfig
- `hostMain/` — Wasmline, WasmlineServices.host, WasmlineLoader, WasmlineLoadState, WasmlineWarmupMode
- `wasmWasiMain/` — WasmlineServices.wasmWasi, WasmlineWasmBridge, WasmlineRouter, WasmMain
- `iosMain/`, `jsMain/`, `wasmJsMain/`, `webMain/`, `jniMain/` — Platform implementations

Core concepts: `WasmlineEndpoint`, `WasmlineGeneratedBridge`, `bindGeneratedBridgeAction(...)`, `requireGeneratedImplementation(...)`, `unknownGeneratedAction(...)`

### Kotlin Compiler Plugin / IR

**Entry:** WasmlineCompilerPluginRegistrar, WasmlineCommandLineProcessor

**IR Core:** WasmlineIrGenerationExtension, WasmlineBridgeGenerator, WasmlineTypedEntryPointRewriter, WasmlineServiceContractValidator, WasmlineWasiEntryExportGenerator

**Utilities:** WasmlineRuntimeSymbols, WasmlineIrDiagnostics, SignatureHash, ir.kt, typeToString.kt, package.kt

**Tests:** `testData/box/`, `testData/diagnostics/`, `test-gen/` (auto-generated runners)

**IR test documentation:** [`docs/ir/index.md`](../../../wasmline-multiplatform/docs/ir/index.md)

> This is an **IR plugin**, not a source generator. Understand Runtime Helper + plugin code + box tests as a unified system.

### CLI / Loader / Packaging

- `wasmline-multiplatform/wasmline-loader/`
- `wasmline-multiplatform/wasmline-cli/`
- `wasmline-multiplatform/wasmline-gradle-plugin/`

### Desktop Native

- `wasmline-multiplatform/docs/zig-build.md`
- `wasmline-multiplatform/wasmline/build.zig`
- `wasmline/src/jniMain/native/`, `src/jvmMain/native/`

Build (only on explicit user instruction):

```bash
cd wasmline-multiplatform/wasmline
zig build --release=small -p src/jvmMain/resources
```

- `src/jvmMain/resources/jni/` = Zig install output, not a stable source entry.
- Default output: `zig-out/jni/`.
- Requires Zig **0.16.0**.

---

## Generated Artifact Constraints

**Forbidden to hand-edit** (unless task explicitly requires regeneration):

- `wasmline-kotlin-plugin/test-gen/` — Auto-generated test runners
- `testData/box/*.fir.txt`, `*.fir.ir.txt` — FIR/IR snapshots
- `testData/diagnostics/*.fir.txt` — Diagnostic snapshots
- `build/platforms/` — Platform assets
- `**/build/` — Build outputs

IR test notes:

- Snapshots are auto-generated and auto-compared.
- First run may fail (missing snapshots); second run passes.
- Never manually edit snapshots for logic changes.

Box test workflow:

1. Create `.kt` under `testData/box/`
2. `./gradlew :wasmline-kotlin-plugin:generateTests`
3. Run tests → snapshots auto-generate
4. Verify before committing

---

## Common Commands

```bash
# Environment pre-check (once per session)
bash ./scripts/doctor.sh

# Initialize platform assets
sh ./scripts/init-wasmtime.sh

# Build native libraries (requires init-wasmtime first)
bash scripts/build-native-assets.sh

# Generate + run box tests (requires explicit user instruction)
cd wasmline-multiplatform
./gradlew :wasmline-kotlin-plugin:generateTests
./gradlew :wasmline-kotlin-plugin:test --tests 'crow.wasmline.kotlin.runners.JvmBoxTestGenerated'

# Diagnostic tests
./gradlew :wasmline-kotlin-plugin:test --tests 'crow.wasmline.kotlin.runners.JvmDiagnosticsTestGenerated'

# Desktop JNI (requires Zig 0.16.0, explicit instruction)
cd wasmline-multiplatform/wasmline
zig build --release=small -p src/jvmMain/resources

# Format check
./gradlew ktlintCheck
find wasmline-core/src wasmline-core/include -name '*.cpp' -o -name '*.h' | xargs clang-format --dry-run --Werror

# Format fix
./gradlew ktlintFormat
find wasmline-core/src wasmline-core/include -name '*.cpp' -o -name '*.h' | xargs clang-format -i
```

---

## Code Formatting

| Language | Tool | Config |
|----------|------|--------|
| Kotlin | ktlint (via `org.jlleitschuh.gradle.ktlint`) | `wasmline-multiplatform/.editorconfig` |
| C/C++ | clang-format | `wasmline-core/.clang-format` |

- ktlint is applied to **all modules** via root `build.gradle.kts` `allprojects {}`.
- Run `./gradlew ktlintCheck` before committing Kotlin changes.
- Run `clang-format --dry-run --Werror` before committing C++ changes.
- CI enforces both checks on every PR.

---

## CI Pipeline

Workflow: `.github/workflows/ci.yml`

**Trigger:** push to `main` / PR to `main` (ignores docs-only changes)

### Job Structure (4 parallel/sequential lanes)

```
┌──────────────┐   ┌──────────────┐
│ lint-kotlin  │   │ lint-clang   │
└──────┬───────┘   └──────┬───────┘
       │                  │
       └────────┬─────────┘
                │
         (both must pass)
                │
       ┌────────▼─────────┐
       │ compile-all      │
       │ - Native build   │
       │ - Compile all    │
       └────────┬─────────┘
                │
                ▼
       ┌──────────────────┐
       │ test-all         │
       │ - Box tests      │
       │ - Diagnostics    │
       │ - Loader jvmTest │
       └──────────────────┘
```

**Conditions:**

- `compile-all` only runs if **both** lint jobs succeed
- `test-all` runs **after compile-all completes** (even if failed via `if: always()`)
- **Exception**: `workflow_dispatch` triggers bypass lint checks (manual run allowed)

### Why Split Into 4 Jobs?

| Stage | Purpose | Benefit |
| ------- | --------- | -------- |
| **lint-kotlin / lint-clang** | Fast format failures | Instant feedback, no wasted compute on heavy builds |
| **compile-all** | Heavy native build + multi-platform compilation | Isolated error context for toolchain/runtime issues |
| **test-all** | Execute unit + integration tests | Clear separation between "can we build?" and "does it work?" |

> `publishToMavenCentral` is NOT part of CI at this stage (project in development).

---

## Recommended Reading Order

1. `README_zh.md` / `README.md` — Project overview
2. `.agents/skills/wasmline/SKILL.md` — Skill constraints
3. `scripts/init-wasmtime.sh` — Platform asset initialization
4. `wasmline-multiplatform/settings.gradle.kts` — Module structure
5. `wasmline-samples/kotlin/settings.gradle.kts` — Sample Composite Build
6. `wasmline-core/` — C/C++ Bridge
7. `wasmline-multiplatform/wasmline/` — Kotlin runtime
8. `wasmline-multiplatform/wasmline-kotlin-plugin/` — IR plugin
9. [`docs/ir/index.md`](../../../wasmline-multiplatform/docs/ir/index.md) — IR test documentation

---

## Cross-Environment Strategy

If workspace is **Windows/Linux** but task involves macOS/iOS:

- Mark Apple platform items as **Environment Deferred**.
- Prioritize work runnable in current environment (JNI, Loader, Runtime, IR, docs).
- Document-only tasks: continue but do not mark as "completed".
- Resume Apple work only in macOS/iOS environment.
