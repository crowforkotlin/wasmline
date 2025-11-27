// WasmtimeSample.cpp
#include <iostream>
#include <fstream>
#include <vector>
#include <string>

// 引入核心封装
#include "WasmtimeCore.h"

// 辅助函数：读取二进制文件
std::vector<uint8_t> readFile(const std::string& path) {
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file.is_open()) {
        std::cerr << "Failed to open file: " << path << std::endl;
        return {};
    }

    std::streamsize size = file.tellg();
    file.seekg(0, std::ios::beg);

    std::vector<uint8_t> buffer(size);
    if (!file.read((char*)buffer.data(), size)) {
        std::cerr << "Failed to read file content" << std::endl;
        return {};
    }
    return buffer;
}

// macOS 本地日志回调
void console_logger(const std::string& msg) {
    std::cout << "[MacOS-Native] " << msg << std::endl;
}

int main(int argc, char** argv) {
    // 1. 获取 wasm 文件路径
    // 如果命令行没传参数，默认读取当前目录下的 wasm/add.wasm
    std::string wasmPath = "wasm/add.wasm";
    if (argc > 1) {
        wasmPath = argv[1];
    }

    std::cout << "Loading Wasm from: " << wasmPath << std::endl;

    // 2. 读取文件
    std::vector<uint8_t> wasmBytes = readFile(wasmPath);
    if (wasmBytes.empty()) {
        return 1;
    }

    // 3. 配置 Wasmtime (针对 macOS 环境)
    WasmConfig config;
    
    // --- Kotlin/Wasm 必须项 (保持 True) ---
    config.enableGc = true;
    config.enableExceptionHandling = true;
    config.enableFunctionReferences = true;

    // --- macOS 性能优化项 (与 Android 不同，这里建议开启) ---
    config.enableSimd = true;              // M1/M2 支持 SIMD
    config.enableSignalsBasedTraps = true; // 桌面端可以使用信号捕获 Trap
    config.enableCraneliftOpt = true;      // 开启 JIT 全速优化

    // 日志
    config.enableWasiOutput = true;

    // 4. 实例化 Core
    WasmtimeCore core(config);
    core.setLogCallback(console_logger);

    // 5. 运行 add 函数 (例如 11 + 22)
    int a = 55;
    int b = 22;
    std::cout << "Calling add(" << a << ", " << b << ")..." << std::endl;
    
    int32_t result = core.runAddFunction(wasmBytes, a, b);

    if (result != -1) {
        std::cout << "Computation Finished. Result = " << result << std::endl;
    } else {
        std::cerr << "Computation Failed." << std::endl;
    }

    return 0;
}