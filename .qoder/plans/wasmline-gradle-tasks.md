# Wasmline Gradle 插件：构建任务和服务器部署实现计划

## Context

当前 wasmline-gradle-plugin 只提供了 Kotlin IR 编译器插件桥接和密钥对生成功能。用户需要通过 Gradle 任务完成完整的 .wlm 构建流程（Kotlin/WasmWasi 编译 → wasmtime AOT 编译 → Manifest 签名 → 打包），以及启动 HTTP 服务器提供 .wlm 网络下载，使客户端可以通过网络请求加载插件。

## 实现方案

### 文件变更总览

**新增文件（9 个）：**

| 文件路径（相对于 `wasmline-multiplatform/wasmline-gradle-plugin/src/main/kotlin/crow/wasmline/`） | 职责 |
|---|---|
| `gradle/extensions/WasmlineExtension.kt` | 顶层 DSL 扩展入口 |
| `gradle/extensions/ManifestExtension.kt` | Manifest 元数据配置 |
| `gradle/extensions/WasmtimeExtension.kt` | wasmtime-min 工具和编译目标配置 |
| `gradle/extensions/ServerExtension.kt` | HTTP 服务器端口配置 |
| `gradle/tasks/WasmlineAssembleTask.kt` | Assemble 构建任务 |
| `gradle/tasks/WasmlineServerDeployTask.kt` | 服务器部署任务 |
| `gradle/internal/ManifestBuilder.kt` | Manifest 构建和签名逻辑 |
| `gradle/internal/WasmtimeCompiler.kt` | wasmtime 编译逻辑封装 |
| `gradle/server/WasmlineHttpServer.kt` | Ktor Server 实现 |

**修改文件（3 个）：**

| 文件路径 | 修改内容 |
|---|---|
| `wasmline-gradle-plugin/src/main/kotlin/crow/wasmline/WasmlinePlugin.kt` | 注册 Extension 和三个新任务 |
| `wasmline-gradle-plugin/build.gradle.kts` | 添加 wasmline-cli 和 Ktor Server 依赖 |
| `gradle/libs.versions.toml` | 添加 ktor-server-core/cio 库声明 |

---

### Task 1：添加版本目录依赖声明

**文件**: `wasmline-multiplatform/gradle/libs.versions.toml`

在 `[libraries]` 区段新增：
```toml
ktor-server-core = { module = "io.ktor:ktor-server-core", version.ref = "ktor" }
ktor-server-cio = { module = "io.ktor:ktor-server-cio", version.ref = "ktor" }
```

复用已有的 `ktor = "3.4.0"` 版本。

---

### Task 2：修改 Gradle 插件构建配置

**文件**: `wasmline-multiplatform/wasmline-gradle-plugin/build.gradle.kts`

1. 在 `dependencies {}` 中添加：
   ```kotlin
   implementation(projects.wasmlineCli)
   implementation(libs.ktor.server.core)
   implementation(libs.ktor.server.cio)
   ```
   
2. 添加 Kotlin 编译选项以支持 `@OptIn(ExperimentalSerializationApi)`：
   ```kotlin
   kotlin {
       compilerOptions {
           freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
       }
   }
   ```

---

### Task 3：创建 DSL 扩展类

#### 3.1 ServerExtension

**文件**: `gradle/extensions/ServerExtension.kt`

```kotlin
abstract class ServerExtension @Inject constructor(objects: ObjectFactory) {
    val port: Property<Int>     // 默认 8080
    val host: Property<String>  // 默认 "0.0.0.0"
}
```

#### 3.2 ManifestExtension

**文件**: `gradle/extensions/ManifestExtension.kt`

