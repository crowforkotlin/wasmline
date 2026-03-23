# Wasmline IR 设计说明

## 文档状态

本文档基于当前仓库现状，整理 Wasmline 的项目定位、双向 RPC 结构，以及未来 Kotlin IR / 编译器插件层应承担的职责。

### 文档定位

建议 **保留本文件与 `ir.md` 两份文档，不合并**：

- `wasmline-ir-design.md`
  - 面向稳定的架构设计、术语定义、职责边界与中长期演进方向
  - 更新频率应低于工作日志
- `ir.md`
  - 面向当前实现状态、最近完成事项、下一步任务与交接说明
  - 允许更频繁地更新

简而言之：

> 本文件回答“为什么这样设计”；`ir.md` 回答“现在做到哪里了、下一步做什么”。

### 当前实现快照（2026-03-24）

截至目前，`wasmline-kotlin-plugin` 已经具备：

- `WasmlineService` contract 发现
- phase-one 约束校验
- `*_WasmlineDefinition` / `*_WasmlineProxy` / `*_WasmlineAdapter` skeleton 生成
- 基于 `testData/box` 的正式 IR box 测试路径
- `FIR_DUMP` 与 `DUMP_IR` 快照输出

当前仍未完成的重点，是把 adapter 的真实绑定逻辑补齐，并继续扩展更多正式 fixture 与 diagnostics 覆盖。

这份文档刻意保持在“架构设计”层，而不是直接绑定到某个具体实现细节。它重点回答以下问题：

- Wasmline 当前已经实现了什么；
- Wasmline 想演进成什么；
- 低层字符串调用是否应该保留；
- `contract`、`endpoint`、`proxy`、`adapter` 分别是什么意思；
- Kotlin IR 层应该做什么，不应该做什么。

---

## 1. 项目概览

### 1.1 Wasmline 是什么

Wasmline 是一个基于 **Wasmtime** 与 **Kotlin/WASI** 的 Kotlin Multiplatform 插件框架。

它当前的核心目标是：

- 加载并执行 WebAssembly 插件；
- 支持 Kotlin/WASI 插件，同时兼容其他能够编译到 WASI 的语言；
- 在 **宿主（host）** 与 **插件（plugin / wasm）** 之间建立 **双向 RPC 通信能力**。

从命名上看，Wasmline 明显受 Zipline 启发，但两者解决的问题并不相同：

- Zipline 主要是 **Kotlin ↔ JavaScript** 的 RPC；
- Wasmline 主要是 **Host ↔ WebAssembly/WASI** 的 RPC。

因此，Wasmline 不应该只被理解为“一个 Wasm 加载器”，更准确地说，它正在朝着一个 **面向服务的双向通信框架** 演进。

---

### 1.2 当前仓库中已经存在的层次

结合当前仓库代码，可以确认 Wasmline 已经包含以下三层：

#### 运行时与原生桥接层

- `wasmline-core/`：封装 Wasmtime、Session、Module、Api 等底层运行时逻辑；
- `wasmline-multiplatform/wasmline/`：暴露 Kotlin 侧公共 API；
- 平台桥接：
  - JVM / Android / Desktop 走 JNI；
  - iOS 走 C Interop。

#### 打包与分发层

- `wasmline-loader/`：manifest、签名、校验；
- `wasmline-cli/`：构建、编译、打包、下载等工具链。

#### 插件与编译器扩展层

- `wasmline-gradle-plugin/` 已存在；
- `wasmline-kotlin-plugin/` 已存在；
- Kotlin 编译器插件目前处于 **phase-one IR generation** 阶段：
  - 已能注册；
  - 已有 Zipline 风格的 IR helper；
  - 已接入 contract 发现、校验与 skeleton 生成；
  - 但 adapter 的完整绑定逻辑、更多 diagnostics 与更大范围的 typed glue 仍在演进中。

---

## 2. 当前已经验证的运行模型

### 2.1 Host 侧公共 API

当前 Host 侧最核心的公共 API 是 `Wasmline`：

- `Wasmline.load(...)`
- `Wasmline.init()`
- `Wasmline.release()`
- `wasmline.call(action, inputBytes)`
- `wasmline.setOutbound(dispatcher)`

这说明当前系统已经具备一个非常明确的低层通信抽象：

- `action: String`
- `payload: ByteArray`
- `result: ByteArray`

也就是说，**Wasmline 现在已经拥有通用的消息 RPC 通道**，只是目前还不是“面向服务接口”的 typed API。

---

### 2.2 Host → Wasm 调用链路

当前仓库中已经可以确认的 Host → Wasm 调用流程为：

1. Host 调用 `wasmline.call(action, payload)`；
2. 各平台绑定层将调用转发到 native 层；
3. native `Api::invokeInbound(...)` 获取或创建 `Session`；
4. `Session::invokeInbound(...)` 调用 Wasm 导出的入口函数；
5. Kotlin/WASI 中的 `WasmlineInitialize(...)` 从 Host 读取 action 与 payload；
6. `WasmRouter.dispatch(action, args)` 查找已注册处理器；
7. 结果通过 `WasmBridge.sendResult(...)` 回写给 Host。

