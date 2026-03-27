# Wasmline IR 工作记录

> 更新时间：2026-03-28  
> 用途：记录 typed service / compiler plugin / bridge 收口的当前真实状态。  
> 主设计文档：`structs/wasmline-design-v2.md`

---

## 0. 当前用户需求（本轮必须保持一致）

当前需求已经明确，不再接受“只是改名 / 换包 / opt-in”的伪隐藏：

1. 用户不应该在主使用路径中感知到 bridge 类型；
2. 独立 `:wasmline-spi` 已不是目标，当前方向是直接移除；
3. `BindingScope` / `Endpoint` / `ServiceDefinition` 这类桥接概念，不应继续作为用户心智的一部分；
4. 如果把 bridge 改成 Kotlin `internal` 后，用户侧体验确实更接近目标，但 IR / box 测试会报错，这个问题必须正面解释并记录；
5. 接下来真正要推进的是：**让生成物不再直接以 bridge 类型为主要 ABI 中心。**

---

## 1. 当前一句话结论

当前实现已经从“独立 `:wasmline-spi` 暴露 bridge”切到：

> **bridge 类型内嵌回 `:wasmline`，统一放到 `crow.wasmline.internal.bridge`，用户主 API 继续只保留 `Wasmline` / `bind` / `link`。**

但有一个必须明确记录的硬限制：

> **如果把这些 bridge 声明全部改成 Kotlin `internal`，当前 IR 生成会开始报错。**

原因不是插件逻辑偶发异常，而是 Kotlin 跨模块可见性规则本身不允许当前这种生成方式引用 runtime 模块的 `internal` 声明。

---

## 2. 当前真实状态

### 2.1 模块边界

当前真实状态：

- `:wasmline-spi` 已经从工程主链路移除
- bridge ABI 不再走 `crow.wasmline.spi.*`
- bridge 当前迁入：`crow.wasmline.internal.bridge.*`
- `:wasmline` 仍然是用户唯一主依赖面

也就是说，当前不再继续维护“用户依赖 `:wasmline`，typed bridge 另放 `:wasmline-spi`”这条路线。

### 2.2 bridge 当前放置位置

当前 bridge 相关类型在：

- `crow.wasmline.internal.bridge.ServiceDefinition`
- `crow.wasmline.internal.bridge.WasmlineEndpoint`
- `crow.wasmline.internal.bridge.WasmlineBindingScope`
- `crow.wasmline.internal.bridge.WasmlineActionHandler`
- `crow.wasmline.internal.bridge.WasmlineHostDispatcher`
- `crow.wasmline.internal.bridge.ServiceId`
- `crow.wasmline.internal.bridge.MethodId`
- `crow.wasmline.internal.bridge.Action`
- `crow.wasmline.internal.bridge.emptyPayload()`
- `crow.wasmline.internal.bridge.registerServiceDefinition(...)`
- `crow.wasmline.internal.bridge.unregisterServiceDefinition(...)`

### 2.3 用户主 API 仍然应该只有这些

对用户主叙事来说，应继续只保留：

- `Wasmline`
- `WasmlineService`
- `bind(...)`
- `bindAs<T>(...)`
- `link<T>()`
- `linkHost<T>()`（如保留）

用户不应该理解 bridge / definition / endpoint / binding scope / registry。

---

## 3. 当前已经完成的工作

### 3.1 compiler plugin 仍可工作

核心文件：

- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineIrGenerationExtension.kt`
- `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineRuntimeSymbols.kt`

当前已确认：

- 能扫描 `interface ... : WasmlineService`
- 能做 phase-one 校验
- 能生成：
  - `*_WasmlineDefinition`
  - `*_WasmlineProxy`
  - `*_WasmlineAdapter`
- `WasmlineRuntimeSymbols` 已切到 `crow.wasmline.internal.bridge`
- 定向 box fixture `echoProxyRoundTrip.kt` 已调整为“不在源码里直接 import bridge 类型，而通过反射验证生成物”

### 3.2 runtime glue 已切到内嵌 bridge 包

当前 runtime 关键文件都已从旧 `spi` 包切到 `internal.bridge`：

- `WasmlineRuntimeGlue.kt`
- `WasmlineServiceRegistry.kt`
- `WasmlineServices.host.kt`
- `WasmlineServices.wasmWasi.kt`
- `Wasmline.kt`
- `Wasmline.jni.kt`
- `Wasmline.ios.kt`
- `ServiceRegistration.kt`

### 3.3 测试路径已同步

当前两条关键验证路径都已打通过：

- runtime：`WasmlineServiceRuntimeTest`
- compiler plugin box：`JvmBoxTestGenerated.testEchoProxyRoundTrip`

注意：IR/FIR snapshot 测试第一次运行会生成/刷新 `*.fir.txt` 与 `*.fir.ir.txt`，第二次运行才稳定对比，这一点仍然成立。

---

## 4. 关键问题结论：为什么改成 `internal` 后 IR 测试会报错？

这是当前最重要的技术结论。

### 4.1 直接原因

当前插件生成的类会在**用户模块**里直接引用 bridge 类型，例如：

- `Proxy` 的字段类型会是 `WasmlineEndpoint`
- `Proxy` 构造函数参数会是 `WasmlineEndpoint`
- `Adapter.bind()` 参数会是 `WasmlineBindingScope`
- `Definition` 会实现 `ServiceDefinition<T>`

这些类型如果在 `:wasmline` 里被声明成 Kotlin `internal`，那么：

- 用户模块生成的 IR
- 不能合法引用 runtime 模块里的 `internal` 声明

于是 FIR2IR / IR validation 会直接报错，典型现象是：

- generated invalid IR
- references 'internal' declaration that is invisible in the current scope

### 4.2 本质原因

Kotlin 的 `internal` 是：

> **模块级可见性**

不是：

> 包级隐藏

所以即使包名是：

- `crow.wasmline.internal.bridge`

只要声明本身不是 Kotlin `internal`，外部模块仍然能看到它。反过来，只要它真的是 Kotlin `internal`，外部用户源码看不到，但 **compiler plugin 在外部模块里生成的代码也同样看不到。**

### 4.3 为什么 runtime test 有时不报，但 box / IR test 会报？

因为两者处在不同的“模块关系”里：

- `:wasmline` 自己的 `commonTest` / `jvmTest`
  - 与 runtime 模块关系更近
  - 某些内部 helper 更容易复用
- `wasmline-kotlin-plugin` 的 box fixture
  - 会模拟“独立用户模块”编译
  - 它最能暴露跨模块 `internal` 不可见的问题

所以你看到的“把 bridge 改成 internal 后用户看不到了，但 IR 测试开始报错”，其实正是在说明：

> **当前 bridge 仍然是生成代码跨模块 ABI 的一部分。**

---

## 5. 为什么 Zipline 能隐藏 `ServiceAdapter`，而 Wasmline 现在还不行？

### 5.1 Zipline 的关键点

Zipline 的 `ZiplineServiceAdapter` 虽然是 internal，但它不是当前这类“让用户模块生成类直接把 internal bridge 类型写进字段/参数/超类型”的用法。

Zipline 的思路更接近：

- internal adapter 留在 runtime 内部
- compiler plugin 在调用点做改写/补参
- 用户模块不把 adapter 类型作为常规 ABI 暴露出去

### 5.2 Wasmline 当前的差异

Wasmline 当前生成模型仍然是：

- 生成 `Definition / Proxy / Adapter`
- 并在这些生成物签名里直接使用 bridge 类型

因此当前 Wasmline bridge 仍然是“跨模块生成 ABI”的一部分。

只要这一点不改，bridge 类型就不能简单全部改成 Kotlin `internal`。

### 5.3 结论

所以当前状态下：

- 你把 bridge 改成 `internal`
  - 用户源码看不到，效果符合你想要的“隐藏”
  - 但 IR 测试会炸
- 原因不是测试框架怪，而是**当前 ABI 设计还没完全 Zipline 化**

---

## 6. 当前最强可落地结论

当前可以明确分成两层：

### 已经做到的

- 独立 `:wasmline-spi` 模块已被拿掉
- bridge 已收回 `:wasmline`
- bridge 包名已切到 `crow.wasmline.internal.bridge`
- 用户源码主路径已经不再需要直接 import 旧 SPI
- `echoProxyRoundTrip.kt` 已改为通过反射验证生成物，而不是源码直接依赖 bridge 类型

### 还没彻底做到的

- 还没有达到 Zipline 那种“bridge 类型本身完全不作为跨模块 ABI 出现”的程度
- 因此当前还不能把 bridge 全部安全地改成 Kotlin `internal`

---

## 7. 当前正在推进的下一步

当前最优先的代码方向已经不是“继续换名字 / 换包”，而是：

> **先让 runtime 的中心从 `ServiceDefinition` 直接收敛到更内部的 runtime entry，再继续削弱 bridge 作为主 ABI 的地位。**

这一层如果不先动，bridge 永远还是编译器生成物的核心类型。

也就是说，本轮真正开始做的是：

- 先缩小 bridge 在 runtime 里的中心性
- 再继续推进生成 ABI 脱离 `ServiceDefinition / Endpoint / BindingScope`

---

## 8. 当前文档维护结论

这份文件以后只记录：

- 当前真实实现状态
- 为什么某些方案会失败
- 下一步应该改哪一层

不再继续保留已经失效的这些叙述：

- `:wasmline-spi` 是当前主方案
- `crow.wasmline.spi.*` 是当前稳定 bridge 边界
- opt-in 隐藏是当前主方向

这些内容都已经过时。
