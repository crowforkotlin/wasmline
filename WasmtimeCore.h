#ifndef WASMTIME_CORE_H
#define WASMTIME_CORE_H

#include <string>
#include <vector>
#include <functional>
#include <cstdint>

// 引入 Wasmtime 相关头文件
#include "wasm.h"
#include "wasi.h"
#include "wasmtime.h"

// 定义日志回调函数类型：接收消息内容
typedef std::function<void(const std::string&)> LogCallback;

/**
 * Wasmtime 运行环境配置结构体
 * 用于在不同平台（Android/Windows）下开关特定特性
 */
struct WasmConfig {
    // 基础特性开关 (Kotlin/Wasm 必须开启)
    bool enableGc = true;                // 开启垃圾回收 (WasmGC)
    bool enableExceptionHandling = true; // 开启异常处理
    bool enableFunctionReferences = true;// 开启函数引用

    // 性能与兼容性开关 (Android 需特殊配置)
    bool enableSimd = true;              // 开启 SIMD 向量指令 (Android 建议关闭)
    bool enableSignalsBasedTraps = true; // 使用信号处理 Trap (Android 必须关闭，Windows 可开启)
    bool enableCraneliftOpt = true;      // 开启 JIT 优化 (Android 建议关闭以防 SIGILL)
    
    // 调试与日志
    bool enableWasiOutput = true;        // 是否捕获 WASI stdout/stderr
};

/**
 * Wasmtime 核心逻辑封装类
 * 该类不包含任何 JNI 代码，可跨平台使用
 */
class WasmtimeCore {
public:
    WasmtimeCore(const WasmConfig& config);
    ~WasmtimeCore();

    // 设置日志回调（可选）
    void setLogCallback(LogCallback callback);

    // 执行 Wasm 中的 add 函数
    // 返回值: 计算结果，如果失败返回 -1 (可根据需求修改错误码策略)
    int32_t runAddFunction(const std::vector<uint8_t>& wasmBytes, int a, int b);

private:
    WasmConfig config;
    LogCallback logCallback;

    // 内部辅助函数
    void log(const std::string& msg);
    void logError(const char* prefix, wasmtime_error_t* error, wasm_trap_t* trap);
    
    // 初始化 Kotlin 运行时 (_initialize / _start)
    bool initializeKotlinRuntime(wasmtime_context_t* context, wasmtime_instance_t* instance);
};

#endif // WASMTIME_CORE_H