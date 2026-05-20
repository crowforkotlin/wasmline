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
2. ~~`jniMain` / `iosMain` actual 实现中混入了过多高层策略，例如文件存在判断、缓存路径逻辑、AOT/JIT 回退流程。~~ → **已解决**：两平台均已收窄为纯 bridge 层，高层加载由 `WasmlineRuntimeLoader` 统一处理。
3. Host 与 Plugin 未来若都需要长期演进，必须提前统一"平台侧使用模型"，避免一边实例式、一边全局式的割裂 API。
4. ~~当前 IR 主链路已经明显稳定，但 runtime 可见性收口、平台模型、实例生命周期命名仍不够清晰。~~ → **已部分解决**：生命周期命名已对齐 `shutdown()` / `close()`；generated bridge SPI 已收口到 `@PublishedApi internal`。
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

### 2.4 当前运行时加载约束（2026-04-07 更新）

V2 当前阶段新增一条硬约束：

> **Host/runtime 侧移除 JIT 加载路径，后续仅接受预编译 artifact。**

也就是说：

1. runtime 侧不再直接加载 `.wasm` 源文件；
2. 当前允许的本地产物类型收口为 `.cwasm` 与 `.pwasm`；
3. `PWASM`（Pulley）与 `CWASM`（AOT）成为后续 loader / manifest / runtime 的唯一 artifact 方向；
4. 旧的 `cache fallback -> JIT compile -> save cache` 流程应逐步从 runtime、JNI、native bridge、文档与样例中移除；
5. Host loader 后续负责“选择哪个 artifact”，而不是把 `.wasm` 直接交给 runtime 即时编译。

---

## 3. 非目标

本计划当前不直接包含以下内容：

1. 不在本轮立即完整实现网络下载、manifest 校验、签名下载链路。
2. 不在本轮立即支持任意 Kotlin 类型、泛型、`suspend`、默认参数、`vararg`、重载。
3. 不在当前 `Windows` 工作阶段执行 iOS callback/native bridge 的本地实现与验证；待切换到可用的 `macOS/iOS` 环境后恢复，并继续作为独立整改项保留。
4. 不在本轮立即强行实现所有平台 100% 对称 API；但会优先设计出最终统一方向。
5. 不在本轮手工修改 `testData/box/*.fir.txt` / `*.fir.ir.txt` 等生成快照。
6. 不在本轮把 `kotlinx-coroutines` 设定为 Wasmline 核心 API 的强制编程模型。

---

### 当前执行策略（Windows 环境）

当前阶段的执行顺序调整为：**先推进不依赖 Apple 平台的后续计划，再在环境切换后回补 iOS 专项事项。**

具体约束如下：

1. 当前机型上不强行推进 `iosMain` 本地实现、联调与回归验证。
2. 优先推进 `WasmlineLoader`、`Wasmline.current` 使用模型统一、`WasmlineLoadRequest` / `WasmlineSource` / `WasmlineArtifact` 抽象，以及 runtime / IR 收口。
3. JNI、Host、Plugin、文档与架构层整理继续按主计划推进。
4. iOS 相关 blocker 仍保留在计划中，但状态记为“环境暂缓”，避免和“已完成”混淆。

---

## 3.1 当前实施状态（截至 2026-04-07）

### 已完成

