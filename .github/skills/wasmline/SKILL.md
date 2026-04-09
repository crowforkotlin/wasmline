---
name: wasmline
description: 用于在 Wasmline 仓库中进行环境预检、平台资产初始化、模块定位以及 Kotlin IR 插件约束处理的仓库技能。
---

# Wasmline 仓库技能

当任务涉及 `wasmline` 仓库的构建、测试、调试、排障或代码修改时，**必须**使用此技能。

## 目录约定

```
.github/skills/wasmline/
├── SKILL.md                        # 技能入口说明（本文件）
└── scripts/
    └── skill_preflight.sh          # 环境预检脚本
```

辅助脚本统一放在 `scripts/` 子目录中，便于维护，也更符合"技能说明与配套资源分离"的组织习惯。

## 目标

在真正开始编译、测试或改代码之前，先确认环境，并将任务路由到正确模块。

## 推荐流程

1. 运行预检脚本，确认是否满足 Gradle/测试前置条件。
2. 按需初始化平台运行时资产。
3. 先定位到正确模块，再进行修改。
4. 严格区分手写源码与生成物，尤其是 IR 快照与测试生成文件。

### 当前执行策略（Windows 环境）

如果当前工作环境是 **Windows**，而任务又涉及 `macOS` / `iOS` 专项实现、联调或验证，则按以下规则处理：

- 当前轮次先把 Apple 平台相关事项标记为**环境暂缓**，不在本机强行执行。
- 优先推进可在当前环境继续的后续计划，例如 `JNI`、`Loader`、`Runtime`、`IR`、公开 API 收口与文档整理。
- 如果只是更新计划、补充设计说明或整理待办，可以继续修改文档，但不要把“暂缓”误写成“已完成”。
- 等切换到可用的 `macOS/iOS` 环境后，再恢复 Apple 平台实现、回归验证与 blocker 收口。

---

## 终端会话记录规范

当任务需要通过终端（PowerShell / Bash / 其他 shell）执行命令时，**必须**遵循以下流程：

1. **新一轮对话开始时**，先确保仓库根目录下存在 `.cache/`，再删除 `.cache/session_chat.txt`（如果存在）：

   ```powershell
   # Windows PowerShell
   New-Item -ItemType Directory -Path ".cache" -Force | Out-Null
   Remove-Item -Path ".cache/session_chat.txt" -ErrorAction SilentlyContinue
   ```

   ```bash
   # Bash / macOS / Linux
   mkdir -p .cache
   rm -f .cache/session_chat.txt
   ```

2. **每次通过终端执行命令时**，将执行的命令本身及其完整输出追加写入仓库根目录的 `.cache/session_chat.txt`，并使用分隔线隔开各次执行记录。记录格式如下：

   ```
   > 执行的命令
   命令的完整输出内容
   ---------------------------------
   ```

3. **写入日志的内容必须是纯文本**。禁止把 ANSI 颜色控制符、光标控制符、其他终端转义序列原样写入 `.cache/session_chat.txt`。

   要点：

   - 优先让命令自身在非 TTY / `NO_COLOR=1` 下输出无色文本。
   - 如果命令仍会输出 ANSI 转义序列，必须在追加到日志前先剥离。
   - `.cache/session_chat.txt` 面向回溯排查，应保持可直接阅读，不应出现 `\u001b[1;36m`、`\033[0m`、`^[` 等控制符残留。

4. **所有终端操作完成后**，自动退出终端会话（即执行 `exit`），不要让终端保持挂起状态。

要点：

- `.cache/session_chat.txt` 是**临时会话日志**，每轮对话开始时清空重建，不提交到版本控制。
- 该文件应始终位于仓库根目录下的 `.cache/` 目录（即 `D:\fish\wasmline\.cache\session_chat.txt` 或对应工作目录根路径）。
- 此规范的目的是留存当次对话中所有终端交互的完整上下文，方便回溯排查。

---

## 第一步：Gradle 之前必须预检

