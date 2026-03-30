# Wasmline IR 单 Bridge 重构计划

## 背景

Wasmline Kotlin IR 插件的主链路已经明显向 `单接口 -> 单 Bridge` 方案迁移：`wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineIrGenerationExtension.kt` 已经生成 `*_WasmlineBridge`，并直接改写 typed 入口调用；运行时也已围绕 `GeneratedBridge.kt`、`Endpoint.kt`、`BindingScope.kt`、`bindGenerated(...)` 收敛。

但文档和剩余测试口径仍有一部分停留在旧的 `Definition + Registry` 叙述上。当前真正需要推进的，不再是“是否切换到单 Bridge 架构”，而是：继续补齐 `link()/bind()/bindAs()` 覆盖、统一最终公开 API 语义、并在新链路稳定后进入旧组件清理阶段。

同时需要遵守 `AGENTS.md` 中的 IR 测试约束：`testData` 下的 `*.fir.txt` / `*.fir.ir.txt` 是生成快照，不手改；快照不一致时删除后重跑测试生成。

---

## 目标

1. 将 Kotlin IR 插件重构为“一个服务接口只生成一个 Bridge 类”的模式。
2. 由 IR 直接替换 typed 入口调用点，而不是在调用点前注入注册逻辑。
3. 移除运行时全局 `Registry` 查表依赖，避免 `Map<KClass, Entry>` 路线。
4. 保持用户接口与业务实现零侵入，不要求用户声明 companion、definition、handler、注解以外额外结构。
5. 保持无反射、无运行时动态代理、无全局注册表。
6. Bridge 层仅供生成代码和 IR 替换引用，用户侧不可感知、不应成为公开 API。
7. 第一阶段先稳定最小闭环：稳定 action、单 Bridge、调用点替换、`ByteArray`/`Unit` phase-one 兼容。
8. 第二阶段再引入多参数、基础类型、无 `@Serializable` 情况的 `ConvertFactory` / 序列化 SPI。

---

## 非目标

1. 本轮不追求一步到位支持任意 Kotlin 类型、泛型方法、`suspend`、默认参数、`vararg`、重载。
2. 本轮不保留旧生成模型的长期兼容层；允许短期过渡，最终目标是彻底移除旧路线。
3. 本轮不手工维护 `testData/box` 下的快照文件。
4. 本轮不把 Bridge 暴露为用户编程模型的一部分；用户不应显式 new、引用、import 或依赖其命名。
5. 本轮不先做复杂的可插拔序列化框架；序列化 SPI 放到第二阶段。

---

## 现状与剩余问题

### 1. 文档描述明显落后于实现

源码现状已经不是“Definition + Registry 主链路”。当前实现已经具备：

- `*_WasmlineBridge` 生成
- `link()/bind()/bindAs()` 的 typed entry point 改写
- `GeneratedBridge.kt` / `bindGenerated(...)` / endpoint helper 运行时支架

因此本计划的主要作用，应从“推动架构切换”转为“定义收口标准与阶段 3 删除边界”。

### 2. 最终公开 API 语义需要收口

当前实现已经收口为单一的公开 link 概念：`link<T>()`。

### 3. 旧 Registry 路线仍需系统性删除

尽管主链路已经切到 Bridge 模式，但旧组件是否彻底不再被任何测试、兼容逻辑或历史文件依赖，仍需以阶段 3 集中清理来完成。目标仍然是移除：

- `WasmlineServiceRegistry`
- `WasmlineRuntimeGlue`
- `registerGeneratedService(...)`
- `internal.bridge.ServiceDefinition`

以及对应的旧生成/旧命名逻辑。

### 4. 测试覆盖仍有缺口

目前 `box` 已覆盖 Bridge round-trip、`bindAs<T>()` 以及 diagnostics 歧义场景，但仍需要把 `link<T>()` 的 call-site rewrite 明确纳入稳定回归。只有这样，阶段 3 才具备开始条件。

---

## 目标架构

### 1. 总体原则

新的主链路应当是：

- 用户写服务接口与实现；
- 编译期为每个接口生成一个 Bridge；
- IR 直接把 `link()/bind()/bindAs()` 替换为对 Bridge 和少量 runtime helper 的静态调用；
- 运行时不再有全局注册表，不再按 `KClass` 查表；
- 用户只看到原来的 API 语法糖，看不到 Bridge 层。

### 2. Bridge 的职责边界

每个服务接口只生成一个 `*_WasmlineBridge`，统一承担以下职责：

1. link 侧代理入口  
   提供一个静态/对象式入口，根据 transport endpoint 返回接口实现；实现形式可以是 Bridge 自己实现接口，或由 Bridge 内部创建匿名代理对象，但对外都只暴露原接口类型。