- [x] Host 核心 API 已切到 sync-first：`Wasmline.load(...)`、`call(...)`、`bind(...)`、`bindGenerated(...)` 不再把 `suspend` 固化进核心 ABI。
- [x] 生命周期命名基线已落地到代码：全局侧使用 `shutdown()`，实例侧使用 `close()`。`jniMain` 和 `iosMain` 均已对齐（`init()` / `shutdown()` / `close()`）。
- [x] generated bridge runtime SPI 已收口到 `@PublishedApi internal`，避免为修复 IR 可见性而直接暴露用户 API。
- [x] platform actual 中重复的本地加载主流程已抽到共享 helper：`jniMain` 和 `iosMain` 均已统一委托 `WasmlineRuntimeLoader.load()` + `WasmlinePlatformLoader` 接口，不再各自维护完整的加载策略。
- [x] iOS `load()` 已与 JNI 完全对齐：通过 `WasmlinePlatformLoader` 提供 `fileExists`、`loadPrecompiled`、`createWasmline` 等回调，实际加载判断（文件存在检查、后缀校验、code 映射）全部由共享的 `WasmlineRuntimeLoader` 完成。
- [x] Plugin 侧已经引入 `Wasmline.current` 与实例式 `bind/link` 入口，顶层 `bind()` 退化为过渡代理。
- [x] Runtime 侧已移除 JIT 加载路径，`WasmlineRuntimeLoader.loadLocal()` 仅接受 `.cwasm` / `.pwasm` 预编译产物。

### 尚未完成

- [ ] iOS callback 仍存在 `findAny()` 的生产 blocker。**根因已明确**：C 层 `OutboundCallback` 函数签名为 `char* (*)(action, actionLen, payload, payloadLen)`，不携带 `key` 参数；而 Kotlin/Native 的 `staticCFunction` 不允许捕获上下文，导致回调触发时无法识别来源模块。详见下方 §10.2 分析。当前因 `Windows` 环境暂缓本地实现与验证，待切换到 `macOS/iOS` 环境后恢复处理。
- [ ] `iosStaticOutboundCallback` 当前仍为 TODO 占位实现（始终返回 `null`），尚未真正分发到 `WasmlineHostDispatcher`。
- [~] `wasmline` runtime 内部已存在共享的本地文件加载 helper（`WasmlineRuntimeLoader`），JNI 和 iOS 均已接入；Host 侧 `WasmlineLoader` 已形成独立入口，但更完整的 public API 收口仍待继续推进。
- [x] `LocalPackageFile` / `RemotePackageUrl` 已补自定义 resolver 扩展点，不再只能立刻 fail-fast；后续仍需继续接上 cache / manifest / signature 主链路。
- [ ] `WasmlineLoadRequest`、`WasmlineSource`、`WasmlineArtifact` 等 Host 级加载抽象仍应落在 `wasmline-loader` 模块，尚未完成正式迁移与公开 API 收口。
- [ ] Plugin 侧仍保留顶层过渡入口，Host / Plugin 文档尚未完全统一为"先拿到 wasmline，再 bind/link"。

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

#### jniMain（2026-04-07 更新）

`Wasmline.jni.kt` 当前职责已收窄为纯平台桥接层：

- engine init/shutdown（委托 `nativeInit()` / `nativeReleaseEngine()`）
- `load()` 完全委托 `WasmlineRuntimeLoader`，自身仅提供 `WasmlinePlatformLoader` 回调
- setOutbound
- call（inbound invoke）
- module release（`close()` → `nativeReleaseModule()`）

**已不再直接包含**：cache 路径判断、JIT/AOT fallback、文件存在检查等高层逻辑。

#### iosMain（2026-04-07 更新）

`Wasmline.ios.kt` 的 `load()` 已与 JNI 完全对齐——同样委托 `WasmlineRuntimeLoader.load()` 并通过 `WasmlinePlatformLoader` 提供平台回调（`NSFileManager.fileExistsAtPath` / `wasmline_load_module`）。

**已完成的收口**：

- 高层加载策略（文件判断、后缀校验、code 映射）已由共享 `WasmlineRuntimeLoader` 统一处理；
- 生命周期命名已对齐：`init()` → `wasmline_init_engine()`、`shutdown()` → `wasmline_release_engine()`、`close()` → `wasmline_release_module()`；
- JIT/cache fallback 已移除，仅通过 `wasmline_load_module(key, path, isUnsafe)` 加载预编译产物。

**仍存在的生产级 blocker**：