本仓库的 Gradle 构建至少要求 **Java 21**；其中 Compose Desktop / 部分桌面 sample 明确配置了 `JvmVendorSpec.JETBRAINS`，因此本技能统一按 **JBR 21** 作为预检标准。

先运行：

```bash
bash ./.github/skills/wasmline/scripts/skill_preflight.sh
```

预检重点如下：

- **不要在未确认 Java/JBR 版本的情况下直接运行 Gradle。**
- 预检脚本会优先检查当前 `JAVA_HOME`，必要时再结合 `java -version`、`<JAVA_HOME>/release` 与 shell 配置中的 JBR/JAVA_HOME 线索进行只读判断。
- 预检脚本会只读检查 `~/.zshrc`、`~/.bashrc`、`~/.bash_profile` 中的 JBR/JAVA_HOME 线索。
- 这些 shell 配置文件**只能读取，不能修改**。
- 如果当前 shell 未切到可用的 JBR 21，应先告知用户并**停止**后续 Gradle 编译/测试动作。
- 不要把任何开发机上的本地 JBR 安装路径硬编码进技能文档、脚本或仓库说明中。

如果任务涉及 **Compose Desktop** 或 **桌面 native** 产物，额外运行：

```bash
bash ./.github/skills/wasmline/scripts/skill_preflight.sh --compose-desktop
```

该模式会额外检查 Zig 版本（要求 **0.15.1**）和桌面 JNI/native 产物状态。

---

## 第二步：按需初始化平台资产

仓库依赖各平台的 Wasmtime C-API 运行时资产。如果 `platforms/` 尚未准备好，选择以下任一方式执行：

```bash
# Bash（需要 bash + curl + tar/unzip）
sh ./scripts/init.sh

# Python 3（需要 Python 3.9+，无第三方依赖）
python3 ./scripts/init.py

# Node.js（需要 Node.js 18+，无第三方依赖）
node ./scripts/init.mjs
```

三个脚本功能完全等价，均支持：

- 交互式选择目标平台与架构
- 配置并发下载数
- 可选代理（第一个参数，如 `127.0.0.1:7890`）
- 下载后自动解压部署到 `platforms/` 目录

说明：

- `platforms/` 主要是下载或解压后的平台运行时资产（头文件 + 静态/动态库）。
- 不要默认这些资产在任何机器上都已存在。
- 如果预检脚本提示已检测到运行时资产，可跳过此步骤。

---

## 第三步：先定位模块，再修改

### 仓库结构总览

| 目录 | 说明 |
|---|---|
| `wasmline-core/` | C/C++ 编写的原生 Wasmtime bridge（Engine、Module、Session、Api） |
| `wasmline-multiplatform/` | Kotlin Multiplatform 主工程 |
| `wasmline-multiplatform/wasmline/` | 核心运行时库（commonMain / hostMain / wasmWasiMain / jniMain / jvmMain 等） |
| `wasmline-multiplatform/wasmline-kotlin-plugin/` | Kotlin IR 编译器插件 |
| `wasmline-multiplatform/wasmline-cli/` | CLI 命令行工具 |
| `wasmline-multiplatform/wasmline-loader/` | Loader 模块 |
| `wasmline-multiplatform/wasmline-gradle-plugin/` | Gradle 插件 |
| `wasmline-multiplatform/wasmline-sample/` | 示例工程（android / application / common / multiplatform / plugin） |
| `wasmline-multiplatform/wasmline-build-logic/` | 构建逻辑（convention plugins） |
| `wasmline-ci/` | CI 与样例自动化脚本 |
| `scripts/` | 仓库级初始化与辅助脚本 |
| `platforms/` | 平台运行时资产（由 `scripts/init.sh` 初始化） |
| `docs/` | 文档站点资源 |

### Runtime / Bridge 相关

涉及 wasm 加载、会话生命周期、宿主与 wasm 的调用链、runtime bridge 行为时，优先看：

**C/C++ Bridge 层：**

