# Wasmline 执行计划

> 本文是 `ir-planv2.md` 的可执行摘要，重写为连续执行顺序，去掉 Phase/P 分级。  
> 每一项均明确"做什么 / 为什么 / 完成标志"，可直接按顺序推进。

---

## 一、基线状态（截至 2026-05-13）

### 1.1 已完成

- Host 核心 API 切到 sync-first：`load / call / bind / bindGenerated` 均为非 `suspend` 版本。
- 生命周期命名基线落地：全局侧 `init() / shutdown()`，实例侧 `close()`；`jniMain / iosMain` 均已对齐。
- Generated bridge runtime SPI 收口到 `@PublishedApi internal`。
- 平台 actual 已收窄为纯桥接层：文件存在判断、artifact 后缀校验、cache 命中/回退逻辑已全部迁入共享 `WasmlineRuntimeLoader`，`jniMain / iosMain` 只通过 `WasmlinePlatformLoader` 回调提供本地能力。
- `WasmlineRuntimeLoader.loadLocal()` 仅接受 `.cwasm / .pwasm`，JIT 路径已移除。
- Plugin 侧 `Wasmline.current` 已存在，实例式 `bind/link` 入口已存在，顶层 `bind()` 已退化为过渡代理。
- `wasmline-loader` 模块已存在并包含：`WasmlineLoader`、`DefaultWasmlineLoader`、`loadWasmline(...)`、`WasmlineLoadRequest`、`WasmlineSource`、`WasmlineArtifact`。

### 1.2 尚未完成

| 项 | 状态 | 说明 |
|---|---|---|
| `wasmline-loader` 成为 Host 主入口 | 已完成 | Host 样例与 Host 编译面测试已切到 `loadWasmline(...)`，`Wasmline.load(...)` 保留为 runtime 直接桥接入口 |
| `engine / loader / module` 三层语义拆分 | 待做 | `Wasmline` companion 仍同时承载 engine 生命周期与加载入口 |
| Loader 数据链路收口 | 待做 | `LocalPackageFile / RemotePackageUrl` 当前 fail-fast，resolver/cache/manifest 扩展点未设计 |
| Host / Plugin 使用模型统一 | 待做 | Plugin 顶层 `bind()` 过渡入口仍在，文档/样例未统一叙述 |
| Runtime public API 最终收口 | 待做 | 可见性审计未完成，`moduleKey` 仍为 public constructor 参数 |
| iOS callback 模块定位 blocker | **环境暂缓** | 根因已明确，待切换到 macOS/iOS 环境后恢复 |

---

## 二、核心约束

以下约束在所有后续实现中都必须成立：

1. **平台桥接层只做桥接**：`jniMain / iosMain / kn` 不承担文件判断、artifact 选择、cache 命中/回退、source 到 artifact 的解析编排；这些职责属于 `WasmlineRuntimeLoader` 或 `WasmlineLoader`。
2. **runtime 只接受预编译产物**：`Wasmline.load(...)` 只接受 `.cwasm / .pwasm`，`.wasm` 不再进入 runtime 路径。
3. **核心 API 不内置 suspend**：`bind / link / load / call` 保持同步接口，协程支持通过可选扩展层（如 `wasmline-coroutines`）提供。
4. **iOS callback 不在本轮强推**：当前环境为 Windows，不在本机做 iOS native bridge 实现与验证。

---

## 三、执行顺序

### 步骤 1 — 让 `wasmline-loader` 成为 Host 侧主入口

**当前问题**：`wasmline-loader` 最小实现已存在，但 Host 调用点与文档仍直接把 `Wasmline.load(...)` 当入口；`loadWasmline(...)` / `WasmlineLoader` 实际上是旁路，不是主路径。这导致 request/source/artifact 的抽象可以被绕开。

**要做的事**：

- 盘点所有 Host 侧直接调用 `Wasmline.load(...)` 的地方（样例、文档、Host 测试）。
- 把这些调用点切到 `loadWasmline(...)` 或通过 `WasmlineLoader.load(request)` 进入。
- 在 `Wasmline.load(...)` 的 KDoc 上明确标注：这是 runtime 层对已准备好的本地预编译 artifact 的直接桥接，Host 工作流应通过 `wasmline-loader` 进入。

**完成标志**：Host 侧任何示例或文档中，加载 wasm 模块的推荐路径都经过 `loadWasmline(...)` 或 `WasmlineLoader`，而不是 `Wasmline.load(...)`。

---

### 步骤 2 — 拆清 `engine / loader / module` 三层语义

**当前问题**：`Wasmline` companion 同时挂了 `init() / shutdown()`（engine 级）和 `load(...)`（loader 级）；`Wasmline` 实例同时代表 module handle 又隐含 engine 状态。调用方无法从 API 形态上区分"全局引擎"和"单个加载好的模块"。

**三层定义**：

