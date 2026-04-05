# Wasmline IR / Runtime / Platform V2 实施计划

> 面向生产上线准备的第二版实施计划。  
> 本文不直接落代码，而是先统一后续逐项实现的目标、边界、职责拆分与演进顺序。

---

## 1. 背景与问题定义

当前 Wasmline 已经具备可用的主链路：

- Host 侧通过 `Wasmline.load(...)` 获得模块实例；
- Kotlin IR 插件会生成 `*_WasmlineBridge` 并直接改写 typed entrypoint；
- Runtime 已围绕 `WasmlineEndpoint`、`WasmlineGeneratedBridge`、`bindGenerated(...)` 收敛；
- Sample 已经可以完成 host <-> wasm 的基础 RPC 往返。

但从“未来要用于生产上线”的标准看，当前实现仍存在明显的职责混杂与长期扩展风险：

1. `Wasmline` 同时承担了 **Engine 生命周期**、**Module 生命周期**、**加载策略**、**平台桥接**、**对外 API 承载** 多类职责。
2. `jniMain` / `iosMain` actual 实现中混入了过多高层策略，例如文件存在判断、缓存路径逻辑、AOT/JIT 回退流程。
3. Host 与 Plugin 未来若都需要长期演进，必须提前统一“平台侧使用模型”，避免一边实例式、一边全局式的割裂 API。
4. 当前 IR 主链路已经明显稳定，但 runtime 可见性收口、平台模型、实例生命周期命名仍不够清晰。
5. 未来会引入网络下载 `wlm` / manifest / cache / 签名校验，如果不先拆分核心结构，后续只会越来越混乱。

因此 V2 计划的核心目标不再只是“继续调 IR”，而是：

> 在保留当前可用主链路的前提下，先把 **平台职责**、**核心 runtime 实例模型**、**加载器职责**、**IR/runtime 边界** 全部理顺，再逐步把现有实现迁移到清晰的生产形态。

---

## 2. 本计划的目标

### 2.1 总目标

建立一套可长期演进的 Wasmline 生产级架构，使下列维度同时成立：

1. **平台职责清晰**：`jni / ios / kn` 只负责平台桥接，不负责高层加载策略与业务语义。
2. **核心对象模型清晰**：区分全局 engine、单模块 runtime handle、loader、source/artifact。
3. **Host / Plugin 使用模型统一**：把两者视为“两个平台”，尽量围绕一致的 `Wasmline` runtime handle 来使用。
4. **IR 主链路继续保持简洁**：IR 只负责校验、桥接生成、typed entrypoint 改写，不承担平台策略。
5. **公开 API 收口明确**：对业务开发者主要暴露 `WasmlineService` 与 `wasmline.bind/link/...` 这种核心能力；内部桥接层继续收口。
6. **为未来网络加载做好架构准备**：即使现在仍以本地文件为主，也要让加载模型天然支持远程 artifact。
7. **核心 API 默认不内置协程语义**：不把 `suspend` 与 `kotlinx-coroutines` 固化为 Wasmline 核心 ABI 的一部分。

### 2.2 关键设计原则

1. **Engine 与 Module 必须分层**。
2. **平台 bridge 不应承担 Loader 角色**。
3. **一个 `Wasmline` 实例只代表一个已就绪的 runtime/module handle**。
4. **Plugin 未来若要统一 API，不能和 Host 使用完全不同的心智模型**。
5. **IR 插件继续保持“单接口 -> 单 Bridge -> 调用点替换”的方向，不回退到 registry 时代**。
6. **核心库采取 sync-first 策略**：先定义同步核心 API，再把异步包装放到可选扩展层。

### 2.3 协程与 `suspend` 决策

V2 计划明确采用以下立场：

> **Wasmline 核心库默认不要求业务方使用协程，也不把 `suspend` 直接写进核心运行时 API。**

原因如下：

1. `WasmlineService` 的桥接 ABI 越简单越稳定；
2. IR 插件当前也仍以同步 contract 为主，继续保持非 `suspend` 更容易稳定；
3. 如果核心类、loader、bind/link 全部引入 `suspend`，等于把 `kotlinx-coroutines` 升级为框架强依赖；
4. 对生产库来说，调用方是否用协程应该是可选策略，而不是唯一使用方式；
5. 即使未来引入网络下载，也可以通过“同步核心 + 可选异步包装”来满足，而不必让底层 ABI 从一开始绑定协程语义。

