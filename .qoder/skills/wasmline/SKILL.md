---
name: wasmline
description: 用于在 Wasmline 仓库中执行环境预检、平台资产初始化、模块定位及 Kotlin IR 插件约束处理的仓库级技能规范。
---

# Wasmline 仓库技能规范

当任务涉及 `wasmline` 仓库的构建、测试、调试、故障排查或代码变更时，**必须**加载并遵守本技能规范。

## 目录约定

```
scripts/
└── doctor.sh                       # 环境预检脚本
```

环境预检的实际实现位于仓库级 `scripts/doctor.sh`。技能规范、README 与仓库脚本共享同一入口，以保证环境判断逻辑的一致性。

## 目标

在执行编译、测试或代码变更之前，完成环境确认，并将任务路由至正确的模块。

---

## 执行约束

以下约束在整个会话（Session）生命周期内持续生效，优先级高于各步骤的具体操作指引。

### 约束一：环境预检仅执行一次

环境预检（`bash ./scripts/doctor.sh`）**仅允许在当前会话初始化阶段执行一次**。在同一会话的后续交互流程中，**禁止重复触发**环境预检；已获得的预检结果应在整个会话期间持续复用。

### 约束二：禁止自主触发编译与测试

在用户**未明确发出**编译（Compile）或测试（Test）指令的前提下，**禁止**以任何形式——包括自主、隐式或链式调用——触发编译流程或测试流程。所有构建与验证动作均须以用户的显式指令为前提。

---

## 推荐工作流程

1. 在会话初始化阶段执行一次预检脚本，确认 Gradle 及测试的前置条件是否满足。
2. 按需初始化平台运行时资产。
3. 明确定位目标模块后，再执行代码变更。
4. 严格区分手写源码与生成产物，尤其是 IR 快照与测试生成文件。

### 当前执行策略（Windows 环境）

若当前工作环境为 **Windows**，而任务涉及 `macOS` / `iOS` 专项实现、联调或验证，则按如下规则处理：

- 将 Apple 平台相关事项标注为**环境暂缓（Environment Deferred）**，禁止在本机强制执行。
- 优先推进可在当前环境中执行的工作项，包括 `JNI`、`Loader`、`Runtime`、`IR`、公开 API 收口及文档整理。
- 若任务仅为更新计划、补充设计说明或整理待办事项，可继续修改文档，但**禁止**将状态标注为"已完成"。
- 切换至可用的 `macOS/iOS` 环境后，方可恢复 Apple 平台实现、回归验证及阻塞项的收口工作。

---

## 第一步：Gradle 构建前置环境预检

本仓库的 Gradle 构建至少要求 **Java 21**；其中 Compose Desktop 及部分桌面 Sample 明确配置了 `JvmVendorSpec.JETBRAINS`，因此本技能统一以 **JBR 21** 作为环境预检的验证标准。

**注意**：根据执行约束，本步骤在当前会话内仅执行一次。

执行命令：

```bash
bash ./scripts/doctor.sh
```

预检项说明：

- **禁止在未确认 Java/JBR 版本的情况下直接执行 Gradle。**
- `doctor.sh` 优先检查当前 `JAVA_HOME`，必要时结合 `java -version`、`<JAVA_HOME>/release` 及 shell 配置文件中的 JBR/JAVA_HOME 声明进行只读判断。
- `doctor.sh` 以只读方式检查 `~/.zshrc`、`~/.bashrc`、`~/.bash_profile` 中的 JBR/JAVA_HOME 相关声明。
- 上述 shell 配置文件**仅允许读取，禁止修改**。
- 若当前 shell 环境未切换至可用的 JBR 21，须告知用户并**终止**后续 Gradle 编译/测试流程。
- **禁止**将任何开发机本地 JBR 安装路径硬编码至技能文档、脚本或仓库说明中。
- `doctor.sh` 检查 `build/platforms/` 下各已知 Wasmtime 平台架构目录（如 `android/arm64-v8a`、`linux/x64`、`mac/aarch64` 等）；缺失项输出 `WARNING`，但不触发 JBR 21 硬阻塞。

