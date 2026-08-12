# Compose sample app

The Compose sample is split into three layers:

- `App.kt` owns screen state, mode selection, and event-to-request conversion.
- `SampleUI.kt` is a presentational Compose screen. It does not load Wasm or call Wasmline APIs.
- `WasmLoader.kt` contains `WasmSampleRunner`, which owns runtime caching and dispatches the three invocation protocols.

The screen exposes these boundaries:

| Mode | Descriptor | Host call |
|---|---|---|
| Core Wasm | `CORE_WASM + WASMLINE_SERVICE` | `link<TimeSyncService>()` |
| Raw Export | `CORE_WASM + RAW_EXPORT` | `invokeRawResult("add_i32", ...)` |
| Component Model | `COMPONENT_MODEL + COMPONENT_EXPORT` | `callResult("sample.echo", ...)` through the WIT envelope |

The raw fixture is in `wasmline-samples/raw/sample-export-plugin/plugin.wat`.
The Component fixture is `sample-component-plugin`; build it with:

```shell
./gradlew :sample-component-plugin:wasmlineAssembleDebug
```

The Core Wasm application sample is also a Gradle task. It assembles the sample
plugin, selects the requested artifact, and runs the host application:

```shell
./gradlew :sample-apps:application:run
./gradlew :sample-apps:application:run -Pwasmline.artifact.format=pwasm64
```

Use `-Pwasmline.artifact.format=pwasm32` only with a 32-bit native runtime;
the current 64-bit desktop runtime rejects it by design. Component AOT builds
need the full Wasmtime CLI, supplied with `WASMTIME_COMPILER` or downloaded by
the `wasmlineDownloadWasmtimeCompiler` task.

Desktop uses the bundled Core Wasm package's signed `manifest.wlm` as its
default path. The host configures the sample public key, and the loader selects
the compatible artifact after verifying the manifest signature and artifact
digest. Optional direct paths can still be supplied without changing the UI.
Raw `.wasm` is a browser/source artifact; native Wasmline requires a matching
`.cwasm` or `.pwasm` artifact. For example, create the raw fixture and its Linux
AOT artifact with:

```shell
wasm-tools parse wasmline-samples/raw/sample-export-plugin/plugin.wat -o /tmp/sample-export.wasm
wasmtime compile /tmp/sample-export.wasm -o /tmp/sample-export.cwasm \
  -C collector=drc \
  -W gc=y -W function-references=y -W exceptions=y -W threads=n \
  -W simd=n -W relaxed-simd=n \
  -O static-memory-guard-size=0 -O dynamic-memory-guard-size=0 \
  -O signals-based-traps=n -O opt-level=2
```

Then pass direct AOT artifacts to the Desktop app:

```shell
-Dwasmline.sample.raw=/tmp/sample-export.cwasm
-Dwasmline.sample.component=/absolute/path/to/sample-x86_64-linux.cwasm
```

The runner supplies the current native runtime's Wasmtime version, CPU, OS, and
bitness when loading direct AOT paths. Other signed `.wlm` packages require the
host to add their trusted public keys to `WasmlineConfig.trustedKeys`.