这条链路已经说明：

> Host → Plugin 的“消息调用”能力已经存在。

---

### 2.3 Wasm → Host 调用链路

反向调用链路同样已经存在：

1. Kotlin/WASI 代码调用 `WasmBridge.callHost(action, payload)`；
2. Wasm 通过导入函数 `bridge_outbound_call_host` 进入宿主环境；
3. native `Session` 将调用转交给 `OutboundHandler`；
4. Kotlin Host 侧以 `WasmlineHostDispatcher.dispatch(action, payload)` 的形式暴露。

这说明：

> Wasmline 当前已经是一个**双向通信架构**。

缺少的不是通信本身，而是通信之上的 **类型化服务层**。

---

### 2.4 当前插件侧的注册方式

当前插件侧使用的是：

- `WasmRouter.register("timeSync") { bytes -> ... }`

这种方式已经能工作，但在服务函数很多时会暴露出明显问题：

- action 字符串全靠手写；
- 处理器分散；
- 缺少接口维度的分组；
- Host 与 Plugin 的契约很难集中表达；
- 当接口包含数十个方法时，维护成本会迅速上升。

这正是你希望通过 IR 层解决的问题。

---

### 2.5 低层字符串调用是否应该保留

这里我明确给出建议：**应该保留，而且应当作为 Wasmline 的开放底层能力长期存在。**

也就是说，未来 Wasmline 最好不是“只有 Service 模式”，而是分成两层：

#### 第一层：低层开放调用层

保留现在这种能力：

- Host 侧：`wasmline.call(action, bytes)`
- Plugin 侧：`WasmRouter.register(action) { ... }`
- Plugin 反向调用 Host：`WasmBridge.callHost(action, bytes)`

这层是最基础、最灵活、最自由的能力。

适合：

- 调试；
- 兼容旧逻辑；
- 动态 action 场景；
- 非 Kotlin 插件作者；
- 不想引入 service 约束的高级用户。

#### 第二层：面向服务的 typed API 层

在低层开放调用之上，再提供：

- `WasmlineService` 风格的接口契约；
- 自动生成 proxy / adapter；
- `link<T>()`、`bindServices { bind(...) }` 之类的高层体验。

这层是“为了提升工程性和可维护性”，而不是为了替代低层能力。

因此更准确的定位是：

> Service API 是对低层字符串通道的上层封装，而不是对低层能力的取代。

---

## 3. 核心术语说明

这一节专门解释你问到的两个关键词：`contract` 和 `endpoint`，并顺带把几个容易混淆的概念一起说明。

### 3.1 Contract 是什么

在 Wasmline 语境里，**contract（契约）** 最适合理解为：

> 跨通信边界共享的一份“服务接口定义”。

它强调的是“双方约定好能调用什么”，而不是“某一端具体怎么实现”。

例如一个日志服务接口：

- 有哪些方法；
- 方法参数是什么；
- 返回值是什么；
- 每个方法在 RPC 层如何被唯一标识。

这些内容共同构成 contract。

所以 contract 不是：

- 具体实现类；
- 一段 action 字符串；
- runtime 对象本身；
- native bridge。

它更接近于：

- 一个共享接口；
- 一组稳定方法签名；
- 一份跨端可识别的语义边界。

如果用一句话总结：

> `contract` = “双方都认可的一组可调用能力定义”。

---

### 3.2 Endpoint 是什么

在 Wasmline 语境里，**endpoint（通信端点）** 最适合理解为：

> 一个能够承载双向通信、导出本地服务、导入远程服务的通信实体。

当前项目里，最接近 endpoint 的对象就是某个 `Wasmline` 实例及其背后绑定的一组运行时能力。

你可以把 endpoint 理解成“一个通信口”。

例如：

- Host 侧的某个 `Wasmline(moduleKey)` 实例，可以视为一个 endpoint；
- 被加载的某个 Wasm 模块，从架构角度也可以视为通信另一端的 endpoint。

endpoint 关心的是：

- 这是谁在跟谁通信；
- 本地导出了哪些服务；
- 远程导入了哪些服务；
- 生命周期归谁管理；
- 这条连接何时关闭。

如果用一句话总结：

> `endpoint` = “承载 RPC 通信与服务注册/获取行为的一端”。

---

### 3.3 Service 是什么

**service** 是 contract 在架构中的实际角色表达。

例如：

- `LogService`
- `TimeService`
- `ConfigService`

它本质上应该是一个 **接口契约**，而不是实现类。

所以更准确地说：

- `service contract`：共享接口定义；
- `service implementation`：某一端的本地实现；
- `service proxy`：代表远端服务的本地代理对象；
- `service adapter`：把本地实现接入底层消息通道的桥接对象。

