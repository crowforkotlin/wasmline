# Native Library Loading and Engine Selection

This document explains how Wasmline distributes platform-specific native libraries (`libwasmline.so`, `libwasmline.dylib`, `libwasmline.dll`) for JVM hosts and static Native bridges for Kotlin/Native consumers. It also covers Gradle resolution and engine module exclusion.

## Kotlin/Native Distribution

Kotlin/Native consumers do not use the JVM JNI JARs described below. The `wasmline-engine-pulley` and `wasmline-engine-cranelift` modules publish Native variants containing the Kotlin/Native cinterop metadata and a target-specific static archive. The archive embeds the selected `libwasmtime.a`, so a consumer executable or framework has no source-checkout path and does not load `libwasmtime.so` at runtime.

```kotlin
kotlin {
    sourceSets {
        val nativeMain by getting {
            dependencies {
                implementation("crow.wasmline:wasmline:1.0.0")
                implementation("crow.wasmline:wasmline-loader:1.0.0")
                implementation("crow.wasmline:wasmline-engine-pulley:1.0.0")
            }
        }
    }
}
```

Use Pulley for portable `.pwasm` artifacts on iOS, macOS, Linux, or Windows. Use
Cranelift when the host needs matching `.cwasm` artifacts. It can use `.pwasm`
only when no compatible CWASM exists and the runtime reports the corresponding
Pulley profile and capability. Select exactly one engine module because both
expose the same Wasmline native bridge symbols. The runtime API remains
`WasmlineLoader.load()` and `WasmlineRuntime`; no JNI loader call is required.

The current signed manifest format accepts Ed25519 only. Runtime, Loader, and
the selected engine must use the same Wasmline Maven version. Native startup
checks the release identity and bridge ABI before deserializing an artifact.

---

## JVM and Android Distribution

The remaining sections describe JVM and Android library loading. They do not apply to Kotlin/Native consumers.

### Architecture Overview

The native library loading system has three layers:

1. **Engine modules** — build and publish platform-specific native JARs to Maven.
2. **Gradle dependency resolution** — selects the correct native JAR for the current platform.
3. **Runtime loader** — extracts the native library from the JAR and loads it via JNI.

```
┌─────────────────────────────────────────────────────┐
│  Engine Module (pulley or cranelift)                │
│                                                     │
│  src/jvmMain/resources/jni/                         │
│  ├── linux/x86_64/libwasmline.so                    │
│  ├── linux/aarch64/libwasmline.so                   │
│  ├── darwin/aarch64/libwasmline.dylib               │
│  ├── darwin/x86_64/libwasmline.dylib                │
│  └── windows/x86_64/libwasmline.dll                 │
│                                                     │
│  Build output (MavenLocal):                         │
│  wasmline-engine-pulley-jvm-1.0.0-linux-x86_64.jar  │
│  wasmline-engine-pulley-jvm-1.0.0-darwin-aarch64.jar│
│  ...                                                │
└──────────────────────┬──────────────────────────────┘
                       │ Gradle resolves platform JAR
                       ▼
┌─────────────────────────────────────────────────────┐
│  Consumer Project (sample-apps)                     │
│                                                     │
│  dependencies {                                     │
│    implementation(engine.pulley)  // base module    │
│    implementation(classifier)     // native JAR     │
│  }                                                  │
│                                                     │
│  Classpath contains:                                │
│  ├── wasmline-engine-pulley-jvm-1.0.0.jar           │
│  └── wasmline-engine-pulley-jvm-1.0.0-linux-x86_64  │
└──────────────────────┬──────────────────────────────┘
                       │ At runtime
                       ▼
┌─────────────────────────────────────────────────────┐
│  NativeLoaderExt (JVM)                              │
│                                                     │
│  1. Detect OS: linux, darwin, windows               │
│  2. Detect arch: x86_64, aarch64                    │
│  3. Build path: /jni/linux/x86_64/libwasmline.so    │
│  4. Extract from JAR to temp file                   │
│  5. System.load(tempFile)                           │
└─────────────────────────────────────────────────────┘
```

---

## Engine Module Structure

Wasmline provides two engine modules. They are mutually exclusive — a project must use exactly one.

| Module | Artifact ID | Description |
|--------|------------|-------------|
| `wasmline-engine-pulley` | `crow.wasmline:wasmline-engine-pulley` | Pulley-only interpreter. Supports `.pwasm` only; smaller binary and broader platform support. Use `pulley32` or `pulley64` to match host bitness. |
| `wasmline-engine-cranelift` | `crow.wasmline:wasmline-engine-cranelift` | Cranelift + Pulley runtime. Requires exact backend profile and target identity for `.cwasm`; uses `.pwasm` only when no compatible CWASM exists and a matching Pulley profile is reported. |

Both modules follow the same build and publishing structure. The examples below use `pulley`.
These modules intentionally do not expose a Kotlin runtime API. The dependency selects the native runtime distribution; the `wasmline` runtime reports the linked engine through `WasmlineEngineKind`.

### Source Layout

Native libraries are stored under `src/jvmMain/resources/jni/` organized by platform and architecture:

```
wasmline-engine-pulley/
└── src/
    ├── jvmMain/
    │   └── resources/
    │       └── jni/
    │           ├── linux/
    │           │   ├── x86_64/libwasmline.so
    │           │   └── aarch64/libwasmline.so
    │           ├── darwin/
    │           │   ├── aarch64/libwasmline.dylib
    │           │   └── x86_64/libwasmline.dylib
    │           └── windows/
    │               └── x86_64/libwasmline.dll
    └── androidMain/
        └── jniLibs/
            ├── arm64-v8a/libwasmline.so
            ├── armeabi-v7a/libwasmline.so
            ├── x86/libwasmline.so
            └── x86_64/libwasmline.so
```

### Main JVM JAR Exclusion

The main JVM JAR excludes native library resources. This keeps the base JAR small and ensures native libraries come only from platform-specific JARs.

```kotlin
// In the shared engine Gradle configuration
tasks.named<Jar>("jvmJar") {
    exclude("jni/**")
}
```

---

## Platform-Specific JAR Publishing

### Build Process

For each platform and architecture combination, the build creates a separate JAR:

```kotlin
val platformMap = mapOf(
    "linux"   to listOf("x86_64" to "x86-64", "aarch64" to "aarch64"),
    "darwin"  to listOf("aarch64" to "aarch64", "x86_64" to "x86-64"),
    "windows" to listOf("x86_64" to "x86-64"),
)
```

Each entry produces a JAR task with:

- **Classifier**: `{platform}-{arch}` (e.g., `linux-x86_64`)
- **Source directory**: `src/jvmMain/resources/jni/{platform}/{arch}/`
- **Internal JAR path**: `jni/{platform}/{arch}/`

Example output:

```
wasmline-engine-pulley-jvm-1.0.0-linux-x86_64.jar
└── jni/linux/x86_64/libwasmline.so
```

### Maven Publication

Native JARs are published under the JVM module's Maven coordinates with a classifier:

```kotlin
publishing.publications {
    register<MavenPublication>("pulleyNativeLinuxX86_64") {
        artifactId = "${project.name}-jvm"
        artifact(jarTask)
    }
}
```

This produces the following MavenLocal layout:

```
~/.m2/repository/crow/wasmline/wasmline-engine-pulley-jvm/1.0.0/
├── wasmline-engine-pulley-jvm-1.0.0.jar                  (main JAR, no native libs)
├── wasmline-engine-pulley-jvm-1.0.0-linux-x86_64.jar     (classifier: linux-x86_64)
├── wasmline-engine-pulley-jvm-1.0.0-linux-aarch64.jar    (classifier: linux-aarch64)
├── wasmline-engine-pulley-jvm-1.0.0-darwin-aarch64.jar   (classifier: darwin-aarch64)
├── wasmline-engine-pulley-jvm-1.0.0-darwin-x86_64.jar    (classifier: darwin-x86_64)
├── wasmline-engine-pulley-jvm-1.0.0-windows-x86_64.jar   (classifier: windows-x86_64)
└── wasmline-engine-pulley-jvm-1.0.0.module               (Gradle module metadata)
```

### Module Metadata Variant Injection

After Gradle generates the `.module` metadata file, a `doLast` block injects native variant definitions. Each variant entry includes attributes that describe the target platform:

```kotlin
tasks.withType<GenerateModuleMetadata>().configureEach {
    doLast {
        // Parse the .module JSON file
        // Add a variant entry for each platform:
        variants.add(mapOf(
            "name" to "pulleyNativeLinuxX86_64",
            "attributes" to mapOf(
                "org.gradle.category" to "library",
                "org.gradle.usage" to "java-runtime",
                "org.gradle.jvm.environment" to "standard-jvm",
                "org.gradle.libraryelements" to "jar",
                "org.jetbrains.kotlin.platform.type" to "jvm",
                "org.gradle.native.operating-system" to "linux",
                "org.gradle.native.architecture" to "x86-64",
                "crow.wasmline.os" to "linux",
                "crow.wasmline.arch" to "x86_64"
            ),
            "files" to listOf(mapOf(
                "name" to "wasmline-engine-pulley-jvm-1.0.0-linux-x86_64.jar",
                "url" to "wasmline-engine-pulley-jvm-1.0.0-linux-x86_64.jar"
            ))
        ))
    }
}
```

The attributes serve three purposes:

- **Standard JVM attributes** (first five) — tell Gradle this is a standard JVM library.
- **Native attributes** (next two) — standard Gradle OS/arch attributes for backward compatibility.
- **Custom wasmline attributes** (last two) — `crow.wasmline.os` and `crow.wasmline.arch` used by the `crow.wasmline` Gradle plugin for automatic variant-aware resolution.

---

## Gradle Dependency Resolution

### KMP Module Redirect

When a consumer adds a dependency on the engine module, Gradle's Kotlin Multiplatform resolution redirects to the JVM sub-module:

```
crow.wasmline:wasmline-engine-pulley:1.0.0
    → crow.wasmline:wasmline-engine-pulley-jvm:1.0.0
```

The main JVM JAR (`wasmline-engine-pulley-jvm-1.0.0.jar`) contains no native libraries. The platform-specific JAR must be added separately.