1. `WasmlineCallbackRegistry` 中的 `findAny()` 派发方案无法正确定位 moduleKey；
2. `iosStaticOutboundCallback` 仍为 TODO 占位实现（始终返回 `null`），未真正把回调分发给 `WasmlineHostDispatcher`；
3. **根因**：C 层 `OutboundCallback` 签名为 `char* (*)(action, actionLen, payload, payloadLen)`，不携带 `key`；而 `staticCFunction` 不能捕获上下文，导致 Kotlin 回调端无法识别来源模块；
4. 多模块 / 多实例 / 并发场景下不可接受。

> 此 blocker 的解决需要修改 C 层接口（例如在 `OutboundCallback` 签名中新增 `key` 参数）或采用其他模块定位机制，属于 **环境暂缓** 项，待 macOS/iOS 环境恢复后优先处理。

#### kn / Native 其他平台

即使当前还未完整落地，也应遵守与 JNI/iOS 相同的职责边界：

- 只负责平台桥接；
- 不负责对外语义；
- 不负责高层加载策略。

---

## 5. 目标架构（V2）

V2 建议把 Wasmline 拆成四层。

---

### 5.1 已放弃：独立 `WasmlineEngine`

#### 定位

该公开拆分类方案已于 2026-05-20 放弃。

#### 职责

保留原因很简单：当前公开新增 `WasmlineEngine` 的收益不够高，反而会扩大 API 面和 sample / 依赖同步成本。现阶段继续保留 `Wasmline.init()` / `Wasmline.shutdown()` 这一现有模型，只在实现和文档层面维持职责边界。

#### 不负责

后续不再把 engine 级能力拆成新的公开类型，而是把重点转到 loader 数据链路、runtime public API 收口、以及 Host / Plugin 使用模型统一。

#### 建议 API 方向

> 2026-05-20 更新：独立 `WasmlineEngine` 方案已放弃，不再作为后续公开 API 演进方向。

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

### 8.1 当前阶段（2026-04-07 更新）

当前阶段以本地预编译产物路径加载为主，仅支持：

- `.cwasm`（AOT 预编译）
- `.pwasm`（Pulley 预编译）

> `.wasm` 源文件已不再被 runtime 接受（参见 §2.4 运行时加载约束）。

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

### 10.1 JNI 平台（2026-04-07 更新）

#### 负责

- native library load
- engine init/shutdown
- local file based module load（通过 `WasmlinePlatformLoader` 提供 native 桥接能力）
- module release
- outbound handler registration
- inbound invoke

#### 不负责

- 高层 `Wasmline.load` 策略聚合（已由 `WasmlineRuntimeLoader` 统一承担）
- URL 下载
- manifest 解析
- 用户级 API 语义命名

#### 整改项状态

1. [x] 删除 `testAAA()` 等无效入口；
2. [x] `load(...)` 中的高层策略已迁移到 `WasmlineRuntimeLoader`，JNI 层仅通过 `WasmlinePlatformLoader` 提供 `fileExists` / `loadPrecompiled` 回调；
3. [x] instance close 与 engine shutdown 命名已区分：`shutdown()` vs `close()`；
4. [ ] `moduleKey` 可见性收口尚未最终完成（仍为 public constructor 参数）。

### 10.2 iOS 平台（2026-04-07 更新）

> 当前说明：由于现阶段工作环境为 `Windows`，本节中的实现与验证任务暂不在本机执行；待切换到可用的 `macOS/iOS` 环境后恢复。

#### 负责

- engine init/shutdown
- local module load（已委托 `WasmlineRuntimeLoader`，自身仅提供 `WasmlinePlatformLoader` 回调）
- outbound callback 桥接
- inbound invoke
- module release

#### 已完成整改

- [x] `load()` 已统一委托 `WasmlineRuntimeLoader`，不再自行维护高层加载逻辑；
- [x] 生命周期命名已对齐 `init()` / `shutdown()` / `close()`；
- [x] JIT/cache fallback 逻辑已移除。

#### 生产 blocker：iOS Outbound Callback 模块定位