2. bind 侧分发入口  
   由 Bridge 将本地实现与 action 注册表绑定，内部以 `when(action)` 分发到具体方法，不再为每个方法生成独立 Handler 类。

3. action 元数据承载  
   每个方法的 action 采用稳定格式：`{fullyQualifiedContractName}#{methodName}`。  
   第一阶段继续禁止重载，以保证 action 规则简单且稳定。

4. phase-one 编解码约束承载  
   第一阶段只支持：
   - 参数：0 个或 1 个，且为 `ByteArray`
   - 返回：`ByteArray` 或 `Unit`

5. phase-two 序列化扩展点承载  
   为后续 `ConvertFactory` / 序列化 SPI 保留插槽，但第一阶段不把复杂 SPI 实装进主链路。

### 3. 调用点替换原则

IR 不再“注入注册再调用原 API”，而是直接替换 typed 入口调用：

- Host 侧 `Wasmline.link<T>()` 直接替换为 Bridge link 静态调用。
- Host 侧 `Wasmline.bind(...)` / `bindAs<T>(...)` 直接替换为 Bridge bind 静态调用。
- Wasm 侧 `bind(...)` / `bindAs<T>(...)` 直接替换为 Bridge bind 静态调用。

这里的“调用点替换”仅指替换 typed 入口调用点，不尝试重写任意接口方法调用表达式。接口方法调用仍通过生成出来的代理对象或 dispatcher 正常执行。

### 4. runtime 最小依赖面

新架构保留并围绕以下 runtime 能力收敛：

- `WasmlineEndpoint`
- `WasmlineBindingScope`
- `WasmlineGeneratedBridge`
- `bindGenerated(...)`
- `emptyPayload()`
- 必要的 unknown-action fail-fast helper

其中 `GeneratedBridge.kt`、`Endpoint.kt`、`BindingScope.kt` 是新路线的基础；`Registry` / `RuntimeGlue` / `ServiceRegistration` 不再是主链路必需。

### 5. 用户可见性要求

Bridge 对用户侧必须遵守以下约束：

1. 不是公开 API。
2. 不作为文档能力暴露。
3. 不应要求用户手写或显式引用。
4. 只能由生成代码与 IR 替换后的调用点引用。

工程实现上建议：

- Bridge 类本体至少为 `internal`；
- 构造器、内部 helper、常量尽可能 `private`；
- 若同文件可行，可进一步收紧可见性；
- 命名与生成 origin 应明确标识为编译器生成物，避免 IDE 自动补全将其误当业务 API。

目标语义是“对用户不可见”；若 Kotlin 可见性模型限制导致无法做到跨文件 `private`，则以 `internal + private ctor/private helper + IR 专用引用` 作为第一落地点。

---

## 分阶段实施计划

### 第一阶段：稳定最小闭环

这是建议优先落地的阶段，也是本计划的主实施目标。

#### 范围

1. 稳定 action  
   将 action 规则统一改为 `fqcn#methodName`。

2. 单 Bridge 生成  
   在 `wasmline-multiplatform/wasmline-kotlin-plugin` 中把“Definition/Proxy/Adapter/Linker/Binder/Handler”生成逻辑收敛为“一个接口一个 Bridge”。

3. 调用点直接替换  
   将 `autoRegisterTypedEntryPoints()` 从“插入 `registerGeneratedService(...)`”改为“直接替换 typed API 调用表达式”。

4. phase-one 类型兼容  
   继续只支持 `ByteArray` 参数、`ByteArray`/`Unit` 返回、最多一个参数。

5. fail-fast 占位 API 保持不变  
   `wasmline/src/hostMain/.../WasmlineServices.host.kt` 与 `wasmline/src/wasmWasiMain/.../WasmlineServices.wasmWasi.kt` 中的 `error(...)` 占位 API 继续保留，用于插件未生效时立即报错。

#### 产出

- `wasmline-kotlin-plugin` 可生成 `*_WasmlineBridge`
- `link()/bind()/bindAs()` 被 IR 完整替换
- 运行时主链路不再走 `Registry`
- `testData/box` 具备新的单 Bridge 快照验证案例

### 第二阶段：序列化与扩展点

#### 范围

1. 定义 `ConvertFactory` / 序列化 SPI 抽象。
2. 设计多参数打包协议。
3. 支持基本类型与无 `@Serializable` 情况的策略扩展。
4. 明确默认实现，例如基于 Kotlin Serialization + ProtoBuf。
5. 在 Bridge 生成期接入 marshalling/unmarshalling 代码生成。

#### 目标

让 Bridge 仍保持“一接口一类”的前提下，具备更广的类型支持，而不是回退到按方法增殖类。

### 第三阶段：旧组件清理

只有在以下条件同时满足后，才建议开始阶段 3：

