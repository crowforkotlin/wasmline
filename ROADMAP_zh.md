# Wasmline 路线图

## 已完成功能

### 核心运行时
- [x] Wasmtime C-API 集成（v47.0.2）
- [x] 双路径执行：原生和 Web
- [x] Session 内存隔离
- [x] Engine 单例生命周期管理
- [x] AOT 编译（`.cwasm`）
- [x] Pulley 可移植字节码（`.pwasm`）
- [x] 模块键控缓存

### 平台支持
- [x] Android（arm64-v8a, arm-eabi, x86_64, x86）
- [x] iOS（arm64）
- [x] macOS（arm64）
- [x] Linux（x86_64）
- [x] Windows（x86_64）
- [x] Web - Kotlin/JS 通过浏览器 WebAssembly API
- [x] Web - Kotlin/WasmJS 通过浏览器 WebAssembly API

### 编译器插件（IR 转换）
- [x] Service 合约发现（`WasmlineService` 接口）
- [x] Bridge 类自动合成（`*_WasmlineBridge`）
- [x] `link<T>()` 调用重低下转换
- [x] `bind(impl)` 调用重低下转换
- [x] SHA-256 action 标识符
- [x] 诊断错误报告
- [x] IR box 测试基础设施
- [x] 合约验证（仅接口、无 suspend、单参数）

### 服务合约
- [x] 基于接口的合约定义
- [x] 单参数方法签名
- [x] 多参数方法签名
- [x] 自动序列化（Protobuf、原始字节）
- [x] Host ↔ Plugin 双向调用
- [x] Web 目标 Base64 编码

### CLI 工具链
- [x] `download` - Wasmtime 二进制下载
- [x] `generate-key-pair` - Ed25519 密钥生成
- [x] `compile` - AOT 和 Pulley 编译
- [x] `manifest` - 签名清单生成
- [x] `build` - 完整流程编排

### 安全与清单
- [x] Ed25519 数字签名
- [x] ECDSA-P256 支持
- [x] Protobuf 清单格式（`.wlm`）
- [x] 加载时清单验证

### Gradle 集成
- [x] `wasmline-gradle-plugin`
- [x] `wasmline-build-logic` 约定插件
- [x] KMP 多平台配置
- [x] Android NDK / CMake 集成
- [x] Zig 0.15.1 JNI 编译

### 网络客户端
- [x] Ktor HTTP 客户端适配
- [x] OkHttp HTTP 客户端适配

### 文档与示例
- [x] 多平台示例应用
- [x] 中英文档
- [x] 架构图和设计文档
- [x] Next.js + Fumadocs 站点

---

## 计划中功能

### 运行时增强
- [ ] 热重载：无需重启 host 动态替换已加载模块
- [ ] 并发多插件执行
- [ ] 每个 session 的资源限制（内存、CPU ticks）
- [ ] 大数据流式/分块传输

### 编译器插件增强
- [ ] 方法重载支持（类型消歧 action IDs）
- [ ] Suspend 函数支持（async/await）
- [ ] 合约中的泛型类型参数
- [ ] 方法签名默认参数
- [ ] 属性访问支持
- [ ] 改进诊断消息和快速修复

### 构建系统改进
- [ ] 增量编译（跳过未变更模块）
- [ ] 并行多目标编译
- [ ] 插件依赖解析和打包
- [ ] Gradle 构建缓存兼容
- [ ] Maven 中央仓库发布

### 安全与沙箱
- [ ] 基于清单的权限声明
- [ ] 运行时权限强制
- [ ] 插件沙箱策略配置
- [ ] 第三方证书链验证

### 平台覆盖
- [ ] Android x86_64 完整 CI 验证
- [ ] Web SharedArrayBuffer 异步执行
- [ ] Web Service Worker 后台执行
- [ ] 其他架构支持（RISC-V、ARMv7）

### 网络与协议
- [ ] WASI Preview 2 HTTP 集成
- [ ] gRPC-over-Wasm 服务发现桥接
- [ ] 插件市场发现协议

### 开发者体验
- [ ] API 参考文档（Dokka）
- [ ] 插件开发指南
- [ ] 主要版本迁移指南
- [ ] 社区插件 registry
- [ ] IDE 插件用于热重载调试

### 测试与质量
- [ ] 序列化模糊测试
- [ ] 性能基准测试套件
- [ ] 跨版本兼容性测试
- [ ] 内存泄漏检测工具

---

## 版本规划

| 阶段   | 版本    | 聚焦重点                        |
|--------|---------|-------------------------------|
| Alpha  | 0.x.x   | 核心稳定、平台覆盖             |
| Beta   | 0.9.x   | 功能完整、性能优化             |
| Stable | 1.0.0   | 生产就绪、完整文档              |

---

*最后更新：2026-07-27*