因此本文后续所有目标 API 默认以**非 `suspend`** 版本为基线；如需协程友好支持，应在后续阶段通过附加模块或扩展层提供，例如：

- `wasmline-coroutines`
- `WasmlineAsyncLoader`
- `suspend` 扩展包装器

---

## 3. 非目标

本计划当前不直接包含以下内容：

1. 不在本轮立即完整实现网络下载、manifest 校验、签名下载链路。
2. 不在本轮立即支持任意 Kotlin 类型、泛型、`suspend`、默认参数、`vararg`、重载。
3. 不在本轮立即重写所有 iOS callback/native bridge；但会把其作为高优先级整改项列出。
4. 不在本轮立即强行实现所有平台 100% 对称 API；但会优先设计出最终统一方向。
5. 不在本轮手工修改 `testData/box/*.fir.txt` / `*.fir.ir.txt` 等生成快照。
6. 不在本轮把 `kotlinx-coroutines` 设定为 Wasmline 核心 API 的强制编程模型。

---

## 3.1 当前实施状态（截至 2026-04-06）

### 已完成

- [x] Host 核心 API 已切到 sync-first：`Wasmline.load(...)`、`call(...)`、`bind(...)`、`bindGenerated(...)` 不再把 `suspend` 固化进核心 ABI。
- [x] 生命周期命名基线已落地到代码：全局侧使用 `shutdown()`，实例侧使用 `close()`。
- [x] generated bridge runtime SPI 已收口到 `@PublishedApi internal`，避免为修复 IR 可见性而直接暴露用户 API。
- [x] platform actual 中重复的本地加载主流程已抽到共享 helper，`jniMain` / `iosMain` 不再各自维护完整的 AOT/JIT fallback 逻辑。
- [x] Plugin 侧已经引入 `Wasmline.current` 与实例式 `bind/link` 入口，顶层 `bind()` 退化为过渡代理。

### 尚未完成

- [ ] iOS callback 仍存在 `findAny()` 的生产 blocker，尚未切到可精确定位 runtime/module 的方案。
- [ ] `WasmlineEngine` / `WasmlineLoader` 仍主要停留在语义拆分与最小 helper 阶段，尚未形成完整独立对象模型。
- [ ] `WasmlineLoadRequest`、`WasmlineSource`、`WasmlineArtifact` 等远程加载抽象尚未落地。
- [ ] Plugin 侧仍保留顶层过渡入口，Host / Plugin 文档尚未完全统一为“先拿到 wasmline，再 bind/link”。

---

## 4. 当前现状总结

### 4.1 IR / 编译器插件

当前已经具备：

- `*_WasmlineBridge` 生成；
- `link()/bind()` typed entrypoint 改写；
- runtime helper 已围绕 `GeneratedBridge.kt`、`Endpoint.kt`、`bindGenerated(...)` 收敛；
- `WasmlineIrGenerationExtension.kt` 已完成拆分、注释补齐、局部去重。

当前判断：

- **IR 主链路已经从“可行性验证”阶段进入“收口与工程化”阶段**。
- 后续 IR 工作应以小步瘦身、可见性收口、runtime contract 稳定化为主，不建议再大幅重写主路径。

### 4.2 Runtime 公共 API

当前 Host 使用模型为：

```kotlin
val wasmline = Wasmline.load(...)
wasmline.bind(...)
wasmline.link<Service>()
```

当前 Plugin 使用模型为：

```kotlin
bind(object : SomeService { ... })
```

当前判断：

- 这种混合模型短期可用；
- 但长期会导致用户心智不统一；
- 若未来 Plugin 也要 `link()` Host 服务，这种“一边实例，一边顶层函数”的混合会越来越别扭。

### 4.3 平台 actual 实现

#### jniMain

`Wasmline.jni.kt` 当前同时承担：

- engine init/release
- module load
- cache 路径判断
- native JIT/AOT 调用
- cache 保存
- setOutbound
- call
- module release

问题：职责过多。

#### iosMain

`Wasmline.ios.kt` 当前也承担类似职责，并且存在一个生产级 blocker：

- 回调注册表中的 `findAny()` 方案无法正确定位 moduleKey；
- 多模块/多实例/并发场景下不可接受；
- 必须尽快改成“回调可明确定位当前模块”的方案。

#### kn / Native 其他平台

即使当前还未完整落地，也应遵守与 JNI/iOS 相同的职责边界：

- 只负责平台桥接；
- 不负责对外语义；
- 不负责高层加载策略。

---

## 5. 目标架构（V2）

V2 建议把 Wasmline 拆成四层。

