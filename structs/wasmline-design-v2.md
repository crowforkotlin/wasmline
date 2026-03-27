# Wasmline Design V2

> 状态：主设计文档 / 2026-03-28  
> 目标：把 Wasmline 的对外 API 收敛成用户真正需要理解的一层。  
> 结论：**保留 `Wasmline`、保留 `bind` / `link`，隐藏所有 binding / definition / registry / JNI 等内部细节。**

---

## 1. 设计结论

Wasmline 的用户模型应该极简，只保留四件事：

1. 获取一个 `Wasmline` 实例；
2. 用 `bind(...)` 暴露本地能力；
3. 用 `link<T>()` 获取远端能力；
4. 在结束时释放实例。

用户不需要理解这些内部概念：

- `BindingScope`
- `Endpoint`
- `Definition`
- `Registry`
- 自动注册时机
- JNI / Native bridge
- IR / 编译器插件生成物

这些都属于实现层，不应进入用户主文档。

---

## 2. 命名拍板

### 2.1 保留 `Wasmline`

`Wasmline` 继续作为唯一公开门面。

它表达的是：

> 一次 Host ↔ Wasm 通信连接的公开实例。

不建议改名，也不建议把它拆成更多用户必须理解的对象。

### 2.2 保留 `bind` / `link`

最终对外命名保持：

- `bind`：把本地实现暴露给对端
- `link`：从对端拿到一个可调用代理

不建议改成 `bindService`、`getService`、`connectService`。

原因很简单：

- `bind` / `link` 更短、更自然；
- 用户并不需要先理解 “service framework” 才能使用；
- 这两个词与 Wasmline 的双向通信模型更贴近。

---

## 3. 用户应该如何理解 Wasmline

用户只需要把 Wasmline 理解成一个通信实例：

- `bind(...)`：把自己这边的能力挂进去；
- `link<T>()`：把对端某个能力连出来；
- 之后像普通接口一样调用。

用户视角的最小示例应该是：

```kotlin
interface EchoService {
    fun echo(payload: ByteArray): ByteArray
}

class EchoServiceImpl : EchoService {
    override fun echo(payload: ByteArray): ByteArray = payload
}

suspend fun use(wasmline: Wasmline) {
    wasmline.bind(EchoServiceImpl())

    val echo = wasmline.link<EchoService>()
    val result = echo.echo("hello".encodeToByteArray())

    println(result.decodeToString())
}
```

也可以直接绑定匿名实现：

```kotlin
wasmline.bind(object : EchoService {
    override fun echo(payload: ByteArray): ByteArray = payload
})
```

这就是用户应该理解的全部主流程。

---

## 4. Service 的设计原则

从用户角度，service 就是一个普通接口。

用户应该能够：

- 定义一个接口；
- 写一个实现类；
- 或直接 `bind` 一个匿名实现。

主文档不应该把重点放在 `WasmlineService` 这类 marker 上。

如果底层实现仍然需要某种 marker 或编译期约束，那应该被视为：

- 内部机制；
- 生成代码约束；
- 运行时兼容要求。

而不是用户 API 的叙事中心。

也就是说，主文档应该写：

> 定义服务接口，然后 `bind` / `link`。

而不是写成：

> 先理解 Wasmline 的内部 service 体系，再开始使用。

---

## 5. 实例获取方式

`Wasmline` 不应默认设计成全局单例，也不建议以 `Wasmline.get()` 作为主模型。

推荐模型是：

- 显式加载；
- 显式持有；
- 显式释放。

例如：

```kotlin
suspend fun loadAndUse(path: String) {
    when (val state = Wasmline.load(path)) {
        is WasmlineLoadState.Success -> {
            val wasmline = state.wasmline
            val echo = wasmline.link<EchoService>()
            echo.echo("hello".encodeToByteArray())
            wasmline.release()
        }

        is WasmlineLoadState.Failure -> {
            println(state.cause)
        }
    }
}
```

如果某个应用确实想做单例缓存，应由应用层自己封装，而不是让 Wasmline 的核心 API 以全局 `get()` 为中心。

---

## 6. `call(action, payload)` 的定位

`call(action, payload)` 可以保留，但必须降级为高级 API。

它的定位应该是：

- raw API
- advanced API
- escape hatch

它不应该主导主文档，更不应该替代 `bind` / `link` 成为主要叙事。

正确关系是：

> `bind` / `link` 是用户主 API；`call(action, payload)` 是底层原语。

因此即便底层最终仍然通过更原始的 `call` 通道实现，对最终用户暴露出来的仍然应该是 `bind` / `link`。

---

## 7. 对外公开边界

### 7.1 用户应该看到的 API

- `Wasmline`
- `load(...)`
- `bind(...)`
- `link<T>()`
- `release()`

### 7.2 用户不应该接触的概念

- 任何 bridge 类型
- 任何 definition / adapter / endpoint / binding scope 类型
- 任何 register / bootstrap / registry 辅助入口
- `WasmlineServiceRegistry`
- linking / bootstrap / registry 细节

这些能力即便继续存在，也只应服务于：

- runtime 内部实现；
- 编译器插件；
- 生成代码；
- 平台接入层。

它们不应该进入首页示例，也不应该成为普通用户需要学习的词汇。

---

## 8. 关于 bridge 隐藏的设计结论

设计目标非常明确：

> **bridge 应该被用户“感知不到”，而不是只是换个名字继续暴露。**

因此这些做法都不算真正完成隐藏：

- 仅仅重命名 bridge 类型
- 仅仅更换包名
- 仅仅在文档里写“不要直接使用”

真正有效的方向只有两类：

### 方向 A：bridge 不是用户主依赖面

也就是：

- 用户主 API 不再需要 import bridge
- 示例、sample、fixture 不再把 bridge 当用户 API 使用
- bridge 留在 runtime / compiler plugin 协作层

### 方向 B：bridge 不再是生成物的直接 ABI

如果要做到接近 Zipline 的隐藏效果，最终还要进一步做到：

- 生成物不再直接把 bridge 类型写进跨模块签名
- internal adapter / helper 留在 runtime 内部
- compiler plugin 主要通过改写 / 注入把用户调用接到内部实现

当前的直接工作重点也已经确定为：

- **先缩小 runtime 对 `ServiceDefinition` 的中心依赖**
- **再继续把生成物从 `ServiceDefinition / Endpoint / BindingScope` 这组三件套中脱开**

也就是说，真正的终点不是“把 bridge 放进 `internal` 包”，而是：

> **让用户模块的生成 ABI 本身不再以 bridge 类型为中心。**

---

## 9. 最终对外表述

Wasmline 应被表达为：

> **一个以显式实例为中心、以 `bind` / `link` 为主体验的双向 Wasm 服务通信框架。**

用户模型固定为：

1. 获取 `Wasmline`；
2. 定义普通接口；
3. `bind(...)` 本地实现；
4. `link<T>()` 获取远端能力；
5. 像普通接口一样调用；
6. 最后 `release()`。

除此之外的 binding、definition、registry、JNI、IR、bootstrap 细节，全部属于内部层。
