<div align="center">

<!-- Logo asset: replace src with actual path -->
<!-- <img src="docs/public/images/logo.png" alt="Wasmline" width="96" /> -->

# Wasmline

**Kotlin Multiplatform WebAssembly Plugin Framework · Cross-Platform WASI Execution Runtime**

[![License](https://img.shields.io/badge/license-Apache%202.0-4078C0?style=flat-square)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.20--RC-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Wasmtime](https://img.shields.io/badge/Wasmtime-45.0.0-5C9BD6?style=flat-square)](https://wasmtime.dev)
[![AGP](https://img.shields.io/badge/AGP-9.2.1-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com/build/releases/gradle-plugin)
[![Platform](https://img.shields.io/badge/platform-Android%20%7C%20iOS%20%7C%20macOS%20%7C%20Linux%20%7C%20Windows%20%7C%20Web-555555?style=flat-square)](#platform-support)
[![WebAssembly](https://img.shields.io/badge/WebAssembly-WASI-654FF0?style=flat-square&logo=webassembly&logoColor=white)](https://wasi.dev)

[中文文档](README_zh.md) · [English](README.md) · [文档](docs/) · [示例](#sample-applications)

</div>

---

## 简介

Wasmline 是一个 **Kotlin Multiplatform WebAssembly 插件框架**，在统一的 API 表面下，为 Android、iOS、Desktop 和 Web 目标提供加载与分发符合 WASI 规范插件的统一、类型安全执行接口。

该框架建立在三个基础不变量之上：

**编译期桥接生成。** 服务契约以扩展 `WasmlineService` 的 Kotlin `interface` 类型表达。Kotlin IR 编译器插件在构建期生成全部序列化、分发与桥接基础设施，消除运行时反射、注解处理以及手工 marshalling 代码。

**双路径运行时架构。** 原生目标（Android、iOS、macOS、Linux、Windows）通过 Zig 0.15.1 编译的 `wasmline-core` 原生桥接层，以 JNI 或 Kotlin/Native C Interop 方式调用 **Wasmtime v45.0.0 (C-API)** 执行插件。Web 目标（Kotlin/JS、Kotlin/WasmJS）则通过浏览器原生 `WebAssembly.Module` / `WebAssembly.Instance` 容器以及自包含、轻量级的内联 JavaScript 运行时执行插件——浏览器执行路径中不包含 Wasmtime。

**语言无关的插件编写。** 插件二进制可以由任何以 WASI 为目标的工具链生成，包括 Kotlin、Rust、C/C++、Go 和 AssemblyScript。

---

## 平台支持

### 运行时架构矩阵

| 平台              | 目标三元组          | 运行时引擎                  | 桥接技术                      | 产物支持              | 模块加载器                |
|-------------------|---------------------|-----------------------------|-------------------------------|-----------------------|---------------------------|
| Android           | `arm64-v8a`         | Wasmtime v45.0.0 C-API      | JNI（Zig 0.15.1 编译）        | `.cwasm` / `.pwasm`   | `wasmline-core` Session   |
| iOS               | `arm64`             | Wasmtime v45.0.0 C-API      | C Interop（`.def` cinterop）  | `.pwasm`              | `wasmline-core` Session   |
| macOS             | `arm64`             | Wasmtime v45.0.0 C-API      | JNI（Zig 0.15.1 编译）        | `.cwasm` / `.pwasm`   | `wasmline-core` Session   |
| Linux             | `x86_64`            | Wasmtime v45.0.0 C-API      | JNI（Zig 0.15.1 编译）        | `.cwasm` / `.pwasm`   | `wasmline-core` Session   |
| Windows           | `x86_64`            | Wasmtime v45.0.0 C-API      | JNI（Zig 0.15.1 编译）        | `.cwasm` / `.pwasm`   | `wasmline-core` Session   |
| Web - Kotlin/Js   | 浏览器 JS 引擎      | 浏览器 `WebAssembly` API    | Inline JS (`js()` interop)    | 仅原始 `.wasm`        | Synchronous XHR fetch     |
| Web - Kotlin/Wasm | 浏览器 JS 引擎      | 浏览器 `WebAssembly` API    | Inline JS (`js()` interop)    | 仅原始 `.wasm`        | Synchronous XHR fetch     |

> [!IMPORTANT]
> **Web 目标独立于 Wasmtime 运行。** 浏览器执行路径仅通过浏览器原生 `WebAssembly.Module` / `WebAssembly.Instance`
> 容器实例化插件二进制。一个自包含、轻量级的 WASI shim 层——包括 `fd_write`、`random_get`、
> `clock_time_get` 以及 Wasmline bridge 协议——以内联 Kotlin JS interop（`js()`）形式嵌入，不会生成
> 外部 JavaScript 文件、原生库二进制或 npm 依赖。payload 数据以 Base64 编码字符串跨越 Kotlin–JS
> 线性内存边界。

> [!WARNING]
> CLI 流水线生成的 AOT 编译产物（`.cwasm`、`.pwasm`）**不是 Web 目标的有效输入**。Web 运行时仅接受原始 `.wasm` 二进制。

---

## 示例应用

<details>
<summary><strong>应用截图</strong></summary>

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

</details>

参考实现位于 `wasmline-samples/kotlin/`：

| 模块                        | 说明                                                                                                    |
|-----------------------------|---------------------------------------------------------------------------------------------------------|
| `sample-common`             | 共享服务契约接口定义：`EchoService`、`TimeSyncService`、`WebBridgeService`                              |
| `sample-plugin`             | Kotlin/WasmWasi 插件——通过 `bind()` 注册实现，通过 `link()` 调用宿主服务                                |
| `sample-apps/android`       | Android 宿主——加载 `.pwasm` 产物，注册宿主回调，并调用插件服务                                          |
| `sample-apps/application`   | JVM 无界面桌面宿主                                                                                      |
| `sample-apps/multiplatform` | Compose Multiplatform 应用——Android 与 Desktop                                                          |
| `sample-apps/web`           | Web 宿主——Kotlin/JS 与 Kotlin/WasmJS 目标                                                               |

---

## 安装与先决条件

### 系统要求

| 组件                             | 要求版本                                                                                         | 适用范围                                                |
|----------------------------------|--------------------------------------------------------------------------------------------------|---------------------------------------------------------|
| JDK / JBR                        | **21**（Compose Desktop 必须使用 [JBR 21](https://github.com/JetBrains/JetBrainsRuntime/releases)） | 所有 Gradle 操作                                         |
| Kotlin                           | 最低 **2.3.20-RC**                                                                               | Wasm GC、函数引用、异常处理提案                          |
| Android Studio                   | **LTS**                                                                                          | AGP 9.2.1 兼容性                                         |
| Zig                              | **0.15.1**                                                                                       | `wasmline-core` JNI 共享库编译                           |
| Bash / Python 3.9+ / Node.js 18+ | 任意一种                                                                                         | 平台运行时资产初始化                                     |

> [!NOTE]
> 开发指南与工具链配置 — 手动执行项目构建或运行相关命令时，项目根目录下需要 `local.properties` 文件。在 Android Studio 中打开项目后，AS 会自动创建该文件。请确认此文件存在后再在 IDE 外执行 Gradle 命令。

> [!WARNING]
> 在确认当前 JVM 环境前，不得调用 Gradle。每次构建会话开始前都必须执行仓库预检：
> ```bash
> bash ./scripts/doctor.sh
> ```
> 该脚本会验证 JBR 21 是否可用，报告桌面 Zig/JNI-native 状态，并报告
> `build/platforms/` 下缺失的 Wasmtime 平台/架构资产 WARNING。

### 平台运行时资产初始化

> [!NOTE]
> 资产初始化仅对 **原生目标构建**（Android、iOS、Desktop）必需。仅面向 Web 的构建不需要 Wasmtime min C-API 资产。

在开始原生目标编译前，`build/platforms/` 下必须存在 Wasmtime min C-API 头文件与预编译库。执行以下任一等价脚本：

```bash
sh ./scripts/init.sh          # Bash — requires curl and tar/unzip
python3 ./scripts/init.py     # Python 3.9+ — no third-party dependencies
node ./scripts/init.mjs       # Node.js 18+  — no third-party dependencies
```

三个脚本都支持交互式平台/架构选择、可配置的下载并发数，以及作为第一个位置参数传入可选 HTTP 代理（例如 `127.0.0.1:7890`）。

---

## 集成参考

### 服务契约定义

服务契约是在共享源码（`commonMain`）中声明的 Kotlin `interface`，并扩展 `WasmlineService`。该契约定义了宿主与插件之间的二进制协议边界；方法签名是 SHA-256 动作标识符派生的基础。

```kotlin
// shared/src/commonMain/kotlin/com/example/EchoService.kt
import crow.wasmline.WasmlineService

interface EchoService : WasmlineService {
    fun echo(message: String): String
}
```

### 插件实现 — WASI 目标

插件二进制通过 `Wasmline.current.bind(impl)` 注册服务实现。IR 编译器插件会在 IR 层将该调用点重写为把实现注册到生成的 `*_WasmlineBridge` 中：

```kotlin
// plugin/src/wasmWasiMain/kotlin/Main.kt
import crow.wasmline.Wasmline
import crow.wasmline.bind

val wasmline = Wasmline.current

fun main() {
    wasmline.bind(object : EchoService {
        override fun echo(message: String): String {
            return "Response from WASI plugin: $message"
        }
    })
}
```

### 宿主端模块加载与服务调用

宿主通过 `loadWasmline` 加载编译后的插件产物，通过 `bind(impl)` 注册插件可访问的宿主侧服务实现，并通过 `link<T>()` 获取插件侧服务的类型安全代理：

```kotlin
import crow.wasmline.WasmlineLoadState
import crow.wasmline.link
import crow.wasmline.bind
import crow.wasmline.loader.loadWasmline

Wasmline.bootstrap()

// 可选：如果你明确知道会优先加载哪种产物，可以提前预热对应 backend
Wasmline.warmup(WasmlineWarmupMode.PULLEY)

val state = loadWasmline(artifactPath = "/data/plugin.pwasm")

when (state) {
    is WasmlineLoadState.Failure -> error("Plugin load failed: ${state.cause}")
    is WasmlineLoadState.Success -> {
        val module = state.wasmline

        module.bind(object : HostNotificationService {
            override fun notify(event: String) { /* host-side handler */
            }
        })

        val result = module.link<EchoService>().echo("ping")

        module.close()
    }
}
```

> [!IMPORTANT]
> `link<T>()` 与 `bind(impl)` 是 **Kotlin IR 编译器插件的重写目标**。IR 插件会将每个调用点替换为对生成的 `*_WasmlineBridge` 的直接调用。如果编译单元未应用 `wasmline-kotlin-plugin`，这些函数会在运行时抛出 `UnsupportedOperationException`。

---

## CLI 参考

`wasmline-cli` 模块实现了完整的插件构建流水线。所有命令都从 `wasmline-multiplatform/` 通过 Gradle 分发：

```bash
cd wasmline-multiplatform
./gradlew :wasmline-cli:run --args="<command> [options]"
```

### 命令集

| 命令                  | 说明                                                                                                    |
|-----------------------|---------------------------------------------------------------------------------------------------------|
| `download`            | 下载一个或多个目标平台的 Wasmtime 发行版二进制                                                           |
| `generate-key-pair`   | 生成 Ed25519 签名密钥对                                                                                  |
| `compile`             | 将原始 `.wasm` 二进制编译为平台特定 `.cwasm` 产物以及一个可移植 `.pwasm` 镜像                            |
| `manifest`            | 从已编译产物集合生成带密码学签名的 `.wlm` 清单（Protobuf + Ed25519）                                     |
| `build`               | 执行完整流水线：`compile → manifest → zip packaging`                                                     |

### 流水线执行

```bash
cd wasmline-multiplatform

# Download Wasmtime min toolchain binaries
./gradlew :wasmline-cli:run --args="download -v v45.0.0"

# Generate Ed25519 signing key pair
./gradlew :wasmline-cli:run --args="generate-key-pair --save"

# Execute the full build pipeline
./gradlew :wasmline-cli:run --args="build \
  -i plugin.wasm \
  -wt build/wasmline/wasmtime/wasmtime-v45.0.0-aarch64-macos \
  --key build/wasmline/keys/ed25519_private.key"
```

### 构建产物目录结构

```text
build/wasmline/
├── output/{name}-{version}/
│   ├── manifest.wlm                      # Signed manifest (Protobuf + Ed25519 signature)
│   ├── {name}-pulley64.pwasm             # Pulley portable bytecode — all native Wasmtime targets
│   ├── {name}-aarch64-android.cwasm      # AOT — Android arm64-v8a
│   ├── {name}-aarch64-macos.cwasm        # AOT — macOS Apple Silicon
│   ├── {name}-aarch64-ios.cwasm          # AOT — iOS arm64
│   ├── {name}-x86_64-linux.cwasm         # AOT — Linux x86_64
│   ├── {name}-x86_64-windows.cwasm       # AOT — Windows x86_64
│   └── debug/
│       ├── compile-result.json
│       └── manifest.json
├── dist/
│   └── {name}-{version}.zip
└── keys/
    ├── ed25519_private.key
    └── ed25519_public.key
```

---

## 架构总览

<details>
<summary><strong>架构思维导图</strong></summary>

<table>
  <tr>
    <th align="center">英文</th>
    <th align="center">中文</th>
  </tr>
  <tr>
    <td align="center">
      <img src="docs/public/images/wasmline_mind_en.png" alt="Wasmline Architecture Mind Map" width="100%" />
    </td>
    <td align="center">
      <img src="docs/public/images/wasmline_mind_zh.png" alt="Wasmline 架构思维导图" width="100%" />
    </td>
  </tr>
</table>

</details>

### 双路径执行模型

Wasmline 对外暴露统一、与平台无关的 API 表面（`commonMain` / `hostMain`），但会根据目标类别将执行路由到不同的引擎栈。

#### 原生目标栈 — Android · iOS · macOS · Linux · Windows

```
Host Application  (commonMain / hostMain)
        │
        │  module.link<T>()      — IR-synthesized typed outbound proxy
        │  module.bind(impl)     — IR-synthesized inbound dispatch registration
        │
        ▼
Platform actual  (jniMain / iosMain)
        │  JNI external declarations          — Android, macOS, Linux, Windows
        │  Kotlin/Native C Interop (.def)     — iOS
        ▼
wasmline-core  (C/C++ · Zig 0.15.1)
        │  Engine.cpp   — Wasmtime Engine singleton; global init / shutdown
        │  Module.cpp   — AOT / Pulley module compilation; keyed module cache
        │  Session.cpp  — Per-invocation isolated linear memory region; execution context
        │  Api.cpp      — JNI / C Interop surface (load, invoke, setOutbound, release)
        ▼
Wasmtime C-API  v45.0.0
        │  Sandboxed execution; hardware-accelerated AOT; per-session memory isolation
        ▼
Plugin binary  (.cwasm — platform-specific AOT  |  .pwasm — Pulley portable bytecode)
```

#### Web 目标栈 — Kotlin/JS · Kotlin/WasmJS

```
Host Application  (commonMain / hostMain)
        │
        │  module.link<T>()      — identical IR-synthesized proxy
        │  module.bind(impl)     — identical dispatch registration
        │
        ▼
webMain / jsMain / wasmJsMain  actual
        │  BrowserWasmlineRuntime · WasmlineWebModuleRegistry · WasmlineWebModule
        ▼
Inline JS runtime  (Kotlin js() interop — no external .js files emitted)
        │
        ▼
Browser WebAssembly API  (WebAssembly.Module + WebAssembly.Instance)
        │
        ├── Import namespace: wasi_snapshot_preview1
        │     fd_write         — console.log (fd=1) / console.error (fd=2)
        │     random_get       — globalThis.crypto.getRandomValues
        │     clock_time_get   — Date.now() * 1_000_000  (nanosecond resolution)
        │     proc_exit        — throws JS Error
        │     all others       — ENOSYS stub
        │
        └── Import namespace: env  (Wasmline bridge protocol)
              bridge_inbound_copy_params     — write action + payload into Wasm linear memory
              bridge_inbound_set_response    — extract response bytes from Wasm linear memory
              bridge_outbound_call_host      — synchronous plugin-to-host dispatch callback
              bridge_outbound_get_response   — copy oversized outbound response to caller buffer
        │
        ▼
Plugin binary  (raw .wasm — synchronous XMLHttpRequest; binary string decoding)
```

### 模块依赖关系图

```
wasmline-cli  ──────►  wasmline-loader  ──────►  wasmline  (core runtime)
                                                       ▲
                               wasmline-core  (C/C++) ─┘  (JNI / C Interop)

wasmline-kotlin-plugin   (compile-time only; no runtime artifact)
wasmline-gradle-plugin  ──►  wasmline-kotlin-plugin
```

### 仓库目录结构

```text
wasmline/
├── wasmline-core/                      # C/C++ Wasmtime bridge (Engine, Module, Session, Api)
├── wasmline-multiplatform/
│   ├── wasmline/                       # Core Kotlin runtime — loading, dispatch, serialization SPI
│   │   ├── commonMain/                 # Platform-agnostic contracts and bridge abstractions
│   │   ├── hostMain/                   # Host API: Wasmline, WasmlineLoadState, link<T>(), bind()
│   │   ├── jniMain/                    # JVM / Android actual — JNI external declarations
│   │   ├── iosMain/                    # iOS actual — Kotlin/Native C Interop
│   │   ├── webMain/                    # Web actual — BrowserWasmlineRuntime + inline JS bridge
│   │   ├── jsMain/                     # Kotlin/JS delegation → webMain
│   │   ├── wasmJsMain/                 # Kotlin/WasmJS delegation → webMain
│   │   └── wasmWasiMain/               # Plugin-side runtime — WasmlineRouter, WasmBridge
│   ├── wasmline-loader/                # WasmlineLoader, .wlm manifest parsing, Ed25519/ECDSA-P256
│   ├── wasmline-cli/                   # CLI: download, compile, manifest, build, generate-key-pair
│   ├── wasmline-android/               # Android native bindings (CMake / JNI C++)
│   ├── wasmline-kotlin-plugin/         # Kotlin IR compiler plugin — bridge synthesis, call-site rewriting
│   ├── wasmline-gradle-plugin/         # Applies the IR plugin to consumer Gradle projects
│   └── wasmline-build-logic/           # Shared Gradle convention plugins
├── wasmline-samples/
│   └── kotlin/
│       ├── sample-common/              # Shared service contract interface definitions
│       ├── sample-plugin/              # Kotlin/WasmWasi WASI plugin implementation
│       └── sample-apps/               # Android, JVM desktop, Compose Multiplatform, and Web hosts
├── wasmline-ci/                        # CI automation scripts
├── scripts/                            # Asset initialization: init.sh / init.py / init.mjs
├── build/                              # 根级构建输出目录，包含 build/platforms/ Wasmtime min C-API 资产
└── docs/                               # Documentation site (Next.js + Fumadocs)
```

---

## Kotlin/Wasm 兼容性

![Kotlin/Wasm 运行时支持矩阵](docs/public/images/kotlin_support.png)

Wasmline 需要 **Kotlin 2.3.20-RC** 或更高版本。Kotlin/WasmWasi 编译器后端必须完整实现以下 WebAssembly 提案：

| 提案                     | 要求     | 原因                                                      |
|--------------------------|----------|-----------------------------------------------------------|
| GC（垃圾回收）           | 必需     | Kotlin 托管对象在 Wasm 线性内存中的分配                   |
| Function References      | 必需     | 接口 vtable 分发与闭包表示                                |
| Exception Handling       | 必需     | Kotlin 异常跨 Wasm 执行边界传播                           |
| Tail Call Optimization   | 推荐     | 防止深层递归调用链中的栈溢出                              |

> [!IMPORTANT]
> 将 `wasmline-multiplatform/gradle/libs.versions.toml` 中的 `kotlin` 版本降到 `2.3.20-RC` 以下，可能会生成不完整的 Wasm 二进制。缺失 GC 或异常处理支持通常会表现为插件加载失败，或在 `Session.invoke` 边界出现未定义行为。

---

## 开发指南与工具链配置

### 环境预检

在执行任何 Gradle 操作前，都必须验证 JBR 21 环境：

```bash
bash ./scripts/doctor.sh
```

### 版本同步

文档和重复配置中的版本号统一由 `scripts/versions.json` 管理，并通过脚本同步：

```bash
python3 scripts/sync_versions.py --check
python3 scripts/sync_versions.py --set wasmtime_version=<new-version>
```

### Gradle 构建参考

所有操作都在 `wasmline-multiplatform/` 下执行：

```bash
cd wasmline-multiplatform

# Full project build
./gradlew build

# wasmline-loader module tests (manifest parsing, cryptographic verification)
./gradlew :wasmline-loader:jvmTest

# wasmline-cli module tests
./gradlew :wasmline-cli:test

# Kotlin/WasmWasi plugin sample compilation
./gradlew :wasmline-sample:plugin:compileProductionLibraryKotlinWasmWasiOptimize

# IR compiler plugin — regenerate test runners from testData fixtures
./gradlew :wasmline-kotlin-plugin:generateTests

# IR compiler plugin — box test execution
./gradlew :wasmline-kotlin-plugin:test \
  --tests 'crow.wasmline.kotlin.runners.JvmBoxTestGenerated'

# IR compiler plugin — diagnostic validation tests
./gradlew :wasmline-kotlin-plugin:test \
  --tests 'crow.wasmline.kotlin.runners.JvmDiagnosticsTestGenerated'
```

### 原生库构建 (Zig 0.15.1)

JVM 原生目标所需的 JNI 共享库由 Zig 构建系统编译。`-p` 参数会将安装目录重定向到 `src/jvmMain/resources/jni/`：

```bash
cd wasmline-multiplatform/wasmline

zig build --release=small -p src/jvmMain/resources   # Release (size-optimized)
zig build -p src/jvmMain/resources                    # Debug
```

> [!WARNING]
> `zig-out/` 下的文件属于 Zig 构建的中间产物，绝不能提交。必须使用 `-p src/jvmMain/resources` 参数，才能将 JNI 库安装到正确的 classpath 资源目录。

---

## 编译器约束与验证

### IR 变换流水线

`wasmline-kotlin-plugin` 运行在 **Kotlin IR 编译阶段**，而不是通过注解处理（KSP）或源码级代码生成。变换流水线按顺序执行四个步骤：

| 步骤                    | 类                                 | 操作                                                                                                                                                         |
|-------------------------|------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1 — 发现                | `WasmlineIrGenerationExtension`    | 遍历所有 `IrDeclarationContainer` 节点；收集在超类型层级中包含 `WasmlineService` 的 `IrClass` 声明                                                           |
| 2 — 校验                | `WasmlineServiceContractValidator` | 根据静态约束集验证每个契约；违反规则时发出带类型的 `WasmlineIrDiagnostics`；存在任何错误都会中止 IR 生成                                                   |
| 3 — 桥接生成            | `WasmlineBridgeGenerator`          | 为每个通过验证的契约生成一个 `internal class {Contract}_WasmlineBridge : WasmlineGeneratedBridge`，并将该类直接注入父级 `IrFile`                          |
| 4 — 调用点重写          | `WasmlineTypedEntryPointRewriter`  | 定位所有 `link<T>()`、`bind(contract, impl)` 与 `bind(impl)` 调用点，并将其替换为直接实例化桥接类的表达式                                                  |

### 静态契约约束

以下约束在编译期强制执行。任何违反都会被报告为 **编译器错误**，并阻止 IR 成功生成：

| 约束                     | 要求                                                                                      |
|--------------------------|-------------------------------------------------------------------------------------------|
| 声明种类                 | 契约必须是 `interface`；抽象类、sealed 类型和 object 都会被拒绝                           |
| 成员可见性               | 所有函数都必须声明为 `public`                                                              |
| suspend 能力             | 任何契约函数都不允许使用 `suspend` 修饰符                                                  |
| 参数数量                 | 每个函数最多只能有一个普通值参数                                                           |
| 泛型类型参数             | 契约接口声明上不允许出现泛型类型参数                                                       |
| 方法名唯一性             | 单个契约内不支持同名重载方法                                                               |
| `vararg` 参数            | 不允许                                                                                    |
| 默认参数值               | 不允许                                                                                    |
| 扩展接收者               | 不支持把扩展函数或扩展属性声明为契约成员                                                   |

### SignatureHash — 动作标识符派生

每个服务方法都会被赋予一个稳定的动作标识符，其计算方式为：**对其完全限定方法签名进行 SHA-256 哈希**：

```
Input:  com.example.EchoService#echo(kotlin.String)
Output: <lowercase hex SHA-256 digest>
```

只要完全限定类名和方法签名保持不变，即使包结构重组，该标识符也保持稳定。它会被嵌入生成的 bridge dispatch table 中，并且必须与插件二进制中 `WasmlineRouter.register(action, handler)` 的调用保持一致。

### IR Box 测试工作流

Box 测试夹具位于 `wasmline-kotlin-plugin/testData/box/`。每个夹具都是一个包含 `fun box(): String` 入口点的源码文件，并在成功执行时返回 `"OK"`：

```bash
cd wasmline-multiplatform

# 1. Author or modify a fixture in testData/box/{name}.kt
# 2. Regenerate the test runner
./gradlew :wasmline-kotlin-plugin:generateTests

# 3. Execute — FIR and IR snapshots are generated on the first run
./gradlew :wasmline-kotlin-plugin:test \
  --tests 'crow.wasmline.kotlin.runners.JvmBoxTestGenerated'

# 4. Review generated *.fir.txt and *.fir.ir.txt before committing
```

> [!WARNING]
> 以下文件属于 **自动生成的构建产物**，绝不能手动编辑：`test-gen/**`、`testData/box/*.fir.txt`、`testData/box/*.fir.ir.txt`、`testData/diagnostics/*.fir.txt`。手工修改会在下一次生成流程中导致测试失败。

---

## 贡献指南

所有贡献在提交 pull request 之前都必须满足以下要求：

1. **分支策略** —— 所有变更都必须基于 `main` 从专用 feature 或 fix 分支发起。
2. **预检验证** —— 在执行任何 Gradle 任务前，必须通过 `./scripts/doctor.sh` 确认 JBR 21 可用。
3. **遵守模块边界** —— 每项变更都必须限定在合适的模块内。修改源码前请先参考 [仓库目录结构](#仓库目录结构) 与 `.github/skills/wasmline/SKILL.md` 进行模块路由。
4. **生成产物完整性** —— IR 快照、`test-gen/` 源码、`build/platforms/` 资产以及所有 `build/` 目录都不得提交，也不得手动修改。
5. **测试覆盖** —— 对 IR 编译器插件的行为变更必须伴随相应的 box 测试夹具或 diagnostic 测试更新。
6. **Apple 平台范围** —— 涉及 macOS 或 iOS 代码路径的变更，必须在 macOS 构建环境中验证；未经验证的 Apple 平台变更不得标记为完成。
7. **架构提案** —— 具有架构影响的变更，在实现前必须先进行 issue 级讨论。

---

## 常见问题

<details>
<summary><strong>浏览器执行路径如何在没有 Wasmtime 的情况下实例化插件？</strong></summary>

Web 目标实现（`webMain` / `jsMain` / `wasmJsMain`）通过浏览器原生 WebAssembly 运行时，直接调用 `WebAssembly.Module` 和 `WebAssembly.Instance`。运行时层通过 Kotlin `js()` interop 内嵌为自包含的 JavaScript 代码——编译过程中不会生成外部 JS 文件。

该运行时层提供：

- **`wasi_snapshot_preview1` shim**：`fd_write`（stdout/stderr 映射到 `console.log`/`console.error`）、`random_get`（`crypto.getRandomValues`）、`clock_time_get`（以纳秒精度使用 `Date.now()`）
- **Wasmline bridge 协议**（`env` 命名空间）：`bridge_inbound_copy_params`、`bridge_inbound_set_response`、`bridge_outbound_call_host`、`bridge_outbound_get_response`

payload 数据以 Base64 编码字符串跨越 Kotlin–JS 线性内存边界（`BrowserPayloadEncoding`）。AOT 编译产物（`.cwasm`、`.pwasm`）不被接受。

</details>

<details>
<summary><strong><code>.cwasm</code> 与 <code>.pwasm</code> 产物有什么区别——为什么不用原始 <code>.wasm</code>？</strong></summary>

这三种产物类型都与 Wasmtime 有关，但它们在**编译发生的时机**以及**编译目标**上有根本区别：

| 产物 | 编译时机 | 执行方式 | 版本关联性 |
|---|---|---|---|
| `.wasm` | 在加载时（JIT）由宿主 Wasmtime 编译 | Cranelift → 原生代码或 Pulley，取决于配置 | **无 — 与所有 Wasmtime 版本兼容** |
| `.cwasm` | 由 `wasmtime compile` 通过 Cranelift 提前编译 | 直接在原生硬件上执行（例如 arm64 machine code） | 与编译它的 Wasmtime 版本关联 |
| `.pwasm` | 由 `wasmtime compile` 通过 Cranelift 提前编译 | Wasmtime **Pulley** 解释器 | 与编译它的 Wasmtime 版本关联 |

**`.cwasm`（平台特定 AOT）** 会为特定 target triple（例如 `aarch64-android`）生成原生机器码。吞吐量最高；但每个平台都需要单独产物。

**`.pwasm`（Pulley AOT）** 与 `.cwasm` 一样，都是通过相同的 Cranelift 流水线编译而来，但其目标不是原生机器码，而是 Wasmtime 的 **Pulley** 字节码 ISA，运行时再由 Pulley 解释器执行。关键在于，`.pwasm` **不是** 把原始 `.wasm` 文件交给解释器运行——它是一个完整的、由 Cranelift 预编译得到的产物，与 `.cwasm` 走的是相同编译路径，只是目标后端不同。由于 Pulley ISA 与平台无关，单个 `.pwasm` 产物可以在所有原生 Wasmtime 目标上运行。

由于 `.wasm` 会在加载时由宿主正在使用的 Wasmtime 版本即时编译，因此它没有版本依赖。`.cwasm` 与 `.pwasm` 都是某个特定 Wasmtime 发行版的预编译输出——升级 Wasmtime 后，这些产物会失效，必须重新编译。

尽管 Pulley 解释器也可以在运行时加载并 JIT 编译原始 `.wasm`，但在 Android 这类内存受限环境中更推荐 `.pwasm`：进程内编译步骤会带来显著更高的内存开销。

> [!IMPORTANT]
> `.cwasm` 与 `.pwasm` 都**与生成它们的 Wasmtime 编译器版本绑定**。一旦升级 Wasmtime 版本，所有已编译产物都会失效，必须重新编译。正因如此，除 Web 外的所有原生目标都使用预编译的 `.cwasm` 或 `.pwasm`，而不是原始 `.wasm`。Web 是唯一的例外，它依赖浏览器自身的 WebAssembly 运行时并使用原始 `.wasm` 文件。

</details>

<details>
<summary><strong>Kotlin IR 编译器插件是宿主集成的必需项吗？</strong></summary>

编译器插件并非必需。底层 `Wasmline.call(action, bytes): ByteArray` API 始终可用于手动分发。如果未应用 `wasmline-kotlin-plugin`——通常通过 `wasmline-gradle-plugin` 间接应用——`link<T>()` 与 `bind(impl)` 调用点会在运行时抛出 `UnsupportedOperationException`。

</details>

<details>
<summary><strong>插件执行的隔离模型是什么？</strong></summary>

在原生目标上，每次插件调用都在独立的 `Session` 实例中执行，并维护其自己的 Wasm 线性内存区域。除非宿主通过 Wasmtime 的 preopened-directory 或 socket-capability 模型显式授予能力，否则插件无法访问宿主进程内存、宿主文件系统或网络。在 Web 目标上，对等的内存隔离由浏览器原生 WebAssembly 沙箱提供。

</details>

---

## 参考资料

| 资源                                                                                                                                                                                  | 说明                                           |
|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------|
| [wasm-kotlin-exploration](https://github.com/crowforkotlin/wasm-kotlin-exploration)                                                                                                   | 研究仓库——WebAssembly 与 Kotlin 集成实验       |
| [Embedding Wasmtime on Android via JNI](https://crowforkotlin.github.io/2025/11/27/Wasm/Android%E4%BD%BF%E7%94%A8JNI%E5%B5%8C%E5%85%A5Wasmtime/)                                      | JNI bridge 集成的技术参考                      |
| [WebAssembly vs. WASI — Architecture and Lifecycle](https://crowforkotlin.github.io/2025/11/25/Wasm/Wasm%E5%92%8CWasi%E5%8C%BA%E5%88%AB%E5%92%8C%E7%94%9F%E5%91%BD%E5%91%A8%E5%BA%86/) | WebAssembly 与 WASI 的架构区分                 |
| [Wasmtime Documentation](https://docs.wasmtime.dev/)                                                                                                                                  | Wasmtime 运行时官方文档                        |
| [WebAssembly Specification](https://webassembly.github.io/spec/)                                                                                                                      | W3C WebAssembly 核心规范                       |
| [WASI Specification](https://github.com/WebAssembly/WASI)                                                                                                                             | WebAssembly System Interface 规范              |

---

## 许可证

Wasmline 基于 **Apache License, Version 2.0** 发布。完整许可证文本请参见 [LICENSE](LICENSE)。

---

<div align="center">

[返回顶部](#wasmline)

</div>
