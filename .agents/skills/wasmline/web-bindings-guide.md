# Web Bindings Guide

Reference for the Wasmline Web platform layer (Kotlin/JS and Kotlin/WasmJs). Referenced by `SKILL.md`. Read it before adding or changing code under `webMain`, `jsMain`, or `wasmJsMain` of the `wasmline` module.

All paths below are relative to `wasmline-multiplatform/`.

---

## Layer Model

The Web layer splits into three source sets. Platform code never enters `webMain`.

| Layer | Source set | File | Role |
| --- | --- | --- | --- |
| Contract | `webMain` | `wasmline/src/webMain/kotlin/crow/wasmline/web/WebBindings.web.kt` | `expect` declarations only |
| JS actual | `jsMain` | `wasmline/src/jsMain/kotlin/crow/wasmline/web/WebBindings.js.kt` | Typed externals (`org.khronos.webgl`) |
| WasmJs actual | `wasmJsMain` | `wasmline/src/wasmJsMain/kotlin/crow/wasmline/web/WebBindings.wasmJs.kt` | `JsAny` handles, constant `js()` |

---

## Naming Rules

Prefix everything in this layer with `web` / `Web`. The prefix matches the `webMain` source set name.

| Use | Do not use | Reason |
| --- | --- | --- |
| `Web*` types | `Browser*` | js/wasmJs also run on Node, not only browsers |
| `web*` functions | `Script*` | vague |
| `WebBindings.*.kt` files | `*Interop*` | redundant |
| `raw*` private js() helpers | — | marks platform-private bridge functions |

---

## Layer Constraints

### webMain (contract)

- Pure Kotlin only.
- No `dynamic`.
- No inline `js(...)` block.
- No platform detail.

```kotlin
internal expect class WebJsValue
internal expect fun webCompileWasm(binary: ByteArray): WebWasmModule
internal expect fun webCallFunction(function: WebJsValue, args: WebJsArray): WebJsValue?
```

### jsMain (Kotlin/JS actual)

- Use typed externals from `org.khronos.webgl` (`ArrayBuffer`, `Int8Array`, `Uint8Array`).
- Declare external classes with `@file:JsQualifier("WebAssembly")`.
- No `dynamic`.

```kotlin
internal actual class WebWasmModule internal constructor(internal val raw: NativeWasmModule)

private fun rawApplyFunction(fn: Any, args: Array<Any?>): Any? =
    js("fn.apply(undefined, args)")
```

### wasmJsMain (Kotlin/WasmJs actual)

- Add `@file:OptIn(ExperimentalWasmJsInterop::class)`.
- Each `js(...)` must be a single constant expression. No multi-statement bodies.
- Parameters must be `JsAny` types.

```kotlin
internal actual fun webFromI32(value: Int): WebJsValue = WebJsValue(js("$value"))
internal actual fun webNowMillis(): Double = js("Date.now()")
```

---

## Value Codec

`WebWasmValue` wraps every numeric type into one sealed interface.

| Type | Kotlin | JS representation |
| --- | --- | --- |
| I32 | `Int` | `number` |
| F32 | `Float` | `number` |
| F64 | `Double` | `number` |
| I64 | `Long` | `BigInt` (`number` lacks precision) |

File: `wasmline/src/webMain/kotlin/crow/wasmline/web/WebWasmValue.kt`

---

## Async Prefetch Pattern

`WasmlineLoader.load()` is synchronous, but a browser downloads `.wasm` only through the async Fetch API. The bridge is a prefetch cache.

1. Call `WasmlineWeb.prefetch(url)` (suspend) at startup. It downloads and caches the bytes.
2. Call `WasmlineLoader.load(url)`. It reads the cache and returns synchronously.

Files:

- `wasmline/src/webMain/kotlin/crow/wasmline/WasmlineWeb.kt` — public prefetch API
- `wasmline/src/webMain/kotlin/crow/wasmline/web/WebWasmArtifacts.kt` — cache and Fetch loader

```kotlin
WasmlineWeb.prefetch("plugin.wasm")          // suspend: download and cache
val result = WasmlineLoader.load("plugin.wasm")  // sync: read cache
```