---

### 3.4 Transport 是什么

**transport（传输层）** 指的是消息如何从一端送到另一端。

在当前 Wasmline 中，它主要体现为：

- `action + ByteArray -> ByteArray`
- `Wasmline.call(...)`
- `WasmBridge.callHost(...)`
- native bridge、memory copy、Session 等底层机制

transport 解决的是“怎么送达”，不是“接口长什么样”。

---

## 4. 你现在实现的是不是 proxy

这个问题很关键，必须拆开讲。

### 4.1 如果按广义理解：你现在已经有“代理式转发”的雏形

如果把 proxy 理解得宽一点——即：

> 某个本地 API 并不直接做业务，而是把请求转交给远端执行。

那么当前确实已经存在一种“代理式调用模型”。

比如：

- Host 调 `wasmline.call("timeSync", bytes)`，实际上业务在 Plugin 里执行；
- Plugin 调 `WasmBridge.callHost("xxx", bytes)`，实际上业务在 Host 里执行。

从这个意义上说，当前系统已经具有一种“转发代理”的味道。

---

### 4.2 但如果按狭义、工程化的 typed proxy 理解：你现在还没有真正完成 proxy 层

如果这里说的 proxy 是指：

> 一个实现了某个服务接口的本地对象；
> 调用它的方法时，底层自动序列化参数并发到远端；
> 调用方感觉自己像在调用普通 Kotlin 接口。

那么当前项目**还没有真正实现这种 typed proxy**。

因为你现在的调用方式依然是：

- 手写 action 字符串；
- 手写参数编码/解码；
- 手写注册与分发。

这更接近于：

- 原始 RPC 通道；
- 消息路由层；
- 手动 dispatch 层。

而不是完整的 typed proxy 层。

---

### 4.3 当前更准确的说法

当前项目可以更准确地描述为：

#### 已经有：

- 双向 transport；
- 手动 action router；
- 基础代理式转发能力；
- endpoint 间的消息通道。

#### 还没有完全有：

- 基于接口 contract 的 typed proxy；
- 基于接口 contract 的 typed adapter；
- 自动生成的 service registry glue；
- `link<T>()` 背后的编译期生成体系。

所以如果你担心“proxy 这个词是不是说大了”，答案是：

> 现在不是完整的 typed service proxy，但已经具备 proxy-like 的基础通信形态。

---

## 5. Proxy、Adapter、Endpoint 三者之间的关系

为了避免后面越讨论越混，这里把三者关系一次说清楚。

### 5.1 Proxy

**proxy（代理）** 是“站在本地，代表远端服务的对象”。

它的职责是：

- 实现某个 service interface；
- 把本地方法调用转成底层 RPC 请求；
- 接收远端结果并还原成返回值。

调用方看到的是接口；
真正执行逻辑的是远端。

例如概念上：

- Host 侧拿到一个 `LogService`；
- 实际这个对象是远端 Plugin 服务的 proxy；
- 调 `logInfo(...)` 时，并不会本地打印，而是会发消息到 Plugin。

---

### 5.2 Adapter

**adapter（适配器）** 是“站在本地，负责把本地实现接到 RPC 通道上的对象”。

它的职责是：

- 持有一个真实本地实现；
- 接收来自远端的 `(methodId, payload)`；
- 找到对应方法；
- 解码参数；
- 调用本地实现；
- 编码返回值。

也就是说：

- proxy 面向“调出去”；
- adapter 面向“接进来”。

---

### 5.3 Endpoint

**endpoint（端点）** 是 proxy 和 adapter 的承载者。

它负责：

- 持有 transport；
- 管理导入的服务 proxy；
- 管理导出的服务 adapter；
- 处理生命周期。

所以三者关系可以概括为：

- endpoint：通信实体 / 容器；
- proxy：远端服务的本地代表；
- adapter：本地服务的远程入口。

---

### 5.4 套回 Wasmline 当前语境

在你当前项目语境下，可以先这样对应理解：

- `Wasmline`：未来最适合作为 Host 侧 endpoint API；
- Plugin 侧运行时：未来也可以抽象成一个对等 endpoint；
- `wasmline.call(...)` / `WasmBridge.callHost(...)`：底层 transport 调用；
- `WasmRouter.register(...)`：当前是手动 adapter 注册方式；
- 未来 IR 生成物：
  - 自动生成 proxy；
  - 自动生成 adapter；
  - 自动生成 registry glue。

---

## 6. 为什么 Service API 仍然值得做

既然低层字符串调用会保留，那为什么还需要 Service API？

答案是：**因为两层解决的是不同问题。**

### 6.1 低层调用解决“自由度”

低层 action 调用的优势是：

- 灵活；
- 不设限；
- 适合调试与实验；
- 适合非 Kotlin / 非 IR 场景；
- 适合动态 action。

它更像 Wasmline 的“汇编层能力”。

