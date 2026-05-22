# Wasmline 实施计划

---

## 当前状态

### 已完成

- Host 核心 API 已切到 sync-first：`load`、`call`、`bind`、`bindGenerated` 均为非 `suspend` 版本，`suspend` 不进入核心 ABI。
- 生命周期命名基线已落地：全局侧 `init()` / `shutdown()`，实例侧 `close()`；`jniMain` / `iosMain` 均已对齐。
- Generated bridge runtime SPI 已收口到 `@PublishedApi internal`。
- Platform actual 已收窄为纯桥接层：文件存在判断、artifact 选择、cache 命中/回退逻辑已全部迁入共享 `WasmlineRuntimeLoader`，`jniMain` / `iosMain` 只通过 `WasmlinePlatformLoader` 回调提供本地能力。
- `WasmlineRuntimeLoader.loadLocal()` 仅接受 `.cwasm` / `.pwasm`，JIT 路径已移除。
- Plugin 侧 `Wasmline.current` 已存在，实例式 `bind/link` 入口已存在，顶层 `bind()` 已退化为过渡代理。
- `wasmline-loader` 模块已存在：`WasmlineLoader`、`DefaultWasmlineLoader`、`loadWasmline(...)`、`WasmlineLoadRequest`、`WasmlineSource`、`WasmlineArtifact` 均已落地。
- `LocalPackageFile` 已可直接读取当前 `manifest.wlm`（签名 envelope）、按宿主目标选择 colocated artifact，并在进入 runtime 前校验 artifact sha256。
- 独立 `WasmlineEngine` 公开 API 方案已明确放弃，继续保留 `Wasmline.init()` / `Wasmline.shutdown()` 现有入口。

### 待完成

| 项 | 说明 |
|---|---|
| Loader 数据链路补全 | `RemotePackageUrl`、cache、manifest signature 验证主链路仍未实现 |
| Host / Plugin 使用模型统一 | Plugin 顶层 `bind()` 过渡入口仍在，文档与样例未统一叙述 |
| Runtime public API 最终收口 | `moduleKey` 可见性未收口；顶层 `bind()` 保留策略未最终决定 |
| iOS callback blocker | **环境暂缓**，根因已明确，待切换到 macOS/iOS 环境后恢复 |

---

## 核心约束

以下约束在所有后续实现中必须成立：

1. **平台桥接层只做桥接**：`jniMain` / `iosMain` / kn 不承担文件判断、artifact 选择、cache 命中/回退、source 到 artifact 的解析编排；这些职责属于 `WasmlineRuntimeLoader` 或 `WasmlineLoader`。
2. **Runtime 只接受预编译产物**：`Wasmline.load(...)` 只接受 `.cwasm` / `.pwasm`，`.wasm` 不再进入 runtime 路径。
3. **核心 API 不内置 suspend**：`bind` / `link` / `load` / `call` 保持同步接口；协程支持通过可选扩展层（如 `wasmline-coroutines`）提供，不作为核心 ABI 强依赖。
4. **iOS callback 不在当前环境强推**：在切换到可用 macOS/iOS 环境前，iOS native bridge 实现与验证保持为环境暂缓状态。

---

## 架构目标

### 四层模型

```
┌──────────────────────────────────────────────────┐
│         Host 业务层 / Plugin 业务层               │
│   loadWasmline(...)  /  Wasmline.current          │
│   wasmline.bind(...)  /  wasmline.link<T>()       │
└────────────────────┬─────────────────────────────┘
                     │
┌────────────────────▼─────────────────────────────┐
│                 WasmlineLoader                    │
│   WasmlineLoadRequest → WasmlineSource            │
│       → artifact 选择 → WasmlineLoadState         │
│   （可插入 cache / manifest / signature / resolver）│
└────────────────────┬─────────────────────────────┘
                     │
┌────────────────────▼─────────────────────────────┐
│        Wasmline（module handle + init/shutdown）  │
│        WasmlineRuntimeLoader（本地 artifact 加载）│
└────────────────────┬─────────────────────────────┘
                     │
┌────────────────────▼─────────────────────────────┐
│          平台桥接层（internal）                    │
│    jniMain / iosMain / kn                        │
│    只做：init engine / load artifact / invoke /   │
│          set outbound / release module           │
└──────────────────────────────────────────────────┘
```

### 目标 API 形态

**`Wasmline`**（单模块 runtime handle）：

```kotlin
interface Wasmline {
    fun <T : WasmlineService> link(): T
    fun bind(implementation: WasmlineService)
    fun <T : WasmlineService> bind(contract: KClass<T>, implementation: T)
    fun close()
    val isClosed: Boolean
}
```