---

### 5.1 `WasmlineEngine`：全局引擎层

#### 定位

进程级、平台级全局引擎对象。

#### 职责

1. 初始化底层 runtime engine；
2. 关闭底层 runtime engine；
3. 管理全局 engine 状态；
4. 作为 loader / runtime handle 的上游依赖；
5. 后续可承载全局 metrics、global registry、platform diagnostics 等。

#### 不负责

1. 不直接代表某个加载后的模块；
2. 不直接提供业务层 `bind/link`；
3. 不直接决定某个具体 artifact 的下载/缓存策略。

#### 建议 API 方向

```kotlin
object WasmlineEngine {
    fun init()
    fun shutdown()
    val isInitialized: Boolean
}
```

> 若第一阶段不想新增对象，也至少要在语义上把当前 `Wasmline.init()` / `Wasmline.release()` 当成 engine 级能力，并计划未来命名迁移为 `shutdown()`。

---

### 5.2 `Wasmline`：单模块 runtime handle

#### 定位

表示一个已加载、已可调用的 Wasmline runtime/module 实例。

#### 职责

1. 提供 `bind(...)`；
2. 提供 `link<T>()`；
3. 提供底层 `call(...)` 能力（internal 或更低层暴露）；
4. 管理当前模块实例生命周期；
5. 承载当前 runtime 上的附加 capability（如 converters、metadata、logger 等）。

#### 不负责

1. 不直接负责从网络/文件系统获取 artifact；
2. 不承担下载/cache fallback 策略；
3. 不代表进程级 engine 全局状态。

#### 建议 API 方向

```kotlin
interface Wasmline {
    fun <T : WasmlineService> link(): T
    fun bind(implementation: WasmlineService)
    fun <T : WasmlineService> bind(contract: KClass<T>, implementation: T)
    fun close()
    val isClosed: Boolean
}
```

#### 说明

- 当前实例 `release()` 建议未来统一迁移为 `close()`；
- `moduleKey` 应收口为 internal 实现细节，而不是核心 public data；
- 若仍保留 `Wasmline` 这个名字作为对外核心类，不建议再让它兼任 engine 静态全局语义。
- 若内部实现需要线程切换或异步调度，应由实现层自行处理，而不是要求核心用户 API 使用 `suspend`。

---

### 5.3 `WasmlineLoader`：加载器层

#### 定位

负责“如何把 source/artifact 转成 `Wasmline` 实例”。

#### 职责

1. 接收 load request；
2. 根据 source/artifact 类型选择加载路径；
3. 协调本地缓存；
4. 协调 AOT/JIT 选择；
5. 后续可扩展到网络下载、manifest、签名校验；
6. 最终调用平台 bridge 得到 runtime handle。

#### 不负责

1. 不暴露业务层 `bind/link`；
2. 不直接实现 native bridge；
3. 不承担业务路由逻辑。

#### 建议 API 方向

```kotlin
interface WasmlineLoader {
    fun load(request: WasmlineLoadRequest): WasmlineLoadState
}
```

#### 说明

- Loader 的核心语义仍然是“返回一个 load 结果”，不强制要求调用方使用协程；
- 若未来需要网络下载或后台线程切换，可通过可选异步 loader 包装层提供 `suspend` 或回调式 API；
- 平台实现内部可以使用线程池、队列或协程，但这不应泄漏为核心 API 形态。

---

### 5.4 `WasmlinePlatformBridge`：平台桥接层（internal）

#### 定位

由 `jniMain / iosMain / kn` actual 实现的底层桥接接口。

#### 职责

1. init engine
2. shutdown engine
3. load module from prepared local artifact/path
4. set outbound handler
5. invoke inbound action
6. release module
7. 必要的 native memory/callback 桥接

#### 不负责

1. 文件下载
2. URL 解析
3. 高层缓存命中策略
4. 对外生命周期命名语义
5. 业务 bind/link 使用模型

#### 建议 API 形态（internal）

```kotlin
internal interface WasmlinePlatformBridge {
    fun initEngine()
    fun shutdownEngine()
    fun loadModule(request: PreparedLoadRequest): PlatformModuleHandle?
    fun setOutbound(handle: PlatformModuleHandle, dispatcher: WasmlineHostDispatcher)
    fun invoke(handle: PlatformModuleHandle, action: String, payload: ByteArray): ByteArray
    fun releaseModule(handle: PlatformModuleHandle)
}
```

---

## 6. 实例模型与使用模型

### 6.1 Host 侧

