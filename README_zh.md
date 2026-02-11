[中文文档](README_zh.md) | [English](README.md)

---

# WasmLine

基于 [Wasmtime](https://wasmtime.dev/) 的 **Kotlin Multiplatform** 插件框架，支持在 Android、iOS、Desktop（macOS / Windows / Linux）和 Web 上加载并执行 **WebAssembly (WASI)** 插件。

插件可以用**任何**能编译到 WASI 的语言编写 —— Kotlin、Rust、C/C++、Go、AssemblyScript 等。

## 平台支持

| 平台 | 架构 | 运行时 | 状态 |
|------|------|--------|------|
| Android | arm64-v8a | Wasmtime (JNI/Zig) | 已支持 |
| iOS | arm64 | Wasmtime (C Interop) | 已支持 |
| macOS | arm64 | Wasmtime (JNI/Zig) | 已支持 |
| Linux | x86_64 | Wasmtime (JNI/Zig) | 已支持 |
| Windows | x86_64 | Wasmtime (JNI/Zig) | 已支持 |
| Web | wasm32-wasi | Kotlin/Wasi | 计划中 |

## 架构

<table>
  <tr>
    <td align="center"><img src="docs/public/images/architecture.png" alt="架构图"></td>
  </tr>
  <tr>
    <td align="center">WasmLine 架构</td>
  </tr>
</table>

### 模块结构

```
wasmline-multiplatform/
├── wasmline/              # 核心运行时 — WASM 模块加载与执行
├── wasmline-loader/       # 加密与清单 — Ed25519/ECDSA-P256 签名、清单序列化
├── wasmline-cli/          # CLI 工具链 — 构建、编译、清单生成、下载、密钥生成
├── wasmline-android/      # Android 原生绑定 (CMake / JNI C++)
├── wasmline-sample/       # 示例应用 (插件, Android, Desktop, Multiplatform Compose)
└── wasmline-build-logic/  # Gradle 约定插件
```

**依赖关系**: `wasmline-cli` → `wasmline-loader` → `wasmline` (核心)

### 核心技术

- **Kotlin Multiplatform** 采用自定义源集层级结构 (`kotlin.mpp.applyDefaultHierarchyTemplate=false`)
- **Wasmtime 41.0.1** 作为底层 WASM 运行时，平台特定的原生绑定（JNI via Zig、iOS C Interop）
- **kotlinx.serialization** 同时支持 JSON 和 Protobuf 格式
- **Ed25519** 数字签名用于强制性的清单签名与验证
- **Clikt** 命令行框架
- **AOT 编译** — `.wasm` → `.cwasm`（平台特定）/ `.pwasm`（Pulley 跨平台字节码）

## 快速开始

### 环境要求

- **JDK 21** 或更高版本
- **Zig 0.15.1**（仅构建原生库时需要）

### 初始化

初始化所需的平台库：

```bash
sh ./scripts/init.sh
```

### 构建

所有 Gradle 命令在 `wasmline-multiplatform/` 目录下运行：

```bash
# 构建全部
./gradlew build

# 运行 loader 测试（commonTest — ManifestTest、加密等）
./gradlew :wasmline-loader:jvmTest

# 运行 CLI 测试
./gradlew :wasmline-cli:test

# 编译 WASM 插件示例
./gradlew :wasmline-sample:plugin:compileProductionLibraryKotlinWasmWasiOptimize
```

### 原生构建 (Zig)

输出当前平台的 JNI 原生库：

```bash
# 在 wasmline/ 目录下
zig build --release=small -p src/jvmMain/resources          # release
zig build -p src/jvmMain/resources                           # debug
```

## CLI 工具链

WasmLine 提供了一套完整的插件构建流水线 CLI 工具，所有命令通过 Gradle 运行：

```bash
./gradlew :wasmline-cli:run --args="<command> [options]"
```

| 命令 | 描述 |
|------|------|
| `download` | 下载目标平台的 Wasmtime 发行版 |
| `generate-key-pair` | 生成用于清单签名的 Ed25519 密钥对 |
| `compile` | 将 `.wasm` 编译为平台特定的 AOT 产物（`.cwasm` / `.pwasm`） |
| `manifest` | 从编译产物生成已签名的清单文件（`.wlm`） |
| `build` | 完整流水线：编译 → 签名 → zip 打包 |

### 示例：完整构建流程

```bash
# 1. 下载 Wasmtime
./gradlew :wasmline-cli:run --args="download -v v41.0.1"

# 2. 生成签名密钥
./gradlew :wasmline-cli:run --args="generate-key-pair --save"

# 3. 构建插件（编译 → 签名 → 打包）
./gradlew :wasmline-cli:run --args="build -i plugin.wasm -wt build/wasmline/wasmtime/wasmtime-v41.0.1-aarch64-macos --key build/wasmline/keys/ed25519_private.key"
```

### 构建产物

```
build/wasmline/
├── output/{name}-{version}/
│   ├── manifest.wlm                # 已签名清单（Protobuf）
│   ├── {name}-pulley64.pwasm       # Pulley 跨平台字节码
│   ├── {name}-aarch64-android.cwasm
│   ├── {name}-aarch64-macos.cwasm
│   ├── {name}-aarch64-ios.cwasm
│   ├── {name}-x86_64-linux.cwasm
│   ├── {name}-x86_64-windows.cwasm
│   └── debug/
│       ├── compile-result.json
│       └── manifest.json           # 可读清单
├── dist/
│   └── {name}-{version}.zip        # 可分发包
└── keys/
    ├── ed25519_private.key
    └── ed25519_public.key
```

详见 [`wasmline-cli/`](wasmline-multiplatform/wasmline-cli/) 中各命令的文档。

## 示例

运行所有示例应用：

```bash
sh ./scripts/samples/run.sh
```

<table>
  <tr>
    <td align="center"><img src="docs/public/images/android_sample.png" alt="Android 示例"></td>
    <td align="center"><img src="docs/public/images/macos_sample.png" alt="macOS 示例"></td>
  </tr>
  <tr>
    <td align="center">Android</td>
    <td align="center">macOS（原生）</td>
  </tr>
</table>
<table>
  <tr>
    <td align="center"><img src="docs/public/images/compose_desktop_mac.png" alt="Compose Desktop"></td>
  </tr>
  <tr>
    <td align="center">Compose Desktop (macOS)</td>
  </tr>
</table>

## Kotlin/Wasi 兼容性

<table>
  <tr>
    <td align="center"><img src="docs/public/images/kotlin_support.png" alt="Kotlin 支持"></td>
  </tr>
  <tr>
    <td align="center">Kotlin/Wasi 运行时支持状态</td>
  </tr>
</table>

WasmLine 需要 **Kotlin 2.3.20-Beta1** 或更高版本才能可靠支持 Kotlin/Wasi。更早版本或其他运行时可能会抛出错误，或缺少所需的 Wasm 特性支持（GC、函数引用、异常处理）。

## 相关资源

- [wasm-kotlin-exploration](https://github.com/crowforkotlin/wasm-kotlin-exploration) — Wasm + Kotlin 研究与测试用例
- [Wasmtime 在 Android 上 (JNI)](https://crowforkotlin.github.io/2025/11/27/Wasm/Android%E4%BD%BF%E7%94%A8JNI%E5%B5%8C%E5%85%A5Wasmtime/) — 通过 JNI 将 Wasmtime 嵌入 Android
- [Wasm 与 Wasi 深度解析](https://crowforkotlin.github.io/2025/11/25/Wasm/Wasm%E5%92%8CWasi%E5%8C%BA%E5%88%AB%E5%92%8C%E7%94%9F%E5%91%BD%E5%91%A8%E5%BA%86/) — Wasm 与 Wasi 的区别及其生命周期

## 许可证

详见 [LICENSE](LICENSE)。