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
import crow.wasmline.network.ktor.KtorNetworkClient

WasmlineLoader.bootstrap()

// Local paths and remote URLs are both supported — http(s):// loads the remote manifest
val module = when (val result = WasmlineLoader.load(
    source = "https://example.com/plugin/manifest.wlm",
    config = WasmlineConfig(networkClient = KtorNetworkClient()),
)) {
    is WasmlineLoadResult.Success -> result.wasmline
    is WasmlineLoadResult.Failure -> error(result.cause)
}

val response = module.link<EchoService>().echo("ping")
module.close()
```

> [!NOTE]
> `link<T>()` and `bind(impl)` are rewrite targets of the Kotlin IR compiler plugin. If `wasmline-kotlin-plugin` is not applied to the compilation unit, these calls throw `UnsupportedOperationException` at runtime.

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

## Architecture Mind Map

![Wasmline Architecture Mind Map](docs/public/images/wasmline_mind_en.png)

## License

Wasmline is distributed under the **Apache License, Version 2.0**. See [LICENSE](LICENSE) for the complete license text.