```kotlin
abstract class ManifestExtension @Inject constructor(objects: ObjectFactory) {
    val pluginId: Property<String>           // 必填
    val version: Property<String>            // 默认 "1.0.0"
    val versionCode: Property<Long>          // 默认 1L
    val minSdkVersion: Property<String>      // 默认当前版本
    val displayName: Property<String>        // 可选
    val author: Property<String>             // 可选
    val description: Property<String>        // 可选
    val iconUrl: Property<String>            // 可选
    val homePageUrl: Property<String>        // 可选
    val signingKey: Property<String>         // 必填：Ed25519 私钥 hex 或文件路径
    val metadata: MapProperty<String, String> // 可选
}
```

#### 3.3 WasmtimeExtension

**文件**: `gradle/extensions/WasmtimeExtension.kt`

```kotlin
abstract class WasmtimeExtension @Inject constructor(objects: ObjectFactory) {
    val directory: DirectoryProperty           // wasmtime-min 所在目录
    val targets: ListProperty<String>          // 默认 Compile.DEFAULT_TARGETS
}
```

#### 3.4 WasmlineExtension

**文件**: `gradle/extensions/WasmlineExtension.kt`

```kotlin
open class WasmlineExtension @Inject constructor(project: Project) {
    val manifest: ManifestExtension
    val wasmtime: WasmtimeExtension
    val server: ServerExtension

    fun manifest(action: ManifestExtension.() -> Unit)
    fun wasmtime(action: WasmtimeExtension.() -> Unit)
    fun server(action: ServerExtension.() -> Unit)
}
```

DSL 用法示例：
```kotlin
wasmline {
    manifest {
        pluginId = "crow.wasmline.demo"
        version = "1.0.0"
        signingKey = file("../keys/private.key").readText()
    }
    wasmtime {
        directory = file(System.getenv("WASMTIME_MIN_HOME") ?: "$home/.wasmline/wasmtime")
        targets = listOf("pulley64", "aarch64-android")
    }
    server {
        port = 8080
    }
}
```

---

### Task 4：创建内部工具类

#### 4.1 WasmtimeCompiler

**文件**: `gradle/internal/WasmtimeCompiler.kt`

封装对 CLI `Compile` 类静态方法的调用：
- `findWasmtimeExecutable(dir)` → 定位 wasmtime-min 可执行文件
- `compileAll(wasmtimeExec, inputFile, outputDir, name, targets, echo)` → 多平台 AOT 编译
- `writeCompileResult(inputFile, debugDir, artifacts)` → 写入 compile-result.json
- `sha256Hex(file)` → 计算文件 SHA-256

#### 4.2 ManifestBuilder

**文件**: `gradle/internal/ManifestBuilder.kt`

封装 Manifest 构建和签名逻辑，复用 CLI `Manifest.resolveKey()`：
1. 构建 `WasmlineManifest` 对象
2. Ed25519 签名
3. Protobuf 编码 `SignedManifestEnvelope`
4. 输出 `manifest.wlm` 和 `debug/manifest.json`

---

### Task 5：创建 WasmlineAssembleTask

**文件**: `gradle/tasks/WasmlineAssembleTask.kt`

```kotlin
abstract class WasmlineAssembleTask : DefaultTask() {
    // 输入
    @get:Input abstract val buildVariant: Property<String>  // "Development" | "Production"
    @get:Input abstract val pluginId: Property<String>
    @get:Input abstract val pluginVersion: Property<String>
    @get:Input abstract val versionCode: Property<Long>
    // ... 其他 manifest 字段

    // 输入文件
    @get:InputFile abstract val wasmInputFile: RegularFileProperty
    @get:InputDirectory abstract val wasmtimeDirectory: DirectoryProperty
    @get:Input abstract val targets: ListProperty<String>

    // 输出
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun assemble() {
        // Step 1: wasmtime AOT 编译
        val artifacts = WasmtimeCompiler.compileAll(...)

        // Step 2: 构建和签名 Manifest
        val wlmFile = ManifestBuilder.buildAndSign(...)

        // Step 3: 打包 zip
        packageZip(outputDir, wlmFile, artifacts)
    }
}
```

