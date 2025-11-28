#ifndef WASM_CONFIG_H
#define WASM_CONFIG_H
#include "WasmCommon.h"

class WasmConfig {
public:
    /**
     * 创建适用于 Android 的 Wasmtime 配置
     * 包含 GC开启, SIMD关闭, 信号关闭, 内存页为0 等关键设置
     */
    static wasm_config_t* createAndroidConfig();
};

#endif //WASM_CONFIG_H