# Minimal Raw Export

A Compose Multiplatform example with one button, one result, and console logs.
The host calls `add_i32(19, 23)` and displays `19 + 23 = 42`.

## Platforms

- Android: arm64-v8a and x86_64, using the Pulley engine.
- Desktop: JVM with the matching Pulley JNI library.
- Web: both Kotlin/JS and Kotlin/WasmJS, using the browser WebAssembly runtime.
- iOS is intentionally not configured.

## Architecture

- `shared/`: the example's initial text, accent color, and typed invocation.
- `desktopApp/`, `androidApp/`, `webApp/`: small platform entry points and packaging.
- `plugin/`: the guest and its signed package.
- `keys/private.key`: public demonstration key, never for production.
- [minimal support](../../sample-support/minimal/README.md): UI components,
  execution state, logging, cancellation, runtime ownership, and platform loading;
  reused by both minimal examples as the `:minimal-support` project.

The stateless screen receives state and an event callback. `RunController` owns
idle/running/success/failure transitions and rejects concurrent execution.
`PluginRunner` loads, invokes, closes the module, and shuts down the runtime
inside one serialized operation. Disposing the screen cancels its coroutine.
The two examples supply different invocations, not copies of the UI or loader.

Android copies the signed package from APK assets to app-private cache.
Desktop loads the signed package from the plugin build directory.
Web resolves the signed manifest relative to the page URL, verifies the package,
and selects its raw Core Wasm variant. Its HTTP client is closed after loading.
Android/Desktop operations use an IO dispatcher; browser execution uses the
browser dispatcher, not a worker thread.

The button uses a 160 ms press animation; result changes fade over 240 ms.
Safe-area padding, scrollable small windows, selectable result/error text,
disabled busy state, and accessibility live-region updates are shared.

## Manual Commands

Run from the repository root with JBR 21. Android also requires the repository's
configured Android SDK; native bridge preparation requires the configured native
toolchain. Build the Pulley JNI assets if missing:

```bash
./scripts/wasmline jni build --engine pulley
```

Desktop on Linux x86-64:

```bash
WASMLINE_NATIVE_LIBRARY_PATH="$PWD/wasmline-engine-pulley/src/jvmMain/resources/jni/linux/x86_64/libwasmline.so" \
  ./gradlew -p samples/minimal-raw-export run
```

On macOS/Windows use the corresponding JNI library. Source-composite engine JARs
exclude JNI resources. Desktop packaging is not configured.

Android:

```bash
./gradlew -p samples/minimal-raw-export :androidApp:assembleDebug
./gradlew -p samples/minimal-raw-export :androidApp:installDebug
adb shell am start -n crow.wasmline.minimal.rawexport/crow.wasmline.minimal.MainActivity
```

Web, choose one target at a time:

```bash
./gradlew -p samples/minimal-raw-export :webApp:jsBrowserDevelopmentRun
./gradlew -p samples/minimal-raw-export :webApp:wasmJsBrowserDevelopmentRun
```

Use the HTTP address printed by the development server. The JS target requires
WebAssembly support; WasmJS also needs a browser with Wasm GC support.

All launch/package tasks build the guest package first. The pinned AOT toolchain
may be downloaded during assembly. Web shares this signed package even though
it consumes only the raw Wasm variant.

Manually exercise a successful click, repeated clicks while running, window or
activity closure during execution, a missing/invalid package, and a narrow
viewport. Verify both the visible result and console/logcat output.