1. `bind()`、`bindAs<T>()`、`link<T>()` 的 Bridge 改写都已有稳定 box 覆盖。
2. diagnostics 已覆盖 `bind(implementation)` 的多契约歧义。
3. IR / 字节码快照中不再依赖 `registerGeneratedService(...)`、`WasmlineServiceRegistry`、`*_WasmlineDefinition` 等旧路径。
4. 最终 API 已定稿为 `link()/bind()/bindAs()`。

满足后，再系统性删除旧运行时与旧生成逻辑。

#### 最终应删除或废弃的组件

##### 运行时侧

- `WasmlineServiceRegistry`
- `RegisteredServiceEntry`
- `registerGeneratedService(...)`
- `unregisterGeneratedService(...)`
- `linkInternal(...)`
- `bindInternal(...)`
- `bindAsInternal(...)`
- `internal.bridge.ServiceDefinition`

对应目录主要集中在：

- `wasmline-multiplatform/wasmline/src/commonMain/kotlin/crow/wasmline/WasmlineServiceRegistry.kt`
- `.../WasmlineRuntimeGlue.kt`
- `.../spi/ServiceRegistration.kt`
- `.../internal/bridge/ServiceDefinition.kt`

##### 插件侧

在 `WasmlineIrGenerationExtension.kt` 中，最终应删除或废弃以下路线：

- `generateDefinitionSkeleton`
- `generateProxySkeleton`
- `generateAdapterSkeleton`
- `generateAdapterHandlerClass`
- `generateDefinitionLinkerClass`
- `generateDefinitionBinderClass`
- 以及围绕 `_WasmlineDefinition / _WasmlineProxy / _WasmlineAdapter / _WasmlineLinker / _WasmlineBinder / _WasmlineHandler` 的命名与符号解析逻辑

---

## 风险

### 1. `bind(implementation)` 的静态歧义

移除 Registry 后，`bind(implementation)` 不能再依赖运行时“matching contracts”查找。  
如果一个实现类同时实现多个 `WasmlineService` 接口，插件必须在编译期处理歧义：

- 只有一个契约时：允许自动替换；
- 多个契约时：编译报错并要求用户改用 `bindAs<T>()`；
- 不建议再保留运行时歧义分流。

这是新架构里的关键语义变化点。

### 2. Bridge 可见性与“完全不可见”之间存在工程张力

“用户完全找不到 Bridge”与“跨文件调用点替换需要可见符号”之间可能有可见性冲突。  
第一阶段应优先保证：

- 用户不需要引用；
- Bridge 不出现在公开 API 中；
- 生成符号尽量收敛为 `internal/private`。

是否能做到“手写全限定名也不可引用”，需要结合 Kotlin IR 对跨文件 private/internal 的可见性限制进一步确认。

### 3. 快照波动较大

重构会显著改变 `testData/box` 的 FIR/IR dump。  
需要严格遵守 `AGENTS.md`：不手改快照文件，删除旧 `*.fir.txt` / `*.fir.ir.txt` 后重跑生成与测试。

### 4. 旧新路线并存期间容易出现双链路污染

如果在过渡期同时保留旧 Definition 路线和新 Bridge 路线，可能出现：

- 调用点有的走注册，有的走直接替换；
- 运行时仍被旧 registry 测试反向绑住；
- 测试预期难以统一。

因此过渡期应尽量短，并以“先打通一条完整新链路”为准。

### 5. 第二阶段序列化 SPI 设计会反向影响 Bridge 形态

若第一阶段把编码逻辑写死在 Bridge 内部，第二阶段可能难以接入 `ConvertFactory`。  
因此第一阶段虽然不实现 SPI，也应在 Bridge 生成结构上预留“参数编码/返回解码”的抽象槽位。

---

## 测试验证策略

### 1. IR box 测试作为主验证面

重点目录：

- `wasmline-multiplatform/wasmline-kotlin-plugin/testData/box`
- `wasmline-multiplatform/wasmline-kotlin-plugin/test-fixtures`
- `wasmline-multiplatform/wasmline-kotlin-plugin/test-gen`

验证目标：

1. 新 Bridge 是否被生成。
2. `link()/bind()/bindAs()` 是否被直接替换。
3. IR 中是否不再出现对 `registerGeneratedService(...)`、`WasmlineServiceRegistry`、`*_WasmlineDefinition` 等旧路径的依赖。
4. action 是否稳定为 `fqcn#methodName`。
5. `ByteArray`/`Unit` phase-one 语义是否闭环。

建议新增/改造 box case，覆盖至少以下场景：

- 单参数 `ByteArray -> ByteArray`
- 无参数 `Unit`
- `bindAs<T>()` 显式绑定
- `link<T>()` host 侧 typed 入口
- 多接口实现触发 `bind(implementation)` 歧义报错
- 不支持语法的诊断仍然成立

### 2. 快照处理规则