`doctor.sh` 同步报告桌面 Zig（要求 **0.15.1**）及桌面 JNI/native 产物状态，供 Compose Desktop 及桌面 native 排障参考。

---

## 第二步：按需初始化平台运行时资产

本仓库依赖各平台的 Wasmtime C-API 运行时资产。若 `build/platforms/` 尚未就绪，选择以下任一方式执行初始化：

```bash
# Bash（需要 bash + curl + tar/unzip）
sh ./scripts/init.sh

# Python 3（需要 Python 3.9+，无第三方依赖）
python3 ./scripts/init.py

# Node.js（需要 Node.js 18+，无第三方依赖）
node ./scripts/init.mjs
```

三个脚本功能完全等价，均支持以下特性：

- 交互式选择目标平台与架构
- 配置并发下载数量
- 可选代理参数（第一个参数，如 `127.0.0.1:7890`）
- 下载完成后自动解压并部署至 `build/platforms/` 目录

说明：

- `build/platforms/` 存放下载或解压后的平台运行时资产（头文件 + 静态/动态库）。
- **禁止**假设这些资产在任意机器上均已存在。
- 若 `doctor.sh` 已确认目标平台资产存在，可跳过本步骤。

---

## 第三步：明确定位模块后执行变更

### 仓库结构总览

| 目录 | 说明 |
|---|---|
| `wasmline-core/` | C/C++ 编写的原生 Wasmtime Bridge（Engine、Module、Session、Api） |
| `wasmline-multiplatform/` | Kotlin Multiplatform 主工程（独立 Gradle 项目） |
| `wasmline-multiplatform/wasmline/` | 核心运行时库（commonMain / hostMain / wasmWasiMain / jniMain / jvmMain / iosMain / jsMain / wasmJsMain / webMain 等） |
| `wasmline-multiplatform/wasmline-kotlin-plugin/` | Kotlin IR 编译器插件 |
| `wasmline-multiplatform/wasmline-cli/` | CLI 命令行工具 |
| `wasmline-multiplatform/wasmline-loader/` | Loader 模块 |
| `wasmline-multiplatform/wasmline-gradle-plugin/` | Gradle 插件 |
| `wasmline-multiplatform/wasmline-android/` | Android 专属 JNI 封装模块 |
| `wasmline-multiplatform/wasmline-build-logic/` | 构建逻辑（Convention Plugins） |
| `wasmline-samples/kotlin/` | 示例工程独立 Gradle 项目（sample-apps / sample-common / sample-plugin） |
| `wasmline-samples/kotlin/sample-apps/android/` | Android 单端示例应用 |
| `wasmline-samples/kotlin/sample-apps/application/` | JVM/Desktop 单端示例应用 |
| `wasmline-samples/kotlin/sample-apps/multiplatform/` | Compose Multiplatform 示例（androidApp / desktopApp / shared / webApp） |
| `wasmline-samples/kotlin/sample-common/` | 示例工程共用逻辑 |
| `wasmline-samples/kotlin/sample-plugin/` | 示例 Wasmline 插件工程 |
| `wasmline-ci/` | CI 及样例自动化脚本 |
| `scripts/` | 仓库级初始化与辅助脚本 |
| `build/platforms/` | 平台运行时资产（由 `scripts/init.sh` 初始化） |
| `docs/` | 文档站点资源 |

> **注意**：`wasmline-samples/kotlin/` 是独立的 Gradle 复合构建（Composite Build），通过 `includeBuild` 依赖 `wasmline-multiplatform`，不属于 `wasmline-multiplatform` 项目的子模块。原 `wasmline-multiplatform/wasmline-sample/` 已废弃并从 `settings.gradle.kts` 中移除。

### Runtime / Bridge 相关

涉及 Wasm 加载、会话生命周期、宿主与 Wasm 的调用链或 Runtime Bridge 行为时，优先参阅以下文件：