- `wasmline-core/include/Engine.h`
- `wasmline-core/src/Engine.cpp`
- `wasmline-core/src/Module.cpp`
- `wasmline-core/src/Session.cpp`
- `wasmline-core/src/Api.cpp`

**Kotlin Bridge 层：**

- `wasmline-multiplatform/wasmline/src/commonMain/kotlin/crow/wasmline/internal/bridge/GeneratedBridge.kt`
- `wasmline-multiplatform/wasmline/src/commonMain/kotlin/crow/wasmline/internal/bridge/Endpoint.kt`
- `wasmline-multiplatform/wasmline/src/commonMain/kotlin/crow/wasmline/internal/bridge/HostDispatcher.kt`
- `wasmline-multiplatform/wasmline/src/commonMain/kotlin/crow/wasmline/internal/bridge/Payload.kt`

**测试：**

- `wasmline-multiplatform/wasmline/src/commonTest/kotlin/crow/wasmline/WasmlineServiceRuntimeTest.kt`

### Kotlin Multiplatform Runtime API 相关

涉及公开 API、binding、generated bridge 接入、平台 runtime 实现时，优先看：

- `wasmline-multiplatform/wasmline/src/commonMain/kotlin/crow/wasmline/WasmlineService.kt` — 服务定义入口
- `wasmline-multiplatform/wasmline/src/hostMain/kotlin/crow/wasmline/Wasmline.kt` — 宿主侧主 API
- `wasmline-multiplatform/wasmline/src/hostMain/kotlin/crow/wasmline/WasmlineServices.host.kt` — 宿主侧服务注册
- `wasmline-multiplatform/wasmline/src/hostMain/kotlin/crow/wasmline/WasmlineLoader.kt` — 宿主侧加载器
- `wasmline-multiplatform/wasmline/src/wasmWasiMain/kotlin/crow/wasmline/WasmlineServices.wasmWasi.kt` — WASI 侧服务注册
- `wasmline-multiplatform/wasmline/src/wasmWasiMain/kotlin/crow/wasmline/WasmBridge.kt` — WASI 侧桥接
- `wasmline-multiplatform/wasmline/src/wasmWasiMain/kotlin/crow/wasmline/WasmRouter.kt` — WASI 侧路由

重点概念：

- `WasmlineEndpoint`
- `WasmlineGeneratedBridge`
- `bindGeneratedBridgeAction(...)`
- `requireGeneratedImplementation(...)`
- `unknownGeneratedAction(...)`

### Kotlin 编译器插件 / IR 相关

涉及 `link()`、`bind()`、`bindAs()`、桥接生成、IR 改写或插件行为时，优先看：

**插件注册与入口：**

- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineCompilerPluginRegistrar.kt`
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineCommandLineProcessor.kt`

**IR 变换核心：**

- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineIrGenerationExtension.kt` — IR 生成扩展入口
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineBridgeGenerator.kt` — 桥接代码生成
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineTypedEntryPointRewriter.kt` — 类型化入口重写
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineServiceContractValidator.kt` — 服务契约校验

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

要点：

- 这里是 **IR 插件**，不是简单的源码生成器。
- 很多行为必须结合 IR 输出、生成测试和 runtime 行为一起验证。
- 只看某一个文件通常不够，需要把 runtime helper、插件代码和 box test 作为一个系统来理解。

### CLI / Loader / 打包相关

涉及 manifest、签名、打包或命令行链路时，优先看：

- `wasmline-multiplatform/wasmline-loader/`
- `wasmline-multiplatform/wasmline-cli/`
- `wasmline-multiplatform/wasmline-gradle-plugin/`

### 桌面 Native 相关

涉及 Compose Desktop、JNI 或本地库时，优先看：

- `wasmline-multiplatform/wasmline/zig-build.md`
- `wasmline-multiplatform/wasmline/build.zig`
- `wasmline-multiplatform/wasmline-sample/multiplatform/shared/src/desktopMain/Requirement.md`
- `wasmline-multiplatform/wasmline/src/jniMain/native/`
- `wasmline-multiplatform/wasmline/src/jvmMain/native/`

