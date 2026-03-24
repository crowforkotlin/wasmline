# Wasmline IR 工作记录（交接文档）

> 更新时间：2026-03-24  
> 用途：快速恢复 `wasmline-kotlin-plugin` 的当前实现状态、最近完成事项与下一步任务。

---

## 0. 文档角色

建议继续与 `wasmline-ir-design.md` **分开维护，不合并**：

- `wasmline-ir-design.md`
  - 负责稳定设计、术语和职责边界
  - 回答“为什么这样设计”
- `ir.md`
  - 负责阶段性状态、近期修复和待办事项
  - 回答“现在做到哪里了、接下来做什么”

如果未来继续推进 typed service 层，这份文件应持续保持“短期可执行”的风格，而不是膨胀成第二份设计文档。

---

## 1. 当前一句话结论

`wasmline-kotlin-plugin` 已经从“只会注册插件”推进到：

> **可以发现 `WasmlineService` contract、执行 phase-one 校验、生成 `Definition / Proxy / Adapter` skeleton、收口 typed runtime API，并开始对 `link<T>() / bind(...) / bindAs<T>()` 等入口注入自动 definition 注册逻辑。**

当前主缺口已经收敛为：

- `Adapter.bind()` 的真实绑定逻辑还未完成
- 自动注册链路虽然已经接入 IR 侧，但仍需要 box / 运行时层面的正式回归验证
- 正式 box fixture 数量还偏少
- diagnostics 覆盖仍待补齐

---

## 2. 当前已经确认可工作的内容

### 2.1 IR 插件能力

核心文件：

- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineCompilerPluginRegistrar.kt`
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineIrGenerationExtension.kt`
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineRuntimeSymbols.kt`

当前已确认：

- 能扫描 `interface ... : WasmlineService`
- 能执行 phase-one 限制校验
- 能为合法 contract 生成：
  - `*_WasmlineDefinition`
  - `*_WasmlineProxy`
  - `*_WasmlineAdapter`
- `Proxy` 已经接到 `endpoint.invoke(...)`
- `Definition.link()` / `Definition.bind()` 已经有基本 glue
- 已开始对 typed 高层入口做 call-site 级别的 definition 自动注册注入：
  - `WasmlineEndpoint.link<T>()`
  - `Wasmline.link<T>()`
  - `linkHost<T>()`
  - `WasmlineBindingScope.bind(...)`
  - `WasmlineBindingScope.bindAs<T>(...)`

### 2.2 typed runtime API 当前状态

近期 runtime 层也做了 API 收口，当前建议按三层理解：

- 用户主 API：
  - `Wasmline`
  - `WasmlineService`
  - `WasmlineEndpoint`
  - `link<T>()`
  - `bindServices { ... }`
  - `WasmlineBindingScope`（高级 / DSL 作用域）
- 生成代码 / bootstrap 过渡 API：
  - `WasmlineServiceDefinition`
  - `registerWasmlineServiceDefinition(...)`
  - `unregisterWasmlineServiceDefinition(...)`
  - `wasmlineEmptyPayload()`
- 内部实现：
  - `WasmlineServiceRegistry` 已改为 `internal`

### 2.3 生成物观察方式

当前插件是 `IrGenerationExtension`，不是源码生成器。

因此不会出现：

- `build/generated/**/*.kt`

而是会直接把声明加进 IR，最终进入编译产物 `.class` 或 IR dump。

可观察方式：

1. 查看编译产物 `.class`
2. 使用 IDE 反编译生成类
3. 运行 `testData/box` 的 FIR / IR snapshot 测试

---

## 3. 最近已经完成的工作

### 3.1 正式 box 测试路径已经打通

当前已经不是“空的 testData/box 目录”状态，而是已经存在正式 case：

- `wasmline-multiplatform/wasmline-kotlin-plugin/testData/box/echoProxyRoundTrip.kt`
- `wasmline-multiplatform/wasmline-kotlin-plugin/testData/box/echoProxyRoundTrip.fir.txt`
- `wasmline-multiplatform/wasmline-kotlin-plugin/testData/box/echoProxyRoundTrip.fir.ir.txt`

并且：

- `GenerateTests.kt` 会为该 fixture 生成对应测试方法
- `JvmBoxTestGenerated` 不再只是空的 `testAllFilesPresentInBox()`
- 定向执行 `JvmBoxTestGenerated` 已确认通过

### 3.2 box 测试已启用 `FIR_DUMP` 与 `DUMP_IR`

当前 `AbstractJvmBoxTest` 已启用：

- `CodegenTestDirectives.DUMP_IR`
- `FirDiagnosticsDirectives.FIR_DUMP`
- `JvmEnvironmentConfigurationDirectives.FULL_JDK`
- `CodegenTestDirectives.IGNORE_DEXING`

这意味着正式 fixture 现在会同时生成并校验：

- `*.fir.txt`
- `*.fir.ir.txt`

### 3.3 修复了 box 测试的 classpath 问题

当前 `WasmlinePluginConfigurator` 已经不只是注册插件，还会补充 compiler test 所需 classpath。

原因是这个插件不是纯模板型插件，而是会生成并依赖 `crow.wasmline.*` runtime 符号。

当前已确认生效的点：

- `addJvmClasspathRoots(...)` 会把 compiler test 所需 runtime/classpath 喂给编译阶段
- `RuntimeClasspathProvider` 会把运行时 classpath 喂给 box 执行阶段
- `build.gradle.kts` 中已显式传入：
  - `wasmlineRuntime.classpath`
  - `wasmlineTestArtifacts.classpath`

### 3.4 当前 fixture 编写策略已经明确

因为插件当前是 **IR-only**，因此 fixture 中不应依赖“源码阶段可见的生成声明”。

当前已验证更稳妥的策略是：

- 优先通过运行时行为或反射观察生成物
- 避免在源代码中直接静态依赖生成声明名
- 通过 `box(): String` 对可观察行为做断言

### 3.5 runtime API 面已经开始收口

当前已完成的收口动作包括：

- `WasmlineServiceRegistry` 不再作为普通用户 API 暴露，而是改为 `internal`
- 新增更窄的过渡 bootstrap hook：
  - `registerWasmlineServiceDefinition(...)`
  - `unregisterWasmlineServiceDefinition(...)`
- `WasmlineServiceDefinition`、`WasmlineEndpoint`、`WasmlineBindingScope`、`wasmlineEmptyPayload()` 的注释已重新标注“用户 API / 高级 API / generated SPI”的职责边界

### 3.6 自动注册逻辑已开始接入 IR 入口改写

当前 `WasmlineIrGenerationExtension` 在生成 definition / proxy / adapter 之外，还新增了一层 call-site 改写：

- 当编译器看到 `link<T>()` / `bind(...)` / `bindAs<T>()` / `linkHost<T>()` 等 typed 入口时
- 会在调用前注入 `registerWasmlineServiceDefinition(...)`
- 从而尽量让用户不必手动接触 registry 或 definition 注册流程

需要注意：

- 这目前是“按调用点注入”的自动注册，不是“全局模块 bootstrap 已完全稳定”
- 这条链路当前已通过代码层错误检查，但本轮会话中尚未重新跑完整 box snapshot 回归

---

## 4. 当前测试架构状态

### 4.1 正式路径

当前正式 IR 测试路径为：

- `wasmline-multiplatform/wasmline-kotlin-plugin/testData/box/*.kt`
- `wasmline-multiplatform/wasmline-kotlin-plugin/test-fixtures/.../GenerateTests.kt`
- `wasmline-multiplatform/wasmline-kotlin-plugin/test-gen/.../JvmBoxTestGenerated.java`
- `wasmline-multiplatform/wasmline-kotlin-plugin/test-fixtures/.../AbstractJvmBoxTest.kt`

### 4.2 当前 box README 也已整理

`testData/box/` 下现在有三份说明文件：

- `README.md` —— 入口页
- `README_en.md` —— 英文说明
- `README_zh.md` —— 中文说明

它们的职责是：

- 说明 fixture 应该测什么
- 说明预期结果是什么
- 说明如何新增、修改和验证 case

---

## 5. 当前仍未完成的核心问题

### 5.1 `Adapter.bind()` 还只是 phase-one stub

当前最重要的功能缺口仍然是：

- `Adapter.bind()` 还没有完成真正的 action 绑定逻辑
- payload 解码 / handler 注册 / 返回值编码链路还未补齐

也就是说，目前 typed service 的“调出去”已经有基础形状，
但“接进来”的完整接线还没有完成。

### 5.2 自动注册链路还缺正式回归证明

当前自动注册已经进入 IR 实现，但还没有形成“可以放心视为稳定能力”的证据链，仍缺：

- 新/旧 box fixture 的正式 snapshot 更新
- 覆盖 `link<T>()`、`bindAs<T>()`、`bind(implementation)` 的更明确 case
- 不同平台入口（尤其 Host / Wasm 两侧）的行为确认

### 5.3 正式 fixture 覆盖还不够

当前正式 case 只有一个 `echoProxyRoundTrip`，还不够支撑后续演进。

建议继续补：

- zero-arg case
- `Unit` return case
- `ByteArray -> ByteArray` 的更小粒度 case
- 明确覆盖 bind / auto-register 行为的 case
- 非法 contract 的 diagnostics case

### 5.4 diagnostics 体系还没有展开

phase-one validator 已有不少限制，但还缺正式 diagnostics 覆盖，例如：

- 两个普通参数
- 非 `ByteArray` 参数
- `suspend`
- property
- overload
- generic contract / generic function

---

## 6. 现在最推荐的下一步

### 第一优先级：补齐 `Adapter.bind()` 的真实逻辑

目标：

- 生成稳定 action id → handler 的绑定逻辑
- 把本地实现真正接入 `WasmlineBindingScope`
- 让 `Definition.bind()` 不再只是走到 error stub

### 第二优先级：验证并固化自动注册链路

目标：

- 重新运行并更新 IR / FIR snapshot
- 明确验证 `link<T>()`、`bindAs<T>()`、`bind(implementation)` 自动注册是否都按预期生效
- 判断当前“按调用点注入”的策略是否足够，还是要升级为模块级 bootstrap

### 第三优先级：继续补正式 box case

建议顺序：

1. zero-arg case
2. `Unit` return case
3. 更聚焦的 `ByteArray -> ByteArray` case
4. 明确覆盖 bind / auto-register 行为的 case

### 第四优先级：补 diagnostics 测试

等基础 box case 稳定后，再开始系统化补 validator 的负例测试。

---

## 7. 推荐的本地验证命令

在 `wasmline-multiplatform/` 目录执行：

```zsh
./gradlew :wasmline-kotlin-plugin:generateTests
./gradlew :wasmline-kotlin-plugin:test --tests 'crow.wasmline.kotlin.runners.JvmBoxTestGenerated'
```

如果本机 toolchain 对 JDK vendor 有要求，请先切换到可用的 JDK 21 / JetBrains Runtime 21。

---

## 8. 当前一句话交接

当前主线已经很明确：

> **继续以正式 `testData/box` 为中心，先验证并固化自动注册链路，再补齐 `Adapter.bind()` 的真实生成逻辑，最后逐步扩大 diagnostics 覆盖。**