- `moduleKey` 收口为 internal 实现细节，不作为 public constructor 参数。
- `init()` / `shutdown()` 保留在 companion object 层面，不新增独立 `WasmlineEngine` 公开类型。

**`WasmlineLoader`**：

```kotlin
interface WasmlineLoader {
    fun load(request: WasmlineLoadRequest): WasmlineLoadState
}
```

**`WasmlinePlatformBridge`**（internal）：

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

### Host / Plugin 统一使用模型

目标：两侧围绕同一 `Wasmline` runtime handle 使用，不保留顶层函数与实例函数混用的割裂模型。

```kotlin
// Host 侧
val wasmline = loadWasmline(request)
wasmline.bind(object : SomeService { ... })
val service = wasmline.link<OtherService>()

// Plugin 侧
val wasmline = Wasmline.current
wasmline.bind(object : SomeService { ... })
val service = wasmline.link<OtherService>()
```

`Wasmline.current` 表示"当前 plugin 执行上下文中的 runtime handle"，不是进程级全局单例。

---

## 待执行任务

### 使 `wasmline-loader` 成为 Host 侧主入口

**问题**：Host 调用点与文档仍有直接使用 `Wasmline.load(...)` 的情况；`loadWasmline(...)` / `WasmlineLoader` 应为推荐路径。

**执行内容**：

- 盘点所有 Host 侧直接调用 `Wasmline.load(...)` 的位置（样例、文档、Host 测试）并切换到 `loadWasmline(...)` 或 `WasmlineLoader.load(request)`。
- 在 `Wasmline.load(...)` 的 KDoc 明确标注：这是 runtime 层对已准备好的本地预编译 artifact 的直接桥接入口；Host 工作流应经由 `wasmline-loader`。

**完成标志**：Host 侧任何示例或文档中，加载 wasm 模块的推荐路径均经过 `loadWasmline(...)` 或 `WasmlineLoader`。

---

### 补全 Loader 数据链路

**问题**：当前 `LocalPackageFile` 主链路可工作，但 `RemotePackageUrl`、cache、manifest signature 验证未实现。

**执行内容**：

- 明确 `LocalArtifactFile` / `LocalPackageFile` / `RemotePackageUrl` 的职责边界：
  - `LocalArtifactFile`：调用方已持有预编译 artifact 本地路径，直接交给 runtime。
  - `LocalPackageFile`：调用方持有 `manifest.wlm` 本地路径；loader 负责读取签名 envelope、选择 artifact、校验完整性后交给 runtime。
  - `RemotePackageUrl`：loader 负责下载、缓存、manifest 校验、artifact 选择。
- 基于现有 resolver 扩展点继续梳理 cache、manifest、signature 的接入位置。
- 确认 `WasmlineLoadRequest.metadata` 的扩展字段归属层级。

**完成标志**：`LocalPackageFile` 主链路可直接工作；`RemotePackageUrl` 及 cache / manifest signature 链路的职责文档明确。

---

### 统一 Host / Plugin runtime handle 使用模型

**问题**：Plugin 侧顶层 `bind()` 仍保留，Host / Plugin 文档尚未统一叙述。

**执行内容**：

- Plugin 样例主入口切到 `Wasmline.current.bind(...)`，顶层 `bind()` 降为兼容层，不再出现在文档正文和样例入口。
- 文档和 KDoc 统一叙述两侧用法：先获得 `wasmline` handle，再进行 `bind` / `link`。
- 明确 `Wasmline.current` 语义：当前 plugin 执行上下文的 runtime handle，不是进程级全局单例。

**完成标志**：Host / Plugin 文档可用一段话统一描述使用模型，不再需要分两套讲解。

---

### Runtime public API 与 IR helper 最终收口

**执行内容**：

- 审计 `wasmline` runtime 模块中仍为 `public` 的 helper / SPI，收口到 `internal` 或 `@PublishedApi internal`。
- 将 `moduleKey` 从 public constructor 参数收口为 internal 实现细节。
- 决定顶层 `bind()` 的最终保留策略：永久过渡入口，还是由 IR 插件完整替换。
- 对 IR 插件做低风险瘦身与 contract 稳定化；不再大改 `WasmlineBridgeGenerator` / `TypedEntryPointRewriter` / `ServiceContractValidator` 主路径。

**完成标志**：对业务开发者暴露的 public API 面稳定且文档可读；内部 bridge / runtime helper 不再扩散。

---

### 非 Apple 平台验证链

前提：切换到可用的 JBR 21 环境后执行。

```bash
cd wasmline-multiplatform
./gradlew --no-daemon :wasmline-kotlin-plugin:compileKotlin
./gradlew --no-daemon :wasmline-kotlin-plugin:publishToMavenLocal
./gradlew --no-daemon :wasmline:jvmTest
```