**C/C++ Bridge 层：**

- `wasmline-core/include/Engine.h`
- `wasmline-core/src/Engine.cpp`
- `wasmline-core/src/Module.cpp`
- `wasmline-core/src/Session.cpp`
- `wasmline-core/src/Api.cpp`

**Kotlin Bridge 层：**

- `wasmline-multiplatform/wasmline/src/commonMain/kotlin/crow/wasmline/internal/bridge/GeneratedBridge.kt`
- `wasmline-multiplatform/wasmline/src/commonMain/kotlin/crow/wasmline/internal/bridge/GeneratedSerialization.kt`
- `wasmline-multiplatform/wasmline/src/commonMain/kotlin/crow/wasmline/internal/bridge/Endpoint.kt`
- `wasmline-multiplatform/wasmline/src/commonMain/kotlin/crow/wasmline/internal/bridge/HostDispatcher.kt`
- `wasmline-multiplatform/wasmline/src/commonMain/kotlin/crow/wasmline/internal/bridge/Payload.kt`

**测试：**

- `wasmline-multiplatform/wasmline/src/commonTest/kotlin/crow/wasmline/WasmlineServiceRuntimeTest.kt`

### Kotlin Multiplatform Runtime API 相关

涉及公开 API、Binding、Generated Bridge 接入或平台 Runtime 实现时，优先参阅以下文件：

- `wasmline-multiplatform/wasmline/src/commonMain/kotlin/crow/wasmline/WasmlineService.kt` — 服务定义入口
- `wasmline-multiplatform/wasmline/src/commonMain/kotlin/crow/wasmline/WasmlineConfig.kt` — 全局配置
- `wasmline-multiplatform/wasmline/src/hostMain/kotlin/crow/wasmline/Wasmline.kt` — 宿主侧主 API
- `wasmline-multiplatform/wasmline/src/hostMain/kotlin/crow/wasmline/WasmlineServices.host.kt` — 宿主侧服务注册
- `wasmline-multiplatform/wasmline/src/hostMain/kotlin/crow/wasmline/WasmlineLoader.kt` — 宿主侧加载器
- `wasmline-multiplatform/wasmline/src/hostMain/kotlin/crow/wasmline/WasmlineLoadState.kt` — 加载状态定义
- `wasmline-multiplatform/wasmline/src/hostMain/kotlin/crow/wasmline/WasmlineWarmupMode.kt` — 引擎预热模式（`PULLEY` / `AOT`）
- `wasmline-multiplatform/wasmline/src/hostMain/kotlin/crow/wasmline/BrowserPayloadEncoding.kt` — Browser 端 Payload 编码
- `wasmline-multiplatform/wasmline/src/wasmWasiMain/kotlin/crow/wasmline/WasmlineServices.wasmWasi.kt` — WASI 侧服务注册
- `wasmline-multiplatform/wasmline/src/wasmWasiMain/kotlin/crow/wasmline/WasmlineWasmBridge.kt` — WASI 侧 Wasm 桥接
- `wasmline-multiplatform/wasmline/src/wasmWasiMain/kotlin/crow/wasmline/WasmlineRouter.kt` — WASI 侧路由
- `wasmline-multiplatform/wasmline/src/wasmWasiMain/kotlin/crow/wasmline/Wasmline.wasmWasi.kt` — WASI 侧平台实现
- `wasmline-multiplatform/wasmline/src/wasmWasiMain/kotlin/crow/wasmline/WasmMain.kt` — WASI 侧入口
- `wasmline-multiplatform/wasmline/src/iosMain/kotlin/crow/wasmline/Wasmline.ios.kt` — iOS 侧平台实现
- `wasmline-multiplatform/wasmline/src/jsMain/kotlin/crow/wasmline/Wasmline.js.kt` — JS 侧平台实现
- `wasmline-multiplatform/wasmline/src/wasmJsMain/kotlin/crow/wasmline/Wasmline.wasmJs.kt` — WasmJs 侧平台实现
- `wasmline-multiplatform/wasmline/src/webMain/kotlin/crow/wasmline/Wasmline.web.kt` — Web（JS+WasmJs 共用）平台实现
- `wasmline-multiplatform/wasmline/src/jniMain/kotlin/crow/wasmline/Wasmline.jni.kt` — JNI 侧平台实现