---

## WASI Preview1 Shims

File: `wasmline/src/webMain/kotlin/crow/wasmline/web/WebWasmPlugin.kt`

| Function | Purpose | Implementation |
| --- | --- | --- |
| `fd_write(fd, iovsPtr, iovsCount, writtenPtr)` | stdout / stderr | Read text from linear memory, route to logger |
| `random_get(bufPtr, bufLen)` | Random bytes | Fill from `Math.random()` |
| `clock_time_get(clockId, precisionPtr)` | Current time | `Date.now()` scaled to nanoseconds |

---

## Bridge Imports (`env.bridge_*`)

| Name | Direction | Purpose |
| --- | --- | --- |
| `bridge_inbound_copy_params(actionPtr, payloadPtr)` | Host → Plugin | Write params into linear memory |
| `bridge_inbound_set_response(responseBytes)` | Plugin → Host | Write response into shared buffer |
| `bridge_outbound_call_host(action, payload)` | Plugin → Host | Plugin calls a host service |
| `bridge_outbound_get_response()` | Host → Plugin | Read host response |

Sync call path:

```
host call(action, payload)
  -> write params to linear memory
  -> invoke __wasmline_wasi_entry export
  -> plugin dispatch
  -> plugin writes response via bridge_inbound_set_response
  -> read response from linear memory
  -> return TypedResult
```

---

## Tests

Test source set: `wasmline/src/webTest/kotlin/crow/wasmline/web/`

| File | Covers |
| --- | --- |
| `WebWasmValueCodecTest.kt` | Codec round-trip |
| `WebWasmRuntimeTest.kt` | compile → instantiate → invoke |
| `WebWasmImportsBuilderTest.kt` | Import namespace grouping |
| `WebTestModule.kt` | Hand-encoded minimal WASM fixture (add, add64, call_host, memory) |

Run:

```bash
./gradlew wasmline:jsBrowserTest       # Kotlin/JS browser tests
./gradlew wasmline:wasmJsBrowserTest   # Kotlin/WasmJs browser tests
```

### CI Coverage

`.github/workflows/ci.yml` includes a dedicated `test-web` job that runs both test suites in parallel after compilation:

- **Job name**: `test-web`
- **Runner**: `ubuntu-latest` with Chrome headless (`CHROME_BIN=/usr/bin/google-chrome`)
- **Steps**:
  1. `kotlinUpgradeYarnLock` — ensures yarn.lock is up-to-date for Karma dependencies
  2. `:wasmline:jsBrowserTest` and `:wasmline:wasmJsBrowserTest`
  3. Artifact upload of test results to GitHub Actions

Results are available as artifacts under:
- `build/reports/tests/*Test/index.html`
- `build/test-results/*/TEST-*.xml`

**Note**: `webMain` is part of the default KMP hierarchy — its tests run automatically as part of `jsTest`/`wasmJsTest` through source set dependency inheritance. No separate `webTest` task exists; instead, `webTest` code is bundled into the same karma/webpack artifact as `jsTest`/`wasmJsTest`.

---

## File Map

```
webMain/kotlin/crow/wasmline/
  ├─ WasmlineWeb.kt              ← public prefetch API
  └─ web/
     ├─ WebBindings.web.kt       ← expect declarations
     ├─ WebWasmValue.kt          ← value model and codec
     ├─ WebWasmRuntime.kt        ← compile / instantiate
     ├─ WebWasmImports.kt        ← import builder
     ├─ WebArtifactFetcher.kt    ← Fetch loader
     ├─ WebWasmArtifacts.kt      ← prefetch cache
     └─ WebWasmPlugin.kt         ← WASI shims and bridge

jsMain/kotlin/crow/wasmline/web/
  ├─ WebBindings.js.kt           ← actual via typed externals
  └─ WasmNamespace.js.kt         ← WebAssembly namespace externals (Module, Instance, Memory)

wasmJsMain/kotlin/crow/wasmline/web/
  └─ WebBindings.wasmJs.kt       ← actual via JsAny
```
