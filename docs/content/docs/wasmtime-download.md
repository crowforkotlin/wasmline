---
title: Wasmtime Download Guide
description: Wasmtime download configuration and troubleshooting for the Wasmline Gradle plugin
---

# Wasmline Gradle Plugin - Wasmtime Download 完整指南

## 📋 目录

- [自动下载 (AutoDownload)](#自动下载-autodownload)
- [手动下载任务](#手动下载任务)
- [环境配置](#环境配置)
- [故障排除](#故障排除)

---

## 🤖 自动下载 (AutoDownload)

### 启用方式

在 `build.gradle.kts` 中配置 `autoDownload = true`：

```kotlin
wasmline {
    wasmtime {
        directory = file("${rootProject.buildDir}/wasmline/wasmtime") // 可选
        autoDownload = true           // ← 启用自动下载
        version = "latest"            // 或 "v47.0.2" 指定版本
    }
}
```

### 工作原理

当运行 `./gradlew wasmlineAssembleDebug` 时，插件会：

1. **检查** wasmtime 是否存在于配置的目录
2. **降级搜索** WASMTIME_ROOT 环境变量
3. **尝试默认路径** `~/.wasmline/wasmtime`
4. **如果未找到且启用了 autoDownload**：
   - 尝试使用项目内的 CLI JAR（仅限 wasmline 开发项目）
   - 尝试全局安装的 `wasmline` 命令行工具
   - 提供详细的手动下载指引

### 下载优先级

```
┌─────────────────────────────────────┐
│ Method 1: Embedded CLI              │ ← Highest (project only)
├─────────────────────────────────────┤
│ Method 2: Global wasmline CLI       │
├─────────────────────────────────────┤
│ Method 3: Manual instructions       │ ← Fallback
└─────────────────────────────────────┘
```

### 输出示例

```bash
$ ./gradlew wasmlineAssembleDebug

✅ Found wasmtime: /home/user/.wasmline/wasmtime/wasmtime-v47.0.2-x86_64-linux-min/wasmtime-min
   Version: wasmtime-47.0.2
...
```

**如果未找到且 autoDownload=true**：

```bash
❌ wasmtime not found!

Attempted locations:
  - ~/.wasmline/wasmtime
  - Environment: /custom/path

⚡ Attempting automatic download...
  Platform: x86_64-linux
  Version: latest
  Output: /home/user/project/build/wasmline/wasmtime

Using wasmline-cli from project...
Executing: java -jar .../wasmline-cli.jar download -a x86_64-linux -v latest -o ...
  📥 Downloading: wasmtime-v47.0.2-x86_64-linux-min.tar.xz
  ✅ Success!
```

---

## 🛠️ 手动下载任务

### 独立运行下载任务

不启用 `autoDownload` 时，可以手动运行下载任务：

```bash
# 基本用法（自动检测平台）
./gradlew wasmlineDownloadWasmtime

# 指定版本
./gradlew wasmlineDownloadWasmtime -Dwasmtime.version=v47.0.2

# 指定平台
./gradlew wasmlineDownloadWasmtime -Pwasmtime.platform=aarch64-macos
```

### 动态配置任务

在 `build.gradle.kts` 中自定义：

```kotlin
tasks.named<DownloadWasmtimeTask>("wasmlineDownloadWasmtime") {
    version.set("v47.0.2")
    platform.set("x86_64-linux")
    outputDir.set(file("/custom/wasmtime/dir"))
}
```

### DSL 配置示例

```kotlin
wasmline {
    wasmtime {
        directory = file("$home/.wasmline/wasmtime")
        autoDownload = false  // 手动控制
        version = "latest"
    }
}

// 或者完全不在 DSL 中配置，使用默认值
wasmline {
    manifest { /* ... */ }
    // wasmtime 块可省略，使用默认路径
}
```

---

## 🔧 环境配置

### 环境变量 WASMTIME_ROOT

设置环境变量指向已下载的 wasmtime 目录：

```bash
# Bash/Zsh
export WASMTIME_ROOT=$HOME/.wasmline/wasmtime/wasmtime-v47.0.2-x86_64-linux-min

# Windows PowerShell
$env:WASMTIME_ROOT="C:\wasmtime\wasmtime-v47.0.2-x86_64-windows-min"

# CMake
set(ENV{WASMTIME_ROOT} "/path/to/wasmtime")
```

Gradle 会自动检测此变量。

### 推荐默认路径

```bash
# Linux/macOS
~/.wasmline/wasmtime/

# Windows
%USERPROFILE%\.wasmline\wasmtime\

# CI 构建缓存
$GITHUB_WORKSPACE/build/wasmline/wasmtime/
```

---

## ⚠️ 故障排除

### 错误 1: wasmtime executable not found

**症状**：
```
❌ wasmtime executable not found in '/path/to/dir'.
```

**解决方案**：

#### 方法 A: 手动下载
```bash
# 1. 访问 releases
https://github.com/crowforkotlin/wasmtime/releases

# 2. 下载对应平台的资产
wget https://github.com/crowforkotlin/wasmtime/releases/download/v47.0.2/wasmtime-v47.0.2-x86_64-linux-min.tar.xz

# 3. 解压
tar -xf wasmtime-v47.0.2-x86_64-linux-min.tar.xz

# 4. 配置目录
# 在 build.gradle.kts 中：
wasmline {
    wasmtime {
        directory = file("$PWD/wasmtime-v47.0.2-x86_64-linux-min")
    }
}
```

#### 方法 B: 设置环境变量
```bash
export WASMTIME_ROOT=$PWD/wasmtime-v47.0.2-x86_64-linux-min
./gradlew wasmlineAssembleDebug
```

#### 方法 C: 启用自动下载
```kotlin
wasmline {
    wasmtime {
        autoDownload = true  # 让插件自动处理
    }
}
```

### 错误 2: 自动下载失败

**症状**：
```
⚠️ Automatic download failed or unavailable.
No download method available.
```

**可能原因**：
- ❌ 不是 wasmline 项目（无法访问嵌入式 CLI）
- ❌ 全局 `wasmline` CLI 未安装
- ❌ 网络问题

**解决方案**：
1. 按错误提示手动下载（见上述方法 A）
2. 或使用环境变量（方法 B）
3. 检查网络连接和防火墙设置

### 错误 3: 多平台构建

**问题**：在 macOS M1 上构建 Linux x86_64 需要交叉编译的 wasmtime

**解决方案**：
```bash
# 下载多平台版本
wasmline download -a x86_64-linux
wasmline download -a aarch64-linux
wasmline download -a aarch64-macos

# 配置多个目录
wasmline {
    server {
        port = 8090
    }
}
```

---

## 🚀 最佳实践

### 1. 开发环境

```kotlin
// Local development with auto-download
wasmline {
    wasmtime {
        autoDownload = true
        version = "latest"  # Always get newest
    }
}
```

### 2. CI/CD 环境

```kotlin
// Deterministic builds in CI
wasmline {
    wasmtime {
        autoDownload = false  # Explicitly managed
        directory = file("$BUILD_DIR/wasmtime/${wasmtimeVersion}")
    }
}

// Run before building
tasks.register("setupWasmtime") {
    doLast {
        exec {
            commandLine "wasmline", "download", "-v", "${wasmtimeVersion}", "-a", "${platform}"
        }
    }
}

wasmlineAssembleDebug.dependsOn("setupWasmtime")
```

### 3. Production/Binary Distribution

```kotlin
// Pin exact version for reproducibility
wasmline {
    wasmtime {
        version = "v47.0.2"
        directory = file("./lib/wasmtime")  # Bundle with app
    }
}
```

---

## 📦 支持的架构

| 平台 | 架构 | 示例命令 |
|------|------|---------|
| **Linux** | x86_64 | `wasmline download -a x86_64-linux` |
| **Linux** | ARM64 | `wasmline download -a aarch64-linux` |
| **macOS** | Intel | `wasmline download -a x86_64-macos` |
| **macOS** | Apple Silicon | `wasmline download -a aarch64-macos` |
| **Windows** | x64 | `wasmline download -a x86_64-windows` |
| **Android** | ARM64 | `wasmline download -a aarch64-android` |
| **iOS** | ARM64 | `wasmline download -a aarch64-ios` |

---

## 🔄 与 CLI 的关系

### 功能对比

| 功能 | Gradle Plugin (`wasmlineDownloadWasmtime`) | Standalone CLI (`wasmline download`) |
|------|---------------------------------------------|--------------------------------------|
| **集成度** | ✅ Gradle 流程一体 | ❌ 外部工具 |
| **便捷性** | ✅ `./gradlew wasmlineDownloadWasmtime` | ⚠️ 需单独安装/调用 |
| **灵活性** | ⚠️ 有限的参数 | ✅ 完整参数支持 |
| **离线能力** | ❌ 依赖网络 | ❌ 依赖网络 |
| **可组合性** | ✅ 作为 Gradle 依赖链 | ❌ 独立执行 |

### 推荐使用场景

**使用 Gradle Task**：
- ✅ Gradle 项目中统一管理
- ✅ 与其他构建步骤串联
- ✅ 团队协作标准化

**使用 CLI**：
- ✅ 非 Gradle 项目
- ✅ 交互式下载（选择版本/平台）
- ✅ 一次性快速部署

---

## 📞 获取帮助

遇到问题？查看：

1. **实时日志** → `./gradlew wasmlineAssembleDebug --info`
2. **调试模式** → 添加 `--stacktrace` 查看详细堆栈
3. **文档站点** → https://wasmline.dev/installation
4. **GitHub Issues** → https://github.com/crowforkotlin/wasmline/issues

---

*最后更新：2026-07-30 | Wasmline v1.0.0*
