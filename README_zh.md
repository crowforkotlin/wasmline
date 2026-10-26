<div align="center">

<!-- Logo asset: replace src with actual path -->
<!-- <img src="fumadocs/public/images/logo.png" alt="wasmline" width="96" /> -->

# wasmline

**用于加载和调用 WASI WebAssembly 插件的 Kotlin Multiplatform API**

[![License](https://img.shields.io/badge/license-Apache%202.0-4078C0?style=flat-square)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![wasmtime](https://img.shields.io/badge/wasmtime-50.0.0-5C9BD6?style=flat-square)](https://wasmtime.dev)
[![AGP](https://img.shields.io/badge/AGP-9.3.0-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com/build/releases/gradle-plugin)
[![Platform](https://img.shields.io/badge/platform-Android%20%7C%20iOS%20%7C%20macOS%20%7C%20Linux%20%7C%20Windows%20%7C%20Web-555555?style=flat-square)](https://wuya.click/wasmline)
[![WebAssembly](https://img.shields.io/badge/WebAssembly-WASI-654FF0?style=flat-square&logo=webassembly&logoColor=white)](https://wasi.dev)

[中文文档](README_zh.md) · [English](README.md) · [文档](https://wuya.click/wasmline)

</div>

---

Wasmline 是一个 Kotlin Multiplatform 库，用于在 Android、iOS、Desktop 与 Web 应用中加载并调用符合 WASI 规范的 WebAssembly 插件。Native 执行使用 Wasmline 分发的 Wasmtime fork；浏览器执行使用平台提供的 WebAssembly API。

<table>
  <tr>
    <th align="center">macOS — 应用</th>
    <th align="center">Arch Linux — 应用</th>
  </tr>
  <tr>
    <td align="center">
      <img src="fumadocs/public/images/wasmline_mac_apps.png" alt="macOS sample apps" width="100%" />
      <br><em>Desktop · iOS · Android · 终端 · Web (Wasm)</em>
    </td>
    <td align="center">
      <img src="fumadocs/public/images/wasmline_archlinux_apps.png" alt="Arch Linux sample apps" width="100%" />
      <br><em>Desktop · Android · 终端 · Web (JS)</em>
    </td>
  </tr>
  <tr>
    <th align="center">macOS — 构建终端</th>
    <th align="center">Arch Linux — 构建终端</th>
  </tr>
  <tr>
    <td align="center">
      <img src="fumadocs/public/images/wasmline_mac_temrinal.png" alt="macOS build terminals" width="100%" />
      <br><em>构建命令：Desktop · iOS · Android · Web (Wasm)</em>
    </td>
    <td align="center">
      <img src="fumadocs/public/images/wasmline_archlinux_temrinals.png" alt="Arch Linux build terminals" width="100%" />
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
    Wasmline.get().bind(object : EchoService {
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
import crow.wasmline.loader.WasmlineLoadOptions
import crow.wasmline.loader.WasmlineTrustedKeySet
import crow.wasmline.network.ktor.KtorNetworkClient

suspend fun main() {
    // 本地路径与远程 URL 均可——http(s):// 会自动按远程 manifest 加载
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
        is WasmlineLoadResult.Failure -> error(result.failure.message)
    }

    val response = module.link<EchoService>().echo("ping")
    module.close()
}
```

下载远程 artifact 时，Loader 将字节流式写入缓存，校验 SHA-256 摘要后再发布到该摘要对应的路径。可通过 `WasmlineLoadOptions.maxCacheBytes` 调整默认的 512 MiB 容量上限。

`WasmlineLoader.load` 是 suspend API。本地产物与本地 manifest 不需要 network adapter。远程 manifest 只有在 fresh manifest 或所选 artifact 未命中缓存时，才需要 `wasmline-network-ktor`、`wasmline-network-okhttp` 或自定义 resolver；runtime 不会写死任何 HTTP engine。

各 API 的职责如下：`WasmlineLoader` 负责解析、校验、选择并加载产物；`WasmlineRuntime` 提供进程级的预加载、引擎预热、运行时信息和全局关闭；每个已加载的 `Wasmline` 都是可独立关闭的产物句柄。加载采用惰性初始化，因此调用 `WasmlineLoader.load()` 前不需要显式初始化运行时。

重复加载同一产物会创建独立的句柄和服务绑定，关闭其中一个不会关闭另一个。`close()` 可重复调用。每个句柄复用自己的 `CoreWasmModule` 外观；关闭句柄会关闭其会话，并更新模块和会话的 `isClosed` 状态。单独关闭会话会立即移除其注册，不需要等待模块关闭。

> [!NOTE]
> `link<T>()` 与 `bind(impl)` 是 Kotlin IR 编译器插件的重写目标。编译单元未应用 `wasmline-kotlin-plugin` 时，这些调用会在运行时失败，错误消息会说明编译器插件未完成重写。

## Package 与 AOT 兼容性

一个插件发行版只使用一个 `manifest.wlm`，用于描述全部已配置的 AOT
generation 和物理 target。插件作者选择 Wasmline 发行 generation；随构建工具
打包的本地 catalog 负责解析不可变且区分 backend 的 profile ID。

```kotlin
import crow.wasmline.gradle.WasmtimeTarget
import crow.wasmline.plugin.core.aot.AotGenerations

wasmline {
    wasmtime {
        // Omit `aotCompatibility` to compile the current generation only.
        aotCompatibility {
            generations(AotGenerations.Current)
        }
        targets = listOf(
            WasmtimeTarget.PULLEY_64,
            WasmtimeTarget.X86_64_LINUX,
            WasmtimeTarget.X86_64_WINDOWS,
        )
        autoDownload.set(true)
    }
}
```

省略该块、使用空块，或只设置 `suppressCompatibilityWarning`，都会选择包含当前
引入的 Wasmline 构建工具版本的那个 generation：

```kotlin
wasmline {
    wasmtime {
        // No `aotCompatibility` call: compile the current generation only.
    }
}
```

类型化策略与具体版本选择可以写在同一个块中；第一次显式调用替换隐式的 current
默认值，之后每次调用都并入结果并集：

```kotlin
wasmline {
    wasmtime {
        aotCompatibility {
            generations(AotGenerations.Current(before = 1))
            generations(AotGenerations.Latest)
            generations(AotGenerations.Earliest(after = 1))
            generations(AotGenerations.Versions(min = "1.0.0", max = "1.0.1"))
            generations(versions = listOf("1.0.0", "1.0.1"))
            generations(min = "1.0.0", max = "1.0.1")
        }
    }
}
```

`AotGenerations.Current`、`Latest`、`Earliest` 与 `All` 各自选择一种策略，
`Current(before = 1, after = 1)`、`Latest(before = 1)` 和 `Earliest(after = 1)`
再附加相邻的 AOT generation。计数是额外的相邻数量：锚点 generation 始终包含在内，
因此当两个方向的相邻 generation 都存在时，`Current(before = 1, after = 1)` 覆盖三个
generation。窗口计数的是 AOT generation，不是 Maven 发行版本；请求的相邻 generation
在本地不存在时构建会失败，不会被裁剪。`All` 需要显式选择，可能编译 packaged catalog
中保留的全部 generation：

```kotlin
wasmline {
    wasmtime {
        aotCompatibility {
            generations(AotGenerations.All)
        }
    }
}
```

`generations(version)` 选择包含该具体 `x.y.z` Wasmline 版本的 generation，
`generations(versions = listOf(...))` 选择非空的具体版本集合，
`generations(min = ..., max = ...)` 选择两个具体版本之间的闭区间。类型化的
`AotGenerations.Versions` 与这些重载等价：`Versions(version)`、`Versions(versions)`
和 `Versions(min, max)`。字符串输入始终是具体版本，不存在符号版本字符串，也没有
`from`/`through` 重载。`Latest` 是本地保留的最新 generation，`Earliest` 是最早的
generation，`Current` 以构建工具发行版本为锚点；当 packaged catalog 中没有更新的
generation 时，`Current` 与 `Latest` 指向同一个 generation。第一次显式调用会替换
隐式的 current 默认值，后续调用取并集。重复 generation 与复用的 backend profile
会去重，因此同一 generation 内的多个版本只编译一次。选择完全基于随构建工具打包的
本地 catalog 解析，不访问网络。每个具体版本都必须位于本地支持的
`minimumSupportedWasmlineVersion`..`currentWasmlineVersion` 区间内；越界、倒置或
不可用的输入会直接构建失败，不会被裁剪。声明了 WASI assemble graph 的项目会在配置
阶段以 `[WLAOT100]` 失败；其他项目的 AOT 与检查 task action 会在下载 compiler 或
签名 package 之前拒绝该选择。公共输入是 Wasmline 发行版本；Wasmtime distribution
版本与 profile digest 属于内部 catalog 与诊断信息。

assemble 成功后，`wasmlineCheckAotCompatibility` 会将本地选择与最新稳定
catalog 比较，并写入 `build/reports/wasmline/aot-compatibility-check.json`。
即使 generation gap 为零也会输出警告。审查报告后可以设置
`suppressCompatibilityWarning.set(true)`；该设置只抑制日志，不会关闭检查或
改变报告与 AOT 产物。Web raw `.wasm` 不在该 native AOT 检查范围内。

Package 按 SHA-256 保存 artifact：

```text
{pluginId}-{version}/
├── manifest.wlm
├── artifacts/sha256/{prefix}/{digest}.wasm|cwasm|pwasm
└── debug/
    ├── manifest.json
    ├── aot-build-record.json
    └── artifact-index.json
```

Core Web `.wasm` 只生成和保存一次，不随 Wasmtime 版本重复。Native CWASM
与 PWASM 只针对 backend 相同的 profile 编译。离线 ZIP 包含该 Package 选择的全部
artifact；远程加载只获取 manifest 和一个已选择 artifact，不下载 ZIP 或无关 target。

Pulley 按 pointer width 选择 `pulley32` 或 `pulley64`。Cranelift 要求 profile、
操作系统、架构、pointer width 与 CPU feature 精确匹配。只有不存在兼容 CWASM，
且 runtime 报告匹配的 Pulley profile 并支持 PWASM 时，才能使用 PWASM。
Artifact 下载或摘要失败不会触发回退。

Compiler archive 由 catalog 锁定，并按摘要缓存在
`~/.wasmline/toolchains/wasmtime/compiler-assets/sha256/`。构建不接受任意
本地 compiler executable。

## 执行模型与调用结果

Wasmline 支持四种显式的宿主调用路径：

| 执行模型 | 调用协议 | 输入 | 结果 |
|---|---|---|---|
| `CORE_WASM` | `WASMLINE_SERVICE` | action 名称与字节 payload | `WasmlineCallResult<ByteArray>` |
| `CORE_WASM` | `RAW_EXPORT` | `CoreWasmModule`/`CoreWasmSession` 数值、同步 imports 与线性内存 | `WasmlineCallResult<List<RawValue>>` |
| `COMPONENT_MODEL` | `WASMLINE_SERVICE` | 通过 `wasmline.wit` 传递 action 名称与字节 payload | `WasmlineCallResult<ByteArray>` |
| `COMPONENT_MODEL` | `COMPONENT_EXPORT` | 已声明的 Component Model 值 | `WasmlineCallResult<WasmlineComponentCallResult>` |

Component Model 路径直接加载已经编译完成的 component binary。Wasmline 不编译 WIT、WIT-Kotlin，也不生成 component adapter。需要时，`contractMetadata` 用于描述调用契约；它不是 WIT 编译输入。

浏览器运行时同时支持 Core Service 与 Core Raw Export 路径。Web 使用 raw
`.wasm`、`WebAssembly.Module`/`WebAssembly.Instance`、同步 imports 和有边界检查的线性内存；
native 使用 Wasmtime bridge 与 `.cwasm`/`.pwasm` AOT 产物。Component Model typed 调用仍仅由 native 提供。

对于 `RAW_EXPORT`，先加载 `CoreWasmModule`，在 `instantiate()` 前注册同步
`RawImport` handler，再调用 `RawValue` export；高频数据通过 `RawMemory`
批量访问。浏览器内嵌 `.wasm` 使用 `WasmlineWeb.registerBytes()`，native
仍只选择 AOT 产物。签名 package 将 export signature、import、memory 与所需
feature 保存在 `runtimeContract.rawAbi` 中，不写入自由格式 `contractMetadata`。

Core Wasmline 调用通过结果返回普通调用错误，不使用异常作为普通控制流：

```kotlin
import crow.wasmline.callResult
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode

when (val result = module.callResult("echo", payload)) {
    is WasmlineCallResult.Success -> usePayload(result.value)
    is WasmlineCallResult.Failure -> {
        if (result.failure.code == WasmlineErrorCode.ACTION_NOT_BOUND) {
            log("插件未绑定该 action")
        }
        log(result.failure.message)
    }
}
```

未绑定 action 返回 `ACTION_NOT_BOUND`，不返回空 payload，也不会使宿主崩溃。未知 action、无效 payload、执行 trap 和 handler 失败也遵循结果优先规则。`throwOnFailure()` 只为明确选择异常风格的调用方提供适配，不是结果 API 的默认行为。

结果 API 对非抛出失败返回 `WasmlineFailure`，`WasmlineException` 只用于显式
抛出适配器，`WasmlineLoadFailure` 描述模块创建前的失败。结果 API 的失败详情
只存放在 `failure` 属性中。

`WASMLINE_SERVICE` 响应帧以四字节 `WLMF` magic 标记开始，并使用一个字节的 `frameVersion`，当前值为 `1`。magic 只用于识别帧格式，不提供安全校验。`frameVersion` 表示响应字节布局，不表示 Wasmtime、Kotlin、框架或业务 API 版本。Raw Export 和 Component Model 调用不使用该 Core 响应帧。

解码器对截断的响应帧和非法 UTF-8 消息返回 `RESPONSE_MALFORMED`。旧式无帧响应仍可使用：不足四字节、仅匹配 `WLMF` 前缀的数据按 payload 返回；完整标记后缺少完整 header 则属于非法响应帧。

有可用 Package 时，通过 `manifest.wlm` 加载。Loader 会校验 package，并将 execution model、
invocation protocol、target identity、artifact format 与 AOT compatibility
profile 写入所选 descriptor。直接加载调用方信任的 AOT artifact 时，必须显式提供
全部字段；仅提供 `component.cwasm` 路径不能证明兼容性。

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

Native 选择使用已链接 engine 实际报告的 AOT compatibility profile ID，
不会根据 Maven 版本或文件名推导兼容性。iOS 只使用 `pulley64` PWASM。

## 安装

> [!NOTE]
> Wasmline 当前通过 `mavenLocal()` 分发，尚未发布到 Maven Central。请参阅[安装说明](fumadocs/content/docs/installation.zh.mdx)。

> [!WARNING]
> 最低需要 **Kotlin 2.4.0** 版本。
>
> 本次发行产物使用该 Kotlin 版本编译并验证。较早的 Kotlin/Native 编译器可能拒绝发布的 KLIB ABI；
> 使用更高版本时，需要重新验证运行时产物与编译器插件的兼容性。

JVM 与 Android 使用 BOM，使 runtime、Loader、network adapter 与 engine
解析为同一个 strict Wasmline 版本：

```kotlin
dependencies {
    implementation(platform("crow.wasmline:wasmline-bom:1.0.0"))
    implementation("crow.wasmline:wasmline-loader")
    implementation("crow.wasmline:wasmline-network-ktor")
    implementation("crow.wasmline:wasmline-engine-cranelift")
}
```

Kotlin Multiplatform source set 应为全部 Wasmline coordinate 使用同一个版本
变量。Engine 模块不能单独升级。Native 启动会在加载 AOT artifact 前校验
Wasmline release identity 与 bridge ABI。

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

默认值为 `DEBUG`，服务地址为 `http://localhost:8080`。
`wasmlineServerDeploy` 会构建并提供所选变体的 package。[Gradle 插件任务参考](<fumadocs/content/docs/(reference)/(plugin-development)/gradle-plugin.zh.mdx>)
列出当前任务及其注册条件。

## Release 构建

以下命令均从仓库根目录执行。每条命令使用其所属项目的 Gradle wrapper：

```bash
# Wasmline 模块与 Android AAR
(cd . && ./gradlew assemble)
(cd . && ./gradlew :wasmline-android:assembleDebug)
(cd . && ./gradlew :wasmline-android:assembleRelease)

# JVM 校验与 Gradle plugin 集成测试
(cd . && ./gradlew :wasmline:jvmTest)
(cd wasmline-plugin-test && ./gradlew jvmTest)

# Web production distribution
(cd . && ./gradlew :wasmline:jsBrowserProductionLibraryDistribution)
(cd . && ./gradlew :wasmline:wasmJsBrowserProductionLibraryDistribution)

# Apple binary（需要 macOS）
(cd . && ./gradlew :wasmline:iosArm64Binaries :wasmline:iosSimulatorArm64Binaries)

# 当前操作系统的 Desktop 分发包
(cd samples/multiplatform-app && ./gradlew :sample-apps:multiplatform:desktopApp:packageDistributionForCurrentOS)
```

GitHub Actions 的发布任务会构建可发布的 Wasmline 模块、校验 AOT catalog，并上传
`aot-compatibility.json` 及其 SHA-256 摘要。该发布任务只响应
`release-x.y.z.v` 格式的 tag；推送到 `main` 不会发布。`x.y.z` 是 Wasmline
Maven 版本，`v` 是 Wasmtime runtime 版本的固定数字编码。

## 架构图

![Wasmline 架构图](fumadocs/public/images/wasmline_mind_zh.png)

## 许可证

Wasmline 基于 **Apache License, Version 2.0** 发布。完整许可证文本请参见 [LICENSE](LICENSE)。