---

### 6.2 Service API 解决“工程可维护性”

当你的系统里出现：

- 一个接口几十个方法；
- Host 与 Plugin 两边都要对齐；
- 方法签名会不断演化；
- 需要统一注册、统一错误提示、统一生成 glue；

此时 Service API 的价值就很大：

- 契约集中；
- 方法不靠手写字符串；
- 可以自动生成；
- 可以做编译期校验；
- IDE 体验更自然。

所以更准确的说法不是“字符串调用和 Service 二选一”，而是：

> Wasmline 应该同时支持“低层自由调用”和“高层服务契约调用”。

---

## 7. 建议的服务模型

### 7.0 第一阶段 API 命名

为了避免 `export` / `get` 这类命名的歧义，第一阶段建议正式采用下面这组名称：

#### 低层 endpoint 调用：`invoke(action, payload)`

`invoke(...)` 是 endpoint 抽象上的统一低层调用入口。

它表达的是：

- 发起一次原始 RPC 请求；
- 输入仍然是 `action + ByteArray`；
- 输出仍然是 `ByteArray`；
- proxy 的底层最终都会落到这里。

注意：现有 `Wasmline.call(...)` 仍然可以继续保留为对外 low-level API；
而 `invoke(...)` 则是更抽象、更适合生成代码依赖的 endpoint 接口名。

#### 本地服务绑定：`bindServices { bind(...) }`

本地服务注册入口使用：

- `bindServices { bind(LocalImpl()) }`

如果需要显式指定 contract，则使用：

- `bindServices { bindAs<MyService>(LocalImpl()) }`

这里的语义是“把本地实现绑定到当前 endpoint 上”，比 `export` 更贴切，也避免未来语言关键字方向的潜在冲突。

#### 远程服务连接：`link<T>()`

远程服务获取入口使用：

- `link<MyService>()`

这里的语义是“与远端某个 contract 建立调用连接”。最终选择 `link<T>()`，正是因为它比 `get<T>()` 更能体现 RPC 语义，也避免被误解为普通容器取值或属性读取。

因此，第一阶段推荐的统一命名是：

- low-level transport：`invoke(...)`
- local service binding：`bindServices { bind(...) }`
- explicit contract binding：`bindAs<Contract>(implementation)`
- remote service access：`link<T>()`

### 7.1 核心建议

引入一个 marker interface，例如：

- `WasmlineService`

任何继承它的接口，都被视为一个 RPC contract。

注意，这里是“接口”，不是实现类。

例如概念上：

- `LogService`
- `TimeService`
- `ConfigService`

都可以是 contract。

---

### 7.2 `link<T>()` 最好基于接口，而不是实现类

这里我仍然建议：

- `wasmline.link<LogService>()`

比：

- `wasmline.link<LogImpl>()`

更合理。

原因是：

- RPC 边界关心的是 contract，不是本地实现类；
- 实现类属于本地细节，不适合穿透到远端获取模型中；
- 如果用实现类，后续很容易混淆“本地依赖获取”和“远端服务导入”。

因此建议统一思路：

- service interface = contract；
- implementation = 本地导出对象；
- proxy / adapter = IR 自动生成的 glue。

---

### 7.3 Host 与 Plugin 都应支持绑定本地服务 / 连接远程服务

因为 Wasmline 是双向 RPC，所以两边都应该是对称的：

#### 绑定本地服务

把本地实现暴露给对端调用。

#### 连接远程服务

从对端拿到一个 typed proxy，在本地像接口一样使用。

因此：

- Host 可以 `bindServices { bind(...) }` 绑定本地服务，也可以 `link<T>()` 连接远程服务；
- Plugin 也应该可以 `bindServices { bind(...) }` 绑定本地服务，也可以 `linkHost<T>()` 连接 Host 侧远程服务。

对称性非常重要，这样整个心智模型才不会裂开成两套体系。

---

### 7.4 第一阶段建议：Service 不强制引入 closeable

这一点我建议继续保持保守：

- `WasmlineService` 先只做 marker；
- 生命周期先绑定 endpoint；
- 先不要把 `Closeable` 混进所有 service。

因为在双向场景里，`close()` 非常容易语义失真：

- 是关闭 proxy？
- 还是注销远端服务？
- 还是关闭 endpoint？
- 还是释放模块？

第一阶段先不要把这些概念混在一起。

---

### 7.5 第一阶段 endpoint API 形状

第一阶段建议把 endpoint 设计成一个非常小的统一接口：

```kotlin
interface WasmlineEndpoint {
    fun invoke(action: String, payload: ByteArray): ByteArray
}
```

这个形状的优点是：

- 它直接承接 Wasmline 当前已经存在的 `action + ByteArray -> ByteArray` 通道；
- Host / Plugin / 本地测试都可以共享同一套抽象；
- proxy 只依赖它，不需要知道 Wasmtime、JNI、WASI 这些细节；
- 未来 IR 生成代码会非常稳定。

