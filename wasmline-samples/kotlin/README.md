# Wasmline Kotlin Samples

All sample builds use the Wasmline Gradle tasks and the catalog-backed AOT
pipeline. Run commands from this directory.

## Core Wasm application

Build the Kotlin/Wasm plugin package, sync it into the application resources,
and run the host. The Loader selects the matching native artifact from the
signed manifest:

```shell
./gradlew :sample-apps:application:run
```

Load a published package instead of the bundled package:

```shell
WASMLINE_MANIFEST_URL=https://example.com/plugins/crow.wasmline.demo/1.0.0/manifest.wlm \
  ./gradlew :sample-apps:application:run
```

The application does not select CWASM or PWASM. The engine identity and
manifest profiles determine the compatible variant.

Assemble the Core Wasm plugin package without running the host:

```shell
./gradlew :sample-plugin:wasmlineAssembleDebug
```

The package contains one signed `manifest.wlm`, one Core Web `.wasm`, and the
content-addressed CWASM/PWASM variants selected by the configured profile and
target matrix. The corresponding ZIP contains the complete matrix.

## Kotlin/Native host

Build and run the host-native smoke sample on Linux x64/ARM64, macOS ARM64,
or Windows x64:

```shell
./gradlew :sample-apps:native:verifyKotlinNativeSample
```

The task assembles the signed Raw Export package, links the Wasmline runtime
and Pulley engine into a Kotlin/Native executable, verifies the package, and
checks that `add_i32(19, 23)` returns `42`. Select the Cranelift engine and its
matching native artifact explicitly with:

```shell
./gradlew :sample-apps:native:verifyKotlinNativeSample \
  -Pwasmline.engine=cranelift
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

Component and Core AOT use the same catalog profiles and digest-locked compiler
assets. With `autoDownload` enabled, the Gradle plugin downloads only missing
assets for the build host. Arbitrary local compiler paths are not used.

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
        aotCompatibility {
            current()
        }
        targets = listOf(
            WasmtimeTarget.PULLEY_64,
            WasmtimeTarget.AARCH64_ANDROID,
        )
    }
}
```

`targets` is a DSL property configured only by assignment; function-style
target selectors are not part of the DSL.

Native AOT builds require one explicit selector. Use `current()`, `minimum()`,
`all()`, or `versionRanges { include(from = "1.0.0", through = "1.20.0") }`.
The selector resolves immutable AOT generations from the packaged catalog;
Wasmtime versions and profile IDs are not direct DSL inputs. The
`wasmlineCheckAotCompatibility` task reports newly published generations after a
successful assemble.

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
  -Pwasmline.engine=cranelift
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
  -Pwasmline.engine=cranelift
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
fixture. The Gradle package contains one signed `manifest.wlm` and matching
content-addressed `.pwasm` and `.cwasm` variants.
