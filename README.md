# WasmLine

```
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