在第一阶段中：

- Host 侧 `Wasmline` 可以适配成 `WasmlineEndpoint`；
- Plugin 侧可以暴露一个 Host endpoint 包装 `WasmBridge.callHost(...)`；
- 测试环境可以使用内存版 endpoint。

---

## 8. Kotlin IR 层应该做什么

IR / 编译器插件最适合做的是“类型驱动、重复性高、样板极多”的部分。

### 8.1 校验 service contract

例如对所有 `WasmlineService` 接口做编译期约束检查：

- 必须是 interface；
- 方法是否 public；
- 是否允许重载；
- 是否允许属性；
- 返回值与参数类型是否在当前规则内；
- 是否存在不支持的继承结构。

---

### 8.2 生成稳定 service / method identity

例如：

- service id：接口全限定名；
- method id：基于稳定签名生成；
- 使用当前仓库里已有的 typeToString / signature / hash 思路。

---

### 8.3 生成 inbound adapter

对导出的本地服务实现，自动生成 adapter：

- 接收 `(methodId, payload)`；
- 解码参数；
- 调本地实现；
- 编码结果。

在 Plugin 侧，它可以替代大量手写 `WasmRouter.register(...)`；
在 Host 侧，它也可以替代手写 dispatcher mapping。

---

### 8.4 生成 outbound proxy

对导入的远程服务，自动生成 proxy：

- 实现接口；
- 序列化参数；
- 发起底层 transport 调用；
- 反序列化结果。

---

### 8.5 生成 typed lookup glue

如果最终你希望这样使用：

- `wasmline.link<MyService>()`

那么 IR 层应生成足够的 glue，让编译器知道：

- `MyService` 是 RPC contract；
- 该如何构造对应 proxy；
- 应该注入哪个 transport endpoint。

---

### 8.6 生成注册元数据

例如：

- service id；
- method id；
- dispatch table；
- adapter / proxy 的引用关系；
- 可能的 schema identity。

这样 runtime 就不需要用户手写并维护一堆 action 字符串映射。

---

### 8.7 第一阶段允许的 `WasmlineService` 语法范围

为了让第一阶段 IR 快速落地，建议先把 `WasmlineService` 约束在一个比较保守的语法子集里。

这里要特别强调：

> 这些限制是**第一阶段实现边界**，不是 Wasmline 长期产品边界。

也就是说，当前先限制，并不代表未来不支持；
它只是为了让第一版先把“contract 发现 → method id → proxy → adapter → runtime glue”整条链路稳定打通。

设计目标仍然应该是：

- 能支持的 Kotlin 接口语法，后续尽量逐步支持；
- 只有在 RPC 语义本身不清晰或生命周期极其复杂时，才长期保守。

#### 第一阶段允许

- `interface`；
- 继承 `WasmlineService`；
- `public` 普通成员函数；
- 非泛型方法；
- 非重载方法；
- 参数与返回值属于当前框架可编码类型；
- 可继承其他 service interface，但继承结构必须能被线性展开。

#### 第一阶段暂不支持

- 属性；
- `suspend`；
- 泛型接口；
- 泛型函数；
- 默认参数；
- `vararg`；
- service 作为参数或返回值；
- companion / static-like API 混入 contract；
- 依赖复杂对象句柄生命周期的远端引用语义。

第一阶段先把约束收紧，目标是先稳定打通：

- contract 发现；
- method id 生成；
- proxy 生成；
- adapter 生成；
- bind/link/runtime glue。

---

### 8.7.1 这些限制未来如何逐步放开

从长期设计上看，建议把这些限制分成三类：

#### A. 未来大概率应支持的语法

这类能力通常只是“工程实现成本高”，不是 RPC 语义本身有问题：

- **property**
  - 可以映射成 getter / setter；
  - method id 可以基于访问器签名稳定生成。

- **overload**
  - 不能只按方法名分发；
  - 但可以按完整签名 / signature hash 分发；
  - 你当前仓库里已有 signature / type-to-string / hash 工具，天然适合支持它。

- **default argument**
  - 远端调用本质上传的是“最终实参”；
  - 编译器插件可以在调用侧补默认值，或要求生成桥接层显式展开。

- **vararg**
  - 本质上可以当作数组参数处理；
  - 只是编解码层和签名层需要统一规则。

- **extension receiver**
  - 本质上可降级成第一个普通参数；
  - 只要生成规则稳定，就不是根本障碍。

#### B. 可以支持，但需要更谨慎设计的语法

这类能力不一定不能做，但会明显增加协议与代码生成复杂度：

- **generic contract / generic method**
  - 要么在编译期完全实化；
  - 要么强依赖 serializer / schema 推导；
  - 如果处理不好，method id 与跨端类型一致性会很难稳定。

