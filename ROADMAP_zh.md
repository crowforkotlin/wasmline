# Wasmline 路线图

状态截至 2026-09-04。Wasmline 1.0.0 当前进入发布收尾阶段：核心运行时、插件工具链、Component Model 路径、包格式和文档系统已经实现。下面列出的内容是明确的范围决策或发布质量工作。

## 已完成功能

### 核心运行时

- [x] Wasmtime C-API 集成（Wasmline Wasmtime 48.0.1）
- [x] Core Wasm 和 Component Model 执行路径
- [x] Native 和浏览器运行时实现
- [x] Session 隔离的执行状态和生命周期管理
- [x] Engine 预热、关闭、Artifact 释放和运行时身份校验
- [x] Module 与 Component Artifact 缓存
- [x] Cranelift AOT 产物（`.cwasm`）
- [x] Pulley 可移植字节码（`.pwasm`）和 Native 回退选择
- [x] Core Wasm `RAW_EXPORT` 调用，包括标量值、Imports 和内存
- [x] 基于结果的调用失败处理和结构化错误码
- [x] Component 类型值、Host Imports、实例和资源所有权管理

### 平台和 CI 覆盖

- [x] Android Native 产物目标：arm64-v8a、armeabi-v7a、x86、x86_64
- [x] iOS 设备和模拟器 arm64 Pulley 目标
- [x] macOS arm64/x86_64 Native 产物目标
- [x] Linux arm64/x86_64 Native 产物目标
- [x] Windows x86_64 Native 产物目标
- [x] Kotlin/JS 和 Kotlin/WasmJS Core Wasm 浏览器运行时
- [x] Kotlin/Wasm WASI 运行时测试
- [x] JVM、Native AOT、iOS 模拟器、浏览器、Node.js 和插件 CI Job

当前目标矩阵比完整 CI 矩阵更广。剩余的平台验证缺口列在“发布收尾”部分。

### Kotlin 编译器插件

- [x] `WasmlineService` 合约发现
- [x] `*_WasmlineBridge` 类合成
- [x] `link<T>()` 和 `bind(impl)` 重写
- [x] SHA-256 action 标识符
- [x] 对无效合约报告编译器诊断
- [x] IR box 测试基础设施
- [x] 单参数和多参数服务方法
- [x] Core WASI 入口生成和 Component Service 初始化钩子
- [x] 对不支持声明的显式校验

当前服务合约边界已经明确记录，并且有意拒绝方法重载、Suspend 函数、泛型合约或泛型方法、默认参数、vararg 参数、属性、扩展接收者和非 public 方法。

### 服务合约和 Component Model

- [x] 基于接口的服务合约
- [x] Protobuf 和原始字节序列化配置
- [x] Host 到 Plugin、Plugin 到 Host 的双向服务调用
- [x] 固定的 `wasmline:service@1.0.0` Component Service WIT 世界
- [x] Component Model 类型化 Export 调用
- [x] Kotlin Host WIT Binding 自动生成
- [x] Owned 和 Borrowed Component 资源处理
- [x] Kotlin、Rust、C、C++ Component Fixture
- [x] 浏览器 Core Service 和 Core Raw Export 路径
- [x] 类型化 Component 实例和资源仅支持 Native 的边界

### Package、Loader 和安全模型

- [x] 签名 Protobuf Manifest 格式（`manifest.wlm`）
- [x] Ed25519 签名和 Trusted Key 校验
- [x] Manifest 规范化校验和受限的不可信输入解码
- [x] SHA-256 内容寻址 Artifact 布局
- [x] 本地和远程 Package 加载
- [x] 流式 Artifact 下载和原子缓存发布
- [x] 根据运行时选择 `.cwasm` 和 `.pwasm` 变体
- [x] Catalog 驱动的多 Profile AOT 兼容性选择
- [x] 带摘要校验的 Wasmtime Compiler/Tool 锁定下载

### CLI 和 Gradle 工具链

- [x] Wasmtime 下载和平台目标查看
- [x] Ed25519 密钥对生成
- [x] Core 和 Component AOT 编译
- [x] Manifest 签名和确定性 Package 创建
- [x] CLI 完整构建流程
- [x] WIT Binding 生成、Componentize、校验和查看命令
- [x] `wasmline-gradle-plugin` 用户配置 DSL 和任务依赖关系
- [x] Gradle Component 工具和 Host Binding Task
- [x] 受限并发的多目标 AOT 并行编译
- [x] AOT、Binding 和 Native Fixture 的可缓存 Gradle Task
- [x] 本地 Package Server 部署 Task
- [x] 仓库版本、AOT Catalog、Toolchain、Lint 和 Doctor 工具

### 网络、文档和发布自动化

