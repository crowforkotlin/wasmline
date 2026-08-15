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

## 执行模型与调用结果

Wasmline 支持四种显式的宿主调用路径：

| 执行模型 | 调用协议 | 输入 | 结果 |
|---|---|---|---|
| `CORE_WASM` | `WASMLINE_SERVICE` | action 名称与字节 payload | `WasmlineCallResult<ByteArray>` |
| `CORE_WASM` | `RAW_EXPORT` | 已声明的 Core Wasm 数值 | `WasmlineCallResult<WasmlineRawCallResult>` |
| `COMPONENT_MODEL` | `WASMLINE_SERVICE` | 通过 `wasmline.wit` 传递 action 名称与字节 payload | `WasmlineCallResult<ByteArray>` |
| `COMPONENT_MODEL` | `COMPONENT_EXPORT` | 已声明的 Component Model 值 | `WasmlineCallResult<WasmlineComponentCallResult>` |

Component Model 路径直接加载已经编译完成的 component binary。Wasmline 不编译 WIT、WIT-Kotlin，也不生成 component adapter。需要时，`contractMetadata` 用于描述调用契约；它不是 WIT 编译输入。

当前浏览器运行时支持 Core Wasmline bridge。Raw Export 和 Component Model typed 调用由可以使用 Wasmtime C API 的 native 宿主后端提供。

Core Wasmline 调用通过结果返回普通调用错误，不使用异常作为普通控制流：

```kotlin
import crow.wasmline.callResult
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode

when (val result = module.callResult("echo", payload)) {
    is WasmlineCallResult.Success -> usePayload(result.value)
    is WasmlineCallResult.Failure -> {
        if (result.error.code == WasmlineErrorCode.ACTION_NOT_BOUND) {
            log("插件未绑定该 action")
        }
        log(result.error.message)
    }
}
```

未绑定 action 返回 `ACTION_NOT_BOUND`，不返回空 payload，也不会使宿主崩溃。未知 action、无效 payload、执行 trap 和 handler 失败也遵循结果优先规则。`throwOnFailure()` 只为明确选择异常风格的调用方提供适配，不是结果 API 的默认行为。

`WASMLINE_SERVICE` 响应帧以四字节 `WLMF` magic 标记开始，并使用一个字节的 `frameVersion`，当前值为 `1`。magic 只用于识别帧格式，不提供安全校验。`frameVersion` 表示响应字节布局，不表示 Wasmtime、Kotlin、框架或业务 API 版本。Raw Export 和 Component Model 调用不使用该 Core 响应帧。

直接调用时，描述对象必须同时声明执行模型和调用协议：

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

Cranelift Wasmtime 运行时同时支持 `.cwasm` 和 `.pwasm`；原生宿主优先选择匹配的 `.cwasm`，缺少时回退到匹配位数的 `.pwasm`。Pulley 引擎仅支持 `.pwasm`。iOS 只能使用解释器，因此始终使用 `.pwasm`。

## 安装

> [!NOTE]
> Wasmline 目前处于开发阶段。详细的安装与集成文档将发布于 [wuya.click/wasmline](https://wuya.click/wasmline)。

> [!WARNING]
> 最低需要 **Kotlin 2.3.0-RC2** 版本。
>
> ![Kotlin/Wasm 运行时支持矩阵](docs/public/images/kotlin_support.png)

## Gradle Wrapper 任务

在使用方项目中应用已发布的 Wasmline Gradle 插件后，可直接执行：

```bash
# 构建调试包，用于本地测试
./gradlew wasmlineAssembleDebug

# 构建用于发布的发行包
./gradlew wasmlineAssembleRelease

# 构建并启动构件服务
./gradlew wasmlineServerDeploy
```

使用类型安全的变体配置选择 `wasmlineServerDeploy` 提供的构件：

```kotlin
import crow.wasmline.gradle.WasmlineBuildVariant

wasmline {
    server {
        deployVariant = WasmlineBuildVariant.RELEASE
    }
}
```

默认值为 `DEBUG`，服务地址为 `http://localhost:8080`。所需工具链与 Component 流水线任务会自动执行。[Gradle 插件任务参考](docs/content/docs/gradle-plugin.zh.mdx)列出全部 15 个面向用户的任务及其注册条件。

## 架构思维导图

![Wasmline 架构思维导图](docs/public/images/wasmline_mind_zh.png)

## 许可证

Wasmline 基于 **Apache License, Version 2.0** 发布。完整许可证文本请参见 [LICENSE](LICENSE)。
