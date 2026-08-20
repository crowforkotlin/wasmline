# Compose sample app

The Compose sample is split into three layers:

- `App.kt` owns screen state, mode selection, and event-to-request conversion.
- `SampleUI.kt` is a presentational Compose screen. It does not load Wasm or call Wasmline APIs.
- `WasmLoader.kt` contains `WasmSampleRunner`, which owns runtime caching and dispatches the packaged contracts plus the cross-language fixture.

The screen exposes all four packaged runtime contracts and one cross-language fixture mode:

| Mode | Descriptor | Host call |
|---|---|---|
| Core Service | `CORE_WASM + WASMLINE_SERVICE` | `link<TimeSyncService>()` |
| Raw Export | `CORE_WASM + RAW_EXPORT` | `invokeRawResult("add_i32", ...)` |
| Component Service | `COMPONENT_MODEL + WASMLINE_SERVICE` | `link<ComponentPluginService>()` through `plugin/invoke` |
| Component Export | `COMPONENT_MODEL + COMPONENT_EXPORT` | `invokeComponentResult("wasmline:sample-component-export/calculator@1.0.0#add", ...)` |
| Component Fixture | `COMPONENT_MODEL + WASMLINE_SERVICE` | Direct `callResult("sample.*", bytes)` plus `bindComponentService` |

The Desktop app builds and bundles all four signed packages automatically. The
corresponding guest modules are `sample-plugin`, `sample-raw-export-plugin`,
`sample-component-plugin`, and `sample-component-export-plugin`.

Build both Component packages with:

```shell
./gradlew :sample-component-plugin:wasmlineAssembleDebug
./gradlew :sample-component-export-plugin:wasmlineAssembleDebug
```

The Core Wasm application sample is also a Gradle task. It assembles the sample
plugin, selects the requested artifact, and runs the host application:

```shell
./gradlew :sample-apps:application:run
./gradlew :sample-apps:application:run -Pwasmline.artifact.format=pwasm64
```

Use `-Pwasmline.artifact.format=pwasm32` only with a 32-bit native runtime;
the current 64-bit desktop runtime rejects it by design. Component AOT builds
download the pinned `cranelift-min` Wasmtime CLI automatically through the Gradle plugin.

Desktop uses one signed `manifest.wlm` per mode. The host configures the sample
public key, and the loader selects the compatible artifact after verifying the
manifest signature and artifact digest. Optional direct paths can still be
entered in the UI. Raw `.wasm` is a browser/source artifact; native Wasmline
requires a matching `.cwasm` or `.pwasm` artifact.

Start Desktop and select any contract from the mode control:

```shell
./gradlew :sample-apps:multiplatform:desktopApp:run
```

Verify the four bundled manifests and invocation paths without opening the UI:

```shell
./gradlew :sample-apps:multiplatform:desktopApp:verifyWasmlineSamples
```

```shell
wasm-tools parse wasmline-samples/raw/sample-export-plugin/plugin.wat -o /tmp/sample-export.wasm
wasmtime compile /tmp/sample-export.wasm -o /tmp/sample-export.cwasm \
  -C collector=drc \
  -W gc=y -W function-references=y -W exceptions=y -W threads=n \
  -W simd=n -W relaxed-simd=n \
  -O static-memory-guard-size=0 -O dynamic-memory-guard-size=0 \
  -O signals-based-traps=n -O opt-level=2
```

Override bundled packages when validating custom builds:

```shell
-Dwasmline.sample.coreService=/path/to/manifest.wlm
-Dwasmline.sample.rawExport=/path/to/manifest.wlm
-Dwasmline.sample.componentService=/path/to/manifest.wlm
-Dwasmline.sample.componentExport=/path/to/manifest.wlm
```

The Component Fixture accepts a signed `.wlm` package. Build a C or C++ raw
Component, copy it into `sample-component-fixture/input/plugin.component.wasm`,
then package it with the Wasmline Gradle task:

```shell
cp ../c/build/plugin.component.wasm sample-component-fixture/input/plugin.component.wasm
./gradlew :sample-component-fixture:wasmlineAssembleDebug

WASMLINE_SAMPLE_COMPONENT_FIXTURE="$PWD/sample-component-fixture/build/wasmline/output/crow.wasmline.component.fixture-1.0.0/manifest.wlm" \
  ./gradlew :sample-apps:multiplatform:desktopApp:run
```

Use the **Component Fixture** mode to run the C or C++ canonical-service
fixture. It checks opaque-byte echo, host callback, and empty payload behavior;
it is not a substitute for the Kotlin business-service sample.

The fixture is signed with the sample key already trusted by the Desktop host.

## Kotlin/Native sample

The `native` module is a host-native command-line smoke test for the loader and
engine integration. It builds the signed `sample-raw-export-plugin` package,
loads its `manifest.wlm`, verifies the Ed25519 signature, selects the compatible
Native artifact, and invokes `add_i32` through the Raw Export API:

```shell
./gradlew :sample-apps:native:verifyKotlinNativeSample
```

Pulley is linked by default. Use Cranelift when validating the platform-specific
CWASM path:

```shell
./gradlew :sample-apps:native:verifyKotlinNativeSample \
  -Pwasmline.engine=cranelift
```

Supported hosts are Linux x64/ARM64, macOS ARM64, and Windows x64. The sample
fails if the linked backend does not match the selected engine or if the call
does not return `42`.