Host 侧应继续走“显式获得实例”的路线：

```kotlin
val wasmline = loader.load(...)
wasmline.bind(...)
val service = wasmline.link<Service>()
```

原因：

- Host 天然持有“某个已加载模块句柄”；
- 允许多模块、多实例、多版本、热更新；
- 最符合工程直觉。

### 6.2 Plugin 侧

长期建议也统一成“围绕 `Wasmline` runtime handle 使用”，而不是继续混合顶层 `bind()` 与未来潜在的实例式 `link()`。

推荐目标语义：

```kotlin
val wasmline = Wasmline.current
wasmline.bind(...)
wasmline.link<HostService>()
```

#### 重要说明

这里的 `Wasmline.current`：

- 不是全局进程单例；
- 而是“当前 Wasmline 运行上下文中的实例句柄”；
- 其本质仍然是一个 `Wasmline` runtime handle，只是获取方式与 Host 不同。

### 6.3 为什么不建议长期混用？

不推荐长期保留如下割裂模型：

- Host：`wasmline.bind/link`
- Plugin：顶层 `bind()` / `link()`
- 或 Plugin：`WasmlineContext.current.link()` + 顶层 `bind()`

原因：

1. 用户心智不统一；
2. 文档难解释；
3. 后续 converters / metadata / session state 等能力无法优雅挂载；
4. 生产级 API 不应让一半功能像“全局函数”，另一半又像“实例能力”。

---

## 7. `Wasmline.current` 后续应承载什么？

如果未来选择 Plugin 侧提供 `Wasmline.current`，则该对象应承载的是 **runtime capability hub**，而不是杂乱的全局配置对象。

### 建议承载能力

1. `bind(...)`
2. `link<T>()`
3. `close()` / 生命周期状态（如适用）
4. `converters` / `codec` capability
5. `logger` / diagnostics capability
6. `metadata` / 当前 runtime 信息

### 不建议直接平铺的能力

1. 大量裸 setter，例如 `setConvertFactory(...)`
2. 与当前 runtime 不相关的静态工具
3. 进程级全局 engine 配置

### 推荐风格

```kotlin
wasmline.bind(...)
wasmline.link<Service>()
wasmline.converters.install(...)
wasmline.metadata
```

而不是：

```kotlin
wasmline.bind(...)
wasmline.link<Service>()
wasmline.setConvertFactory(...)
wasmline.setLogger(...)
```

---

## 8. 加载策略的演进路线

### 8.1 当前阶段

当前阶段仍允许以本地路径加载为主：

- `.wasm`
- `.cwasm`

### 8.2 未来阶段

未来会扩展为：

- 下载 `.wlm`
- 解析 manifest / metadata
- 校验签名
- 提取 wasm / compiled artifact
- 使用缓存
- 最终生成 `Wasmline` 实例

### 8.3 因此现在必须做的准备

即使当前只支持本地，也要先把这两个概念抽出来：

1. `WasmlineLoadRequest`
2. `WasmlineSource` / `WasmlineArtifact`

建议示意：

```kotlin
sealed interface WasmlineSource {
    data class LocalFile(val path: String) : WasmlineSource
    data class RemoteUrl(val url: String) : WasmlineSource
    data class LocalPackage(val path: String) : WasmlineSource // .wlm
}
```

这一步的目的不是立刻支持远程，而是防止未来又把“下载逻辑”继续塞回 `Wasmline` 本体。

---

## 9. IR / Runtime 的边界要求

### 9.1 IR 继续负责什么

IR 插件继续负责：

1. 发现并校验 `WasmlineService` contract；
2. 生成 `*_WasmlineBridge`；
3. 直接改写 `link()/bind()` typed entrypoint；
4. 使用稳定 action 规则；
5. 保持 bridge/runtime helper 的最小依赖面。

### 9.2 IR 不负责什么

IR 不应该继续承担：

1. 平台资源管理；
2. 加载策略；
3. engine 生命周期；
4. 下载/缓存策略；
5. runtime current 实例获取策略。

### 9.3 继续收口方向

当前仍建议继续推进：

1. runtime 中非必要 public API 审计；
2. internal helper 继续收口；
3. 保持 `WasmlineGeneratedBridge` 等 bridge 协议层仅作为内部 runtime contract；
4. 不建议为了“纯净”而把所有 helper 全内联进 IR。

---

## 10. 平台层职责清单

### 10.1 JNI 平台

#### 负责

- native library load
- engine init/shutdown
- local file based module load
- module release
- outbound handler registration
- inbound invoke
- native cache save/load bridge