| 层 | 职责 | 当前承载位置 |
|---|---|---|
| Engine 层 | `init / shutdown / isInitialized`；进程级全局 runtime engine 生命周期 | `Wasmline.init() / Wasmline.shutdown()`（挂在同一个 class 上） |
| Loader 层 | 接收 `WasmlineLoadRequest`，把 source 解析为可执行 artifact，最终得到 `Wasmline` 实例 | `wasmline-loader` 模块（已存在，但与 engine 语义未拆开） |
| Module 层 | `bind / link / call / close`；代表一个已就绪的 wasm module 实例 | `Wasmline` 实例（当前也承载 engine 静态入口） |

**要做的事**：

- 决定是否立即抽出独立的 `WasmlineEngine` 对象（可选），或先做语义上的隔离（至少在文档和注释中把 engine 职责与 module 职责说清楚）。
- 无论是否立即重命名，`Wasmline` 实例应只代表"一个已加载模块的 handle"；engine 初始化/销毁不应与模块实例生命周期挂在同一个类型上。
- 把 `moduleKey` 收口为 `internal`，不再作为 public constructor 参数暴露。

**完成标志**：调用方理解 `Wasmline` 实例等于一个模块 handle，engine 的 init/shutdown 是独立的全局操作，loader 是两者中间的流程编排层。

---

### 步骤 3 — 把 loader 数据链路补完整

**当前问题**：`WasmlineLoadRequest / WasmlineSource / WasmlineArtifact` 已存在，但 `DefaultWasmlineLoader` 只能真正执行 `LocalArtifactFile`，其他来源直接失败；扩展插槽（cache、manifest、signature、resolver）均未设计。

**要做的事**：

- 梳理 `LocalArtifactFile / LocalPackageFile / RemotePackageUrl` 各自的边界：
  - `LocalArtifactFile`：调用方已持有预编译 artifact 本地路径，直接交给 runtime。
  - `LocalPackageFile`：调用方持有 `.wlm` 包文件本地路径，需要 loader 负责解包、选择 artifact、验证 manifest。
  - `RemotePackageUrl`：需要 loader 负责下载、缓存、manifest 校验、artifact 选择。
- 为 `LocalPackageFile / RemotePackageUrl` 设计"未支持时如何插入自定义 resolver"的扩展点，而不是永远 fail-fast。
- 确认 `WasmlineLoadRequest.metadata` 的用途边界；cache key、签名策略等需要的扩展字段应放在哪一层。

**完成标志**：loader 数据链路有明确的职责文档；`LocalPackageFile / RemotePackageUrl` 提供可插入的 resolver 扩展点，而不只是失败占位。

---

### 步骤 4 — 统一 Host / Plugin 的 runtime handle 使用模型

**当前问题**：Host 侧已接近"先拿到 `wasmline` 实例，再 `bind/link`"的模型；Plugin 侧虽然引入了 `Wasmline.current`，但顶层 `bind()` 仍然保留并被用户直接调用，两侧的心智模型不统一。

**目标模型**：

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

**要做的事**：

- 把 Plugin 样例的主入口切到 `Wasmline.current.bind(...)`，顶层 `bind()` 降为兼容层，不再出现在文档正文和样例入口。
- 确保文档和 KDoc 都能用同一套叙述解释两侧用法：先拿 handle，再操作。
- 明确 `Wasmline.current` 语义：它是"当前 plugin 执行上下文中的 runtime handle"，不是进程级全局单例。未来若插件需要 `link()` Host 侧服务，也通过它进入。

**完成标志**：Host / Plugin 文档可以用一段话统一描述使用模型，不再需要分两套讲解。

---

### 步骤 5 — Runtime public API 与 IR helper 最终收口

**当前问题**：generated bridge SPI 已完成一轮 `@PublishedApi internal` 收口，但尚未完成最终审计；`moduleKey` 仍为 public；顶层 `bind()` 等过渡 API 的长期保留策略尚未决定；IR 有小步瘦身空间但还没收尾。

**要做的事**：

- 审计 `wasmline` runtime 模块中还暴露为 `public` 的 helper / SPI，能收成 `internal` 或 `@PublishedApi internal` 的继续收口。
- 明确 `moduleKey` 的可见性：它是 native 层内部实现细节，不应作为 public constructor 参数暴露给 Host 开发者。
- 决定顶层 `bind()` 的最终保留策略：继续作为 `@Deprecated` 兼容层，还是完全依赖 IR 插件替换掉。
- 对 IR 插件做小步去重和 contract 稳定化，不再大改主链路（`WasmlineBridgeGenerator / TypedEntryPointRewriter / ServiceContractValidator` 的职责边界已经清晰，重点是继续减少 helper 散落）。

**完成标志**：对业务开发者暴露的 public API 面稳定且文档可读；内部 bridge/runtime helper 不再继续扩散。

---

### 步骤 6 — 非 Apple 验证链

**时机**：每完成上面一个步骤后即可做局部验证，最终完成全链路确认。

**前提**：先切到可用的 JBR 21，再运行以下验证链：