### Consumer Dependency Configuration

With the `crow.wasmline` Gradle plugin applied, consumers only need a single dependency declaration. The plugin automatically configures JVM runtime configurations with OS/architecture attributes, enabling Gradle's variant-aware resolution to select the correct platform-specific native JAR:

```kotlin
val desktopMain by getting {
    dependencies {
        // Single dependency — the `crow.wasmline` plugin handles native variant resolution
        implementation(libs.crow.wasmline.engine.pulley)
    }
}
```

The `crow.wasmline` Gradle plugin:
1. Detects the current build machine's OS and architecture.
2. Sets `crow.wasmline.os` and `crow.wasmline.arch` attributes on JVM runtime classpath configurations.
3. Registers compatibility rules so non-wasmline dependencies (which lack these attributes) remain compatible.
4. Registers disambiguation rules to pick the correct variant when multiple are available.

Gradle then automatically resolves the matching native JAR from the module metadata — no manual classifier configuration required.

For Android targets, the engine module provides native libraries through `androidMain/jniLibs/`, which Android's build system handles automatically:

```kotlin
val androidMain by getting {
    dependencies {
        implementation(libs.crow.wasmline.engine.pulley)
    }
}
```

---

## Engine Module Exclusion

### The Problem

A project should never include both `pulley` and `cranelift` on the classpath. They provide the same native symbols and would conflict at link time.

### Capability-Based Exclusion

Both engine modules declare the same Gradle capability in their module metadata. When Gradle detects two dependencies providing the same capability, it raises a conflict error.

In the `.module` metadata, each native variant declares:

```json
"capabilities": [
    {
        "group": "crow.wasmline",
        "name": "wasmline-engine-pulley-jvm",
        "version": "1.0.0"
    }
]
```

If a consumer accidentally adds both engines:

```kotlin
implementation(libs.crow.wasmline.engine.pulley)
implementation(libs.crow.wasmline.engine.cranelift)  // CONFLICT
```

Gradle reports a capability conflict and the build fails. The developer must choose one engine.

---

## Runtime Native Library Loading

The runtime loader is implemented in `NativeLoaderExt.jvm.kt`. It performs the following steps:

### Step 1: Detect Platform

```kotlin
val osName = System.getProperty("os.name").lowercase(US)
val osArch = normalizeArch(System.getProperty("os.arch"))
val platform = normalizePlatform(osName)
```

| `os.name` value | Normalized platform |
|-----------------|-------------------|
| `linux` | `linux` |
| `mac os x` | `darwin` |
| `windows 10` | `windows` |

| `os.arch` value | Normalized arch |
|-----------------|----------------|
| `amd64` | `x86_64` |
| `x86_64` | `x86_64` |
| `aarch64` | `aarch64` |
| `arm64` | `aarch64` |

### Step 2: Determine File Extension

```kotlin
val extension = when {
    osName.contains("linux") -> "so"
    osName.contains("mac") -> "dylib"
    osName.contains("windows") -> "dll"
}
```

### Step 3: Search Classpath

The loader builds candidate paths and searches the classpath:

```kotlin
val wasmlineJarPath = candidateArchs
    .map { "/jni/$platform/$it/libwasmline.$extension" }
    .firstOrNull { Wasmline::class.java.getResource(it) != null }
```

For Linux x86_64, the search order is:

1. `/jni/linux/x86_64/libwasmline.so`
2. `/jni/linux/amd64/libwasmline.so` (fallback)

### Step 4: Extract and Load

```kotlin
private fun extractAndLoad(loaderClass: Class<*>, jarPath: String) {
    val url = loaderClass.getResource(jarPath)
    val tmp = Files.createTempFile("wasmline-native", null)
    tmp.toFile().deleteOnExit()
    url.openStream().use { input -> Files.copy(input, tmp, REPLACE_EXISTING) }
    System.load(file.toAbsolutePath().toString())
}
```

The native library is extracted from the JAR to a temporary file, then loaded via `System.load()`. The temporary file is marked for deletion on JVM exit.

---

## Summary: Data Flow

```
Build time:
  Zig compiler → libwasmline.{so,dylib,dll}
       ↓
  src/jvmMain/resources/jni/{platform}/{arch}/
       ↓
  Jar task → {artifactId}-jvm-{version}-{platform}-{arch}.jar
       ↓
  Maven publish → ~/.m2/.../{artifactId}-jvm/{version}/

Resolution time:
  Consumer build.gradle.kts
       ↓
  implementation(libs.crow.wasmline.engine.pulley)
       ↓
  `crow.wasmline` plugin sets crow.wasmline.os/arch attributes
       ↓
  Gradle variant-aware resolution selects matching native JAR
       ↓
  {artifactId}-jvm-{version}-linux-x86_64.jar added to classpath

Runtime:
  WasmlineRuntime.preload() or the first native runtime access
       ↓
  ensureNativeRuntimeLoaded()
       ↓
  Detect: platform=linux, arch=x86_64
       ↓
  Search: /jni/linux/x86_64/libwasmline.so
       ↓
  Extract to temp → System.load()
```