#### 不负责

- 高层 `Wasmline.load` 策略聚合
- URL 下载
- manifest 解析
- 用户级 API 语义命名

#### 必做整改项

1. 删除 `testAAA()`；
2. 逐步把 `load(...)` 中的高层策略迁移到 loader 层；
3. 区分 instance close 与 engine shutdown 命名；
4. 收口 `moduleKey` 可见性。

### 10.2 iOS 平台

#### 负责

- engine init/shutdown
- local module load
- outbound callback 桥接
- inbound invoke
- module release

#### 生产 blocker

必须移除当前 `findAny()` callback 派发方案，改为：

- callback 能定位到准确 module/runtime handle；
- 多模块、多实例并发场景下完全正确；
- 不允许“随便取一个 dispatcher”的临时实现进入生产。

### 10.3 KN / Native 其他平台

当前即使尚未完整落地，也必须从设计上遵守与 JNI/iOS 相同的分层边界。

---

## 11. API 命名收口建议

### 当前存在的问题

- companion `release()`：语义上是 engine release
- instance `release()`：语义上是 module release

这在生产 API 中不应继续保留。

### 建议命名

#### 全局级

```kotlin
WasmlineEngine.shutdown()
```

若短期不拆类，也至少计划迁移为：

```kotlin
Wasmline.shutdown()
```

#### 实例级

```kotlin
wasmline.close()
wasmline.isClosed
```

### 说明

- `close()` 对当前实现而言，本质就是 `nativeReleaseModule(moduleKey)`；
- 之所以建议命名迁移，是为了明确它是“实例 handle 生命周期”的能力，而不是底层 native 释放细节。

---

## 12. 分阶段实施计划

### Phase 0：生产 blocker 与命名风险先收口

#### 目标

先解决最危险、最容易阻碍后续演进的问题。

#### 任务

1. [x] 删除 `testAAA()` 等无效入口；
2. [ ] 把 iOS callback 的 `findAny()` 替换为可准确定位 runtime 的方案；
3. [x] 清理明显不合理的 public/internal 暴露；
4. [x] 为 instance/global lifecycle 建立新的命名目标，并已在当前代码中落到 `shutdown()` / `close()` 基线。

#### 完成标志

- 不再存在生产 blocker 级 callback 定位问题；
- 生命周期语义在文档和代码中不再混淆。

---

### Phase 1：先拆 Engine / Loader / Module 语义

#### 目标

把现有 `Wasmline` 全能类拆出最基本的三层语义。

#### 任务

1. [ ] 抽出独立的 `WasmlineEngine` 对象；
2. [~] 抽出 `WasmlineLoader`：已落地最小共享 loader helper，但尚未形成完整独立 loader 对象。
3. [~] 把 `Wasmline` 明确定位为单模块 runtime handle：语义与实例生命周期已明显收口，但 engine / loader 静态语义仍挂在同名类型上。
4. [x] 把 `load(...)` 中的文件存在判断、cache fallback 等高层逻辑从 platform actual 中抽到共享加载流程。

#### 完成标志

- `jniMain / iosMain` actual 不再直接承担完整 loader 策略；
- `Wasmline` 本体不再同时管理 engine 与 loader 语义。

---

### Phase 2：统一 Host / Plugin 使用模型

#### 目标

使两侧都围绕一致的 `Wasmline` runtime handle 使用，而不是长期混合顶层函数与实例函数。

#### 任务

1. [x] 设计并落地 Plugin 侧 `Wasmline.current` 的获取方式；
2. [x] 明确其语义是“当前上下文中的 runtime instance”，不是进程级全局单例；
3. [~] 让 Plugin 侧最终也以 `wasmline.bind/link` 为主：实例入口已存在，样例已接入，但顶层过渡入口仍保留。
4. [x] 已把顶层 `bind()` 视为过渡 API，并让其委托到 `Wasmline.current`。

#### 完成标志

- Host / Plugin 文档可以统一描述为：先获得 `wasmline`，再进行 bind/link。

---

### Phase 3：为网络加载建立抽象层

#### 目标

在不要求立刻实现远程下载的前提下，把数据模型先搭起来。

#### 任务

1. [ ] 定义 `WasmlineLoadRequest`；
2. [ ] 定义 `WasmlineSource` / `WasmlineArtifact`；
3. [ ] 设计 cache / manifest / signature 的扩展插槽；
4. [ ] 让 loader 能同时支持本地与未来远程来源。

#### 完成标志