核心概念：

- `WasmlineEndpoint`
- `WasmlineGeneratedBridge`
- `bindGeneratedBridgeAction(...)`
- `requireGeneratedImplementation(...)`
- `unknownGeneratedAction(...)`

### Kotlin 编译器插件 / IR 相关

涉及 `link()`、`bind()`、`bindAs()`、桥接代码生成、IR 变换或插件行为时，优先参阅以下文件：

**插件注册与入口：**

- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineCompilerPluginRegistrar.kt`
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineCommandLineProcessor.kt`

**IR 变换核心：**

- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineIrGenerationExtension.kt` — IR 生成扩展入口
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineBridgeGenerator.kt` — 桥接代码生成
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineTypedEntryPointRewriter.kt` — 类型化入口重写
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineServiceContractValidator.kt` — 服务契约校验
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineWasiEntryExportGenerator.kt` — WASI 侧入口导出生成

**符号解析与工具：**

- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineRuntimeSymbols.kt` — Runtime 符号解析
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineIrDiagnostics.kt` — 诊断信息
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/SignatureHash.kt` — 签名哈希
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/ir.kt` — IR 工具函数
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/typeToString.kt` — 类型序列化
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/package.kt` — 包级声明

**测试数据：**

- `wasmline-multiplatform/wasmline-kotlin-plugin/testData/box/` — box test 用例
- `wasmline-multiplatform/wasmline-kotlin-plugin/testData/diagnostics/` — 诊断测试用例

**设计文档：**

- `wasmline-multiplatform/wasmline-kotlin-plugin/testData/box/README_zh.md`
- `.github/plans/ir-planv2.md`

重要说明：

- 本模块为 **IR 插件**，而非简单的源码生成器。
- 大量行为须结合 IR 输出、生成测试与 Runtime 行为进行系统性验证。
- 仅阅读单一文件通常不足以理解完整行为，须将 Runtime Helper、插件代码与 box test 作为一个整体系统加以理解。

### CLI / Loader / 打包相关

涉及 Manifest、签名、打包或命令行链路时，优先参阅以下目录：

- `wasmline-multiplatform/wasmline-loader/`
- `wasmline-multiplatform/wasmline-cli/`
- `wasmline-multiplatform/wasmline-gradle-plugin/`

### 桌面 Native 相关

涉及 Compose Desktop、JNI 或本地库时，优先参阅以下文件：

- `wasmline-multiplatform/wasmline/zig-build.md`
- `wasmline-multiplatform/wasmline/build.zig`
- `wasmline-samples/kotlin/sample-apps/multiplatform/shared/src/desktopMain/Requirement.md`
- `wasmline-multiplatform/wasmline/src/jniMain/native/`
- `wasmline-multiplatform/wasmline/src/jvmMain/native/`

典型构建命令（**仅在用户明确发出编译指令后执行**）：

```bash
cd wasmline-multiplatform/wasmline
zig build --release=small -p src/jvmMain/resources
```

补充说明：

- `src/jvmMain/resources/jni/` 适合作为 Zig 安装输出目录，而非稳定的源码阅读入口。
- 默认输出路径为 `zig-out/jni/`；若显式传入 `-p <目录>`，则 JNI 产物将安装至该目录下的 `jni/` 子目录。
- 仓库文档要求 Zig 版本为 **0.15.1**。

---

## 第四步：严格遵守生成产物约束

除非任务明确要求重新生成，否则**禁止手动修改**以下内容：

- `wasmline-multiplatform/wasmline-kotlin-plugin/test-gen/` — 自动生成的测试运行器
- `wasmline-multiplatform/wasmline-kotlin-plugin/testData/box/*.fir.txt` — FIR 快照
- `wasmline-multiplatform/wasmline-kotlin-plugin/testData/box/*.fir.ir.txt` — FIR IR 快照
- `wasmline-multiplatform/wasmline-kotlin-plugin/testData/diagnostics/*.fir.txt` — 诊断快照
- `build/platforms/` — 平台运行时资产
- `**/build/` — 构建产物

IR 测试注意事项：

- `*.fir.txt` 与 `*.fir.ir.txt` 为**自动生成、自动比对**的快照文件。
- 首次执行测试时，可能因快照缺失或 IR 发生变化而报告失败。
- 第二次执行时，在正确快照生成后通常可恢复通过。
- 若仅变更实现逻辑，**禁止**手动编辑上述 IR 快照文件。

若需新增或更新 box test，标准操作流程如下：

1. 在 `testData/box/` 目录下创建 `.kt` 测试源文件。
2. 执行 `./gradlew :wasmline-kotlin-plugin:generateTests` 以生成测试运行器。
3. 执行测试，快照文件将自动生成。
4. 确认快照内容无误后提交。

---

## 常用命令

### 环境预检

```bash
bash ./scripts/doctor.sh
```

### 初始化平台运行时

```bash
sh ./scripts/init.sh            # Bash
python3 ./scripts/init.py       # Python 3.9+
node ./scripts/init.mjs         # Node.js 18+
```

### 生成插件测试并执行 box test

> **前提**：须已收到用户明确的测试指令。

```bash
cd wasmline-multiplatform
./gradlew :wasmline-kotlin-plugin:generateTests
./gradlew :wasmline-kotlin-plugin:test --tests 'crow.wasmline.kotlin.runners.JvmBoxTestGenerated'
```

### 执行诊断测试

> **前提**：须已收到用户明确的测试指令。

```bash
cd wasmline-multiplatform
./gradlew :wasmline-kotlin-plugin:test --tests 'crow.wasmline.kotlin.runners.JvmDiagnosticsTestGenerated'
```

### 构建桌面 JNI 产物（要求 Zig 0.15.1）

> **前提**：须已收到用户明确的编译指令。

```bash
cd wasmline-multiplatform/wasmline
zig build --release=small -p src/jvmMain/resources
```

---

## 推荐阅读顺序

第一次接触仓库时，建议按以下顺序阅读：

1. `README_zh.md` / `README.md` — 项目概述
2. `.github/skills/wasmline/SKILL.md` — 本文件
3. `scripts/init.sh` — 平台资产初始化流程
4. `wasmline-multiplatform/settings.gradle.kts` — 主工程模块划分
5. `wasmline-samples/kotlin/settings.gradle.kts` — 示例工程 Composite Build 结构
6. `wasmline-core/` — C/C++ Bridge 层实现
7. `wasmline-multiplatform/wasmline/` — Kotlin 核心运行时
8. `wasmline-multiplatform/wasmline-kotlin-plugin/` — IR 编译器插件
9. `wasmline-multiplatform/wasmline-kotlin-plugin/testData/box/README_zh.md` — box test 说明
10. `.github/plans/ir-planv2.md` — IR 变换设计计划

---

## 工作原则

1. **环境预检优先。** 执行 Gradle 前须确认 JBR 21 就绪；预检结果在当前会话内持续有效，禁止重复触发。
2. **资产确认先于编译。** 执行平台相关构建前，须确认运行时资产完整性。
3. **模块定位先于变更。** 须明确需求对应的目标模块后，方可执行代码变更。
4. **禁止手改生成产物。** IR 快照、`test-gen/`、`build/` 均属生成产物，禁止手动编辑。
5. **按显式指令执行编译与测试。** 编译与测试流程须以用户明确指令为前提，禁止自主或隐式触发。
6. **系统性理解 IR 插件行为。** IR 插件行为须结合 Runtime Helper、插件代码与 box test 进行整体验证。

核心原则：**环境预检（一次） → 资产确认 → 模块定位 → 按指令执行实现。**