验证重点：

1. `wasmline-loader` 成为主入口后编译稳定性。
2. Plugin 侧 `Wasmline.current` 路径与实例 `bind/link` 一致性。
3. JNI 多模块加载 / 释放行为符合预期。
4. Runtime API 收口后 IR 生成与 box test 仍通过。

---

## 平台桥接层职责说明

### JNI（jniMain）

**负责**：native library 加载、engine init/shutdown、本地预编译 artifact 加载（通过 `WasmlinePlatformLoader` 回调）、outbound handler 注册、inbound invoke、module release。

**不负责**：高层加载策略聚合、URL 下载、manifest 解析、用户级 API 语义。

**待完成**：`moduleKey` 可见性收口（当前仍为 public constructor 参数）。

### iOS（iosMain）— 环境暂缓

**负责**：engine init/shutdown、本地预编译 artifact 加载（已委托 `WasmlineRuntimeLoader`）、outbound callback 桥接、inbound invoke、module release。

**生产 blocker**：`iosStaticOutboundCallback` 当前始终返回 `null`（TODO 占位）；`WasmlineCallbackRegistry.findAny()` 无法正确定位来源模块。

**根因**：C 层 `OutboundCallback` 签名为 `char* (*)(action, actionLen, payload, payloadLen)`，不携带 `key` 参数；Kotlin/Native `staticCFunction` 不允许捕获上下文，导致回调无法识别来源模块。

**候选方案**（恢复 macOS/iOS 环境后评估）：

| 方案 | 描述 |
|---|---|
| **A. 扩展 C 回调签名** | 修改为 `char* (*)(const char* key, size_t keyLen, const char* action, ...)` — 最简洁，需改 `WasmlineNative.h` / Engine.cpp / cinterop 定义 |
| **B. 携带 opaque context** | 改为 `char* (*)(void* context, const char* action, ...)`，context 由 Kotlin 侧通过 `StableRef` 提供并原样传回 — 更灵活 |
| **C. TLS 标记** | C++ `onOutboundInvoke` 入口处设置 TLS 当前 key，Kotlin 侧通过 C 函数查询 — 无需改签名，但增加 TLS 依赖 |

**建议方向**：方案 A 或 B，从协议层根治问题，不引入隐式状态。

恢复 Apple 环境后的执行顺序：修改 C 层签名 → 更新 `WasmlineNative.h` / `Engine.cpp` / `wasmline.def` → 实现 Kotlin/Native callback 分发 → 补多模块 / 并发回调验证 → 补专项回归。

### KN / Native 其他平台

遵守与 JNI/iOS 相同的分层边界：只负责平台桥接，不负责对外语义，不负责高层加载策略。

---

## 不做的事

- 不实现完整的网络下载、manifest 校验、签名下载链路（抽象已预留，实现待后续）。
- 不在 IR 生成中支持 `suspend`、泛型、默认参数、`vararg`、重载等复杂 Kotlin 类型。
- 不手改 `*.fir.txt` / `*.fir.ir.txt` 等 IR 快照（只能由测试自动生成）。
- 不把 `kotlinx-coroutines` 升级为核心 ABI 的强依赖。
- 不在当前 Windows 环境强推 iOS native bridge 实现与回归验证。

---

## 关键文件索引

| 文件 | 说明 |
|---|---|
| `wasmline-multiplatform/wasmline-loader/src/commonMain/.../WasmlineLoadRequest.kt` | `WasmlineLoadRequest` / `WasmlineSource` 定义 |
| `wasmline-multiplatform/wasmline-loader/src/hostMain/.../WasmlineLoader.kt` | `WasmlineLoader` / `DefaultWasmlineLoader` / `loadWasmline(...)` |
| `wasmline-multiplatform/wasmline-loader/src/commonMain/.../model/Manifest.kt` | `WasmlineArtifact` / `WasmlineArtifactType` |
| `wasmline-multiplatform/wasmline/src/hostMain/.../Wasmline.kt` | Host 侧 `expect Wasmline` 声明 |
| `wasmline-multiplatform/wasmline/src/wasmWasiMain/.../Wasmline.wasmWasi.kt` | Plugin 侧 `Wasmline.current` |
| `wasmline-multiplatform/wasmline/src/wasmWasiMain/.../WasmlineServices.wasmWasi.kt` | 顶层 `bind()` 过渡代理 |
| `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/.../WasmlineIrGenerationExtension.kt` | IR 生成入口 |
| `wasmline-core/src/Engine.cpp` · `Module.cpp` · `Session.cpp` | C 层 runtime bridge |