**Wasm 输入文件定位规则**（从 `run-sample-common.sh` 提取）：
- Development: `{projectDir}/build/compileSync/wasmWasi/main/developmentLibrary/optimized/*.wasm`
- Production: `{projectDir}/build/compileSync/wasmWasi/main/productionLibrary/optimized/*.wasm`

**产物输出目录**: `{projectDir}/build/wasmline/output/{pluginId}-{version}/`

---

### Task 6：创建 Ktor HTTP 服务器

**文件**: `gradle/server/WasmlineHttpServer.kt`

使用 Ktor Server CIO 引擎（纯 Kotlin 实现，无 JNI 依赖），前台阻塞模式运行：

路由设计：
- `GET /` → HTML 索引页，列出所有可下载文件
- `GET /{filename}` → 文件下载（包括 .wlm、.wasm、.cwasm、.pwasm）
- 特别处理 `GET /manifest.wlm` 端点

---

### Task 7：创建 WasmlineServerDeployTask

**文件**: `gradle/tasks/WasmlineServerDeployTask.kt`

```kotlin
abstract class WasmlineServerDeployTask : DefaultTask() {
    @get:InputDirectory abstract val serveDirectory: DirectoryProperty
    @get:Input abstract val port: Property<Int>
    @get:Input abstract val host: Property<String>

    @TaskAction
    fun deploy() {
        // 前台阻塞模式启动 HTTP 服务器
        WasmlineHttpServer.start(serveDirectory.get().asFile, host.get(), port.get())
    }
}
```

---

### Task 8：修改 WasmlinePlugin 注册逻辑

**文件**: `wasmline-gradle-plugin/src/main/kotlin/crow/wasmline/WasmlinePlugin.kt`

在 `apply(target: Project)` 中新增：

```kotlin
override fun apply(target: Project) {
    // 1. 注册 DSL 扩展
    val extension = target.extensions.create("wasmline", WasmlineExtension::class.java, target)

    // 2. 保留现有密钥生成任务
    createGenerateKeyPairTasks(target)

    // 3. 在 afterEvaluate 中注册新任务（确保 DSL 配置已就绪）
    target.afterEvaluate { project ->
        registerAssembleTasks(project, extension)
        registerServerDeployTask(project, extension)
    }
}
```

**任务注册**：

```kotlin
// wasmlineAssembleDebug → dependsOn compileDevelopmentLibraryKotlinWasmWasiOptimize
// wasmlineAssembleRelease → dependsOn compileProductionLibraryKotlinWasmWasiOptimize
// wasmlineServerDeploy → dependsOn wasmlineAssembleDebug（默认）
```

所有任务 `group = "wasmline"`。

`wasmlineServerDeploy` 默认依赖 `wasmlineAssembleDebug`（因为用户大部分时候只需要测试），但 DSL 可配置选择 Release。

---

### Task 9：验证

1. 在 `wasmline-samples/kotlin/sample-plugin/build.gradle.kts` 中添加 DSL 配置
2. 运行 `./gradlew :sample-plugin:wasmlineAssembleDebug` 验证构建流程
3. 运行 `./gradlew :sample-plugin:wasmlineServerDeploy` 验证服务器启动
4. 使用 `curl http://localhost:8080/manifest.wlm` 验证文件下载

---

### 任务依赖关系

```
compileDevelopmentLibraryKotlinWasmWasiOptimize
    └── wasmlineAssembleDebug
            └── wasmlineServerDeploy (默认)

compileProductionLibraryKotlinWasmWasiOptimize
    └── wasmlineAssembleRelease
```

### 风险与注意事项

- **Ktor Server CIO classpath 冲突**：如果 Gradle 插件运行时出现类冲突，考虑使用 Gradle Worker API 隔离 classloader
- **wasmtime-min 缺失**：Task 执行前做前置检查，输出清晰错误信息
- **前台服务器阻塞**：Ctrl+C 停止 Gradle 进程即可关闭服务器
- **wasm 输入文件路径**：Development/Production 的编译输出路径不同，需要正确映射