- 当前本地加载仍可工作；
- 未来远程加载不需要推翻现有对象模型。

---

### Phase 4：IR / Runtime 最终收口

#### 目标

在平台与核心模型清晰后，再继续做最后一轮 runtime/IR 收口。

#### 任务

1. [~] 再审计 runtime 非必要 public API：generated bridge SPI 已完成一轮 `@PublishedApi internal` 收口，但尚未完成最终审计。
2. [ ] 评估 top-level `bind()` 等过渡入口是否继续保留；
3. [x] bridge/runtime helper 已按当前阶段要求保持 `internal` 收口，并通过 `@PublishedApi` 解决跨模块 IR 可见性。
4. [~] 仅做低风险 IR 继续瘦身，不再大改主链路：当前已进入该方向，但尚未完成最终收口。

#### 完成标志

- 对业务开发者的公开 API 面稳定；
- 内部 bridge/runtime helper 不再继续扩散。

---

## 13. 风险清单

### 13.1 过早引入大量新对象名，导致迁移成本过高

解决方案：

- 先以语义拆分为主；
- 必要时允许短期“文档上先区分，代码上逐步迁移”。

### 13.2 Plugin 侧 `current` 语义做成假单例

解决方案：

- 明确 `current` 表示“当前上下文实例”；
- 避免误导为全局唯一 runtime。

### 13.3 平台 actual 继续偷偷承载高层策略

解决方案：

- 明确 code review 标准：platform actual 只负责 bridge，不负责 loader policy。

### 13.4 iOS callback 桥改造风险

解决方案：

- 先把模块定位方案设计清楚；
- 再改 callback bridge；
- 这项不应被后置到最后。

---

## 14. 验证策略

### 14.1 架构层验证

1. 任一平台 actual 文件中，不应再出现完整高层 loader fallback 策略；
2. `Wasmline` 不再同时暴露 engine lifecycle 与 module lifecycle 的同名 `release()` 语义；
3. 文档可清晰解释 Host / Plugin 获取 `wasmline` 的方式。

### 14.2 编译与 IR 验证

继续沿用当前已稳定的验证链：

```bash
export JAVA_HOME="/Users/crowforkotlin/WuYa/tools/jbrsdk_jcef-21.0.9-osx-aarch64-b1163.94/Contents/Home"
cd /Users/crowforkotlin/github/wasmline/wasmline-multiplatform
./gradlew --no-daemon :wasmline-kotlin-plugin:compileKotlin
./gradlew --no-daemon :wasmline-kotlin-plugin:publishToMavenLocal
./gradlew --no-daemon :wasmline-sample:plugin:compileProductionLibraryKotlinWasmWasiOptimize
./gradlew --no-daemon :wasmline:jvmTest
```

### 14.3 平台专项验证

后续需要补充：

1. JNI 多模块加载/释放验证；
2. iOS 多模块 callback 正确分发验证；
3. Plugin current runtime 获取与 bind/link 路径验证。

---

## 15. 第一批建议实现项（按优先级）

### P0

1. 修 iOS callback module 定位问题；
2. 删除明显的临时代码与无效入口；
3. 梳理生命周期命名：`close` / `shutdown` 目标语义。

### P1

4. 抽出 `WasmlineLoader` 设计与最小实现；
5. 将 `Wasmline.load(...)` 的高层流程迁移到 loader；
6. 让 platform actual 收敛为纯 bridge。

### P2

7. 设计 Host / Plugin 统一 runtime handle 模型；
8. 明确 `Wasmline.current` 的语义与边界；
9. 规划顶层 `bind()` 过渡策略。

### P3

10. 定义远程 source / artifact 抽象；
11. 预留 converters / metadata capability 入口；
12. 做最后一轮 runtime public API 收口。

---

## 16. 结论

V2 的重点不是继续把更多逻辑塞进现有 `Wasmline`，而是：

> **先把平台桥接、核心 runtime handle、loader、engine 生命周期、Plugin/Host 使用模型理顺，再继续往生产化方向演进。**

对于后续实现，应始终遵守以下判断标准：

1. 这个职责是 engine 级，还是 module 级？
2. 这个逻辑应该在 loader，还是 platform bridge？
3. 这个 API 是给业务开发者用的，还是给 IR/runtime 内部用的？
4. Host 与 Plugin 是否还能围绕统一的 `Wasmline` runtime handle 来理解？

只要这四个问题持续回答清楚，后续逐项实现时就不容易再次回到“能跑但越来越混乱”的状态。

