---
name: wasmline
description: 用于在 Wasmline 仓库中进行环境预检、平台资产初始化、模块定位以及 Kotlin IR 插件约束处理的仓库技能。
---

# Wasmline 仓库技能

当任务涉及 `wasmline` 仓库的构建、测试、调试、排障或代码修改时，使用此技能。

## 目录约定

本技能目录采用如下组织方式：

- `SKILL.md`：技能入口说明
- `scripts/skill_preflight.sh`：环境预检脚本
- `scripts/skill_context_snapshot.py`：上下文快照脚本

辅助脚本统一放在 `scripts/` 子目录中，便于维护，也更符合“技能说明与配套资源分离”的组织习惯。

## 目标

在真正开始编译、测试或改代码之前，先确认环境、聚合上下文，并将任务路由到正确模块。

## 推荐流程

1. 先运行预检脚本，确认是否满足 Gradle/测试前置条件。
2. 如有需要，初始化平台运行时资产。
3. 在大范围阅读或多文件修改前，生成上下文快照。
4. 先定位到正确模块，再进行修改。
5. 严格区分手写源码与生成物，尤其是 IR 快照与测试生成文件。

## 第一步：Gradle 之前必须预检

本仓库的 Gradle 构建至少要求 **Java 21**；其中 Compose Desktop / 部分桌面 sample 明确配置了 `JvmVendorSpec.JETBRAINS`，因此本技能统一按 **JBR 21** 作为预检标准。

先运行：

```bash
bash ./.github/skills/wasmline/scripts/skill_preflight.sh
```

预检重点如下：

- 不要在未确认 Java/JBR 版本的情况下直接运行 Gradle。
- 预检脚本会优先检查当前 `JAVA_HOME`，必要时再结合 `java -version`、`<JAVA_HOME>/release` 与 shell 配置中的 JBR/JAVA_HOME 线索进行只读判断。
- 预检脚本会只读检查 `~/.zshrc`、`~/.bashrc`、`~/.bash_profile` 中的 JBR/JAVA_HOME 线索。
- 这些 shell 配置文件只能读取，不能修改。
- 如果当前 shell 未切到可用的 JBR 21，应先告知用户并停止后续 Gradle 编译/测试动作。
- 不要把任何开发机上的本地 JBR 安装路径硬编码进技能文档、脚本或仓库说明中。

如果任务涉及 Compose Desktop 或桌面 native 产物，再运行：

```bash
bash ./.github/skills/wasmline/scripts/skill_preflight.sh --compose-desktop
```

## 第二步：按需初始化平台资产

仓库依赖各平台的 Wasmtime C-API 运行时资产。如果 `platforms/` 尚未准备好，执行：

```bash
sh ./scripts/init.sh
```

说明：

- `platforms/` 主要是下载或解压后的平台运行时资产。
- 不要默认这些资产在任何机器上都已存在。
- 如果作者已经预先准备好，可跳过初始化。

## 第三步：生成上下文快照

当仓库体量较大、阅读入口较分散，或者准备做一轮较大修改时，先生成快照：

```bash
python3 ./.github/skills/wasmline/scripts/skill_context_snapshot.py
```

该脚本会生成：

- `.cache/skill/context-latest.md`
- `.cache/skill/context-<timestamp>.md`

快照包含：

- 当前 Git 分支、提交和工作区状态
- 仓库目录摘要
- `wasmline-multiplatform/` 模块摘要
- 关键文档与关键入口文件摘录
- 当前变更文件和 diff 预览

适用场景：

- 第一次快速理解仓库
- 进入复杂需求前聚合上下文
- 一轮修改完成后复核重点变更

## 第四步：先定位模块，再修改

### 仓库结构总览

- `wasmline-core/`：C/C++ 编写的原生 Wasmtime bridge
- `wasmline-multiplatform/`：Kotlin Multiplatform 主工程
- `wasmline-ci/`：CI 与样例自动化脚本
- `scripts/`：仓库级初始化与辅助脚本
- `platforms/`：平台运行时资产
- `structs/`：设计说明与 IR 计划
- `docs/`：文档站点资源

### Runtime / Bridge 相关问题

如果需求涉及 wasm 加载、会话生命周期、宿主与 wasm 的调用链、runtime bridge 行为，优先看：

- `wasmline-core/include/Engine.h`
- `wasmline-core/src/Engine.cpp`
- `wasmline-core/src/Module.cpp`
- `wasmline-core/src/Session.cpp`
- `wasmline-core/src/Api.cpp`
- `wasmline-multiplatform/wasmline/src/commonMain/kotlin/crow/wasmline/internal/bridge/`
- `wasmline-multiplatform/wasmline/src/commonTest/kotlin/crow/wasmline/WasmlineServiceRuntimeTest.kt`

### Kotlin Multiplatform Runtime API 相关问题