对 `testData/box` 下的快照遵循固定流程：

1. 修改或新增 `*.kt` fixture。
2. 删除对应 `*.fir.txt` / `*.fir.ir.txt`。
3. 运行 `GenerateTests.kt` 生成测试。
4. 重跑定向测试生成新快照并比对。
5. 若第二次仍失败，再判断是否为 IR 问题。

明确规则：快照文件不手工编辑。

### 3. runtime 单测作为辅助验证面

关注目录：

- `wasmline-multiplatform/wasmline/src/commonTest/kotlin/crow/wasmline`

当前 `WasmlineServiceRuntimeTest.kt` 已更接近 Bridge dispatcher / binding scope 模型，不应再回到 registry 注册/查表口径。  
新架构下建议将其重心迁移为：

- Bridge dispatcher `when(action)` 的分发正确性
- duplicate action fail-fast
- unknown action fail-fast
- bind/link 通过 `WasmlineBindingScope` 的本地回路闭环
- 插件未替换时 placeholder API 是否 fail-fast

### 4. 编译与测试环境要求

运行和测试需要使用 `AGENTS.md` 指定的 JBR 环境，即 `D:\program\jbrsdk-21.0.9-windows-x64-b1163.94`。  
计划实施时，所有与 `wasmline-kotlin-plugin` 相关的生成与测试都应以此环境为准，避免工具链差异导致快照抖动。

---

## 迁移顺序

### 1. 先冻结第一阶段边界

先明确并锁定第一阶段只做：

- 稳定 action
- 单 Bridge
- 调用点替换
- `ByteArray`/`Unit` phase-one 兼容

避免在第一阶段混入复杂序列化 SPI。

### 2. 先改插件，再改 runtime 测试口径

优先在 `wasmline-multiplatform/wasmline-kotlin-plugin` 完成生成模型与调用点改写切换；  
同时把 `testData/box` 预期从 Definition/Proxy/Adapter 改成 Bridge + call-site rewrite。

### 3. 以最小 runtime helper 承接新链路

围绕 `wasmline/src/commonMain/kotlin/crow/wasmline/internal/bridge` 与 `hostMain/wasmWasiMain` 的 `bindGenerated(...)` / endpoint helper 收敛接口，避免新插件再次依赖旧 registry runtime。

### 4. 新链路稳定后移除旧注册表路线

当 box 测试已经覆盖 `link()/bind()/bindAs()` 主链路，且 runtime 测试完全切到新模型后，再删除：

- `Registry`
- `registerGeneratedService`
- `Definition/Linker/Binder/Handler` 路线
- `RuntimeGlue` 旧入口

不建议长期双轨维护。

### 5. 最后再推进第二阶段 SPI

在第一阶段完成并稳定后，再进入多参数、基本类型、无 `@Serializable` 支持的 `ConvertFactory` / 序列化 SPI 设计与接入。

---

## 待决策项

1. `bind(implementation)` 的歧义策略  
   推荐：编译期只能在“唯一匹配契约”时自动替换；多契约实现直接编译报错，要求使用 `bindAs<T>()`。

2. Bridge 的具体形态  
   可选方案：
   - 方案 A：Bridge 自身实现 dispatcher，并提供 `link()` 工厂返回匿名代理；
   - 方案 B：Bridge 自身同时实现契约与 dispatcher，通过构造参数切换模式。  
   推荐优先 A，职责更清晰，也更贴近“Bridge 对用户不可见”。

3. Bridge 可见性下限  
   推荐：第一阶段采用 `internal class + private ctor/private helper`，确保不进入公开 API；后续再评估能否进一步收紧到更强不可见性。

4. action 是否允许未来支持重载  
   第一阶段不支持重载，保持 `fqcn#methodName` 简单稳定。若未来支持重载，再单独引入签名编码策略，而不是现在提前复杂化。

5. 第二阶段 SPI 的注入位置  
   推荐在 Bridge 生成代码中预留统一 marshalling 槽位，而不是散落到调用点替换逻辑里，避免 IR 改写与序列化策略强耦合。

6. 旧文件处理策略  
   推荐：第一阶段可先标记旧文件为过渡态；当新链路通过后，在同一收口迭代中成批删除，避免仓库长期同时存在两套真实入口。

---

## 结论

本次重构应以 `structs/wasmline_design_notes.md` 文末目标为唯一对齐标准，优先确保 `link()/bind()/bindAs()` 这条单 Bridge 主链路稳定；复杂序列化 SPI 继续后置。  
完成后，`wasmline-multiplatform/wasmline-kotlin-plugin` 的生成模型、`wasmline-multiplatform/wasmline/src/commonMain` 的 runtime 组织方式，以及 `testData/box` 的测试口径都将统一切换到“无反射、无全局注册表、用户零侵入、Bridge 对用户不可见”的最终方向。

