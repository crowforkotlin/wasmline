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

## Four execution contracts

Each valid execution-model/protocol pair has a standalone signed sample package:

| Module | Runtime contract |
|---|---|
| `sample-plugin` | `CORE_WASM + WASMLINE_SERVICE` |
| `sample-raw-export-plugin` | `CORE_WASM + RAW_EXPORT` |
| `sample-component-plugin` | `COMPONENT_MODEL + WASMLINE_SERVICE` |
| `sample-component-export-plugin` | `COMPONENT_MODEL + COMPONENT_EXPORT` |
| `sample-component-fixture` | Packages an external C/C++ `wasmline:service` Component |

`sample-plugin` intentionally omits both manifest properties to exercise the
defaults. The other four modules declare both properties explicitly.

Assemble them independently:

```shell
./gradlew :sample-plugin:wasmlineAssembleDebug
./gradlew :sample-raw-export-plugin:wasmlineAssembleDebug
./gradlew :sample-component-plugin:wasmlineAssembleDebug
./gradlew :sample-component-export-plugin:wasmlineAssembleDebug
```

Component AOT requires the full Wasmtime CLI. The Gradle plugin downloads its
pinned compiler automatically for Component package tasks; no shell export is
required.

```shell
./gradlew :sample-component-plugin:wasmlineAssembleDebug
```

### Type-safe target selection

When `targets` is omitted, the plugin compiles every supported Pulley and
Cranelift target. An explicit assignment replaces that complete target set,
similar to an NDK ABI filter:

```kotlin
import crow.wasmline.gradle.WasmtimeTarget

wasmline {
    wasmtime {
        targets = listOf(
            WasmtimeTarget.PULLEY_64,
            WasmtimeTarget.AARCH64_ANDROID,
        )
    }
}
```

`targets` is a DSL property configured only by assignment; function-style
target selectors are not part of the DSL.

Use `WasmtimeTarget.custom("target-triple")` only for a Wasmtime target that
does not have a predefined value.

## Other targets

Desktop defaults to the Pulley engine. Its `run` task assembles and bundles all
four signed packages. Select any mode in the first row; no artifact path or JVM
property is required:

```shell
./gradlew :sample-apps:multiplatform:desktopApp:run
```

Verify the four bundled manifests and invocation paths without opening the UI:

```shell
./gradlew :sample-apps:multiplatform:desktopApp:verifyWasmlineSamples
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

Android defaults to Pulley + `pwasm64`. The producer package already contains
all supported targets, so Android CWASM only needs the Cranelift engine and
CWASM artifact selection:

```shell
./gradlew :sample-apps:android:wasmlineRunDebug \
  -Pwasmline.engine=cranelift \
  -Pwasmline.artifact.format=cwasm
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

## C/C++ Component fixtures

The C and C++ fixtures implement the canonical `wasmline:service@1.0.0` WIT
world while treating payloads as opaque bytes. Their `build.sh` scripts need
only the WASI SDK and produce a raw Component:

```shell
export WASI_SDK_PATH=/path/to/wasi-sdk-33.0
bash ../c/build.sh
```

Package the Component through the Kotlin Gradle fixture module:

```shell
cd wasmline-samples/kotlin
cp ../c/build/plugin.component.wasm sample-component-fixture/input/plugin.component.wasm
./gradlew :sample-component-fixture:wasmlineAssembleDebug

WASMLINE_SAMPLE_COMPONENT_FIXTURE="$PWD/sample-component-fixture/build/wasmline/output/crow.wasmline.component.fixture-1.0.0/manifest.wlm" \
  ./gradlew :sample-apps:multiplatform:desktopApp:run
```

Select **Component Fixture** in the application. It verifies `sample.echo`,
`sample.callback` through the Kotlin host callback, and `sample.empty`. Replace
the copied C Component with `../cpp/build/plugin.component.wasm` for the C++
fixture. The Gradle package contains the matching `.pwasm`, `.cwasm`, and
signed `manifest.wlm` artifacts.