- **suspend**
  - 技术上当然可以支持；
  - 但它会把当前同步 `invoke(action, payload)` endpoint 模型推进到异步协议层；
  - 一旦支持 `suspend`，proxy、adapter、endpoint、Host/Plugin 调用约定都要一起升级。

#### C. 最后再支持的高级能力

这类能力会直接牵涉到“远端对象语义”或“生命周期模型”：

- **service contract 作为参数或返回值**
  - 这本质上不是普通值传递，而是“远端对象引用/句柄传递”；
  - 一旦开放，就需要 remote handle、lease、release、callback routing 等整套机制。

因此，这类能力不建议过早进入第一阶段。

---

### 8.7.2 推荐的阶段化支持顺序

如果你的目标是“最终尽量全支持”，那我建议的顺序是：

#### Phase 1

- 普通 interface function
- link / bind / definition / proxy / adapter 跑通
- method id 与 registry 稳定

#### Phase 2

- property
- overload
- default argument
- vararg
- extension receiver

#### Phase 3

- generic contract / generic method
- suspend

#### Phase 4

- service 作为参数或返回值
- callback / remote handle / scoped reference / release model

这个顺序的好处是：

- 先解决“最核心、最稳定”的跨端接口调用；
- 再解决“语法糖层面”的 Kotlin 丰富语法；
- 最后再处理“分布式对象语义”这一类最复杂的问题。

---

### 8.8 第一阶段编译期生成物建议形状

第一阶段推荐为每个 contract 生成三类核心产物：

#### 1）Definition

例如：

- `LogService_WasmlineDefinition`

它负责：

- 暴露 `contract`；
- 暴露 `serviceId`；
- 提供 `link(endpoint)`；
- 提供 `bind(implementation, scope)`；
- 作为 runtime registry 的注册入口。

#### 2）Proxy

例如：

- `LogService_WasmlineProxy(endpoint)`

它负责：

- 实现 `LogService`；
- 把每个方法调用转成 `endpoint.invoke(action, payload)`；
- 反序列化结果并返回给调用方。

#### 3）Adapter

例如：

- `LogService_WasmlineAdapter(implementation)`

它负责：

- 接收 action 与 payload；
- 找到对应 method；
- 解码参数；
- 调用本地实现；
- 编码结果并绑定到 `WasmlineBindingScope`。

从概念上看：

- Definition 负责“统一描述与接线”；
- Proxy 负责“调出去”；
- Adapter 负责“接进来”。

---

## 9. Kotlin IR 层不应该做什么

### 9.1 不负责 transport 本身

IR 不应该负责：

- Wasmtime session 创建；
- native bridge；
- memory copy；
- module load / release；
- outbound handler 安装。

这些都属于 runtime / native 层。

---

### 9.2 不负责 endpoint 所有权模型

IR 不应该擅自决定：

- endpoint 谁拥有；
- session 什么时候释放；
- module 生命周期如何管理；
- 服务到底是单例还是 scope 对象。

这些是 runtime API 设计问题。

---

### 9.3 不应独断序列化策略

IR 可以配合某种序列化策略，但不应该在没有明确框架决策前“偷偷决定一切”。

例如未来可以有不同选择：

- 原始 `ByteArray`；
- `kotlinx.serialization`；
- protobuf-first；
- 自定义 codec；
- 注解驱动的序列化方案。

IR 可以适配，但不应越权成为唯一产品决策者。

---

## 10. 推荐的职责分层

### Runtime 层职责

- endpoint 生命周期；
- host/plugin 低层 transport；
- session 与 module 生命周期；
- registry 存储；
- outbound dispatcher 安装；
- 错误传播；
- 实际消息收发。

### IR / 编译器插件层职责

- service contract 校验；
- 稳定 ID 生成；
- proxy 生成；
- adapter 生成；
- typed lookup glue；
- 编译期诊断；
- 元数据生成。

### 用户职责

- 定义 service 接口；
- 提供本地实现；
- 决定导出哪些服务；
- 决定哪些场景继续使用低层字符串调用；
- 使用 typed API 或 low-level API。

---

## 11. 关于 closeable 的当前建议

这是整个设计里最容易踩坑的部分之一。

### 11.1 为什么现在不建议把 Closeable 混进 Service

因为一旦出现 `close()`，语义就会开始分裂：

1. 关闭本地 proxy 对象；
2. 注销远端 service；
3. 释放远端对象句柄；
4. 关闭整个 endpoint；
5. 释放 session 或 module。

这些含义完全不是一回事。

所以当前第一阶段建议是：

> Service 先不承担生命周期协议，生命周期优先绑定到 endpoint。

---

### 11.2 第一阶段推荐模型

第一阶段建议采用：

#### 每个 endpoint 上，每个 contract 对应一个远程服务视图

也就是说：

- `link<Service>()` 返回绑定到 endpoint 的一个 proxy；
- proxy 本身尽量轻量；
- endpoint 关闭时，其导入的 proxy 统一失效；
- 绑定到本地的 service 由 endpoint 或 registry 管理。