**当前状态**：`iosStaticOutboundCallback` 始终返回 `null`（TODO 占位），`WasmlineCallbackRegistry.findAny()` 无法正确定位模块。

**根因分析**：

1. C 层 `OutboundCallback` 函数签名为：
   ```c
   typedef char* (*OutboundCallback)(const char* action, size_t actionLen, const char* payload, size_t payloadLen);
   ```
   该签名 **不携带 `key`**（moduleKey）参数。

2. C++ 侧 `wasmline_set_outbound_handler(key, callback)` 会为每个 key 创建独立的 `IosOutboundHandler` 实例，各自持有同一个 `kotlinCallback` 函数指针；但当 handler 触发 `onOutboundInvoke` 时，它只传回 `(action, payload)` 而不传 `key`。

3. Kotlin/Native 的 `staticCFunction(::iosStaticOutboundCallback)` 不允许捕获任何上下文（闭包），因此 Kotlin 侧无法在编译期或运行期绑定 key 到特定回调实例。

4. 结果：Kotlin 回调被触发时，无法确定是哪个模块发起的 outbound 调用。

**候选解决方案**（待 macOS/iOS 环境恢复后评估与实施）：

| 方案 | 描述 | 优缺点 |
|------|------|--------|
| **A. 扩展 C 回调签名** | 修改 `OutboundCallback` 为 `char* (*)(const char* key, size_t keyLen, const char* action, ...)` | 最简洁正确；需改 `WasmlineNative.h` / `.cpp` 及 Kotlin cinterop 定义 |
| **B. 携带 opaque context** | 改为 `char* (*)(void* context, const char* action, ...)`，`context` 由 Kotlin 侧提供并在回调时原样传回 | 更灵活，可绑定任意 Kotlin 对象引用（通过 `StableRef`） |
| **C. 线程本地/全局当前模块** | 在 C++ 的 `onOutboundInvoke` 入口处设置 TLS 标记当前 key，Kotlin 侧通过 C 函数查询 | 不需要改回调签名，但增加 TLS 依赖，多线程并发需格外小心 |

**建议方向**：方案 A 或 B，因为它们从协议层根治问题，不引入隐式状态。

在当前 `Windows` 阶段，这一 blocker 保持为**环境暂缓**，不从计划中删除；恢复 Apple 环境后应重新提升到平台专项优先级。

### 10.3 KN / Native 其他平台

当前即使尚未完整落地，也必须从设计上遵守与 JNI/iOS 相同的分层边界。

---

## 11. API 命名收口建议（2026-04-07 更新）

### 当前状态

命名迁移已基本完成，代码中不再混用 `release()`：

- 全局级：`Wasmline.init()` / `Wasmline.shutdown()` — `jniMain` 和 `iosMain` 均已对齐
- 实例级：`wasmline.close()` — 两个平台均已使用 `close()` 而非 `release()`
- `hostMain/Wasmline.kt` expect 声明已统一为 `shutdown()` / `close()`

### 建议命名

#### 全局级

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

### Phase 0：生产 blocker 与命名风险先收口（当前 Windows 阶段先处理非 Apple 项）

#### 目标

先解决最危险、最容易阻碍后续演进的问题。

#### 任务

1. [x] 删除 `testAAA()` 等无效入口；
2. [ ] 把 iOS callback 的 `findAny()` 替换为可准确定位 runtime 的方案（待切换到 `macOS/iOS` 环境后恢复执行）；
3. [x] 清理明显不合理的 public/internal 暴露；
4. [x] 为 instance/global lifecycle 建立新的命名目标，并已在当前代码中落到 `shutdown()` / `close()` 基线。

#### 完成标志

- 当前 `Windows` 阶段：生命周期语义在文档和代码中不再混淆，且非 Apple 平台的高风险临时代码已收口；
- 切换到 `macOS/iOS` 环境后：补完 iOS callback 定位问题，恢复 Phase 0 的完整完成判定。

---

