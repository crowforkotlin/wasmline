<div align="center">

<!-- Logo asset: replace src with actual path -->
<!-- <img src="docs/public/images/logo.png" alt="wasmline" width="96" /> -->

# wasmline

**Kotlin Multiplatform WebAssembly Plugin Framework · Cross-Platform WASI Execution Runtime**

[![License](https://img.shields.io/badge/license-Apache%202.0-4078C0?style=flat-square)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![wasmtime](https://img.shields.io/badge/wasmtime-47.0.2-5C9BD6?style=flat-square)](https://wasmtime.dev)
[![AGP](https://img.shields.io/badge/AGP-9.3.0-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com/build/releases/gradle-plugin)
[![Platform](https://img.shields.io/badge/platform-Android%20%7C%20iOS%20%7C%20macOS%20%7C%20Linux%20%7C%20Windows%20%7C%20Web-555555?style=flat-square)](https://wuya.click/wasmline)
[![WebAssembly](https://img.shields.io/badge/WebAssembly-WASI-654FF0?style=flat-square&logo=webassembly&logoColor=white)](https://wasi.dev)

[中文文档](README_zh.md) · [English](README.md) · [Documentation](https://wuya.click/wasmline)

</div>

---

Wasmline is a Kotlin Multiplatform framework for loading and calling WASI-compliant WebAssembly plugins in Android, iOS, Desktop, and Web applications.

<table>
  <tr>
    <th align="center">macOS — Apps</th>
    <th align="center">Arch Linux — Apps</th>
  </tr>
  <tr>
    <td align="center">
      <img src="docs/public/images/wasmline_mac_apps.png" alt="macOS sample apps" width="100%" />
      <br><em>Desktop · iOS · Android · Terminal · Web (Wasm)</em>
    </td>
    <td align="center">
      <img src="docs/public/images/wasmline_archlinux_apps.png" alt="Arch Linux sample apps" width="100%" />
      <br><em>Desktop · Android · Terminal · Web (JS)</em>
    </td>
  </tr>
  <tr>
    <th align="center">macOS — Build Terminals</th>
    <th align="center">Arch Linux — Build Terminals</th>
  </tr>
  <tr>
    <td align="center">
      <img src="docs/public/images/wasmline_mac_temrinal.png" alt="macOS build terminals" width="100%" />
      <br><em>Build commands: Desktop · iOS · Android · Web (Wasm)</em>
    </td>
    <td align="center">
      <img src="docs/public/images/wasmline_archlinux_temrinals.png" alt="Arch Linux build terminals" width="100%" />
      <br><em>Build commands: Desktop · Android · Web (JS)</em>
    </td>
  </tr>
</table>

## Sample

**Define the service contract (`commonMain`):**

```kotlin
// shared/src/commonMain/kotlin/com/example/EchoService.kt
import crow.wasmline.WasmlineService

interface EchoService : WasmlineService {
    fun echo(message: String): String
}
```

**Register the implementation in the plugin (`wasmWasiMain`):**

```kotlin
// plugin/src/wasmWasiMain/kotlin/Main.kt
import crow.wasmline.Wasmline
import crow.wasmline.bind

fun main() {
    Wasmline.current.bind(object : EchoService {
        override fun echo(message: String): String {
            return "Response from WASI plugin: $message"
        }
    })
}
```

**Load the plugin and invoke services from the host:**

```kotlin
import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineLoadResult
import crow.wasmline.link
import crow.wasmline.loader.WasmlineLoader
import crow.wasmline.loader.WasmlineLoadOptions
import crow.wasmline.loader.WasmlineTrustedKeySet
import crow.wasmline.network.ktor.KtorNetworkClient

suspend fun main() {
    // Local paths and remote URLs are both supported — http(s):// loads the remote manifest
    val module = when (val result = WasmlineLoader.load(
        source = "https://example.com/plugin/manifest.wlm",
        options = WasmlineLoadOptions(
            runtimeConfig = WasmlineConfig(),
            networkClient = KtorNetworkClient(),
            trustedKeys = WasmlineTrustedKeySet.Builder()
                .addHex(
                    algorithm = "Ed25519",
                    keyId = "release",
                    publicKeyHex = "5a778289bee0c57b05a1c48c8ef312da6ce8e4e4f13fc1a2e8e5aa4cde7ae0db",
                )
                .build(),
        ),
    )) {
        is WasmlineLoadResult.Success -> result.wasmline
        is WasmlineLoadResult.Failure -> error(result.cause)
    }

    val response = module.link<EchoService>().echo("ping")
    module.close()
}
```

Remote artifacts use a streaming, atomically published content-addressed file cache. Set `WasmlineLoadOptions.maxCacheBytes` to change its 512 MiB default capacity.

`WasmlineLoader.load` is a suspending API. Local artifacts and local manifests do not require a network adapter. A remote manifest needs `wasmline-network-ktor`, `wasmline-network-okhttp`, or a custom resolver only when its fresh manifest or selected artifact is missing from the configured cache. The runtime never hardcodes an HTTP engine.

API ownership is explicit: `WasmlineLoader` resolves, verifies, selects, and loads artifacts; `WasmlineRuntime` owns process-wide preload, engine warm-up, runtime information, and shutdown; each loaded `Wasmline` is an independently closeable artifact handle. Loading is lazy, so applications do not need an explicit runtime initialization call before `WasmlineLoader.load()`.

> [!NOTE]
> `link<T>()` and `bind(impl)` are rewrite targets of the Kotlin IR compiler plugin. If `wasmline-kotlin-plugin` is not applied to the compilation unit, these calls throw `UnsupportedOperationException` at runtime.

## Execution Models and Call Results

Wasmline supports four explicit host-side invocation paths:

| Execution model | Invocation protocol | Input | Result |
|---|---|---|---|
| `CORE_WASM` | `WASMLINE_SERVICE` | action name and byte payload | `WasmlineCallResult<ByteArray>` |
| `CORE_WASM` | `RAW_EXPORT` | declared Core Wasm numeric values | `WasmlineCallResult<WasmlineRawCallResult>` |
| `COMPONENT_MODEL` | `WASMLINE_SERVICE` | action name and byte payload through `wasmline.wit` | `WasmlineCallResult<ByteArray>` |
| `COMPONENT_MODEL` | `COMPONENT_EXPORT` | declared Component Model values | `WasmlineCallResult<WasmlineComponentCallResult>` |

The runtime side of the Component Model path loads an already compiled component
binary. The optional plugin build pipeline can generate bindings and create that
binary from WIT through `wasmline-plugin-core`, the Gradle plugin, or the CLI;
the loader itself does not run those tools. `contractMetadata` describes the
call contract when needed; it is not a WIT compiler input. See the
[Wasmline Service guide](docs/content/docs/component-service.mdx).

The current browser runtime supports the Core Wasmline bridge. Raw Export and Component Model typed calls are provided by the native host backend, where the Wasmtime C API is available.

Core Wasmline calls return results instead of using exceptions for normal call failures:

```kotlin
import crow.wasmline.callResult
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode

when (val result = module.callResult("echo", payload)) {
    is WasmlineCallResult.Success -> usePayload(result.value)
    is WasmlineCallResult.Failure -> {
        if (result.error.code == WasmlineErrorCode.ACTION_NOT_BOUND) {
            log("The plugin did not bind this action.")
        }
        log(result.error.message)
    }
}
```

An unbound action returns `ACTION_NOT_BOUND`. It does not return an empty payload and does not crash the host. The same result-first rule applies to unknown actions, invalid payloads, traps, and handler failures. `throwOnFailure()` is an explicit adapter for code that chooses exception-style handling; it is not used by the result API by default.

The `WASMLINE_SERVICE` response frame starts with the four-byte `WLMF` magic marker and a one-byte `frameVersion` whose current value is `1`. The magic marker identifies the frame format; it is not a security check. `frameVersion` identifies the response byte layout; it is not a Wasmtime, Kotlin, framework, or business API version. Raw Export and Component Model calls do not use this Core response frame.

For direct calls, the descriptor must declare both the execution model and protocol:

```kotlin
val component = WasmlineLoader.load(
    WasmlineArtifactDescriptor(
        path = "component.wasm",
        executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
        invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
        exportName = "add",
    ),
)
```

## Platform Support

| Platform | Architecture | Artifact Support | Loading |
|----------|--------------|------------------|---------|
| Android  | v8a, x86_64  | `.cwasm` / `.pwasm` | wasmtime |
| Android  | v7a, x86     | `.pwasm` only    | wasmtime |
| iOS      | arm64        | `.pwasm`         | wasmtime |
| macOS    | arm64        | `.cwasm` / `.pwasm` | wasmtime |
| Linux    | x86_64       | `.cwasm` / `.pwasm` | wasmtime |
| Windows  | x86_64       | `.cwasm` / `.pwasm` | wasmtime |
| Web (Kotlin/JS · Kotlin/WasmJS) | Browser JS engine | Raw `.wasm` only | web |

## Installation

> [!NOTE]
> Wasmline is under active development. Detailed installation and integration documentation will be available at [wuya.click/wasmline](https://wuya.click/wasmline).

> [!WARNING]
> The minimum required Kotlin version is **2.3.0-RC2**.
>
> ![Kotlin/Wasm runtime support matrix](docs/public/images/kotlin_support.png)

## Gradle Wrapper Tasks

After applying the released Wasmline Gradle plugin, run:

```bash
# Build a debug package for local testing
./gradlew wasmlineAssembleDebug

# Build a release package for distribution
./gradlew wasmlineAssembleRelease

# Build and serve the configured package
./gradlew wasmlineServerDeploy
```

Select the package served by `wasmlineServerDeploy` with a typed value:

```kotlin
import crow.wasmline.gradle.WasmlineBuildVariant

wasmline {
    server {
        deployVariant = WasmlineBuildVariant.RELEASE
    }
}
```

The default is `DEBUG`, served at `http://localhost:8080`. Required toolchain and Component pipeline tasks run automatically. The [Gradle plugin task reference](docs/content/docs/gradle-plugin.mdx) covers all 15 user-facing tasks and their registration conditions.

## Architecture Mind Map

![Wasmline Architecture Mind Map](docs/public/images/wasmline_mind_en.png)

## License

Wasmline is distributed under the **Apache License, Version 2.0**. See [LICENSE](LICENSE) for the complete license text.
