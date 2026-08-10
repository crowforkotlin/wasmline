# Wasmline Kotlin Samples

All sample builds use the Wasmline Gradle tasks. Run commands from this directory.

## Core Wasm application

Build the Kotlin/Wasm plugin, produce the native artifact, sync it into the
application resources, and run the host:

```shell
./gradlew :sample-apps:application:run
```

Select a portable artifact explicitly:

```shell
./gradlew :sample-apps:application:run -Pwasmline.artifact.format=pwasm64
./gradlew :sample-apps:application:run -Pwasmline.artifact.format=cwasm
```

`pwasm32` can be assembled, but it requires a 32-bit native runtime. A 64-bit
desktop runtime rejects it by design.

Assemble the Core Wasm plugin package without running the host:

```shell
./gradlew :sample-plugin:wasmlineAssembleDebug
```

## Component Model

Assemble the Component Model sample package:

```shell
./gradlew :sample-component-plugin:wasmlineAssembleDebug
```

Component AOT requires the full Wasmtime CLI. Configure it explicitly or run
the download task first:

```shell
WASMTIME_COMPILER=/absolute/path/to/wasmtime \
  ./gradlew :sample-component-plugin:wasmlineAssembleDebug

./gradlew :sample-component-plugin:wasmlineDownloadWasmtimeCompiler
./gradlew :sample-component-plugin:wasmlineAssembleDebug
```

## Other targets

The desktop and web Gradle tasks remain available:

```shell
./gradlew :sample-apps:multiplatform:desktopApp:run
./gradlew :sample-apps:multiplatform:webApp:jsBrowserDevelopmentRun
./gradlew :sample-apps:multiplatform:webApp:wasmJsBrowserDevelopmentRun
```

Android tasks still need a connected device for installation and launch:

```shell
./gradlew :sample-apps:android:installDebug
./gradlew :sample-apps:multiplatform:androidApp:installDebug
```

The iOS sample keeps `run-ios.sh` because it coordinates Xcode, simulator
selection, framework builds, installation, and launch:

```shell
./run-ios.sh
```

The Android, desktop, and web shell scripts are still compatibility helpers
for resource synchronization and device/browser launch. They can be removed
after those remaining copy and launch steps are modeled as Gradle task inputs.
