# Wasmline IR box testData 说明

这个目录保存 `wasmline-kotlin-plugin` 的**正式 IR fixture**。

它的职责刻意保持很窄：只验证 Kotlin IR 插件发现了什么、生成了什么，并把这些结果固化为稳定快照。

## 这里应该放什么

一个标准 case 通常由三类文件组成：

- `caseName.kt` —— 源 fixture，包含 `box(): String` 入口
- `caseName.fir.txt` —— FIR dump 快照
- `caseName.fir.ir.txt` —— IR dump 快照

当前示例：

- `echoProxyRoundTrip.kt`
- `echoProxyRoundTrip.fir.txt`
- `echoProxyRoundTrip.fir.ir.txt`

## 这些 fixture 应该验证什么

范围请聚焦在 **IR 插件行为** 本身：

- service contract 发现
- phase-one 校验规则
- 生成的 `*_WasmlineDefinition`
- 生成的 `*_WasmlineProxy`
- 生成的 `*_WasmlineAdapter`
- `endpoint.invoke(...)` 这类生成调用形状
- `link()` 以及当前临时 `bind()` stub 等 glue 行为

## 一个有效 case 的预期结果

当 fixture 正确、插件接线正常时，应满足：

1. `generateTests` 会在 `test-gen/.../JvmBoxTestGenerated.java` 中生成或更新对应测试方法
2. 运行生成后的测试可以通过
3. `*.fir.txt` 与当前前端快照一致
4. `*.fir.ir.txt` 与当前 IR 快照一致

由于 `wasmline-kotlin-plugin` 是 **IR 插件**，生成声明不会出现在 `build/generated` 里的源码文件中。
它们会直接注入 IR，因此最终主要通过编译产物或 IR snapshot 观察。

## 编写 fixture 时的重要规则

优先通过**运行时可观察行为 / 反射 / 间接验证**来测试生成声明，而不是在源码里直接静态引用生成名。

原因是：

- 当前插件是在 IR 阶段生成声明，不是在 FIR/源码阶段
- 直接在源码里写死生成名，对 IR-only 测试并不稳定
- 用反射或行为验证，更符合当前实现阶段

## 如何验证 testData

在 `wasmline-multiplatform/` 目录执行：

```zsh
./gradlew :wasmline-kotlin-plugin:generateTests
./gradlew :wasmline-kotlin-plugin:test --tests 'crow.wasmline.kotlin.runners.JvmBoxTestGenerated'
```

如果你的本地 Gradle 需要 JetBrains Runtime / JDK 21 toolchain，请先切换到对应 JDK 再执行。

修改 fixture 后，建议额外检查：

- `wasmline-kotlin-plugin/test-gen/crow/wasmline/kotlin/runners/JvmBoxTestGenerated.java` 中是否出现新的测试方法
- 测试类是否不再只是一个空的 `testAllFilesPresentInBox()`
- 提交前是否已经审阅新的 `*.fir.txt` 与 `*.fir.ir.txt`

## 如何新增或修改 case

1. 在本目录中新增或修改一个 `*.kt` fixture。
2. 保持 case 尽量小，只覆盖一个 IR 行为。
3. 运行对应 box test。
4. 检查生成或更新后的 `*.fir.txt` 与 `*.fir.ir.txt`。
5. 将 fixture 与快照一起提交。

## 不应该放到这里的内容

不要把下面这些内容混到这里：

- KSP 风格源码生成测试
- 横跨多个模块的大型 runtime 集成流程
- packaging / manifest / loader 行为
- Wasmtime 原生生命周期测试
- UI 或应用层行为

这些应该分别放在 runtime、CLI、loader 或 sample 对应测试中。

## 提交前快速检查清单

- [ ] 一个 `*.kt` fixture 只验证一个行为
- [ ] `box(): String` 最终返回 `"OK"`
- [ ] 已生成对应测试方法
- [ ] `*.fir.txt` 已更新
- [ ] `*.fir.ir.txt` 已更新
- [ ] 定向 box 测试通过