- [x] Ktor HTTP 客户端适配器
- [x] OkHttp HTTP 客户端适配器
- [x] 中英文文档
- [x] 架构、运行时、测试、CLI、Gradle 和 Component 指南
- [x] Next.js + Fumadocs 静态文档站点
- [x] Dokka API 参考生成和文档站点部署流程
- [x] Maven Central 发布配置和发布流程
- [x] GitHub Release 产物和 Release Notes 流程

## 部分完成和发布收尾

| 领域 | 当前状态 | 剩余工作 |
| --- | --- | --- |
| Native 并发 | 多个 Native Session 和并发 Core Service 调用已有覆盖；单个 Raw/Component Session 仍会串行化或拒绝重叠操作 | 明确定义并测试所有协议和平台的并发调用契约 |
| 增量构建和缓存 | 已有 Compiler/Tool Cache 以及多个 `@CacheableTask` | 验证完整 Gradle Build Cache 行为；如有需要再增加编译单元级复用 |
| 大数据传输 | 远程 Artifact 下载支持流式处理 | Service 调用仍使用 `ByteArray` 或 WIT `list<u8>`，尚未实现调用级流式传输 |
| 平台支持 | Android armv7 和 x86_64 目标已存在；Native 产物构建覆盖面大于运行时 CI | 完成 Android x86_64/设备验证，并记录各目标的构建产物、运行方式和 CI 状态 |
| 性能 | 已有调用基准代码 | 建立可重复的基准测试套件、基线和回归阈值 |
| 兼容性 | 已有 AOT Catalog、Profile 选择和兼容性报告 | 增加同一 Package 在多个 Wasmtime Profile 上实际运行的测试 |
| Maven 发布 | 已有发布配置和 CI 流程 | 执行并确认首次公开发布；保持 Release Tag 与 Maven Release 配对 |

## 尚未完成的功能

### 运行时增强

- [ ] 热重载：无需重启 Host 即可原子替换已加载 Module
- [ ] 完整定义多 Plugin 并发加载和调用语义
- [ ] 每个 Session 的资源限制，包括内存、Fuel/CPU 预算和超时策略
- [ ] 大 Payload 的 Service 调用流式或分块传输

### 编译器插件增强

- [ ] 方法重载支持，并使用类型消歧的 action 标识符
- [ ] Suspend 函数支持，并定义显式异步 Host 协议
- [ ] 服务合约中的泛型类型参数
- [ ] 服务方法默认参数
- [ ] 属性访问支持
- [ ] 面向 IDE 的诊断和 Quick Fix

### 构建系统增强

- [ ] 编译单元级增量编译
- [ ] Plugin 依赖解析和依赖打包
- [ ] 对 Wasmline 的全部 Gradle Task 进行 Build Cache 验证

### 安全和沙箱

- [ ] 基于 Manifest 的权限声明
- [ ] 运行时权限强制
- [ ] 可配置的 Plugin 沙箱策略
- [ ] 第三方 Plugin 签名的证书链校验

### 平台覆盖

- [ ] 完成 Android x86_64 CI/设备验证
- [ ] Web SharedArrayBuffer 异步执行
- [ ] Web Service Worker 后台线程执行
- [ ] RISC-V 支持

### 网络和协议

- [ ] WASI Preview 2 HTTP 集成
- [ ] gRPC-over-Wasm 服务发现桥接
- [ ] Plugin Marketplace 发现协议

### 开发者体验

- [ ] 主要版本迁移指南
- [ ] 社区 Plugin Registry
- [ ] 用于热重载调试的 IntelliJ/IDE Plugin

### 测试和质量

- [ ] 序列化模糊测试
- [ ] 生产级性能基准测试套件
- [ ] 跨版本运行时兼容性测试矩阵
- [ ] 内存泄漏检测工具

## 1.0.0 发布清单

- [ ] 执行并审查所有必需 CI Job，包括 Native AOT 和 iOS Gate
- [ ] 发布并验证 Maven 产物及对应的 Release Tag
- [ ] 发布并验证中英文文档站点和 Dokka API 参考
- [ ] 记录支持的平台、Artifact 类型、执行模型和调用协议，并注明每项的验证状态
- [ ] 记录编译器插件不支持的功能以及 Native/浏览器边界
- [ ] 发布首个稳定版本的 Release Notes、迁移政策和支持政策

## 版本规划

| 阶段 | 版本 | 聚焦重点 |
| --- | --- | --- |
| 当前 | 1.0.0 | 发布收尾、支持边界、CI、发布和文档验证 |
| 1.0.0 之后 | 1.x | 可选的运行时、编译器、安全、协议和平台扩展 |

---

*最后更新：2026-09-04*
