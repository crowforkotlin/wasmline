<div align="center">

<!-- Logo asset: replace src with actual path -->
<!-- <img src="docs/public/images/logo.png" alt="wasmline" width="96" /> -->

# wasmline

**Kotlin Multiplatform WebAssembly Plugin Framework · Cross-Platform WASI Execution Runtime**

[![License](https://img.shields.io/badge/license-Apache%202.0-4078C0?style=flat-square)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![wasmtime](https://img.shields.io/badge/wasmtime-45.0.6-5C9BD6?style=flat-square)](https://wasmtime.dev)
[![AGP](https://img.shields.io/badge/AGP-9.2.1-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com/build/releases/gradle-plugin)
[![Platform](https://img.shields.io/badge/platform-Android%20%7C%20iOS%20%7C%20macOS%20%7C%20Linux%20%7C%20Windows%20%7C%20Web-555555?style=flat-square)](https://wuya.click/wasmline)
[![WebAssembly](https://img.shields.io/badge/WebAssembly-WASI-654FF0?style=flat-square&logo=webassembly&logoColor=white)](https://wasi.dev)

[中文文档](README_zh.md) · [English](README.md) · [文档](https://wuya.click/wasmline)

</div>

---

Wasmline 是一个 Kotlin Multiplatform 框架，用于在 Android、iOS、Desktop 与 Web 应用中加载并调用符合 WASI 规范的 WebAssembly 插件。

<table>
  <tr>
    <th align="center">macOS — 应用</th>
    <th align="center">Arch Linux — 应用</th>
  </tr>
  <tr>
    <td align="center">
      <img src="docs/public/images/wasmline_mac_apps.png" alt="macOS sample apps" width="100%" />
      <br><em>Desktop · iOS · Android · 终端 · Web (Wasm)</em>
    </td>
    <td align="center">
      <img src="docs/public/images/wasmline_archlinux_apps.png" alt="Arch Linux sample apps" width="100%" />
      <br><em>Desktop · Android · 终端 · Web (JS)</em>
    </td>
  </tr>
  <tr>
    <th align="center">macOS — 构建终端</th>
    <th align="center">Arch Linux — 构建终端</th>
  </tr>
  <tr>
    <td align="center">
      <img src="docs/public/images/wasmline_mac_temrinal.png" alt="macOS build terminals" width="100%" />
      <br><em>构建命令：Desktop · iOS · Android · Web (Wasm)</em>
    </td>
    <td align="center">
      <img src="docs/public/images/wasmline_archlinux_temrinals.png" alt="Arch Linux build terminals" width="100%" />
      <br><em>构建命令：Desktop · Android · Web (JS)</em>
    </td>
  </tr>
</table>

## Sample

**定义服务契约（`commonMain`）：**

```kotlin
// shared/src/commonMain/kotlin/com/example/EchoService.kt
import crow.wasmline.WasmlineService

interface EchoService : WasmlineService {
    fun echo(message: String): String
}
```

**在插件中注册实现（`wasmWasiMain`）：**

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

**在宿主中加载插件并调用服务：**

```kotlin
import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineLoadResult
import crow.wasmline.link
import crow.wasmline.loader.WasmlineLoader
import crow.wasmline.network.ktor.KtorNetworkClient

WasmlineLoader.bootstrap()

// 本地路径与远程 URL 均可——http(s):// 会自动按远程 manifest 加载
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
> `link<T>()` 与 `bind(impl)` 是 Kotlin IR 编译器插件的重写目标。编译单元未应用 `wasmline-kotlin-plugin` 时，这些调用会在运行时抛出 `UnsupportedOperationException`。

## 支持平台

| 平台 | 架构 | 产物支持 | 加载方式 |
|------|------|----------|----------|
| Android | v8a、x86_64 | `.cwasm` / `.pwasm` | wasmtime |
| Android | v7a、x86 | 仅 `.pwasm` | wasmtime |
| iOS | arm64 | `.pwasm` | wasmtime |
| macOS | arm64 | `.cwasm` / `.pwasm` | wasmtime |
| Linux | x86_64 | `.cwasm` / `.pwasm` | wasmtime |
| Windows | x86_64 | `.cwasm` / `.pwasm` | wasmtime |
| Web（Kotlin/JS · Kotlin/WasmJS） | 浏览器 JS 引擎 | 仅原始 `.wasm` | web |

## 安装

> [!NOTE]
> Wasmline 目前处于开发阶段。详细的安装与集成文档将发布于 [wuya.click/wasmline](https://wuya.click/wasmline)。

> [!WARNING]
> 最低需要 **Kotlin 2.3.0-RC2** 版本。
>
> ![Kotlin/Wasm 运行时支持矩阵](docs/public/images/kotlin_support.png)

## 架构思维导图

![Wasmline 架构思维导图](docs/public/images/wasmline_mind_zh.png)

## 许可证

Wasmline 基于 **Apache License, Version 2.0** 发布。完整许可证文本请参见 [LICENSE](LICENSE)。
