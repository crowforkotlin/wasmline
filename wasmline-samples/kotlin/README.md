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

Desktop defaults to the Pulley engine. It loads the signed `manifest.wlm`
package, which verifies the manifest and artifact digest before selecting the
matching `pwasm64` artifact:

```shell
./gradlew :sample-apps:multiplatform:desktopApp:run
```

Run Desktop with Cranelift and assemble a package containing the matching
native artifact:

```shell
./gradlew :sample-apps:multiplatform:desktopApp:run \
  -Pwasmline.engine=cranelift \
  -Pwasmline.artifact.format=cwasm
```

The two Android applications expose Gradle tasks that assemble and sync the
plugin into APK assets, install the debug APK, and launch the activity with
`adb`:

```shell
./gradlew :sample-apps:android:wasmlineRunDebug
./gradlew :sample-apps:multiplatform:androidApp:wasmlineRunDebug
```

Select a device with `-Pandroid.device=SERIAL`:

```shell
./gradlew :sample-apps:multiplatform:androidApp:wasmlineRunDebug \
  -Pandroid.device=emulator-5554
```

Android defaults to Pulley + `pwasm64`. For Android CWASM, select Cranelift
and compile the plugin for the Android target:

```shell
./gradlew :sample-apps:android:wasmlineRunDebug \
  -Pwasmline.engine=cranelift \
  -Pwasmline.artifact.format=cwasm \
  -Pwasmline.compile.target=aarch64-linux-android
```

Web tasks assemble and sync the raw `.wasm` into the browser resources before
starting the development server:

```shell
./gradlew :sample-apps:multiplatform:webApp:jsBrowserDevelopmentRun
./gradlew :sample-apps:multiplatform:webApp:wasmJsBrowserDevelopmentRun
```

To only build an Android APK without installing it, use the normal Android
Gradle task:

```shell
./gradlew :sample-apps:android:installDebug
./gradlew :sample-apps:multiplatform:androidApp:installDebug
```

The iOS sample keeps `run-ios.sh` because it coordinates Xcode, simulator
selection, framework builds, installation, and launch:

```shell
./run-ios.sh
```