```bash
cd wasmline-multiplatform
./gradlew --no-daemon :wasmline-kotlin-plugin:compileKotlin
./gradlew --no-daemon :wasmline-kotlin-plugin:publishToMavenLocal
./gradlew --no-daemon :wasmline:jvmTest
```

**优先确认**：

1. `wasmline-loader` 成为主入口后编译是否稳定；
2. Plugin 侧 `Wasmline.current` 路径与实例 `bind/link` 是否一致；
3. JNI 多模块加载 / 释放行为符合预期；
4. runtime API 收口后 IR 生成与 box test 仍然通过。

---

### 步骤 7 — iOS callback blocker（环境暂缓）

**状态**：blocked，等切换到 macOS/iOS 环境后恢复。

**根因**：C 层 `OutboundCallback` 签名为 `char* (*)(action, actionLen, payload, payloadLen)`，不携带 `key`；Kotlin/Native 的 `staticCFunction` 不允许捕获上下文，因此 `iosStaticOutboundCallback` 无法识别来源模块，当前始终返回 `null`。

**恢复后执行顺序**：

1. 在 C 层修改 `OutboundCallback` 签名，新增 `key` 参数（方案 A）或改为 opaque context（方案 B），从协议层根治问题。
2. 更新 `WasmlineNative.h` / `Engine.cpp` / `wasmline.def` cinterop 定义。
3. 在 Kotlin/Native 侧实现真正的 callback 分发，把 `action / payload` 正确路由到对应模块的 `WasmlineHostDispatcher`。
4. 补多模块 / 并发回调正确分发验证。
5. 补专项回归。

---

## 四、目标架构（V2 四层）

```
┌──────────────────────────────────────────────────┐
│              Host 业务层 / Plugin 业务层           │
│    loadWasmline(...)  /  Wasmline.current         │
│    wasmline.bind(...)  /  wasmline.link<T>()      │
└────────────────────┬─────────────────────────────┘
                     │
┌────────────────────▼─────────────────────────────┐
│                  WasmlineLoader                   │
│  WasmlineLoadRequest → WasmlineSource             │
│       → artifact 选择 → WasmlineLoadState         │
│  （可插入 cache / manifest / signature / resolver）│
└────────────────────┬─────────────────────────────┘
                     │
┌────────────────────▼─────────────────────────────┐
│         Wasmline（module handle）                 │
│         WasmlineEngine（全局 engine 生命周期）     │
│         WasmlineRuntimeLoader（本地 artifact 加载）│
└────────────────────┬─────────────────────────────┘
                     │
┌────────────────────▼─────────────────────────────┐
│           平台桥接层（internal）                   │
│    jniMain / iosMain / kn                        │
│    只做：init engine / load artifact / invoke /   │
│          set outbound / release module           │
└──────────────────────────────────────────────────┘
```

---

## 五、不做的事（本轮）

- 不做网络下载、manifest 校验、签名下载链路的完整实现。
- 不支持 `suspend`、泛型、默认参数、`vararg`、重载等复杂 Kotlin 类型进入 IR 生成。
- 不手改 `*.fir.txt / *.fir.ir.txt` 等 IR 快照（只能由测试自动生成）。
- 不把 `kotlinx-coroutines` 升级为核心 ABI 的强依赖。
- 不在当前 Windows 环境强推 iOS native bridge 实现与回归验证。

---

## 六、关键文件索引

| 文件 | 说明 |
|---|---|
| `wasmline-multiplatform/wasmline-loader/src/commonMain/kotlin/crow/wasmline/loader/WasmlineLoadRequest.kt` | `WasmlineLoadRequest` / `WasmlineSource` 定义 |
| `wasmline-multiplatform/wasmline-loader/src/hostMain/kotlin/crow/wasmline/loader/WasmlineLoader.kt` | `WasmlineLoader` / `DefaultWasmlineLoader` / `loadWasmline(...)` |
| `wasmline-multiplatform/wasmline-loader/src/commonMain/kotlin/crow/wasmline/loader/model/Manifest.kt` | `WasmlineArtifact` / `WasmlineArtifactType` |
| `wasmline-multiplatform/wasmline/src/hostMain/kotlin/crow/wasmline/Wasmline.kt` | Host 侧 `expect Wasmline` 声明 |
| `wasmline-multiplatform/wasmline/src/wasmWasiMain/kotlin/crow/wasmline/Wasmline.wasmWasi.kt` | Plugin 侧 `Wasmline.current` |
| `wasmline-multiplatform/wasmline/src/wasmWasiMain/kotlin/crow/wasmline/WasmlineServices.wasmWasi.kt` | 顶层 `bind()` 过渡代理 |
| `wasmline-multiplatform/wasmline-kotlin-plugin/src/main/kotlin/crow/wasmline/kotlin/WasmlineIrGenerationExtension.kt` | IR 生成入口 |
| `wasmline-core/src/Engine.cpp` / `Module.cpp` / `Session.cpp` | C 层 runtime bridge |
| `.github/plans/ir-planv2.md` | 完整背景与设计细节（本文参考来源） |