这个模型能最大限度地降低复杂度。

---

### 11.3 以后如果真需要更细粒度生命周期

以后如果你要支持：

- 回调 service 作为参数传递；
- 远端实例句柄；
- 租赁式 service；
- 按对象级别释放资源；

那时再引入：

- remote handle；
- leased proxy；
- scoped reference；
- explicit release。

这些更适合放到第二阶段甚至第三阶段。

---

## 12. 一个现实可行的第一阶段目标

为了让事情能稳稳落地，第一阶段 IR 建议只做：

- interface contract；
- 基于方法的 RPC；
- 每个 endpoint / contract 一个远程服务视图；
- typed `link<Service>()`；
- typed `bindServices { bind(...) }` / `bindAs<Contract>(...)`；
- 稳定 method id；
- 自动生成 adapter / proxy；
- 底层继续复用现有 `action + ByteArray` transport；
- 同时保留低层开放调用能力。

第一阶段最好先不做：

- 任意对象图；
- service 当作参数来回传；
- 回调对象透传；
- 每个 proxy 独立 close；
- 动态 service factory。

---

## 13. 这套设计如何套回当前仓库

当前仓库其实已经把“底座”搭得差不多了：

- Host → Plugin 调用已存在；
- Plugin → Host 调用已存在；
- action + payload 路由已存在；
- 各平台桥接已存在；
- module / session 生命周期已存在。

当前缺的主要是：

- 面向 contract 的 typed service 层；
- 自动生成的 proxy / adapter；
- 自动生成的 lookup / registration glue；
- 更完整的编译期约束与错误提示。

同时，当前 `wasmline-kotlin-plugin` 里也已经出现了明显信号：

- Zipline 风格 IR helper；
- signature hash；
- type render；
- 生成 IR declaration 的辅助工具。

这说明整个项目正处于一个很自然的过渡阶段：

> runtime 已经具备，typed IR service 层是下一步最合理的演进方向。

---

## 14. 当前推荐的关键设计决策

### 14.1 保留低层字符串调用

不要把 Service 模式设计成唯一入口。

Wasmline 最好同时保留：

- low-level action 调用；
- high-level service 调用。

---

### 14.2 让接口成为 RPC 边界

把接口 contract 当作远程能力的边界，而不是实现类。

---

### 14.3 让 `link<T>()` 表示“连接远端服务”

这样语义最清晰，不会和本地依赖注入混淆，也更符合 RPC 的连接语义。

---

### 14.4 本地服务注册使用 `bindServices { bind(...) }`

本地服务注册建议使用：

- `bindServices { bind(LocalImpl()) }`
- 若有歧义，则 `bindServices { bindAs<MyService>(LocalImpl()) }`

而不是 `export(...)`。

---

### 14.5 生命周期先绑 endpoint

先不要把 service closeable 复杂化。

---

### 14.6 用 IR 做生成与校验，不做 runtime ownership 决策

这样插件职责会更清晰，也更容易长期维护。

---

## 15. 建议的演进路线

### 第一阶段：typed service over current transport

- 定义 `WasmlineService`；
- 发现 contract 接口；
- 生成稳定 method id；
- 生成本地 adapter 与远端 proxy；
- 支持 typed `link<Service>()`；
- 支持 typed `bindServices { bind(...) }` / `bindAs<Contract>(...)`；
- 保留 low-level action 调用。

### 第二阶段：更完整的诊断与元数据

- 编译期错误提示；
- 定位到源码位置；
- 自动 registry metadata；
- 更好的调试能力。

### 第三阶段：高级生命周期与回调模型

- remote handle；
- scoped reference；
- explicit release；
- 若仍有需要，再支持 callback service 传递。

---

## 16. 后续最值得优先阅读的文件

### Runtime 与 transport

- `README.md`
- `wasmline-multiplatform/wasmline/src/hostMain/kotlin/crow/wasmline/Wasmline.kt`
- `wasmline-multiplatform/wasmline/src/hostMain/kotlin/crow/wasmline/WasmlineHostDispatcher.kt`
- `wasmline-multiplatform/wasmline/src/wasmWasiMain/kotlin/crow/wasmline/WasmRouter.kt`
- `wasmline-multiplatform/wasmline/src/wasmWasiMain/kotlin/crow/wasmline/WasmBridge.kt`
- `wasmline-multiplatform/wasmline/src/wasmWasiMain/kotlin/crow/wasmline/WasmMain.kt`

### Native core

- `wasmline-core/include/Session.h`
- `wasmline-core/include/OutboundHandler.h`
- `wasmline-core/include/Api.h`
- `wasmline-core/src/Session.cpp`
- `wasmline-core/src/Api.cpp`

### 编译器插件脚手架

- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineCompilerPluginRegistrar.kt`
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineCommandLineProcessor.kt`
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/ir.kt`
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/typeToString.kt`
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/SignatureHash.kt`

### 使用示例

- `wasmline-multiplatform/wasmline-sample/plugin/src/wasmWasiMain/kotlin/crow/wasmline/sample/Main.kt`
- `wasmline-multiplatform/wasmline-sample/multiplatform/shared/src/commonMain/kotlin/crow/mordecai/wasmline/sample/WasmLoader.kt`
- `wasmline-multiplatform/wasmline-sample/android/src/androidMain/kotlin/crow/wasmline/MainActivity.kt`

---

## 17. 最终结论

Wasmline 已经不只是一个 Wasm 运行时包装层。

它当前已经具备：

- Host ↔ Plugin 双向消息通信；
- 原始 action + ByteArray transport；
- 手动 router / dispatcher 模型。

下一阶段最重要的演进方向，是把这套能力提升为：

- 面向 contract 的；
- 面向接口的；
- 可由 IR 自动生成 glue 的；
- 同时保留低层开放调用能力的。

因此，当前最合适的定位是：

- **低层 transport 保留且长期开放；**
- **高层 service API 建立在 transport 之上；**
- **endpoint 作为生命周期与通信承载者；**
- **proxy 表示远端服务的本地代表；**
- **adapter 表示本地服务接入 RPC 的入口；**
- **IR 负责生成、校验、稳定标识与 glue；**
- **runtime 负责真正的通信与生命周期。**

这套模型既符合你当前项目的真实状态，也适合 Wasmline 后续逐步演进。

---

## 18. 如何测试当前 IR

当前阶段的 IR 还处于 **phase-one skeleton generation**，因此测试最好分成两层：

### 18.1 编译期测试：确认插件发现 contract 并生成骨架

当前仓库里已经放入一个最小模板：

- `wasmline-multiplatform/wasmline-sample/common/src/commonMain/kotlin/crow/wasmline/sample/ir/EchoService.kt`

这个模板故意只使用当前已支持的子集。你可以在本地把它取消注释，或复制到任意应用了 `crow.wasmline` 插件的模块中：

- `interface`
- 0 或 1 个 `ByteArray` 参数
- 返回 `ByteArray` 或 `Unit`

#### 你可以这样测试

从 `wasmline-multiplatform/` 目录执行：

```zsh
./gradlew :wasmline-sample:common:compileKotlinJvm
./gradlew :wasmline-sample:common:compileKotlinWasmWasi
```

如果编译器插件已经接入成功，你应该在编译输出里看到类似信息：

- `[Wasmline] compiler plugin registered`
- `[Wasmline] discovered service contract ...EchoService`
- `[Wasmline] generated definition skeleton EchoService_WasmlineDefinition ...`

这说明当前 IR 至少已经完成：

- contract discovery
- phase-one validation
- Definition / Proxy / Adapter skeleton generation

---

### 18.2 负例测试：确认校验真的生效

为了验证 IR 校验是否工作，你可以临时添加一个非法 contract，例如：

- 属性
- overload
- `suspend`
- 两个普通参数
- 非 `ByteArray` 参数

例如临时改成：

```kotlin
interface BadEchoService : WasmlineService {
    fun echo(a: ByteArray, b: ByteArray): ByteArray
}
```

然后重新编译：

```zsh
./gradlew :wasmline-sample:common:compileKotlinJvm
```

当前 phase-one 预期会报出类似错误：

- `Phase-one Wasmline generation currently supports at most one regular parameter.`

这说明 validator 生效正常。

---

### 18.3 运行时测试：当前能测什么，不能测什么

当前已经可以确认的运行时结构测试包括：

- `WasmlineServiceRuntimeTest`
- `bind(...)`
- `bindAs<Contract>(...)`
- `link<Contract>()`
- `WasmlineBindingScope.endpoint()`

但需要注意：

#### 当前还不能完整验证 typed end-to-end RPC

原因是：

- `Definition` 已生成；
- `Proxy` 已生成并开始接 `endpoint.invoke(...)`；
- `Adapter` 已生成骨架并接到 `Definition.bind()`；
- **但 Adapter 的 action 分发 / payload 编解码逻辑还没有全部实现完成。**

所以当前最适合的测试目标是：

1. 插件是否发现 contract；
2. 插件是否拒绝非法 contract；
3. 生成骨架是否成功进入编译流程；
4. 现有 runtime SPI 是否仍保持工作。

---

### 18.4 如果你想直接看生成结果

当前最现实的方法不是直接反编译目标类，而是：

1. 编译 sample/common；
2. 观察编译输出日志；
3. 结合后续逐步补全的 proxy/adapter 逻辑，再在 JVM 目标上查看编译产物。

后续当 `Definition.link()`、`Proxy`、`Adapter.bind()` 的真实逻辑全部补齐后，再增加：

- 端到端 typed service round-trip 测试；
- 直接断言生成类行为的编译器测试；
- 或编译后字节码/IR 产物检查。


