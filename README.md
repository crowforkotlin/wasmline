[中文文档](README_zh.md) | [English](README.md)

---

# WasmLine

## 🚀 WasmLine Support & Key Findings

> WasmLine is designed to provide compatibility across several platforms: **Windows**, **Ubuntu**, **MacOS**, and **Android**. It is fundamentally built upon **Kotlin Multiplatform**, while also supporting the use of **any programming language** for developing Wasi-compliant plugins.

### ✨ Features Support (Future Exploration)
-   **IOS** (Requires research and testing, Using Kotlin Native)
-   **Web**

### ⚠️ Important Runtime Note (Kotlin/Wasi)

As of **December 4, 2025**, testing across several mainstream runtimes indicates a critical compatibility point for Kotlin/Wasi:
> Currently, only **Kotlin 2.3.0-RC** offers reliable support. All other runtimes either throw errors or do not fully support the latest Wasm features required.

## 📚 Relevant Resources

Detailed findings, including **Wasmtime** configuration for Android and specific Kotlin-Wasi support notes, are documented in the following resources:

*   **Wasm/Kotlin Exploration:** [wasm-kotlin-exploration](https://github.com/crowforkotlin/wasm-kotlin-exploration)
*   **Wasmtime on Android (JNI):** [wasmtime-android-issue-blog](https://crowforkotlin.github.io/2025/11/27/Wasm/Android%E4%BD%BF%E7%94%A8JNI%E5%B5%8C%E5%85%A5Wasmtime/) (Wasntime embedded on Android using JNI)
*   **Wasm vs. Wasi Deep Dive:** [understand wasm and wasi diff](https://crowforkotlin.github.io/2025/11/25/Wasm/Wasm%E5%92%8CWasi%E5%8C%BA%E5%88%AB%E5%92%8C%E7%94%9F%E5%91%BD%E5%91%A8%E5%BA%86/) (Difference between Wasm and Wasi and their lifecycles)

---

## 🖼️ Architecture & Visuals

### Kotlin Support Status
<table>
	<tr>
		<td align="center"><img src="docs/images/kotlin_support.png"></td>
	</tr>
    <tr>
		<td align="center">Kotlin Support</td>
	</tr>
</table>   

### Architecture Overview
<table>
	<tr>
		<td align="center"><img src="docs/images/architecture.png"></td>
	</tr>
    <tr>
		<td align="center">Architecture</td>
	</tr>
</table>

---

## 🛠️ Pre-Build Setup

### Initialization
To initialize the necessary platform libraries, execute the following script:

```bash
sh ./scripts/init.sh
```
***

## ✨ Samples

### Running Samples

To run the sample applications on the supported platforms:

```bash
sh ./scripts/samples/run.sh
```

### Sample Demos
<table>
	<tr>
		<td align="center"><img src="docs/images/android_sample.png"></td>
		<td align="center"><img src="docs/images/macos_sample.png"></td>
	</tr>
    <tr>
		<td align="center">Android</td>
		<td align="center">Macos</td>
	</tr>
</table>

---

## ⚙️ AOT Compilation (compile cwasm)

- Use the following configuration with `wasmtime compile` to perform Ahead-Of-Time (AOT) compilation for an Android target (`aarch64-linux-android`), enabling key features like Garbage Collection (`gc=y`) and Function References:

- Kotlin requires support for specific features. The settings for simd, relaxed-simd, and memory guards are necessary conditions added after continuous testing on Android.

```bash

wasmtime compile plugin.wasm -o plugin.cwasm \
    --target aarch64-linux-android \
    -W gc=y \
    -W function-references=y \
    -W exceptions=y \
    -W simd=n \
    -W relaxed-simd=n \
    -O static-memory-guard-size=0 \
    -O dynamic-memory-guard-size=0 \
    -O signals-based-traps=n \
    -O opt-level=2 \
    -C cranelift-debug-verifier=no
```