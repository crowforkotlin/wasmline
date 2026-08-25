# Web Bindings Guide

Reference for the Kotlin/JS and Kotlin/WasmJS host runtime. Read it before changing `webMain`, `jsMain`, `wasmJsMain`, or `webTest` under `wasmline-multiplatform/wasmline/`.

Paths below are relative to `wasmline-multiplatform/`.

## Contents

- [Supported Boundary](#supported-boundary)
- [Source-set Layers](#source-set-layers)
- [Naming](#naming)
- [Layer Constraints](#layer-constraints)
- [Value Codec](#value-codec)
- [Raw Wasm Prefetch and Suspended Load](#raw-wasm-prefetch-and-suspended-load)
- [WASI Preview 1 Imports](#wasi-preview-1-imports)
- [Environment Bridge Imports](#environment-bridge-imports)
- [Tests and CI](#tests-and-ci)
- [File Map](#file-map)

## Supported Boundary

The Web host accepts these Core Wasm combinations:

- physical format: raw `.wasm`
- execution model: `CORE_WASM`
- invocation protocol: `WASMLINE_SERVICE` or `RAW_EXPORT`

Component Model calls remain outside the browser boundary. Browser validation
rejects Component descriptors before artifact resolution. `RAW_EXPORT` is a
direct numeric export/import/memory contract; it is not encoded as a Service
action or payload frame.

## Source-set Layers

| Layer | Source set | Main file | Responsibility |
| --- | --- | --- | --- |
| Shared host | `webMain` | `wasmline/src/webMain/kotlin/crow/wasmline/Wasmline.web.kt` | Loading rules, module registry, and shared browser facade |
| Binding contract | `webMain` | `wasmline/src/webMain/kotlin/crow/wasmline/web/WebBindings.web.kt` | Pure Kotlin `expect` declarations |
| JS actual | `jsMain` | `wasmline/src/jsMain/kotlin/crow/wasmline/web/WebBindings.js.kt` | Typed JS externals and isolated constant `js()` helpers |
| WasmJS actual | `wasmJsMain` | `wasmline/src/wasmJsMain/kotlin/crow/wasmline/web/WebBindings.wasmJs.kt` | `JsAny` handles and compile-time-constant `js()` snippets |

Keep platform interop out of `webMain`. Shared code accesses JavaScript only through the binding contract.

## Naming

Use the `Web` or `web` prefix for the binding layer:

| Use | Avoid | Reason |
| --- | --- | --- |
| `Web*` types | `Browser*` for low-level bindings | JS hosts are not necessarily browsers |
| `web*` contract functions | vague `script*` names | The prefix identifies the owning source set |
| `WebBindings.*.kt` | generic `*Interop*` names | The expect/actual role is already explicit |
| `raw*` private helpers | unmarked JavaScript helpers | The prefix identifies platform-private interop |

The high-level host facade may use `Browser*` when behavior is specifically limited to browser-style loading.

## Layer Constraints

### `webMain`

- Pure Kotlin only.
- No `dynamic`.
- No `js(...)` calls.
- No `JsAny`, DOM, Fetch, or typed-array types.
- Declare opaque handles and operations with `expect`.

```kotlin
internal expect class WebJsValue
internal expect fun webCompileWasm(binary: ByteArray): WebWasmModule
internal expect fun webCallFunction(function: WebJsValue, args: WebJsArray): WebJsValue?
```

### `jsMain`

- Use typed externals such as `ArrayBuffer`, `Int8Array`, `Uint8Array`, `Response`, and `Promise` where available.
- Keep `dynamic` out of the implementation.
- Isolate unavoidable JavaScript expressions in small private `raw*` helpers.
- Keep public behavior in the shared `webMain` layer.

```kotlin
internal actual class WebWasmModule internal constructor(internal val raw: NativeWasmModule)

private fun rawApplyFunction(fn: Any, args: Array<Any?>): Any? =
    js("fn.apply(undefined, args)")
```

### `wasmJsMain`

- Opt in to `ExperimentalWasmJsInterop` at file level.
- Carry JavaScript values with `JsAny` handles.
- The string passed to `js()` must be a compile-time constant. It may contain an expression or a fixed block, but it must not be constructed dynamically.
- Keep conversion and JavaScript access in private `raw*` helpers.

```kotlin
private fun rawNowMillis(): Double = js("Date.now()")
private fun rawNewWasmModule(bytes: JsAny): JsAny = js("new WebAssembly.Module(bytes)")
```

## Value Codec

`WebWasmValue` represents WebAssembly numeric values without leaking platform handles.

| WebAssembly type | Kotlin type | JavaScript representation |
| --- | --- | --- |
| `i32` | `Int` | number |
| `i64` | `Long` | BigInt-compatible value |
| `f32` | `Float` | number |
| `f64` | `Double` | number |

Implementation: `wasmline/src/webMain/kotlin/crow/wasmline/web/WebWasmValue.kt`.

## Raw Wasm Prefetch and Suspended Load

`WasmlineLoader.load()` is a suspending API, while browser raw `.wasm` downloads
remain an explicit prefetch step. Web callers can either prefetch a URL or
register already available bytes before loading a descriptor:

```kotlin
WasmlineWeb.prefetch("plugin.wasm")
val result = WasmlineLoader.load("plugin.wasm")
```

For embedded or otherwise preloaded resources:

```kotlin
WasmlineWeb.registerBytes("plugin.wasm", bytes)
val result = WasmlineLoader.load(
    WasmlineArtifactDescriptor(
        path = "plugin.wasm",
        artifactFormat = WasmlineArtifactFormat.RAW_WASM,
        executionModel = WasmlineExecutionModel.CORE_WASM,
        invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
    ),
)
```

The sequence is:

1. `WasmlineWeb.prefetch(url)` downloads bytes through the Fetch API.
2. `WebWasmArtifacts` caches the bytes by URL.
3. `WasmlineLoader.load(url)` resolves the cached bytes and hands them to the browser runtime.
4. The Web backend compiles the module. Service modules use
   `WebWasmPlugin`; raw modules use a separate `CoreWasmModule`/session path.

The loader's remote-manifest network adapters are not part of this raw-Wasm path. Loading a URL that was not prefetched fails with an explicit instruction. Use `WasmlineWeb.invalidate(url)` to remove one cached artifact; runtime shutdown clears all cached artifacts and live modules.

## RAW_EXPORT sessions and imports

`CoreWasmModule` exposes export inventory and capabilities. Callers provide
`RawImport` values in `CoreWasmSessionOptions` before `instantiate()`. Import
handlers are synchronous and receive `RawImportContext`, including the current
session memory when an exported memory is configured. A handler must not wait
for Fetch, IndexedDB, user interaction, or any other Promise-producing API;
prepare those resources before entering Wasm.

The browser WebAssembly API does not provide general function-signature
reflection. Raw function signatures therefore come from `rawAbi` metadata or
`CoreWasmSessionOptions.exportSignatures`. Calls support `i32`, `i64`, `f32`,
and `f64`, including void and multi-value results. Web `i64` crosses the JS
boundary as `BigInt`; the public API continues to expose Kotlin `Long`.

`RawMemory` checks every range and refreshes typed-array views after
`memory.grow`. Use `readInto`/`writeFrom` for batch transfers. Component
browser boundaries still reject Component typed calls, and Web does not claim
threads/shared memory. SIMD, bulk memory, and reference types are reported by
`CoreWasmCapabilities` and must be checked before loading a module that
requires them.

## WASI Preview 1 Imports

`WebWasmPlugin.kt` currently supplies:

| Import | Signature | Behavior |
| --- | --- | --- |
| `fd_write` | `(fd, iovsPointer, iovsCount, writtenPointer)` | Reads UTF-8 iovecs and routes stdout/stderr to `WasmlineLog` or `println` |
| `random_get` | `(bufferPointer, bufferLength)` | Writes bytes from Kotlin `Random.nextBytes` |
| `clock_time_get` | `(clockId, precision, timePointer)` | Writes `Date.now()` converted to nanoseconds |

Do not document an import until it is registered in `WebWasmPlugin.buildImports()`.

## Environment Bridge Imports

The following imports use the `env.bridge_*` namespace for the Service path:

| Import | Current boundary |
| --- | --- |
| `bridge_inbound_copy_params(kind, destination, length)` | Copies the pending action (`kind == 0`) or payload into linear memory |
| `bridge_inbound_set_response(pointer, length)` | Copies the plugin response out of linear memory |
| `bridge_outbound_call_host(actionPointer, actionLength, payloadPointer, payloadLength, outputPointer, outputCapacity)` | Dispatches a plugin-to-host call and returns the written size or a negative overflow size |
| `bridge_outbound_get_response(outputPointer)` | Copies an overflow response into plugin memory |

Core call flow:

```text
prefetched bytes
  -> compile and instantiate
  -> __wasmline_wasi_init
  -> copy action and payload into linear memory
  -> __wasmline_wasi_entry
  -> plugin router
  -> bridge_inbound_set_response
  -> decode Wasmline result frame
```

This Service/WASI flow is independent from the raw session flow. Raw sessions
do not assume `__wasmline_wasi_init`, `__wasmline_wasi_entry`, or
`env.bridge_*`; they call ordinary exports and register only the imports their
module declares.

## Tests and CI

Runtime Web tests are under `wasmline/src/webTest/kotlin/crow/wasmline/`:

| File | Coverage |
| --- | --- |
| `web/WebWasmValueCodecTest.kt` | Numeric value encoding and result decoding |
| `web/WebWasmRuntimeTest.kt` | Compilation, instantiation, exports, invocation, and memory |
| `web/WebWasmImportsBuilderTest.kt` | Import grouping and typed host functions |
| `web/WebTestModule.kt` | Hand-encoded Core Wasm fixture |
| `WasmlineComponentBrowserBoundaryTest.kt` | Component and typed-invocation rejection |
| `WasmlineNativeRuntimeInfoWebTest.kt` | Absence of native runtime identity on Web |

Run only with explicit user instruction:

```bash
cd wasmline-multiplatform
./gradlew :wasmline:jsBrowserTest :wasmline:wasmJsBrowserTest
```

The CI `test-web` job also runs `:wasmline-loader:jsBrowserTest` and `:wasmline-loader:wasmJsBrowserTest` with Chrome configured through `CHROME_BIN`.

## File Map

```text
wasmline/src/webMain/kotlin/crow/wasmline/
├── Wasmline.web.kt
├── WasmlineWeb.kt
├── extensions/NativeLoaderExt.web.kt
└── web/
    ├── WebArtifactFetcher.kt
    ├── WebBindings.web.kt
    ├── WebWasmArtifacts.kt
    ├── WebWasmImportsBuilder.kt
    ├── WebWasmPlugin.kt
    ├── WebWasmRuntime.kt
    └── WebWasmValue.kt

wasmline/src/jsMain/kotlin/crow/wasmline/
├── Wasmline.js.kt
├── extensions/NativeLoaderExt.js.kt
└── web/
    ├── WasmNamespace.js.kt
    └── WebBindings.js.kt

wasmline/src/wasmJsMain/kotlin/crow/wasmline/
├── Wasmline.wasmJs.kt
├── extensions/NativeLoaderExt.wasmJs.kt
└── web/WebBindings.wasmJs.kt
```