典型构建命令：

```bash
cd wasmline-multiplatform/wasmline
zig build --release=small -p src/jvmMain/resources
```

补充说明：

- `src/jvmMain/resources/jni/` 更适合作为 Zig 安装输出目录，而不是稳定的源码阅读入口。
- 默认输出会落到 `zig-out/jni/`；如果显式传入 `-p <目录>`，则 JNI 产物会安装到该目录下的 `jni/` 子目录。
- 仓库文档要求 Zig 版本为 **0.15.1**。

---

## 第四步：严格遵守生成物约束

除非任务明确要求重新生成，否则**不要手改**以下内容：

- `wasmline-multiplatform/wasmline-kotlin-plugin/test-gen/` — 自动生成的测试运行器
- `wasmline-multiplatform/wasmline-kotlin-plugin/testData/box/*.fir.txt` — FIR 快照
- `wasmline-multiplatform/wasmline-kotlin-plugin/testData/box/*.fir.ir.txt` — FIR IR 快照
- `wasmline-multiplatform/wasmline-kotlin-plugin/testData/diagnostics/*.fir.txt` — 诊断快照
- `platforms/` — 平台运行时资产
- `**/build/` — 构建产物

关于 IR 测试，需要牢记：

- `*.fir.txt` 和 `*.fir.ir.txt` 是**自动生成、自动比较**的快照文件。
- 第一次运行测试时，可能因为快照缺失或 IR 有变化而失败。
- 第二次运行在生成正确快照后，通常能恢复通过。
- 如果只是修改实现逻辑，**不应**手工编辑这些 IR 快照。

如果需要新增或更新 box test，正确做法是：

1. 在 `testData/box/` 下编写 `.kt` 测试源文件。
2. 运行 `./gradlew :wasmline-kotlin-plugin:generateTests` 以生成测试运行器。
3. 运行测试，快照文件会自动生成。
4. 确认快照内容后提交。

---

## 常用命令

### 环境预检

```bash
bash ./.github/skills/wasmline/scripts/skill_preflight.sh
```

### 初始化平台运行时

```bash
sh ./scripts/init.sh            # Bash
python3 ./scripts/init.py       # Python 3.9+
node ./scripts/init.mjs         # Node.js 18+
```

### 生成插件测试并运行 box test

```bash
cd wasmline-multiplatform
./gradlew :wasmline-kotlin-plugin:generateTests
./gradlew :wasmline-kotlin-plugin:test --tests 'crow.wasmline.kotlin.runners.JvmBoxTestGenerated'
```

### 运行诊断测试

```bash
cd wasmline-multiplatform
./gradlew :wasmline-kotlin-plugin:test --tests 'crow.wasmline.kotlin.runners.JvmDiagnosticsTestGenerated'
```

### 构建桌面 JNI 产物（需要 Zig 0.15.1）

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
4. `wasmline-multiplatform/settings.gradle.kts` — 多平台模块划分
5. `wasmline-core/` — C/C++ bridge 层实现
6. `wasmline-multiplatform/wasmline/` — Kotlin 核心运行时
7. `wasmline-multiplatform/wasmline-kotlin-plugin/` — IR 编译器插件
8. `wasmline-multiplatform/wasmline-kotlin-plugin/testData/box/README_zh.md` — box test 说明
9. `.github/plans/ir-planv2.md` — IR 变换设计计划

---

## 工作原则

1. **先预检，再构建。** Gradle 之前先确认 JBR 21。
2. **先确认资产，再编译。** 平台相关构建前先确认运行时资产是否齐全。
3. **先定位模块，再修改。** 不要盲改，确认需求对应的模块后再动手。
4. **生成物不手改。** 把 IR 快照、`test-gen/`、`build/` 视为生成物，而不是手写源码。
5. **系统性理解。** IR 插件行为需要 runtime helper + 插件代码 + box test 三者结合验证。

一句话原则：**先预检，再确认资产，再定位模块，最后才开始实现。**