如果需求涉及公开 API、binding、generated bridge 接入、平台 runtime 实现，优先看：

- `wasmline-multiplatform/wasmline/src/hostMain/kotlin/crow/wasmline/Wasmline.kt`
- `wasmline-multiplatform/wasmline/src/hostMain/kotlin/crow/wasmline/WasmlineServices.host.kt`
- `wasmline-multiplatform/wasmline/src/wasmWasiMain/kotlin/crow/wasmline/WasmlineServices.wasmWasi.kt`
- `wasmline-multiplatform/wasmline/src/commonMain/kotlin/crow/wasmline/internal/bridge/GeneratedBridge.kt`
- `wasmline-multiplatform/wasmline/src/commonMain/kotlin/crow/wasmline/internal/bridge/Endpoint.kt`
- `wasmline-multiplatform/wasmline/src/commonMain/kotlin/crow/wasmline/internal/bridge/BindingScope.kt`

重点概念：

- `WasmlineEndpoint`
- `WasmlineBindingScope`
- `WasmlineGeneratedBridge`
- `bindGeneratedBridgeAction(...)`
- `requireGeneratedImplementation(...)`
- `unknownGeneratedAction(...)`

### Kotlin 编译器插件 / IR 相关问题

如果需求涉及 `link()`、`bind()`、`bindAs()`、桥接生成、IR 改写或插件行为，优先看：

- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineCompilerPluginRegistrar.kt`
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineCommandLineProcessor.kt`
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineIrGenerationExtension.kt`
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineRuntimeSymbols.kt`
- `wasmline-multiplatform/wasmline-kotlin-plugin/testData/box/README_zh.md`
- `.github/plans/ir-plan.md`

要点：

- 这里是 **IR 插件**，不是简单的源码生成器。
- 很多行为必须结合 IR 输出、生成测试和 runtime 行为一起验证。
- 只看某一个文件通常不够，需要把 runtime helper、插件代码和 box test 作为一个系统理解。

### CLI / Loader / 打包相关问题

如果需求涉及 manifest、签名、打包或命令行链路，优先看：

- `wasmline-multiplatform/wasmline-loader/`
- `wasmline-multiplatform/wasmline-cli/`
- `wasmline-multiplatform/wasmline-gradle-plugin/`

### 桌面 Native 相关问题

如果需求涉及 Compose Desktop、JNI 或本地库，优先看：

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

仓库文档要求 Zig 版本为 **0.15.1**。

## 第五步：严格遵守生成物约束

除非任务明确要求重新生成，否则不要手改以下内容：

- `wasmline-multiplatform/wasmline-kotlin-plugin/test-gen/`
- `wasmline-multiplatform/wasmline-kotlin-plugin/testData/box/*.fir.txt`
- `wasmline-multiplatform/wasmline-kotlin-plugin/testData/box/*.fir.ir.txt`
- `platforms/`
- `**/build/`

关于 IR 测试，需要牢记：

- `*.fir.txt` 和 `*.fir.ir.txt` 是自动生成、自动比较的快照文件。
- 第一次运行测试时，可能因为快照缺失或 IR 有变化而失败。
- 第二次运行在生成正确快照后，可能恢复通过。
- 如果只是修改实现逻辑，通常不应手工编辑这些 IR 快照。

## 常用命令

### 只做阅读与分析

```bash
bash ./.github/skills/wasmline/scripts/skill_preflight.sh
python3 ./.github/skills/wasmline/scripts/skill_context_snapshot.py
```

### 初始化平台运行时

```bash
sh ./scripts/init.sh
```

### 生成插件测试并运行 box test

```bash
cd wasmline-multiplatform
./gradlew :wasmline-kotlin-plugin:generateTests
./gradlew :wasmline-kotlin-plugin:test --tests 'crow.wasmline.kotlin.runners.JvmBoxTestGenerated'
```

## 推荐阅读顺序

第一次接触仓库时，建议按以下顺序阅读：

1. `README_zh.md`
2. `README.md`
3. `.github/skills/wasmline/SKILL.md`
4. `.github/skills/wasmline/scripts/skill_preflight.sh`
5. `scripts/init.sh`
6. `wasmline-multiplatform/settings.gradle.kts`
7. `wasmline-core/`
8. `wasmline-multiplatform/wasmline/`
9. `wasmline-multiplatform/wasmline-kotlin-plugin/`
10. `wasmline-multiplatform/wasmline-kotlin-plugin/testData/box/README_zh.md`
11. `.github/plans/ir-plan.md`

## 工作原则

- Gradle 之前先确认 JBR 21。
- 平台相关构建前先确认运行时资产是否齐全。
- 大任务开始前先生成上下文快照。
- 修改前先定位模块，不要盲改。
- 把 IR 快照和生成测试文件视为生成物，而不是手写源码。

一句话原则：**先预检，再确认资产，再聚合上下文，最后才开始实现。**
