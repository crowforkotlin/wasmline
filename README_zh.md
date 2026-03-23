[中文文档](README_zh.md) | [English](README.md)

---

# Wasmline

Wasmline 是一个构建在 [Wasmtime](https://wasmtime.dev/) 之上的 **Kotlin Multiplatform 插件框架**。
它关注三件事：加载 **WASI 插件**、面向多平台打包分发插件，以及在当前低层 `action + ByteArray` 通道之上逐步演进出由 Kotlin 编译器插件生成的 typed service 层。

## Wasmline 在做什么

当前仓库主要包含三层：

- **运行时与原生桥接层** —— 负责加载模块、管理 session、在 host 与 plugin 之间搬运字节数据
- **打包与分发层** —— 负责编译、签名、描述和分发插件产物
- **编译器与 Gradle 工具层** —— 负责生成 typed glue 并改善 Kotlin 开发体验

## 当前状态

### 已可用或已进入可验证阶段

- 基于 Wasmtime 的 JVM / Android / iOS 等目标运行时集成
- 基于 `action: String` 与 `payload: ByteArray` 的低层 host ↔ plugin RPC
- CLI 下载、编译、manifest、签名、打包流水线
- 示例应用与示例插件
- Kotlin IR 编译器插件的 phase-one 生成能力，当前已能生成：
  - `*_WasmlineDefinition`
  - `*_WasmlineProxy`
  - `*_WasmlineAdapter`

### 仍在推进中的部分

- 建立在 runtime transport 之上的完整 typed service round-trip
- 更完整的 adapter 绑定逻辑
- 更丰富的编译器诊断与 IR 测试覆盖

## 这个仓库适合谁看

### 如果你想**使用 Wasmline**

建议先看：

- `wasmline-multiplatform/wasmline/` —— runtime API
- `wasmline-multiplatform/wasmline-loader/` —— manifest、签名、校验
- `wasmline-multiplatform/wasmline-cli/` —— 构建流水线命令
- `wasmline-multiplatform/wasmline-sample/` —— host 与 plugin 示例工程

### 如果你想**开发 Wasmline 本身**

建议先看：

- `wasmline-core/` —— 面向 Wasmtime 的原生核心
- `wasmline-multiplatform/wasmline/` —— Kotlin runtime SPI 与公共 API
- `wasmline-multiplatform/wasmline-kotlin-plugin/` —— Kotlin 编译器插件
- `wasmline-multiplatform/wasmline-gradle-plugin/` —— Gradle 集成
- `structs/wasmline-ir-design.md` —— 稳定的 IR 架构 / 设计说明
- `structs/ir.md` —— 当前 IR 工作记录与下一步计划

## 仓库结构总览

```text
wasmline/
├── docs/                         # 文档站点源码
├── platforms/                    # 预构建平台产物
├── scripts/                      # 初始化与辅助脚本
├── structs/                      # 设计文档与工作记录
├── wasmline-core/                # Wasmtime 原生桥接层 (C/C++)
├── wasmline-ci/                  # CI 辅助脚本
└── wasmline-multiplatform/
    ├── wasmline/                 # runtime API 与 transport 抽象
    ├── wasmline-loader/          # manifest + crypto + verification
    ├── wasmline-cli/             # 命令行构建流水线
    ├── wasmline-kotlin-plugin/   # Kotlin 编译器插件（IR 为主）
    ├── wasmline-gradle-plugin/   # Gradle 插件接线
    ├── wasmline-sample/          # 示例插件与宿主应用
    └── wasmline-build-logic/     # 共享 Gradle 约定
```

## 架构快照

从整体上看，Wasmline 正在朝着下面这套分层演进：

1. **Transport 层**
   - 低层 `invoke(action, payload)` 风格通信
   - host → plugin 与 plugin → host 的消息流
2. **Runtime 层**
   - endpoint、session、module 生命周期、binding scope、dispatch
3. **Typed service 层**
   - `WasmlineService` contract
   - 生成的 definition / proxy / adapter glue
4. **工具层**
   - Gradle plugin、compiler plugin、CLI、test fixture、snapshot

更完整的设计讨论见 `structs/wasmline-ir-design.md`。

## 快速开始

### 环境要求

- JDK 21+
- Zig 0.15.1+（用于原生 / JNI 构建）
- 按需初始化本地 Wasmtime 相关产物

### 初始化本地依赖

```zsh
sh ./scripts/init.sh
```

### 常规 Gradle 入口

所有 Gradle 命令都从 `wasmline-multiplatform/` 目录执行：

```zsh
cd wasmline-multiplatform
./gradlew build
```

### 常用命令

```zsh
cd wasmline-multiplatform

./gradlew :wasmline-loader:jvmTest
./gradlew :wasmline-cli:test
./gradlew :wasmline-sample:plugin:compileProductionLibraryKotlinWasmWasiOptimize
./gradlew :wasmline-kotlin-plugin:test --tests 'crow.wasmline.kotlin.runners.JvmBoxTestGenerated'
```

## CLI 流水线概览

Wasmline CLI 通过 Gradle 暴露：

```zsh
cd wasmline-multiplatform
./gradlew :wasmline-cli:run --args="<command> [options]"
```

主要命令：

- `download` —— 下载 Wasmtime 发行版
- `generate-key-pair` —— 生成签名密钥
- `compile` —— 构建 AOT 插件产物
- `manifest` —— 生成已签名插件元数据
- `build` —— 执行完整打包流水线

更细的命令说明见 `wasmline-multiplatform/wasmline-cli/`。

## Compiler plugin 与 IR 测试

当前 Kotlin compiler plugin 主要聚焦 **IR-only generation**。

建议入口：

- `wasmline-multiplatform/wasmline-kotlin-plugin/testData/box/README_zh.md`
- `wasmline-multiplatform/wasmline-kotlin-plugin/test-fixtures/crow/wasmline/kotlin/GenerateTests.kt`
- `structs/wasmline-ir-design.md`
- `structs/ir.md`

当前 box 测试主要验证：

- contract discovery
- phase-one validation
- 生成的 `Definition / Proxy / Adapter` skeleton
- 已提交 fixture 的 FIR / IR snapshot

## 文档索引

- `README.md` / `README_zh.md` —— 仓库级入口文档
- `docs/` —— 文档站点源码
- `structs/wasmline-ir-design.md` —— 稳定架构与设计说明
- `structs/ir.md` —— 当前状态、交接记录与下一步任务

## License

详见 `LICENSE`。