### Phase 1：继续完善 Loader / Module 边界（已放弃独立 Engine 公开拆分）

#### 目标

继续收紧 loader 与 module 的职责边界，但不再把 engine 级能力拆成新的公开 API。

#### 任务

1. [x] 明确放弃独立 `WasmlineEngine` 公开 API，继续保留 `Wasmline.init()` / `shutdown()` 现有入口。
2. [~] 抽出 `WasmlineLoader`：runtime 内部本地加载流程已共享，Host loader 模块入口与请求模型已存在，但 package/remote 主链路仍未补完。
3. [~] 把 `Wasmline` 明确定位为单模块 runtime handle：实例生命周期已明显收口，但 `init()/shutdown()` 与 `load(...)` 仍共存于同一公开类型。
4. [x] 把 `load(...)` 中的文件存在判断、cache fallback 等高层逻辑从 platform actual 中抽到共享加载流程。

#### 完成标志

- `jniMain / iosMain` actual 不再直接承担完整 loader 策略；
- 不再新增 `WasmlineEngine` 这类额外公开类型，后续重点转向 loader 数据链路补全与 runtime API 收口。

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

1. [ ] 定义 `WasmlineLoadRequest`：应作为 `wasmline-loader` 的 Host 级公开请求模型落地；
2. [ ] 定义 `WasmlineSource` / `WasmlineArtifact`：应由 `wasmline-loader` 统一承载 source/package/artifact 语义，而不是继续留在 runtime 模块；
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
- 当前 `Windows` 阶段可以暂缓执行，但不应从路线图中删除；
- 恢复 Apple 环境后不应继续后置到最后。

---

## 14. 验证策略

### 14.1 架构层验证

1. 任一平台 actual 文件中，不应再出现完整高层 loader fallback 策略；
2. `Wasmline` 不再同时暴露 engine lifecycle 与 module lifecycle 的同名 `release()` 语义；
3. 文档可清晰解释 Host / Plugin 获取 `wasmline` 的方式。

### 14.2 当前 Windows 阶段优先验证

前提：先切到可用的 `JBR 21`，再运行不依赖 Apple 平台的验证链。

```bash
cd wasmline-multiplatform
./gradlew --no-daemon :wasmline-kotlin-plugin:compileKotlin
./gradlew --no-daemon :wasmline-kotlin-plugin:publishToMavenLocal
./gradlew --no-daemon :wasmline:jvmTest
```

优先关注：

1. `WasmlineLoader` / runtime / IR 收口后的编译稳定性；
2. Plugin current runtime 获取与 `bind/link` 路径；
3. JNI 多模块加载/释放行为。

### 14.3 Apple 环境恢复后补做验证

切换到可用的 `macOS/iOS` 环境后，再补做以下验证：

1. iOS 多模块 callback 正确分发验证；
2. iOS runtime/module 精确定位与并发回调验证；
3. 如有 Apple 平台相关 bridge 调整，再补平台回归验证。

---

## 15. 第一批建议实现项（按优先级）

### P0

1. 抽出 `WasmlineLoader` 设计与最小实现；
2. 将 `Wasmline.load(...)` 的高层流程继续迁移到 loader；
3. 统一 Host / Plugin runtime handle 使用模型，继续收口 `Wasmline.current` 与顶层 `bind()` 过渡策略。

### P1

4. 定义 `WasmlineLoadRequest`；
5. 定义 `WasmlineSource` / `WasmlineArtifact`；
6. 为 cache / manifest / signature / metadata 预留扩展插槽。

### P2

7. 继续推进 runtime public API 收口；
8. 做低风险 IR 瘦身与 internal helper 审计；
9. 完成 JNI 多模块与 Plugin current 路径的非 Apple 平台验证。

### P3

10. 切换到 `macOS/iOS` 环境后，修 iOS callback module 定位问题；
11. 补做 iOS 多模块 callback 正确分发验证；
12. 视 Apple 平台 bridge 调整结果，完成对应的专项回归。

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
