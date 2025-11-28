#include "WasmConfig.h"

wasm_config_t* WasmConfig::createAndroidConfig() {
    wasm_config_t* conf = wasm_config_new();

    // 1. 开启高级特性
    wasmtime_config_wasm_gc_set(conf, true);
    wasmtime_config_wasm_function_references_set(conf, true);
    wasmtime_config_wasm_exceptions_set(conf, true);

    // 2. Android 兼容性配置 (至关重要)
    // 关闭 SIMD: 防止指令集不兼容
    wasmtime_config_wasm_simd_set(conf, false);
    wasmtime_config_wasm_relaxed_simd_set(conf, false);

    // 关闭信号 Trap: 这一步决定了必须在本地编译，不能用编译的文件
    wasmtime_config_signals_based_traps_set(conf, false);

    // 内存页保护设为 0: 适配 Android 虚拟内存机制
    wasmtime_config_memory_guard_size_set(conf, 0);

    // 限制栈大小 (512KB)
    wasmtime_config_max_wasm_stack_set(conf, 512 * 1024);

    return conf;
}